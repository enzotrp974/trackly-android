package app.trackly.live

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.AssistantModel
import app.trackly.assistant.openai.ImageInput
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.dictation.DictationKeywords
import app.trackly.dictation.DictationMode
import app.trackly.dictation.FileTranscriptionConfig
import app.trackly.dictation.LiveConfig
import app.trackly.dictation.LiveEvent
import app.trackly.dictation.OkHttpFileTranscriber
import app.trackly.dictation.OkHttpLiveTranscriber
import app.trackly.dictation.audio.Pcm
import app.trackly.testing.SampleData
import app.trackly.usage.CostCalculator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Contrôles réels de Trackly V1 contre l'API OpenAI.
 *
 * Ignorés par défaut : ils ne tournent qu'avec `-PliveChecks=true` (propriété système
 * `trackly.liveChecks`) **et** la clé dans la variable `TRACKLY_LIVE_OPENAI_KEY`. La suite normale
 * ne fait donc aucun appel réseau. La clé n'est jamais affichée ni écrite : elle ne sert qu'à
 * l'en-tête `Authorization` du vrai client de l'app, sans journal HTTP.
 *
 * Chaque contrôle s'exécute une fois (aucun nouvel essai automatique : [RetryPolicy.None]) et
 * affiche une ligne de résultat : outils appelés, kcal, coût estimé par le compteur de l'app.
 */
class LiveChecksTest {
    private val today = LocalDate.parse(LIVE_DAY)

    @Before
    fun requireLiveChecks() {
        assumeTrue("Contrôles réels désactivés (-PliveChecks=true et TRACKLY_LIVE_OPENAI_KEY)", LiveEnv.enabled)
    }

    // ───── 1 et 7 : snack déclaré, puis « enlève la pomme » ─────

    @Test
    fun `c01 et c07 - snack barre Nuvo et pomme enregistre, puis la pomme enlevee`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val first = interpreter(engine, meter).run(TurnInput(SNACK)).completed()
        val day = engine.daySummary(today)
        val cards = first.cards.filterIsInstance<RecordCard>()
        report(
            "1", "snack barre Nuvo + pomme (Sol)", first, meter,
            "consommations=${day.consumptions.map { "${it.food.label} ${it.food.nutrition.kcal.roundToInt()} kcal [${it.moment.code}]" }}; " +
                "total=${day.totals.kcal.roundToInt()} kcal; cartes=${cards.size}",
        )
        assertEquals(2, day.consumptions.size)
        assertTrue("carte d'enregistrement attendue", cards.isNotEmpty())
        assertTrue("moment snack attendu", day.consumptions.all { it.moment == Moment.Snack })
        assertTrue("kcal plausibles (barre ~200 + pomme ~80)", day.totals.kcal in 200.0..450.0)
        val apple = day.consumptions.single { it.food.label.contains("pomme", ignoreCase = true) }

        val meter7 = CostMeter()
        val history = listOf(
            HistoryMessage(HistoryMessage.Role.User, SNACK),
            HistoryMessage(HistoryMessage.Role.Assistant, (listOf(first.reply) + first.cards.map(ChatCard::summaryForModel)).filter { it.isNotBlank() }.joinToString("\n")),
        )
        val second = interpreter(engine, meter7).run(TurnInput("enlève la pomme", history = history)).completed()
        val after = engine.daySummary(today)
        val versions = engine.history(apple.entryId)
        report(
            "7", "enlève la pomme", second, meter7,
            "versions pomme=${versions.map { "v${it.version} ${it.state.code}" }}; restant=${after.consumptions.map { it.food.label }}; " +
                "total=${after.totals.kcal.roundToInt()} kcal (avant ${day.totals.kcal.roundToInt()})",
        )
        assertEquals(RecordState.Deleted, versions.maxBy { it.version }.state)
        assertEquals(1, after.consumptions.size)
        assertEquals(after.consumptions.sumOf { it.food.nutrition.kcal }, after.totals.kcal, 0.001)
        assertEquals(day.totals.kcal - apple.food.nutrition.kcal, after.totals.kcal, 0.001)
    }

    // ───── 2 et 3 : question et intention, rien n'est compté ─────

    @Test
    fun `c02 - question sur la banane, aucune consommation`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val outcome = interpreter(engine, meter).run(TurnInput("combien de calories dans une banane ?")).completed()
        report("2", "question banane", outcome, meter, "consommations=${engine.daySummary(today).consumptions.size}")
        assertTrue(outcome.applied.isEmpty())
        assertTrue(engine.daySummary(today).consumptions.isEmpty())
    }

    @Test
    fun `c03 - intention pizza ce soir, aucune consommation`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val outcome = interpreter(engine, meter).run(TurnInput("ce soir je vais manger une pizza")).completed()
        report("3", "intention pizza", outcome, meter, "consommations=${engine.daySummary(today).consumptions.size}")
        assertTrue(outcome.applied.isEmpty())
        assertTrue(engine.daySummary(today).consumptions.isEmpty())
    }

    // ───── 4 : restaurant nommé, recherche web ─────

    @Test
    fun `c04 - Big Mac a midi, enregistre, recherches comptees`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val outcome = interpreter(engine, meter).run(TurnInput("à midi j'ai mangé un Big Mac au McDonald's")).completed()
        val day = engine.daySummary(today)
        report(
            "4", "Big Mac à midi", outcome, meter,
            "consommations=${day.consumptions.map { "${it.food.label} ${it.food.nutrition.kcal.roundToInt()} kcal [${it.moment.code}]" }}; " +
                "recherches web (usage)=${meter.webSearches}; cartes=${outcome.cards.map { it::class.simpleName }}",
        )
        assertTrue(day.consumptions.isNotEmpty())
        assertTrue(day.consumptions.all { it.moment == Moment.Midi })
        assertTrue("Big Mac ~ 500-600 kcal", day.totals.kcal in 400.0..700.0)
    }

    // ───── 5 : objectif changé dès le jour de suivi ─────

    @Test
    fun `c05 - objectif a 2 300 kcal des aujourd hui, macros coherentes`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val outcome = interpreter(engine, meter).run(TurnInput("finalement je veux être à 2 300 kcal")).completed()
        val goal = engine.daySummary(today).goal!!
        val yesterday = engine.daySummary(today.minusDays(1)).goal!!
        report(
            "5", "objectif 2 300 kcal", outcome, meter,
            "objectif du jour=${goal.kcal.roundToInt()} kcal P${goal.proteinG?.roundToInt()} G${goal.carbsG?.roundToInt()} L${goal.fatG?.roundToInt()} " +
                "(macros=${GoalCoherence.macroKcal(goal).roundToInt()} kcal); veille=${yesterday.kcal.roundToInt()} kcal",
        )
        assertEquals(2300.0, goal.kcal, 0.0)
        assertEquals(CURRENT_GOAL, yesterday)
        assertTrue(GoalCoherence.hasAllMacros(goal))
        assertTrue(abs(GoalCoherence.macroKcal(goal) - goal.kcal) <= GoalCoherence.tolerance(goal.kcal))
    }

    // ───── 6 : photo d'étiquette ─────

    @Test
    fun `c06 - photo d etiquette, enregistree et fiche memorisee, aucune image gardee`() = runBlocking {
        val started = System.currentTimeMillis()
        val engine = engineWithBar()
        val meter = CostMeter()
        val image = ImageInput("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(labelJpeg()))
        val outcome = interpreter(engine, meter).run(TurnInput("", images = listOf(image))).completed()
        val day = engine.daySummary(today)
        val memorized = engine.allProducts().filter { it.name.contains("Exemple", ignoreCase = true) || it.brand?.contains("Exemple", ignoreCase = true) == true }
        val leftovers = imageFilesSince(started)
        report(
            "6", "photo d'étiquette", outcome, meter,
            "consommations=${day.consumptions.map { "${it.food.label} ${it.food.nutrition.kcal.roundToInt()} kcal P${it.food.nutrition.proteinG} G${it.food.nutrition.carbsG} L${it.food.nutrition.fatG}" }}; " +
                "fiches=${memorized.map { "${it.name} (${it.kind.code})" }}; images restantes=${leftovers.size}",
        )
        assertEquals(1, day.consumptions.size)
        assertEquals(180.0, day.totals.kcal, 5.0)
        assertTrue("fiche produit mémorisée", memorized.isNotEmpty())
        assertTrue("aucun fichier image ne doit rester : $leftovers", leftovers.isEmpty())
    }

    // ───── 8 : même phrase avec Terra ─────

    @Test
    fun `c08 - snack avec gpt-6_1-terra`() = runBlocking {
        val engine = engineWithBar()
        val meter = CostMeter()
        val outcome = interpreter(engine, meter, AssistantModel.Terra.code).run(TurnInput(SNACK)).completed()
        val day = engine.daySummary(today)
        report(
            "8", "snack (Terra)", outcome, meter,
            "modèle réponse=${meter.models}; consommations=${day.consumptions.map { "${it.food.label} ${it.food.nutrition.kcal.roundToInt()} kcal" }}; total=${day.totals.kcal.roundToInt()} kcal",
        )
        assertEquals(2, day.consumptions.size)
        assertTrue(day.totals.kcal in 200.0..450.0)
    }

    // ───── 9 et 10 : dictée ─────

    @Test
    fun `c09 - transcription apres l arret (gpt-transcribe)`() = runBlocking {
        val wav = audioFile()
        val keywords = DictationKeywords.from(engineWithBar())
        val transcriber = OkHttpFileTranscriber(OkHttpResponsesTransport.defaultClient(), apiKey = { LiveEnv.key })
        val startedAt = System.nanoTime()
        val result = transcriber.transcribe(wav.readBytes(), FileTranscriptionConfig(keywords = keywords))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val audioMs = result.seconds?.let { (it * 1000).toLong() } ?: Pcm.durationMs(pcmOf(wav).size)
        val cost = CostCalculator.transcription(DictationMode.AfterStop.model, audioMs).total
        line(
            "9", "transcription fichier", "réussi",
            "texte=« ${result.text} »; mots-clés=$keywords; Nuvo écrit=${result.text.contains("Nuvo")}; " +
                "usage.seconds=${result.seconds}; délai=$elapsedMs ms; coût=${dollars(cost)}",
        )
        assertTrue(result.text.contains("barre", ignoreCase = true))
        assertTrue(result.text.contains("pomme", ignoreCase = true))
    }

    @Test
    fun `c10 - transcription en direct (gpt-live-transcribe)`() = runBlocking {
        val pcm = pcmOf(audioFile())
        val keywords = DictationKeywords.from(engineWithBar())
        val http = OkHttpResponsesTransport.defaultClient()
        val transcriber = OkHttpLiveTranscriber(OkHttpLiveTranscriber.defaultClient(http), apiKey = { LiveEnv.key })
        val connectStart = System.nanoTime()
        val connection = transcriber.connect(LiveConfig(keywords = keywords))
        val connectMs = (System.nanoTime() - connectStart) / 1_000_000
        var firstDeltaMs: Long? = null
        val streamStart = System.nanoTime()
        val final = CompletableDeferred<LiveEvent.Completed>()
        val collected = async {
            connection.events.onEach {
                if (it is LiveEvent.Delta && firstDeltaMs == null) firstDeltaMs = (System.nanoTime() - streamStart) / 1_000_000
                if (it is LiveEvent.Completed) final.complete(it)
            }.toList()
        }
        // Morceaux de 200 ms envoyés au rythme de la parole, comme le micro du téléphone.
        pcm.asList().chunked(Pcm.CHUNK_BYTES).forEach { chunk ->
            check(connection.sendAudio(chunk.toByteArray()))
            delay(Pcm.durationMs(chunk.size))
        }
        val commitAt = System.nanoTime()
        check(connection.commit())
        val completed = try {
            withTimeout(20_000) { final.await() }
        } finally {
            connection.close() // même si l'attente expire
        }
        val finalMs = (System.nanoTime() - commitAt) / 1_000_000
        val events = collected.await()
        val failures = events.filterIsInstance<LiveEvent.Failed>()
        val audioMs = completed.seconds?.let { (it * 1000).toLong() } ?: Pcm.durationMs(pcm.size)
        val cost = CostCalculator.transcription(DictationMode.Live.model, audioMs).total
        line(
            "10", "transcription en direct", if (failures.isEmpty()) "réussi" else "échecs=$failures",
            "texte=« ${completed.text} »; deltas=${events.count { it is LiveEvent.Delta }}; connexion=$connectMs ms; " +
                "premier delta=${firstDeltaMs ?: "aucun"} ms après le début; texte final=$finalMs ms après le commit; " +
                "usage.seconds=${completed.seconds}; Nuvo écrit=${completed.text.contains("Nuvo")}; coût=${dollars(cost)}",
        )
        assertTrue(completed.text.contains("barre", ignoreCase = true))
        assertTrue(completed.text.contains("pomme", ignoreCase = true))
    }

    // ───── 11 : clé invalide ─────

    @Test
    fun `c11 - cle invalide, cle refusee partout`() = runBlocking {
        val bad = "sk-invalide-" + "0".repeat(40)
        val http = OkHttpResponsesTransport.defaultClient()
        val chat = TurnInterpreter(ResponsesClient(OkHttpResponsesTransport(http, apiKey = { bad }), RetryPolicy.None), engineWithBar())
            .run(TurnInput(SNACK))
        val chatError = (chat as? TurnOutcome.Failed)?.error
        val fileError = try {
            OkHttpFileTranscriber(http, apiKey = { bad }).transcribe(audioFile().readBytes(), FileTranscriptionConfig())
            null
        } catch (e: AssistantException) {
            e.error
        }
        val liveError = try {
            OkHttpLiveTranscriber(OkHttpLiveTranscriber.defaultClient(http), apiKey = { bad }).connect(LiveConfig()).close()
            null
        } catch (e: AssistantException) {
            e.error
        }
        line("11", "clé invalide", "observé", "chat=$chatError; fichier=$fileError; direct=$liveError; coût=0 \$")
        assertEquals(AssistantError.KeyRejected, chatError)
        assertEquals(AssistantError.KeyRejected, fileError)
        assertEquals(AssistantError.KeyRejected, liveError)
    }

    // ───── Outils ─────

    /** Jeu d'essai : objectif 2 600 kcal et une « barre Nuvo » récurrente (marque fictive), avec son raccourci. */
    private fun engineWithBar(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("${LIVE_DAY}T16:10")).also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(CURRENT_GOAL, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            val bar = NewProduct(
                productId = "prod-barre-nuvo",
                name = "Barre protéinée Nuvo chocolat",
                brand = "Nuvo",
                nutrients = Nutrients(380.0, 36.0, 22.0, 14.0),
                basis = NutritionBasis(100.0, "g"),
                portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(209.0, 19.8, 12.1, 7.7)),
                shortcuts = listOf("barre nuvo"),
                kind = ProductKind.Recurring,
            )
            check(engine.execute(MemorizeProduct(bar)) is ActionOutcome.Applied)
        }
    }

    /** Vrai client de l'app (OkHttp, sans journal HTTP), sans nouvel essai automatique. */
    private fun interpreter(engine: TracklyEngine, meter: CostMeter, model: String = AssistantModel.Sol.code) = TurnInterpreter(
        ResponsesClient(OkHttpResponsesTransport(OkHttpResponsesTransport.defaultClient(), apiKey = { LiveEnv.key }), RetryPolicy.None),
        engine,
        config = AssistantConfig(model = { model }),
        usageSink = meter,
    )

    private fun TurnOutcome.completed(): TurnOutcome.Completed {
        if (this is TurnOutcome.Failed) {
            line("!", "tour en échec", "échoué", "erreur=$error; outils=${toolCalls.map { it.name }}; appliqués=${applied.size}")
        }
        return this as TurnOutcome.Completed
    }

    /** Compteur de coût de l'app ([CostCalculator]), comme `RoomUsageLedger`, sans base. */
    private class CostMeter : UsageSink {
        val events = mutableListOf<UsageEvent>()
        override suspend fun record(event: UsageEvent) {
            events += event
        }

        val micros: Long get() = events.sumOf { CostCalculator.text(it.model, it.usage, it.webSearchRequests).total }
        val webSearches: Int get() = events.sumOf { it.webSearchRequests ?: 0 }
        val models: Set<String> get() = events.map { it.model }.toSet()
        fun tokens(): String = "requêtes=${events.size}, entrée=${events.sumOf { it.usage.inputTokens }} (cache ${events.sumOf { it.usage.cachedTokens }}), " +
            "sortie=${events.sumOf { it.usage.outputTokens }} (raisonnement ${events.sumOf { it.usage.reasoningTokens }})"
    }

    private fun report(id: String, title: String, outcome: TurnOutcome.Completed, meter: CostMeter, detail: String) {
        val tools = outcome.toolCalls.map { it.name } + List(meter.webSearches) { "web_search" }
        line(id, title, "observé", "outils=$tools; $detail; ${meter.tokens()}; coût=${dollars(meter.micros)}; réponse=« ${outcome.reply.oneLine()} »")
        // Le chat met en page le Markdown : aucune marque ne doit rester visible.
        assertFalse("marque Markdown dans la réponse affichée", app.trackly.assistant.markdown.Markdown.plain(outcome.reply).contains("**"))
    }

    private fun line(id: String, title: String, status: String, detail: String) {
        val text = "CONTROLE $id | $title | $status | $detail"
        // La clé ne passe jamais par ici ; garde-fou contre toute fuite dans le rapport de test.
        val safe = LiveEnv.key?.let { text.replace(it, "[clé masquée]") } ?: text
        println(safe)
    }

    private fun String.oneLine(max: Int = 300): String = replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

    private fun dollars(micros: Long) = "%.4f \$".format(java.util.Locale.ROOT, micros / 1_000_000.0)

    /** Phrase [SNACK] enregistrée (par exemple avec `say`) puis convertie en WAV PCM16 24 kHz mono. */
    private fun audioFile(): File {
        val file = File(System.getenv("TRACKLY_LIVE_AUDIO") ?: DEFAULT_AUDIO)
        assumeTrue("Fichier audio absent : $file", file.isFile)
        return file
    }

    /** PCM brut du bloc `data` d'un WAV (afconvert ajoute un bloc de remplissage avant). */
    private fun pcmOf(wav: File): ByteArray {
        val bytes = wav.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") return bytes.copyOfRange(offset + 8, minOf(bytes.size, offset + 8 + size))
            offset += 8 + size + (size and 1)
        }
        error("WAV sans bloc data")
    }

    /** Étiquette nutritionnelle lisible dessinée en mémoire (Java2D), encodée en JPEG sans fichier. */
    private fun labelJpeg(): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val image = BufferedImage(900, 1100, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 52)
        g.drawString("Barre protéinée Exemple", 50, 110)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 40)
        g.drawString("Poids net : 40 g (1 barre)", 50, 190)
        g.drawRect(40, 240, 820, 760)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 44)
        g.drawString("Valeurs nutritionnelles", 70, 310)
        g.drawString("pour 1 barre (40 g)", 70, 370)
        g.drawLine(40, 400, 860, 400)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 44)
        listOf("Énergie" to "180 kcal", "Protéines" to "15 g", "Glucides" to "18 g", "Lipides" to "6 g").forEachIndexed { i, (name, value) ->
            val y = 480 + i * 120
            g.drawString(name, 70, y)
            g.drawString(value, 600, y)
            g.drawLine(40, y + 40, 860, y + 40)
        }
        g.dispose()
        ImageIO.setUseCache(false)
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "jpg", out))
            out.toByteArray()
        }
    }

    /** Fichiers image apparus depuis [since] dans le dossier temporaire de la JVM et celui des contrôles. */
    private fun imageFilesSince(since: Long): List<String> {
        val dirs = listOfNotNull(System.getProperty("java.io.tmpdir"), File(DEFAULT_AUDIO).parent).map(::File).filter { it.isDirectory }
        val image = Regex("\\.(jpe?g|png|webp|heic|bmp|tmp)$|^imageio", RegexOption.IGNORE_CASE)
        return dirs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.lastModified() >= since && image.containsMatchIn(it.name) } }
            .map { it.path }
    }

    private object LiveEnv {
        val key: String? = System.getenv("TRACKLY_LIVE_OPENAI_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        val enabled: Boolean = System.getProperty("trackly.liveChecks") == "true" && key != null
    }

    private companion object {
        const val LIVE_DAY = "2026-11-03"
        const val SNACK = "comme snack j'ai pris une barre Nuvo et une pomme, 150 g"
        val DEFAULT_AUDIO: String = File(System.getProperty("java.io.tmpdir"), "trackly-live/phrase.wav").path
        val CURRENT_GOAL = GoalTargets(2600.0, 140.0, 352.0, 70.0)
    }
}
