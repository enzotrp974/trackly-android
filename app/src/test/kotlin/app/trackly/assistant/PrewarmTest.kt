package app.trackly.assistant

import app.trackly.assistant.net.HttpPrewarmer
import app.trackly.assistant.net.LatencyEvents
import app.trackly.assistant.net.LatencyLine
import app.trackly.assistant.net.LatencyLog
import app.trackly.assistant.net.Millis
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.dictation.LiveConfig
import app.trackly.dictation.OkHttpLiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Préchauffage de la connexion face à un serveur local (MockWebServer, aucun appel à OpenAI) : rien sans clé,
 * aucune clé envoyée, la vraie requête réutilise la connexion ouverte, au plus une fois par intervalle ; et la
 * mesure de latence ne contient que des durées.
 */
class PrewarmTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var now = 0L
    private val clock = Millis { now }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val completed = """{"id":"resp_1","object":"response","status":"completed","model":"gpt-6.1-sol","output":[],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}"""

    @Test
    fun `sans cle, aucune requete`() = runBlocking {
        val warmer = HttpPrewarmer(OkHttpClient(), server.url("/v1/models"), scope, ready = { false }, clock = clock)
        assertFalse(warmer.warmNow())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `avec cle - HEAD sans cle ni contenu, puis le message reutilise la connexion`() = runBlocking {
        val lines = mutableListOf<LatencyLine>()
        val client = OkHttpClient.Builder().eventListenerFactory(LatencyEvents({ lines += it }, clock)).build()
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().body(completed).addHeader("Content-Type", "application/json").build())
        val warmer = HttpPrewarmer(client, server.url("/v1/models"), scope, ready = { true }, clock = clock)

        assertTrue(warmer.warmNow())
        val transport = OkHttpResponsesTransport(client, apiKey = { "sk-test-cle-factice-0000000000" }, baseUrl = server.url("/v1/"))
        transport.send(buildJsonObject { put("model", "gpt-6.1-sol") })

        val warm = server.takeRequest(5, TimeUnit.SECONDS)!!
        val real = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("HEAD", warm.method)
        assertNull("la clé ne part jamais avec le préchauffage", warm.headers["Authorization"])
        assertEquals(0L, warm.bodySize)
        assertEquals("POST", real.method)
        assertEquals("la vraie requête reprend la connexion préchauffée", warm.connectionIndex, real.connectionIndex)

        // Mesure : le préchauffage a ouvert la connexion, la requête l'a réutilisée ; aucune adresse ni texte.
        assertEquals(listOf("préchauffage", "requête"), lines.map { it.what })
        assertEquals("connexion neuve", lines[0].note)
        assertEquals("connexion réutilisée", lines[1].note)
        assertTrue(lines.none { it.format().contains("sk-") || it.format().contains("/v1") || it.format().contains("127.0.0.1") })
    }

    @Test
    fun `au plus un prechauffage par intervalle`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse.Builder().code(401).build()) }
        val warmer = HttpPrewarmer(OkHttpClient(), server.url("/v1/models"), scope, ready = { true }, clock = clock, minIntervalMs = 45_000)

        assertTrue(warmer.warmNow())
        now = 10_000
        assertFalse(warmer.warmNow())
        now = 50_000
        assertTrue(warmer.warmNow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `dictee en direct - la WebSocket s ouvre sur la connexion prechauffee en HTTP 1_1`() = runBlocking {
        val base = OkHttpClient()
        val live = OkHttpLiveTranscriber.defaultClient(base)
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"session.created","session":{"type":"transcription"}}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("session.update")) webSocket.send("""{"type":"session.updated"}""")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ).build(),
        )
        val warmer = HttpPrewarmer(live.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build(), server.url("/v1/realtime"), scope, ready = { true }, clock = clock)
        assertTrue(warmer.warmNow())

        val transcriber = OkHttpLiveTranscriber(live, apiKey = { "sk-test-cle-factice-0000000000" }, baseUrl = server.url("/v1/realtime"))
        transcriber.connect(LiveConfig()).close()

        val warm = server.takeRequest(5, TimeUnit.SECONDS)!!
        val socket = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(warm.headers["Authorization"])
        assertEquals("websocket", socket.headers["Upgrade"]?.lowercase())
        assertEquals(warm.connectionIndex, socket.connectionIndex)
    }

    @Test
    fun `le chronometre d un tour ne note que des etapes et des durees`() {
        val lines = mutableListOf<LatencyLine>()
        val watch = app.trackly.assistant.net.TurnStopwatch(clock, LatencyLog { lines += it })
        now = 1_200
        watch.mark("carte")
        now = 2_000
        watch.mark("texte")
        watch.mark("carte")
        now = 2_500
        watch.finish("1 carte(s)")
        assertEquals("tour : carte 1200 ms, texte 2000 ms, fin 2500 ms (1 carte(s))", lines.single().format())
    }
}
