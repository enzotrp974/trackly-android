package app.trackly.assistant.turn

import app.trackly.core.review.WeekRange
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalNoting
import app.trackly.assistant.cards.RecordAlerts
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.SourceLink
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.cards.frozen
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.AssistantModel
import app.trackly.assistant.openai.ImageInput
import app.trackly.assistant.openai.InputItems
import app.trackly.assistant.openai.ModelResponse
import app.trackly.assistant.openai.OutputItem
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesRequest
import app.trackly.assistant.openai.ServiceTier
import app.trackly.assistant.openai.StreamEvent
import app.trackly.assistant.openai.StreamListener
import app.trackly.assistant.openai.Usage
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.assistant.tools.ToolResult
import app.trackly.core.action.ActionResult
import app.trackly.core.engine.TracklyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Message passé de la conversation, renvoyé au modèle (texte seulement). */
data class HistoryMessage(val role: Role, val text: String) {
    enum class Role { User, Assistant }
}

/** Message de l'utilisateur pour un tour. */
data class TurnInput(
    val text: String,
    /** Messages précédents, du plus ancien au plus récent (déjà tronqués). */
    val history: List<HistoryMessage> = emptyList(),
    val effort: ReasoningEffort = ReasoningEffort.Default,
    /** Photos : envoyées au modèle, jamais conservées. */
    val images: List<ImageInput> = emptyList(),
    /** Instant d'envoi ; par défaut l'horloge du moteur. */
    val sentAt: Instant? = null,
    /** Enregistrement visé par le bouton « Corriger » d'une carte, ou `null`. */
    val focus: CorrectionFocus? = null,
    /** Modèle de ce tour, ou `null` : celui du réglage du chat ([AssistantConfig.model]). Réflexion pendant l'appel. */
    val model: String? = null,
    /** Second message `developer`, après le contexte du jour (consignes de l'appel), ou `null`. */
    val extraContext: String? = null,
    /** Format imposé à la réponse finale ([ResponsesRequest.textFormat]), ou `null` : texte libre. */
    val textFormat: JsonObject? = null,
    /**
     * Période du bilan choisie par l'écran pour cet envoi, ou `null` : contexte et outils du bilan prennent
     * celle-ci, la même que le fil où le message est rangé, même si un rapport se valide pendant le tour.
     */
    val period: WeekRange? = null,
    /** « Noter » d'une carte de proposition en cours, confié aux outils ; `null` sinon. */
    val noting: ProposalNoting? = null,
)

/** Appel d'outil fait pendant le tour (journal, tests). */
data class ToolCallRecord(val name: String, val arguments: String, val output: JsonObject)

/** Consommation de jetons d'une requête, pour le compteur de coût. */
data class UsageEvent(
    val model: String,
    val effort: ReasoningEffort,
    val usage: Usage,
    val webSearchRequests: Int?,
    val at: Instant,
    /** Rang de la requête dans le message : 0 pour la première, 1+ après un appel d'outil. */
    val round: Int = 0,
    /** Niveau de service facturé, renvoyé par l'API (`fast` ou `priority` : tarif doublé), ou `null`. */
    val serviceTier: String? = null,
)

/** Reçoit l'usage de chaque réponse d'OpenAI. Le compteur de coût l'implémente. */
fun interface UsageSink {
    suspend fun record(event: UsageEvent)

    companion object {
        val None = UsageSink { }
    }
}

/** Résultat d'un tour : texte final et cartes, ou erreur (les actions déjà appliquées restent). */
sealed interface TurnOutcome {
    val cards: List<ChatCard>
    val applied: List<ActionResult>
    val usage: List<Usage>
    val toolCalls: List<ToolCallRecord>

    data class Completed(
        val reply: String,
        override val cards: List<ChatCard>,
        override val applied: List<ActionResult>,
        override val usage: List<Usage>,
        override val toolCalls: List<ToolCallRecord>,
    ) : TurnOutcome

    data class Failed(
        val error: AssistantError,
        override val cards: List<ChatCard>,
        override val applied: List<ActionResult>,
        override val usage: List<Usage>,
        override val toolCalls: List<ToolCallRecord>,
    ) : TurnOutcome {
        /** Renvoyer le même message est sûr seulement si rien n'a été enregistré. */
        val canRetry: Boolean get() = applied.isEmpty()
    }
}

data class AssistantConfig(
    /** Code du modèle, relu au début de chaque message (réglage « Modèle de l'assistant »). GPT-6.1 Sol par défaut. */
    val model: () -> String = { AssistantModel.Default.code },
    /** Garde-fou : nombre maximal d'allers-retours d'outils dans un tour. */
    val maxToolRounds: Int = 6,
    val maxOutputTokens: Int? = null,
    /** Plafond de recherches web (outils intégrés) par réponse : borne le coût (0,01 $ par recherche). */
    val maxWebSearches: Int = 5,
    /**
     * Rétention du cache d'entrée (`prompt_cache_options.ttl`). 12 h : le préfixe stable (consignes et outils,
     * ~16 000 jetons) écrit au premier message de la journée est relu par les suivants, même espacés de plusieurs
     * heures ; avec la rétention par défaut (30 min), presque chaque message le réécrit. Mesuré sur la vraie API
     * (banc d'évaluation) : relu après 37 min en 12 h, réécrit après 38 min en 30 min.
     * `null` : défaut d'OpenAI.
     */
    val promptCacheTtl: String? = PROMPT_CACHE_TTL,
    /** Niveau de service demandé (« priority »), ou `null` : celui par défaut. Aucun usage ne le demande actuellement. */
    val serviceTier: ServiceTier? = null,
) {
    companion object {
        const val PROMPT_CACHE_TTL = "12h"
    }
}

/**
 * Interpréteur d'un tour de conversation : message de l'utilisateur → requête → boucle
 * d'appels d'outils (`function_call` → exécution locale → `function_call_output`) → texte
 * final et cartes. Pur : aucune dépendance Android ; le transport est injecté.
 *
 * Requête (ordre pensé pour le cache d'entrée) : consignes stables, outils, historique,
 * contexte du message (message `developer`), message de l'utilisateur ; puis, pendant le
 * tour, les éléments de sortie renvoyés tels quels et les résultats d'outils.
 */
class TurnInterpreter(
    private val client: ResponsesClient,
    private val engine: TracklyEngine,
    private val tools: ToolRegistry = ToolRegistry.default(),
    private val prompt: SystemPrompt = SystemPrompt.default(),
    private val context: ContextBuilder = ContextBuilder.default(),
    private val config: AssistantConfig = AssistantConfig(),
    private val usageSink: UsageSink = UsageSink.None,
    /** Rouge de la carte d'enregistrement, figé ici ; l'assistant ne le voit pas. */
    private val recordAlerts: RecordAlerts = RecordAlerts.None,
) {
    private val instructions: String by lazy { prompt.render() }
    private val toolDefinitions: List<JsonObject> by lazy { tools.definitions() }

    /**
     * `prompt_cache_key` : tirée des consignes et des outils, le préfixe en cache. Stable tant qu'ils ne changent pas ;
     * la même pour le chat et la réflexion de l'appel, une autre pour le bilan. La même clé oriente vers les mêmes machines ;
     * le cache n'est relu que si le reste du préfixe est identique (le format de réponse de l'appel et le modèle peuvent en
     * faire partie : partage avec le chat non mesuré). Sur la vraie
     * API, 3 requêtes sur 13 avaient raté le cache sans elle, parfois juste après une requête qui l'avait relu.
     */
    private val cacheKey: String by lazy { PromptCacheKey.of(instructions, toolDefinitions) }

    /**
     * Joue le tour. Avec [listener] (chat), chaque réponse arrive en streaming : l'étape en cours et le texte
     * sont signalés au fil de l'eau, et chaque carte dès que son action est appliquée. La boucle d'outils, le
     * compteur de coût et les erreurs ne changent pas : les outils ne sont exécutés que sur une réponse complète.
     */
    suspend fun run(turn: TurnInput, listener: TurnListener? = null): TurnOutcome {
        val sentAt = turn.sentAt ?: engine.now()
        // Un seul modèle pour tout le message (cache d'entrée, coût lisible), même si le réglage change pendant le tour.
        val model = turn.model ?: config.model()
        val cards = mutableListOf<ChatCard>()
        val applied = mutableListOf<ActionResult>()
        val usage = mutableListOf<Usage>()
        val calls = mutableListOf<ToolCallRecord>()
        val input = mutableListOf<JsonObject>()
        for (message in turn.history) {
            input += when (message.role) {
                HistoryMessage.Role.User -> InputItems.user(message.text)
                HistoryMessage.Role.Assistant -> InputItems.assistant(message.text)
            }
        }
        input += InputItems.developer(context.build(ContextEnv(engine, sentAt, focus = turn.focus, text = turn.text, period = turn.period)))
        turn.extraContext?.takeIf { it.isNotBlank() }?.let { input += InputItems.developer(it) }
        // Photo seule : le modèle reçoit une phrase neutre (un texte vide est refusé par certains clients).
        input += InputItems.user(turn.text.ifBlank { PHOTO_ONLY_TEXT }, turn.images)
        val toolContext = ToolContext(engine, sentAt, period = turn.period, noting = turn.noting)

        // Sources de la recherche web : une carte discrète en fin de tour (aucune si rien n'a été consulté).
        val sources = linkedMapOf<String, SourceLink>()
        fun cardsWithSources(): List<ChatCard> =
            if (sources.isEmpty()) cards.toList() else cards + SourcesCard(sources.values.take(MAX_SOURCES))

        fun failed(error: AssistantError) = TurnOutcome.Failed(error, cards, applied, usage, calls)

        // Texte de la requête en cours : remis à zéro à chaque requête (seule la dernière donne la réponse).
        val text = StringBuilder()
        val stream: StreamListener? = listener?.let { l ->
            StreamListener { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        text.append(event.delta)
                        l.onText(text.toString())
                    }
                    StreamEvent.Restarted -> {
                        text.setLength(0)
                        l.onText("")
                        l.onStatus(TurnStatus.Thinking)
                    }
                    StreamEvent.Thinking -> l.onStatus(TurnStatus.Thinking)
                    StreamEvent.SearchingWeb -> l.onStatus(TurnStatus.Searching)
                    is StreamEvent.CallingTool -> l.onStatus(TurnStatus.forTool(event.name))
                }
            }
        }

        var round = 0
        // « Noter » tranché : une seule réponse finale, sans outils ; le modèle ne peut plus rien retenter.
        var finalOnly = false
        while (true) {
            if (round > 0 && listener != null) {
                text.setLength(0)
                listener.onText("")
                listener.onStatus(TurnStatus.Thinking)
            }
            fun request(tier: String?) = ResponsesRequest(
                instructions = instructions,
                input = input.toList(),
                tools = toolDefinitions,
                toolChoice = if (finalOnly) "none" else "auto",
                effort = turn.effort,
                model = model,
                maxOutputTokens = config.maxOutputTokens,
                maxToolCalls = if (finalOnly || tools.hosted.isEmpty()) null else config.maxWebSearches,
                promptCacheTtl = config.promptCacheTtl,
                promptCacheKey = cacheKey,
                serviceTier = tier,
                textFormat = turn.textFormat,
            )
            val response: ModelResponse = try {
                val tier = config.serviceTier
                val asked = tier?.current()
                try {
                    client.create(request(asked), stream)
                } catch (e: AssistantException) {
                    // Niveau de service refusé par ce modèle : la même requête repart sans, et les suivantes s'en passent.
                    if (tier == null || asked == null || !ServiceTier.refuses(e.error)) throw e
                    tier.refused = true
                    client.create(request(null), stream)
                }
            } catch (e: AssistantException) {
                return failed(e.error)
            }
            response.usage?.let { u ->
                usage += u
                usageSink.record(
                    UsageEvent(response.model ?: model, turn.effort, u, response.webSearchRequests, engine.now(), round, serviceTier = response.serviceTier),
                )
            }
            response.citations.mapNotNull(SourceLink::of).forEach { sources.putIfAbsent(it.url, it) }
            val functionCalls = response.functionCalls
            if (finalOnly && functionCalls.isNotEmpty()) {
                // Aucun outil permis, un appel est impossible ; s'il arrive quand même, il n'est pas exécuté.
                return TurnOutcome.Completed(response.text.ifBlank { response.refusal.orEmpty() }, cardsWithSources(), applied, usage, calls)
            }
            if (functionCalls.isEmpty()) {
                val text = response.text.ifBlank { response.refusal.orEmpty() }
                if (text.isBlank() && response.status == "incomplete") {
                    return failed(AssistantError.Unexpected(null, "Réponse incomplète (${response.incompleteReason ?: "raison inconnue"})"))
                }
                return TurnOutcome.Completed(text, cardsWithSources(), applied, usage, calls)
            }
            if (round >= config.maxToolRounds) {
                return failed(AssistantError.Unexpected(null, "Trop d'appels d'outils dans un même message"))
            }
            round++
            // Tous les éléments de sortie repartent tels quels (raisonnement chiffré compris),
            // puis un résultat par appel d'outil, dans l'ordre.
            input += response.output.map(OutputItem::raw)
            for (call in functionCalls) {
                listener?.onStatus(TurnStatus.forTool(call.name))
                // « Noter » tranché : les autres appels du même lot ne s'exécutent pas (chacun reçoit quand même sa réponse).
                val result = if (finalOnly) ToolResult.error("Non exécuté : « Noter » est déjà tranché pour cette carte.") else execute(call, toolContext)
                result.card?.let { raw ->
                    val card = if (raw is RecordCard) raw.frozen(engine, recordAlerts) else raw
                    cards += card
                    // Montrée tout de suite : l'enregistrement est fait, le texte du modèle viendra après.
                    listener?.onCard(card)
                }
                result.applied?.let(applied::add)
                calls += ToolCallRecord(call.name, call.arguments, result.output)
                if (turn.noting?.outcome != null) finalOnly = true
                input += InputItems.functionCallOutput(call.callId, result.output.toString())
            }
        }
    }

    private companion object {
        const val PHOTO_ONLY_TEXT = "(photo envoyée sans message)"
        const val MAX_SOURCES = 4
    }

    private suspend fun execute(call: OutputItem.FunctionCall, context: ToolContext): ToolResult {
        val tool = tools.find(call.name) ?: return ToolResult.error("Outil inconnu : ${call.name}")
        val arguments = try {
            Json.parseToJsonElement(call.arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return ToolResult.error("Arguments illisibles : JSON objet attendu")
        return try {
            tool.execute(arguments, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "Valeur invalide")
        }
    }
}
