package app.trackly.call

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.RecordAlerts
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.asModelContext
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.tools.AssistantTool
import app.trackly.assistant.tools.RecordConsumptionsTool
import app.trackly.assistant.tools.Schema
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.assistant.tools.ToolResult
import app.trackly.assistant.tools.decodeArgs
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnListener
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.catalog.Dishes
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate

/**
 * Réflexion pendant l'appel : **l'agent Trackly du chat**, avec ses consignes, ses outils, sa mémoire et ses actions
 * versionnées et annulables. C'est le seul chemin par lequel un appel note un repas.
 *
 * - Contexte : le **fil du jour** ([history], jour de rattachement courant), le contexte calculé par l'app (le même que le
 *   chat : jamais l'état des barres ni le trait de moyenne), puis les consignes de l'appel et la transcription.
 * - Modèle et effort du réglage « Réflexion pendant l'appel », au niveau de service Standard : `priority` (renvoyé « fast »)
 *   doublait chaque facture, un raté du cache passait de 0,11 à 0,22 $, sans gain mesuré, et la voix n'attend pas.
 * - Ce qu'il note ou corrige s'écrit dans le chat du jour dès l'action appliquée, comme dans le chat : une carte avec
 *   « Annuler » et « Corriger », puis son récap. Il valide par défaut.
 * - Réponse finale structurée ([CallAnswer]) : phrase à dire, questions dans l'ordre d'importance, récap du chat écrit.
 */
class AgentCallThinker(
    private val client: ResponsesClient,
    private val engine: TracklyEngine,
    private val history: ChatHistory,
    private val usage: UsageSink = UsageSink.None,
    private val recordAlerts: RecordAlerts = RecordAlerts.None,
    private val prompt: SystemPrompt = SystemPrompt.default(),
    private val context: ContextBuilder = ContextBuilder.default(),
    private val config: AssistantConfig = AssistantConfig(),
    private val tools: (List<NotedEntry>) -> ToolRegistry = { CallTools.registry(it) },
) : CallThinker, CallReviewer {
    /**
     * Une réflexion à la fois pour toute l'app : une réflexion d'un appel fini qui tourne encore passe avant celle de l'appel
     * suivant, qui voit alors ce qu'elle a noté dans la journée.
     */
    private val lock = Mutex()

    override suspend fun think(request: ThinkRequest): ThinkResult = lock.withLock { thinkAlone(request) }

    /**
     * Relecture après l'appel : même agent, même modèle et même effort que la réflexion de l'appel, au niveau de service
     * habituel. Même verrou : la réflexion d'un appel suivant voit ce qu'elle a noté.
     */
    override suspend fun review(request: ReviewRequest): ReviewResult = lock.withLock { reviewAlone(request) }

    private suspend fun reviewAlone(request: ReviewRequest): ReviewResult {
        val written = mutableListOf<ChatCard>()
        var replyId: Long? = null
        val listener = object : TurnListener {
            override suspend fun onCard(card: ChatCard) {
                // Une carte de sources seule n'ouvre pas de message : rien de noté, rien à montrer.
                if (card is SourcesCard && written.none { it !is SourcesCard }) return
                written += card
                // Écrite tout de suite, comme dans le chat : un arrêt d'Android juste après ne la perd pas.
                withContext(NonCancellable) {
                    val id = replyId
                    if (id == null) {
                        replyId = history.append(engine.now(), ChatRole.Assistant, CallReview.HEADER, written.toList())
                    } else {
                        history.update(id, CallReview.HEADER, written.toList(), engine.now())
                    }
                }
            }
        }
        return try {
            val past = history.last(TrackingDay.of(engine.now()), ChatTurns.HISTORY_LIMIT).asModelContext()
            // Garde relue à chaque enregistrement : ce que le chat note pendant la relecture, ou une écriture dont la carte
            // n'a pas pu suivre (délai, arrêt), compte comme déjà noté.
            val registry = CallTools.registry(request.noted, since = request.startedAt)
            val interpreter = TurnInterpreter(client, engine, registry, prompt, context, config, usage, recordAlerts)
            val outcome = interpreter.run(
                TurnInput(
                    text = reviewText(request.transcript),
                    history = past,
                    effort = request.reflection.effort,
                    sentAt = engine.now(),
                    model = request.reflection.model.code,
                    extraContext = CallInstructions.review(request),
                    textFormat = ReviewAnswer.FORMAT,
                ),
                listener,
            )
            val cards = outcome.cards.ifEmpty { written.toList() }
            val noted = notedFrom(cards)
            when (outcome) {
                is TurnOutcome.Completed -> {
                    val answer = ReviewAnswer.parse(outcome.reply)
                    if (answer == null) {
                        // Ce qui est noté reste noté et visible ; la relecture sera reprise (l'essai suivant le retrouve).
                        if (replyId == null && cards.isNotEmpty()) history.append(engine.now(), ChatRole.Assistant, CallReview.HEADER, cards)
                        return ReviewResult.Failed("réponse illisible", noted)
                    }
                    val questions = answer.questions.distinctBy { it.lowercase() }.take(CallReview.MAX_QUESTIONS)
                    val recorded = cards.any { it !is SourcesCard }
                    val parts = mutableListOf(CallReview.HEADER)
                    if (recorded) answer.recap.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                    if (questions.isNotEmpty()) parts += CallReview.questionsBlock(questions)
                    // Rien de noté, aucune question : rien n'est écrit (le récap de l'appel est déjà dans le fil).
                    if (recorded || questions.isNotEmpty()) {
                        val text = parts.joinToString("\n\n")
                        val shown = if (recorded) cards else emptyList()
                        val id = replyId
                        if (id != null) history.update(id, text, shown, engine.now()) else history.append(engine.now(), ChatRole.Assistant, text, shown)
                    }
                    ReviewResult.Done(questions, noted)
                }
                is TurnOutcome.Failed -> {
                    if (replyId == null && cards.isNotEmpty()) history.append(engine.now(), ChatRole.Assistant, CallReview.HEADER, cards)
                    ReviewResult.Failed(outcome.error.javaClass.simpleName, noted)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReviewResult.Failed(e.javaClass.simpleName, notedFrom(written))
        }
    }

    private suspend fun thinkAlone(request: ThinkRequest): ThinkResult {
        val written = mutableListOf<ChatCard>()
        var replyId: Long? = null
        val listener = object : TurnListener {
            override suspend fun onCard(card: ChatCard) {
                written += card
                runCatching { request.onNoted(notedFrom(listOf(card))) }
                // Écrite tout de suite, comme dans le chat : visible et annulable même si l'appel finit avant le texte, et
                // jusqu'au bout même si la réflexion est coupée à cet instant (l'action, elle, est déjà appliquée).
                withContext(NonCancellable) {
                    val id = replyId
                    if (id == null) {
                        replyId = history.append(engine.now(), ChatRole.Assistant, HEADER, written.toList())
                    } else {
                        history.update(id, HEADER, written.toList(), engine.now())
                    }
                }
            }
        }
        return try {
            val past = history.last(TrackingDay.of(engine.now()), ChatTurns.HISTORY_LIMIT).asModelContext()
            val interpreter = TurnInterpreter(client, engine, tools(request.noted), prompt, context, config, usage, recordAlerts)
            val outcome = interpreter.run(
                TurnInput(
                    text = transcriptText(request.transcript),
                    history = past,
                    effort = request.reflection.effort,
                    sentAt = engine.now(),
                    model = request.reflection.model.code,
                    extraContext = CallInstructions.thinker(request.noted, request.asked),
                    textFormat = CallAnswer.FORMAT,
                ),
                listener,
            )
            // Cartes de l'interpréteur : celles déjà écrites, plus d'éventuelles cartes finales (sources web).
            val cards = outcome.cards.ifEmpty { written.toList() }
            val noted = notedFrom(cards)
            when (outcome) {
                is TurnOutcome.Completed -> {
                    val answer = CallAnswer.parse(outcome.reply)
                    // Un enregistrement, une correction, un produit mémorisé… : tout ce qui change ses données a son récap.
                    val recorded = cards.any { it !is SourcesCard }
                    if (recorded) {
                        val text = listOf(HEADER, answer?.recap.orEmpty().trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
                        val id = replyId
                        if (id != null) history.update(id, text, cards, engine.now()) else history.append(engine.now(), ChatRole.Assistant, text, cards)
                    }
                    if (answer == null) {
                        // Réponse illisible : ce qui est noté reste noté (et visible), rien n'est dit ni demandé à la voix,
                        // et la parole de l'utilisateur reste à traiter : rien ne dit que tout a été compris.
                        ThinkResult.Failed("réponse illisible", noted)
                    } else {
                        ThinkResult.Done(answer.say?.trim()?.takeIf { it.isNotEmpty() }, answer.questions, noted)
                    }
                }
                is TurnOutcome.Failed -> {
                    if (replyId == null && cards.isNotEmpty()) history.append(engine.now(), ChatRole.Assistant, HEADER, cards)
                    ThinkResult.Failed(outcome.error.javaClass.simpleName, noted)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Base de données ou outil : le type seulement, jamais le message (il pourrait citer des données).
            ThinkResult.Failed(e.javaClass.simpleName, notedFrom(written))
        }
    }

    companion object {
        /** Première ligne du message écrit dans le chat du jour pour ce qui est noté pendant un appel. */
        const val HEADER = "📞 Pendant l’appel"

        /** La transcription telle que la réflexion la lit : une réplique par ligne. */
        fun transcriptText(lines: List<CallLine>): String = buildString {
            append("Transcription de l'appel en cours (la voix attend ta réponse) :")
            for (line in lines) {
                if (line.text.isBlank()) continue
                append('\n').append(line.speaker.label).append(" : ").append(line.text.trim())
            }
        }

        /** L'appel tel que la relecture le lit : toute la transcription, une réplique par ligne. */
        fun reviewText(lines: List<CallLine>): String = buildString {
            append("Transcription de l'appel qui vient de finir (relecture : l'utilisateur a raccroché, il lira le chat plus tard) :")
            for (line in lines) {
                if (line.text.isBlank()) continue
                append('\n').append(line.speaker.label).append(" : ").append(line.text.trim())
            }
        }

        /** Consommations notées, corrigées ou supprimées, d'après les cartes (construites par l'app, jamais par le modèle). */
        fun notedFrom(cards: List<ChatCard>): List<NotedEntry> = cards.filterIsInstance<RecordCard>().flatMap { card ->
            card.items.map { item ->
                NotedEntry(
                    entryId = item.entryId,
                    label = item.label,
                    moment = item.moment,
                    day = item.day,
                    productId = item.productId,
                    recipeId = item.recipeId,
                    actionId = card.actionId,
                    deleted = card.kind == RecordKind.Deleted,
                )
            }
        }
    }
}

/** Réponse finale de la réflexion pendant l'appel : sortie structurée stricte (`text.format`). */
object CallAnswer {
    data class Parsed(val say: String?, val questions: List<String>, val recap: String)

    val FORMAT: JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", "reponse_appel")
        put("strict", true)
        put(
            "schema",
            Schema.obj(
                "say" to Schema.string(
                    "Réponse à une question directe de l'utilisateur (chiffres de l'app) ou correction d'un chiffre faux dit par la voix (« En fait non, … ») ; sinon null.",
                    nullable = true,
                ),
                "questions" to Schema.array(
                    Schema.string("Une question courte, à l'oral, sur un seul point flou qui change l'estimation."),
                    "Questions restantes, la plus importante d'abord ; liste vide si tout est clair.",
                ),
                "recap" to Schema.string("Réponse habituelle du chat écrit pour ce qui vient d'être noté ou corrigé ; chaîne vide sinon."),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** `null` si le texte n'est pas l'objet attendu. */
    fun parse(text: String): Parsed? {
        val obj = runCatching { json.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return null
        val say = (obj["say"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        val questions = (obj["questions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
            ?: return null
        val recap = (obj["recap"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return Parsed(say, questions, recap)
    }
}

/** Réponse finale de la relecture après l'appel : sortie structurée stricte (`text.format`). */
object ReviewAnswer {
    data class Parsed(val questions: List<String>, val recap: String)

    val FORMAT: JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", "relecture_appel")
        put("strict", true)
        put(
            "schema",
            Schema.obj(
                "questions" to Schema.array(
                    Schema.string("Une question courte, écrite, tutoyée, sur un seul point flou qui change vraiment l'estimation."),
                    "Questions restées sans réponse et importantes, la plus importante d'abord ; liste vide si tout est clair (le cas le plus fréquent).",
                ),
                "recap" to Schema.string("Réponse habituelle du chat écrit pour ce que tu viens de noter ou de corriger pendant cette relecture ; chaîne vide sinon."),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** `null` si le texte n'est pas l'objet attendu. */
    fun parse(text: String): Parsed? {
        val obj = runCatching { json.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return null
        val questions = (obj["questions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
            ?: return null
        val recap = (obj["recap"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return Parsed(questions, recap)
    }
}

/** Outils de la réflexion pendant l'appel : ceux du chat, dans le même ordre, avec la garde anti-doublon. */
object CallTools {
    /** [since] : tout ce que le moteur a enregistré depuis cet instant compte aussi comme déjà noté (relecture après l'appel). */
    fun registry(noted: List<NotedEntry>, since: Instant? = null): ToolRegistry {
        val chat = ToolRegistry.default()
        return ToolRegistry(chat.tools.map { if (it === RecordConsumptionsTool) CallRecordGuard(noted, since) else it }, chat.hosted)
    }
}

/**
 * `record_consumptions` pendant l'appel : même nom, même description, même schéma (le préfixe du cache d'entrée ne change
 * pas), mais un aliment déjà noté pendant cet appel (même produit, même plat ou même nom, au même moment du même jour) est
 * refusé avec l'entrée à corriger. Une réflexion qui suit un ajout de l'utilisateur ne peut donc pas noter deux fois le même repas.
 */
class CallRecordGuard(noted: List<NotedEntry>, private val since: Instant? = null) : AssistantTool by RecordConsumptionsTool {
    private val noted = noted.filterNot { it.deleted }.toMutableList()

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(RecordConsumptionsTool.Args.serializer(), arguments).getOrNull()
            ?: return RecordConsumptionsTool.execute(arguments, context)
        val today = TrackingDay.of(context.messageAt).toString()
        if (since != null) {
            // Relu au moment d'enregistrer, dans le moteur lui-même : rien de ce qui est noté depuis le début de l'appel ne
            // se note une deuxième fois, même sans carte dans le fil.
            for (day in args.items.map { it.day ?: today }.distinct()) {
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
                for (c in context.engine.daySummary(date).consumptions) {
                    if (c.recordedAt.isBefore(since) || noted.any { it.entryId == c.entryId.value }) continue
                    noted += NotedEntry(c.entryId.value, c.food.label, c.moment.code, c.day.toString(), c.food.productId, c.food.recipeId, c.actionId?.value ?: 0)
                }
            }
        }
        // Un plat se compare à son plat entier : le moteur enregistre la variante par défaut quand le modèle donne le plat.
        val recipes = if (args.items.any { it.dish != null } || noted.any { it.recipeId != null }) context.engine.allRecipes() else emptyList()
        fun dishKey(recipeId: String?): String? = recipeId?.let { id -> Dishes.dishOf(recipes, id)?.let { "plat:" + it.base.recipeId } ?: "recette:$id" }
        for (item in args.items) {
            val day = item.day ?: today
            val dish = item.dish?.let { dishKey(it.recipeId) }
            val same = noted.firstOrNull { e ->
                e.moment == item.moment && e.day == day && (
                    (item.product?.productId != null && item.product.productId == e.productId) ||
                        (dish != null && dish == dishKey(e.recipeId)) ||
                        (item.food != null && key(item.food.label) == key(e.label)) ||
                        (item.product?.newProduct != null && key(item.product.newProduct.name) == key(e.label))
                    )
            } ?: continue
            return ToolResult.error(
                "« ${same.label} » est déjà noté pendant cet appel (entry_id ${same.entryId}). Ne le note pas une deuxième fois : " +
                    "corrige cette entrée (correct_consumption, switch_product ou switch_dish), puis note seulement les aliments nouveaux.",
            )
        }
        val result = RecordConsumptionsTool.execute(arguments, context)
        (result.card as? RecordCard)?.let { noted += AgentCallThinker.notedFrom(listOf(it)) }
        return result
    }

    private fun key(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
}
