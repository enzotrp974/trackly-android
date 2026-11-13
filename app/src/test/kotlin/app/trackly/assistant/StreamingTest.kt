package app.trackly.assistant

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.InputItems
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesRequest
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.openai.StreamEvent
import app.trackly.assistant.openai.StreamListener
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnListener
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.TurnStatus
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.SseStreams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Streaming SSE de la Responses API face à un serveur local (MockWebServer, aucun appel à OpenAI) :
 * flux découpé en tout petits morceaux, erreurs avant et pendant le flux, coupure avec repli sans
 * streaming, et tour complet avec appels d'outils (carte avant le texte, coût identique, aucune double écriture).
 */
class StreamingTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private val monday = LocalDate.parse("2026-11-02")

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val transport get() = OkHttpResponsesTransport(OkHttpClient(), apiKey = { "sk-test-cle-factice-0000000000" }, baseUrl = server.url("/v1/"))

    private fun client(retry: RetryPolicy = RetryPolicy(maxRetries = 2)) = ResponsesClient(transport, retry, sleep = { sleeps += it })

    private val request = ResponsesRequest(instructions = "Consignes", input = listOf(InputItems.user("bonjour")))

    /** Flux SSE découpé en morceaux de [chunk] octets (un caractère accentué peut être coupé en deux). */
    private fun sse(body: String, chunk: Int = 5) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream; charset=utf-8")
        .chunkedBody(body, chunk)
        .build()

    private fun sseOf(fixture: String, cutBefore: String? = null) = sse(SseStreams.of(OpenAiFixtures.json(fixture), cutBefore = cutBefore))

    private fun json(code: Int, fixture: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(OpenAiFixtures.text(fixture))
        .build()

    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    private class Recorder : StreamListener {
        val events = mutableListOf<StreamEvent>()
        override suspend fun onEvent(event: StreamEvent) {
            events += event
        }
        val text: String get() = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.delta }
    }

    // ───── Client HTTP ─────

    @Test
    fun `stream true, Accept text event-stream, texte au fil de l eau, reponse finale entiere avec usage`() = runBlocking {
        server.enqueue(sseOf("snack-2-reponse.json"))
        val recorder = Recorder()

        val response = client().create(request, recorder)

        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", recorder.text)
        assertTrue("le texte arrive en plusieurs morceaux", recorder.events.count { it is StreamEvent.TextDelta } > 5)
        assertEquals(recorder.text, response.text)
        assertEquals(3120L, response.usage!!.inputTokens)
        val recorded = server.takeRequest()
        assertEquals("text/event-stream", recorded.headers["Accept"])
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream_options"]!!.jsonObject["include_obfuscation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sans suivi, aucune requete en streaming`() = runBlocking {
        server.enqueue(json(200, "snack-2-reponse.json"))
        client().create(request)
        assertNull(sentBody()["stream"])
    }

    @Test
    fun `serveur qui repond en JSON malgre stream - reponse lue telle quelle`() = runBlocking {
        server.enqueue(json(200, "snack-2-reponse.json"))
        val response = client().create(request, Recorder())
        assertTrue(response.text.startsWith("C’est noté"))
    }

    @Test
    fun `erreur HTTP avant le flux - meme classement, credit epuise sans nouvel essai`() {
        server.enqueue(json(429, "erreur-429-credit.json"))
        assertEquals(AssistantError.CreditExhausted, errorOf { client().create(request, Recorder()) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `erreur temporaire au milieu du flux - texte oublie, nouvel essai en streaming`() = runBlocking {
        server.enqueue(sse(SseStreams.errorMidStream("server_error")))
        server.enqueue(sseOf("snack-2-reponse.json"))
        val recorder = Recorder()

        val response = client().create(request, recorder)

        assertTrue(response.text.startsWith("C’est noté"))
        assertEquals(2, server.requestCount)
        assertEquals(1, sleeps.size)
        val restart = recorder.events.indexOf(StreamEvent.Restarted)
        assertTrue("le texte du premier essai est oublié", restart > 0)
        assertEquals(recorder.text.removePrefix("Je comm"), recorder.events.drop(restart).filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.delta })
    }

    @Test
    fun `erreur de facturation au milieu du flux - credit epuise, sans nouvel essai`() {
        server.enqueue(sse(SseStreams.errorMidStream("insufficient_quota")))
        assertEquals(AssistantError.CreditExhausted, errorOf { client().create(request, Recorder()) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `flux coupe avant response completed - repli sans streaming, une seule fois`() = runBlocking {
        server.enqueue(sseOf("snack-2-reponse.json", cutBefore = "response.completed"))
        server.enqueue(json(200, "snack-2-reponse.json"))
        val recorder = Recorder()

        val response = client().create(request, recorder)

        assertTrue(response.text.startsWith("C’est noté"))
        assertEquals(StreamEvent.Restarted, recorder.events.last())
        assertEquals("true", sentBody()["stream"]!!.jsonPrimitive.content)
        assertNull("le repli n'est pas en streaming", sentBody()["stream"])
    }

    @Test
    fun `connexion fermee au milieu du flux - repli sans streaming`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .chunkedBody(SseStreams.of(OpenAiFixtures.json("snack-2-reponse.json")), 5)
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        server.enqueue(json(200, "snack-2-reponse.json"))

        val response = client().create(request, Recorder())

        assertTrue(response.text.startsWith("C’est noté"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `delai depasse en plein flux - Timeout directement, sans repli ni deuxieme requete`() {
        // Le flux démarre (200, text/event-stream) puis s'arrête de couler : l'appel dépasse son délai global.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .chunkedBody(SseStreams.of(OpenAiFixtures.json("snack-2-reponse.json")), 5)
                .bodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        server.enqueue(json(200, "snack-2-reponse.json"))
        val slow = OkHttpClient.Builder().callTimeout(700, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val client = ResponsesClient(
            OkHttpResponsesTransport(slow, apiKey = { "sk-test-cle-factice-0000000000" }, baseUrl = server.url("/v1/")),
            RetryPolicy(maxRetries = 2), sleep = { sleeps += it },
        )
        val recorder = Recorder()

        assertEquals(AssistantError.Timeout, errorOf { client.create(request, recorder) })

        assertEquals("pas de repli sans streaming : une seule requête", 1, server.requestCount)
        assertFalse("pas de Restarted", recorder.events.contains(StreamEvent.Restarted))
        assertTrue(sleeps.isEmpty())
    }

    // ───── Tour complet ─────

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    /** Chronologie de ce que le chat voit pendant le tour. */
    private class Timeline : TurnListener {
        val steps = mutableListOf<Any>()
        override suspend fun onStatus(status: TurnStatus) {
            if (steps.lastOrNull() != status) steps += status
        }
        override suspend fun onText(text: String) {
            steps += "texte:$text"
        }
        override suspend fun onCard(card: ChatCard) {
            steps += card
        }
        fun indexOfCard() = steps.indexOfFirst { it is RecordCard }
        fun firstText() = steps.indexOfFirst { it is String && it.length > "texte:".length }
    }

    private val snack = "comme snack j'ai pris une barre protéinée et une pomme, 150 g"

    @Test
    fun `tour en streaming - j enregistre, carte avant le texte, texte au fil de l eau, cout identique`() = runBlocking {
        val engine = engine()
        val usage = mutableListOf<UsageEvent>()
        server.enqueue(sseOf("snack-1-appel-outil.json"))
        server.enqueue(sseOf("snack-2-reponse.json"))
        val timeline = Timeline()

        val outcome = TurnInterpreter(client(), engine, usageSink = UsageSink { usage += it }).run(TurnInput(snack), timeline)

        outcome as TurnOutcome.Completed
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", outcome.reply)
        assertEquals(300.0, engine.daySummary(monday).totals.kcal, 0.001)
        // « J'enregistre… » pendant la préparation de l'appel, puis la carte, puis seulement le texte.
        assertTrue(timeline.steps.indexOf(TurnStatus.Recording) in 0 until timeline.indexOfCard())
        assertTrue("carte avant le texte : ${timeline.steps}", timeline.indexOfCard() < timeline.firstText())
        assertEquals("texte:" + outcome.reply, timeline.steps.last { it is String })
        // Compteur de coût : l'usage de chaque `response.completed`, comme sans streaming.
        assertEquals(listOf(2850L, 3120L), usage.map { it.usage.inputTokens })
        assertEquals(listOf(0, 1), usage.map { it.round })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `flux coupe juste apres l appel d outil - repli, un seul enregistrement`() = runBlocking {
        val engine = engine()
        // Arguments complets reçus, mais pas `response.completed` : l'outil n'est PAS exécuté sur ce flux.
        server.enqueue(sseOf("snack-1-appel-outil.json", cutBefore = "response.completed"))
        server.enqueue(json(200, "snack-1-appel-outil.json"))
        server.enqueue(sseOf("snack-2-reponse.json"))
        val timeline = Timeline()

        val outcome = TurnInterpreter(client(), engine).run(TurnInput(snack), timeline)

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(1, outcome.applied.size)
        assertEquals(1, timeline.steps.count { it is RecordCard })
        val day = engine.daySummary(monday)
        assertEquals(300.0, day.totals.kcal, 0.001)
        assertEquals(2, day.consumptions.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `recherche web en streaming - je cherche sur Internet, puis les sources`() = runBlocking {
        server.enqueue(sseOf("recherche-web-reponse.json"))
        val timeline = Timeline()

        val outcome = TurnInterpreter(client(), engine()).run(TurnInput("un Big Mac au McDo"), timeline)

        assertEquals(TurnStatus.Searching, timeline.steps.first())
        outcome as TurnOutcome.Completed
        assertTrue(outcome.cards.single() is SourcesCard)
        // Le balisage des citations n'est jamais visible, ni pendant le flux ni à la fin.
        assertFalse(outcome.reply.contains("]("))
    }

    private fun errorOf(block: suspend () -> Unit): AssistantError = runBlocking {
        try {
            block()
            fail("une erreur était attendue")
            error("inaccessible")
        } catch (e: AssistantException) {
            e.error
        }
    }
}
