package app.trackly.live

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.openai.StreamListener
import app.trackly.call.AgentCallThinker
import app.trackly.call.CallLine
import app.trackly.call.CallReflection
import app.trackly.call.CallReview
import app.trackly.call.NotedEntry
import app.trackly.call.ReviewRequest
import app.trackly.call.ReviewResult
import app.trackly.call.Speaker
import app.trackly.call.ThinkRequest
import app.trackly.call.ThinkResult
import app.trackly.call.ThinkTrigger
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.TracklyDatabase
import app.trackly.testing.SampleData
import app.trackly.usage.PriceTable
import app.trackly.usage.RoomUsageLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.LocalDate

/**
 * Contrôles réels de l'appel : la **réflexion pendant l'appel** ([AgentCallThinker], réglages Moyenne = Sol medium et
 * Rapide = Terra medium, niveau Standard) et la **relecture après l'appel** contre la vraie API, avec une base Room de
 * test, le vrai moteur, le vrai historique du chat et le vrai compteur de coût de l'app ([RoomUsageLedger]).
 *
 * Ignorés comme les autres contrôles réels : `-PliveChecks=true` **et** `TRACKLY_LIVE_OPENAI_KEY`. Données fictives
 * seulement. Aucune réponse réelle n'est enregistrée : seules des lignes `CONTROLE` sont affichées
 * (questions, phrase dite, ce qui est noté, jetons et coût), jamais la requête ni la clé.
 *
 * Garde-fou de dépense : chaque requête est refusée **avant l'envoi** si son pire cas (cache raté : toute la requête écrite
 * au tarif 12 h, plus sortie et recherche web) risque de faire dépasser le plafond `TRACKLY_LIVE_CAP_USD` (0,90 $ par défaut).
 * Le cumul, obligatoire, est gardé hors du dépôt dans `TRACKLY_LIVE_SPENT_FILE`. Aucun nouvel essai automatique
 * ([RetryPolicy.None]).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING) // a01… d'abord : le cache d'entrée reste chaud d'un contrôle à l'autre
class CallLiveChecksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TracklyDatabase
    private lateinit var engine: TracklyEngine
    private lateinit var history: RoomChatHistory
    private lateinit var ledger: RoomUsageLedger
    private lateinit var meter: MeteredTransport
    private lateinit var thinker: AgentCallThinker
    private val today = LocalDate.parse(LIVE_DAY)

    @Before
    fun setUp() {
        assumeTrue("Contrôles réels désactivés (-PliveChecks=true et TRACKLY_LIVE_OPENAI_KEY)", CallLiveEnv.enabled)
        db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).allowMainThreadQueries().build()
        engine = TracklyEngine(RoomTracklyStore(db), SampleData.clock("${LIVE_DAY}T13:10"))
        history = RoomChatHistory(db.chat())
        ledger = RoomUsageLedger(db.usage())
        meter = MeteredTransport(OkHttpResponsesTransport(OkHttpResponsesTransport.defaultClient(), apiKey = { CallLiveEnv.key }))
        thinker = AgentCallThinker(ResponsesClient(meter, RetryPolicy.None), engine, history, ledger)
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2400.0, 130.0, 300.0, 75.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
            val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
            val poulet = DishIngredientInput("Poulet grillé", 120.0, Nutrients(165.0, 31.0, 0.0, 3.6))
            val sauce = DishIngredientInput("Sauce César", 30.0, Nutrients(150.0, 1.0, 1.0, 16.0))
            check(engine.execute(CreateDish("Salade thon", listOf(laitue, thon), shortcuts = listOf("ma salade habituelle"), dishId = "rcp-salade-thon")) is ActionOutcome.Applied)
            check(engine.execute(CreateDish("Salade César poulet", listOf(laitue, poulet, sauce), dishId = "rcp-salade-cesar")) is ActionOutcome.Applied)
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ───── 1 : pomme sans taille (premier contrôle : `text.format` json_schema avec outils) ─────

    @Test
    fun `a01 - pomme sans taille, une seule question, sans chiffre`() = runBlocking {
        val result = think("1", "pomme sans taille", listOf(user("Ce midi j'ai mangé une pomme.")))
        val done = result as? ThinkResult.Done
        assertNotNull("réponse structurée lue (text.format avec outils)", done)
        assertEquals("une seule question", 1, done!!.questions.size)
        assertTrue("aucun chiffre dans la question", done.questions.none { it.any(Char::isDigit) })
        assertTrue("aucun chiffre à la voix", done.say == null || done.say!!.none(Char::isDigit))
        assertTrue("rien de noté avant sa réponse", done.noted.isEmpty() && engine.daySummary(today).consumptions.isEmpty())
    }

    // ───── 2 et 3 : salade sans variante, puis plat connu ─────

    @Test
    fun `a02 - une salade sans dire laquelle, question sur la salade`() = runBlocking {
        val done = think("2", "salade sans variante", listOf(user("À midi j'ai mangé une salade."))) as ThinkResult.Done
        assertTrue("au moins une question", done.questions.isNotEmpty())
        assertTrue("la première porte sur la salade", done.questions.first().contains("salade", ignoreCase = true))
        assertTrue("aucun chiffre", done.questions.none { it.any(Char::isDigit) })
        assertTrue("rien de noté avant sa réponse", done.noted.isEmpty() && engine.daySummary(today).consumptions.isEmpty())
    }

    @Test
    fun `a03 - ma salade habituelle, notee sans question`() = runBlocking {
        val done = think("3", "plat connu", listOf(user("À midi j'ai mangé ma salade habituelle."))) as ThinkResult.Done
        assertTrue("aucune question", done.questions.isEmpty())
        assertEquals("rcp-salade-thon", done.noted.single().recipeId)
    }

    // ───── 4 : chiffre faux dit par la voix ─────

    @Test
    fun `a04 - la voix a dit un chiffre faux, en fait non`() = runBlocking {
        val done = think(
            "4", "en fait non",
            listOf(user("Une banane moyenne, ça fait combien de calories ?"), voice("Environ 350 calories.")),
        ) as ThinkResult.Done
        assertTrue("correction « En fait non »", done.say.orEmpty().contains("en fait non", ignoreCase = true))
    }

    // ───── 5 : repas complet noté une seule fois, puis un ajout ─────

    @Test
    fun `a05 - repas complet note une fois, l ajout suivant ne renote rien`() = runBlocking {
        val first = listOf(user("Ce matin j'ai pris un yaourt nature de 125 grammes et une grosse pomme."))
        val one = think("5a", "repas complet", first) as ThinkResult.Done
        val second = first + voice("C'est bon, c'est noté.") + user("Ah, et aussi un café noir.")
        val two = think("5b", "ajout d'un café", second, noted = one.noted, trigger = ThinkTrigger.Pause) as ThinkResult.Done
        val labels = engine.daySummary(today).consumptions.map { it.food.label.lowercase() }
        line("5", "repas complet, bilan", "observé", "consommations=$labels; notés 1=${one.noted.map { it.label }}; notés 2=${two.noted.map { it.label }}")
        assertEquals("yaourt une fois", 1, labels.count { "yaourt" in it })
        assertEquals("pomme une fois", 1, labels.count { "pomme" in it })
        assertEquals("café une fois", 1, labels.count { "café" in it || "cafe" in it })
    }

    // ───── 6 : relecture après l'appel ─────

    @Test
    fun `b01 - relecture apres l appel, question restee sans reponse et parole non traitee`() = runBlocking {
        val startedAt = engine.now().minusSeconds(120)
        val applied = engine.execute(
            AddConsumptions(NewConsumption(Food("Pomme", Nutrition(80.0, 0.4, 19.0, 0.3)), Moment.Midi, entryId = EntryId("e-pomme-appel"))),
        ) as ActionOutcome.Applied
        val noted = listOf(NotedEntry("e-pomme-appel", "Pomme", "midi", LIVE_DAY, actionId = applied.result.id.value))
        val transcript = listOf(
            user("Ce midi j'ai mangé une pomme."),
            voice("Ta pomme, elle était grosse comment ?"),
            user("Attends. Et aussi un yaourt nature de 125 grammes. Bon, je dois y aller, salut."),
        )
        val request = ReviewRequest(
            transcript = transcript,
            startedAt = startedAt,
            noted = noted,
            openQuestions = listOf("Ta pomme, elle était grosse comment ?"),
            unprocessed = listOf("Attends. Et aussi un yaourt nature de 125 grammes. Bon, je dois y aller, salut."),
            provisional = false,
            reflection = CallReflection.Medium,
        )
        val before = meter.calls.size
        val started = System.nanoTime()
        val result = thinker.review(request)
        val ms = (System.nanoTime() - started) / 1_000_000
        val messages = history.last(today, 10).filter { it.role == ChatRole.Assistant }
        val labels = engine.daySummary(today).consumptions.map { it.food.label.lowercase() }
        reportCost("6", "relecture après l'appel", before)
        line(
            "6", "relecture après l'appel", result.javaClass.simpleName,
            "délai=$ms ms; questions=${(result as? ReviewResult.Done)?.questions}; notés=${result.noted.map { it.label }}; " +
                "consommations=$labels; message=« ${messages.lastOrNull()?.text?.oneLine()} »",
        )
        val done = result as? ReviewResult.Done
        assertNotNull("réponse structurée lue", done)
        assertTrue("question de la pomme reprise", done!!.questions.isNotEmpty())
        assertEquals("pomme jamais renotée", 1, labels.count { "pomme" in it })
        assertEquals("yaourt noté une fois", 1, labels.count { "yaourt" in it })
        assertTrue(messages.last().text.startsWith(CallReview.HEADER))
    }

    /** La pomme attendait la réponse de l'utilisateur, qui a raccroché ; la relecture la note une fois et garde la question. */
    @Test
    fun `b02 - relecture, pomme jamais notee pendant l appel, notee en portion habituelle`() = runBlocking {
        val question = "Ta pomme était petite, moyenne ou grosse ?"
        val transcript = listOf(user("Ce midi j'ai mangé une pomme."), voice(question), user("Ah, on m'appelle, à plus."))
        val request = ReviewRequest(transcript, engine.now().minusSeconds(120), emptyList(), listOf(question), emptyList(), false, CallReflection.Medium)
        val before = meter.calls.size
        val result = thinker.review(request)
        val labels = engine.daySummary(today).consumptions.map { it.food.label.lowercase() }
        reportCost("8", "relecture, pomme non notée", before)
        line("8", "relecture, pomme non notée", result.javaClass.simpleName, "questions=${(result as? ReviewResult.Done)?.questions}; consommations=$labels")
        assertEquals("pomme notée une fois", 1, labels.count { "pomme" in it })
        assertTrue("question gardée", (result as ReviewResult.Done).questions.isNotEmpty())
    }

    // ───── 9 : réglage Rapide (Terra medium), `text.format` avec outils ─────

    @Test
    fun `d01 - terra medium, plat connu note, reponse structuree lue`() = runBlocking {
        val result = think("9", "Terra medium, plat connu", listOf(user("À midi j'ai mangé ma salade habituelle.")), reflection = CallReflection.Fast)
        assertTrue("réponse structurée lue (Terra, text.format avec outils) : $result", result is ThinkResult.Done)
        assertEquals("rcp-salade-thon", result.noted.single().recipeId)
        assertTrue(meter.calls.all { it.model.startsWith("gpt-6.1-terra") })
    }

    // ───── Outils ─────

    private fun user(text: String) = CallLine(Speaker.User, text)
    private fun voice(text: String) = CallLine(Speaker.Voice, text)

    private suspend fun think(
        id: String,
        title: String,
        transcript: List<CallLine>,
        noted: List<NotedEntry> = emptyList(),
        trigger: ThinkTrigger = ThinkTrigger.Voice,
        reflection: CallReflection = CallReflection.Medium,
    ): ThinkResult {
        val before = meter.calls.size
        val started = System.nanoTime()
        val result = thinker.think(ThinkRequest(listOf("item_live"), transcript, reflection, noted, trigger = trigger))
        val ms = (System.nanoTime() - started) / 1_000_000
        val message = history.last(today, 20).lastOrNull { it.role == ChatRole.Assistant && it.text.startsWith(AgentCallThinker.HEADER) }
        val detail = when (result) {
            is ThinkResult.Done -> "say=${result.say?.let { "« ${it.oneLine()} »" }}; questions=${result.questions}; notés=${result.noted.map { "${it.label} [${it.moment}]" }}"
            is ThinkResult.Failed -> "raison=${result.reason}; notés=${result.noted.map { it.label }}"
        }
        reportCost(id, title, before)
        line(id, title, result.javaClass.simpleName, "délai=$ms ms; $detail; chat=« ${message?.text?.oneLine()} »")
        return result
    }

    /** Compteur de l'app (base Room) contre la consommation renvoyée par l'API, pour les requêtes de ce contrôle. */
    private suspend fun reportCost(id: String, title: String, from: Int) {
        val calls = meter.calls.drop(from)
        val rows = db.usage().all().takeLast(calls.size)
        val app = rows.sumOf { it.textMicros + it.searchMicros }
        val api = calls.sumOf { it.apiMicros }
        val large = calls.sumOf { it.largeMicros }
        val tiers = calls.map { "${it.model}/${it.tier ?: "?"}" }
        val tokens = calls.joinToString(" ; ") { "in=${it.input} (cache ${it.cached}, écrit ${it.written}/${it.written12h} en 12 h) out=${it.output} (raisonnement ${it.reasoning})" }
        line(
            id, "$title - coût", "observé",
            "requêtes=${calls.size}; niveaux=$tiers; $tokens; compteur app=${dollars(app)}; API×PriceTable=${dollars(api)}; " +
                "compté large=${dollars(large)}; cumul large=${dollars(meter.spentMicros())}; refus de niveau=${meter.rejections}",
        )
        // Le compteur de l'app doit égaler la consommation renvoyée par l'API, au tarif de PriceTable (arrondi d'1 µ$ par requête).
        assertEquals("lignes du compteur de l'app", calls.size, rows.size)
        assertTrue("compteur de l'app ${dollars(app)} contre API ${dollars(api)}", kotlin.math.abs(app - api) <= calls.size)
    }

    private fun line(id: String, title: String, status: String, detail: String) {
        println(CallLiveEnv.mask("CONTROLE APPEL $id | $title | $status | $detail"))
    }

    private fun String.oneLine(max: Int = 400): String = replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

    private fun dollars(micros: Long) = "%.4f \$".format(java.util.Locale.ROOT, micros / 1_000_000.0)

    /**
     * Transport réel enveloppé : garde-fou de dépense avant chaque envoi, puis relevé des jetons et du niveau de service
     * renvoyés par l'API. Rien n'est écrit sauf le cumul compté large (un nombre), hors du dépôt.
     */
    private class MeteredTransport(private val real: ResponsesTransport) : ResponsesTransport {
        class Call(
            val model: String, val tier: String?, val input: Long, val cached: Long, val written: Long, val written12h: Long,
            val output: Long, val reasoning: Long, val apiMicros: Long, val largeMicros: Long,
        )

        val calls = mutableListOf<Call>()
        var rejections = 0

        /** Cumul compté large de tous les passages, lu dans le fichier (obligatoire) ; un fichier illisible arrête tout. */
        fun spentMicros(): Long {
            val file = CallLiveEnv.spentFile
            if (!file.exists()) return 0
            return checkNotNull(file.readText().trim().toLongOrNull()) { "Cumul de dépense illisible : $file" }
        }

        private fun addSpent(micros: Long) = CallLiveEnv.spentFile.writeText((spentMicros() + micros).toString())

        override suspend fun send(body: JsonObject): JsonObject = guarded(body) { real.send(it) }

        override suspend fun stream(body: JsonObject, listener: StreamListener): JsonObject = guarded(body) { real.stream(it, listener) }

        private suspend fun guarded(asked: JsonObject, block: suspend (JsonObject) -> JsonObject): JsonObject {
            val body = asked
            val reserve = worstCase(body)
            val spent = spentMicros()
            check(spent + reserve <= CallLiveEnv.capMicros) {
                "Plafond de dépense : ${spent / 1e6} \$ dépensés (compté large), une requête de plus risque de dépasser ${CallLiveEnv.capMicros / 1e6} \$"
            }
            check(++sentThisRun <= MAX_REQUESTS_PER_RUN) { "Trop de requêtes dans ce passage ($MAX_REQUESTS_PER_RUN au plus)" }
            // La réserve est comptée AVANT l'envoi : un flux coupé, une réponse sans `usage` ou un modèle sans prix restent
            // comptés large. Elle n'est rendue que pour un refus HTTP 4xx (rien n'est facturé).
            addSpent(reserve)
            val json = try {
                block(body)
            } catch (e: AssistantException) {
                val detail = CallLiveEnv.mask(e.error.toString())
                if (detail.contains("service_tier")) rejections++
                val error = e.error
                if (error is AssistantError.Unexpected && error.status in 400..499) addSpent(-reserve)
                println(CallLiveEnv.mask("CONTROLE APPEL ! | requête refusée | ${body["service_tier"] ?: "sans niveau"} | erreur=$detail"))
                throw e
            }
            val usage = json["usage"] as? JsonObject ?: return json
            val call = measure(json, usage)
            calls += call
            addSpent(call.largeMicros - reserve)
            return json
        }

        /**
         * Pire cas d'une requête, d'après elle-même : le cache raté, donc toute la requête écrite au tarif 12 h (un jeton pour
         * 3,4 caractères : 3,64 mesurés le 6/11, 76 576 caractères pour 21 025 jetons), la sortie maximale (`max_output_tokens`,
         * sinon 8 000 jetons, raisonnement compris), toutes les recherches web permises (`max_tool_calls`, chacune avec 5 000
         * jetons de résultats en entrée), au tarif Fast même sans `service_tier` (le projet pourrait l'appliquer).
         */
        private fun worstCase(body: JsonObject): Long {
            val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return UNKNOWN_MODEL_RESERVE
            val price = PriceTable.textModel(model)?.standard ?: return UNKNOWN_MODEL_RESERVE
            val searches = (body["max_tool_calls"] as? JsonPrimitive)?.longOrNull ?: 1L
            val tokens = body.toString().length * 10L / 34 + searches * SEARCH_RESULT_TOKENS
            val output = (body["max_output_tokens"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_MAX_OUTPUT
            return (tokens * price.cacheWrite12h + output * price.output) * PriceTable.PRIORITY_MULTIPLIER / 1_000_000 +
                searches * PriceTable.WEB_SEARCH_PER_CALL + 1
        }

        private fun measure(json: JsonObject, usage: JsonObject): Call {
            val model = (json["model"] as? JsonPrimitive)?.contentOrNull ?: "?"
            val tier = (json["service_tier"] as? JsonPrimitive)?.contentOrNull
            val inDetails = usage["input_tokens_details"] as? JsonObject
            val outDetails = usage["output_tokens_details"] as? JsonObject
            val input = usage.long("input_tokens")
            val cached = inDetails?.long("cached_tokens") ?: 0
            val written = inDetails?.long("cache_write_tokens") ?: 0
            val written12h = (inDetails?.get("cache_write_tokens_details") as? JsonObject)?.long("ttl_12h_tokens") ?: 0
            val output = usage.long("output_tokens")
            val reasoning = outDetails?.long("reasoning_tokens") ?: 0
            val price = checkNotNull(PriceTable.textModel(model)) { "modèle sans prix : $model" }.let {
                if (input > it.longContextAbove) it.longContext else it.standard
            }
            val ordinary = input - cached - written
            val multiplier = if (PriceTable.isFastTier(tier)) PriceTable.PRIORITY_MULTIPLIER else 1L
            // Recherches web (0,01 $ chacune), comptées comme l'app : appels `web_search_call` de la réponse.
            val searches = (json["output"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .count { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "web_search_call" } * PriceTable.WEB_SEARCH_PER_CALL
            val api = (ordinary * price.input + cached * price.cachedInput + (written - written12h) * price.cacheWrite30m +
                written12h * price.cacheWrite12h + output * price.output) * multiplier / 1_000_000 + searches
            // Compté large : toute écriture du cache au tarif 12 h, au niveau facturé, arrondi au micro-dollar supérieur.
            val large = (ordinary * price.input + cached * price.cachedInput + written * price.cacheWrite12h + output * price.output) *
                multiplier / 1_000_000 + searches + 1
            return Call(model, tier, input, cached, written, written12h, output, reasoning, api, large)
        }

        private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0

        companion object {
            /** Modèle sans prix connu : plus qu'un raté du cache de Sol en Fast (0,2185 $ mesuré le 6/11). */
            const val UNKNOWN_MODEL_RESERVE = 230_000L
            const val DEFAULT_MAX_OUTPUT = 8_000L
            const val SEARCH_RESULT_TOKENS = 5_000L
            const val MAX_REQUESTS_PER_RUN = 24

            /** Requêtes de tout le passage Gradle (même JVM), pas seulement du contrôle en cours. */
            @Volatile
            private var sentThisRun = 0
        }
    }

    private object CallLiveEnv {
        val key: String? = System.getenv("TRACKLY_LIVE_OPENAI_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        val enabled: Boolean = System.getProperty("trackly.liveChecks") == "true" && key != null
        val capMicros: Long by lazy {
            val raw = System.getenv("TRACKLY_LIVE_CAP_USD")?.takeIf { it.isNotBlank() } ?: return@lazy 900_000L
            (checkNotNull(raw.trim().toDoubleOrNull()) { "TRACKLY_LIVE_CAP_USD illisible (point décimal : 0.90)" } * 1_000_000).toLong()
        }

        /** Cumul de dépense hors du dépôt, obligatoire : sans lui, chaque passage repartirait de zéro. */
        val spentFile: File by lazy {
            File(checkNotNull(System.getenv("TRACKLY_LIVE_SPENT_FILE")?.takeIf { it.isNotBlank() }) { "TRACKLY_LIVE_SPENT_FILE manquant (cumul de dépense)" })
        }

        /** La clé ne passe jamais par les lignes affichées ; garde-fou contre toute fuite, même partielle, dans le rapport. */
        fun mask(text: String): String =
            (key?.let { text.replace(it, "[clé masquée]") } ?: text).replace(Regex("sk-[A-Za-z0-9_*\\-]{6,}"), "[clé masquée]")
    }

    private companion object {
        /** Mercredi 4 novembre 2026 : journée fictive. */
        const val LIVE_DAY = "2026-11-04"
    }
}
