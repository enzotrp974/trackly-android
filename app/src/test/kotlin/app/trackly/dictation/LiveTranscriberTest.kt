package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Le client WebSocket de `gpt-live-transcribe` face à un faux serveur Realtime (MockWebServer) :
 * adresse, en-tête, configuration de session, morceaux audio, commit, deltas, texte final, erreurs.
 * Aucun appel à OpenAI.
 */
class LiveTranscriberTest {
    private val server = MockWebServer()
    private val fake = FakeRealtime()
    private var online = true
    private var key: String? = "sk-test-cle-factice-0000000000"

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        fake.socket?.close(1000, "fin du test")
        server.close()
    }

    /** Faux serveur : suit le protocole décrit dans la note (session.created, session.update -> session.updated, commit -> événements). */
    private class FakeRealtime : WebSocketListener() {
        val received = LinkedBlockingQueue<JsonObject>()
        @Volatile var socket: WebSocket? = null
        var confirmUpdate = true
        var onCommit: (WebSocket) -> Unit = { ws ->
            ws.send("""{"type":"input_audio_buffer.committed","item_id":"item_1"}""")
            ws.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","delta":"Bonjour "}""")
            ws.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"Bonjour, une barre PRO.","usage":{"type":"duration","seconds":1.5}}""")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"session.created","session":{"type":"transcription"}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            received.add(message)
            when (message["type"]!!.jsonPrimitive.content) {
                "session.update" -> if (confirmUpdate) webSocket.send("""{"type":"session.updated"}""")
                "input_audio_buffer.commit" -> onCommit(webSocket)
            }
        }
    }

    private fun transcriber(handshakeTimeoutMs: Long = 5_000) = OkHttpLiveTranscriber(
        client = OkHttpClient(),
        apiKey = { key },
        baseUrl = server.url("/v1/realtime"),
        isOnline = { online },
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private fun upgrade() = server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build())

    private fun next(): JsonObject = checkNotNull(fake.received.poll(5, TimeUnit.SECONDS)) { "aucun message reçu" }

    private fun errorOf(block: suspend () -> Unit): AssistantError = runBlocking {
        try {
            block()
            fail("une erreur était attendue")
            error("inaccessible")
        } catch (e: AssistantException) {
            e.error
        }
    }

    @Test
    fun `connexion - adresse sans model, intent transcription, cle en Authorization`() = runBlocking {
        upgrade()
        val connection = transcriber().connect(LiveConfig(keywords = listOf("PRO")))
        val request = server.takeRequest()
        assertEquals("/v1/realtime", request.url.encodedPath)
        assertEquals("transcription", request.url.queryParameter("intent"))
        assertNull("model ne doit pas figurer dans l'adresse", request.url.queryParameter("model"))
        assertEquals("Bearer sk-test-cle-factice-0000000000", request.headers["Authorization"])
        assertNull(request.headers["OpenAI-Beta"])
        connection.close()
    }

    @Test
    fun `configuration de session - modele, languages en liste, mots-cles, PCM 24 kHz, pas de detection de silence`() = runBlocking {
        upgrade()
        transcriber().connect(LiveConfig(keywords = listOf("PRO", "Barre protéinée PRO chocolat"))).close()

        val update = next()
        assertEquals("session.update", update["type"]!!.jsonPrimitive.content)
        val session = update["session"]!!.jsonObject
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        val transcription = input["transcription"]!!.jsonObject
        assertEquals("gpt-live-transcribe", transcription["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("fr"), transcription["languages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("`language` (singulier) n'existe pas avec ces modèles", transcription.containsKey("language"))
        assertEquals(listOf("PRO", "Barre protéinée PRO chocolat"), transcription["keywords"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("`delay` n'est pas envoyé tant qu'il n'est pas vérifié", transcription.containsKey("delay"))
        // `gpt-live-transcribe` n'a pas de VAD serveur : turn_detection est null (présent, pas absent).
        assertEquals(JsonNull, input["turn_detection"])
    }

    @Test
    fun `sans mot-cle, la liste est omise (au moins un element est exige)`() = runBlocking {
        upgrade()
        transcriber().connect(LiveConfig(keywords = emptyList())).close()
        val transcription = next()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject["transcription"]!!.jsonObject
        assertFalse(transcription.containsKey("keywords"))
    }

    @Test
    fun `delay - envoye seulement s il est demande`() = runBlocking {
        upgrade()
        transcriber().connect(LiveConfig(delay = "low")).close()
        val transcription = next()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject["transcription"]!!.jsonObject
        assertEquals("low", transcription["delay"]!!.jsonPrimitive.content)
    }

    @Test
    fun `audio - un evenement append par morceau, en base64, dans l ordre`() = runBlocking {
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        next() // session.update
        val first = ByteArray(9_600) { (it % 251).toByte() }
        val second = ByteArray(4_800) { (it % 13).toByte() }
        assertTrue(connection.sendAudio(first))
        assertTrue(connection.sendAudio(second))

        val a = next()
        val b = next()
        assertEquals("input_audio_buffer.append", a["type"]!!.jsonPrimitive.content)
        assertArrayEquals(first, Base64.getDecoder().decode(a["audio"]!!.jsonPrimitive.content))
        assertArrayEquals(second, Base64.getDecoder().decode(b["audio"]!!.jsonPrimitive.content))
        connection.close()
    }

    @Test
    fun `commit, deltas affiches, texte final avec la duree facturee`() = runBlocking {
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        next()
        connection.sendAudio(ByteArray(9_600))
        next()
        assertTrue(connection.commit())
        assertEquals("input_audio_buffer.commit", next()["type"]!!.jsonPrimitive.content)

        val events = withTimeout(5_000) {
            val seen = ArrayList<LiveEvent>()
            connection.events.first { seen += it; it is LiveEvent.Completed }
            seen
        }
        assertEquals(LiveEvent.Committed("item_1"), events[0])
        assertEquals(LiveEvent.Delta("item_1", "Bonjour "), events[1])
        assertEquals(LiveEvent.Completed("item_1", "Bonjour, une barre PRO.", seconds = 1.5), events[2])
        connection.close()
    }

    @Test
    fun `usage en jetons - pas de duree, l app mesure elle-meme`() = runBlocking {
        fake.onCommit = { ws ->
            ws.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i","transcript":"Salut","usage":{"type":"tokens","input_tokens":5,"output_tokens":2,"total_tokens":7}}""")
        }
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        connection.commit()
        val completed = withTimeout(5_000) { connection.events.first { it is LiveEvent.Completed } } as LiveEvent.Completed
        assertEquals("Salut", completed.text)
        assertNull(completed.seconds)
        connection.close()
    }

    @Test
    fun `sans cle, rien n est envoye`() {
        key = null
        assertEquals(AssistantError.MissingKey, errorOf { transcriber().connect(LiveConfig()) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sans reseau - pas d Internet, rien n est envoye`() {
        online = false
        assertEquals(AssistantError.NoInternet, errorOf { transcriber().connect(LiveConfig()) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `serveur injoignable - pas d Internet`() {
        server.close()
        assertEquals(AssistantError.NoInternet, errorOf { transcriber().connect(LiveConfig()) })
    }

    @Test
    fun `401 a l ouverture - cle refusee`() {
        server.enqueue(
            MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}""").build(),
        )
        assertEquals(AssistantError.KeyRejected, errorOf { transcriber().connect(LiveConfig()) })
    }

    @Test
    fun `429 a l ouverture - credit epuise ou plafond, jamais confondus`() {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""").build(),
        )
        assertEquals(AssistantError.CreditExhausted, errorOf { transcriber().connect(LiveConfig()) })
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"Project spend limit","type":"invalid_request_error","code":"project_spend_limit_exceeded"}}""").build(),
        )
        assertEquals(AssistantError.SpendLimitReached, errorOf { transcriber().connect(LiveConfig()) })
    }

    @Test
    fun `evenement error pendant la configuration - erreur de connexion`() {
        fake.confirmUpdate = false
        fake.onCommit = {}
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"session.created"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""{"type":"error","error":{"type":"invalid_request_error","code":"invalid_value","message":"Invalid keyword"}}""")
                    }
                },
            ).build(),
        )
        val error = errorOf { transcriber().connect(LiveConfig(keywords = listOf("x"))) }
        assertTrue("erreur inattendue : $error", error is AssistantError.Unexpected)
    }

    @Test
    fun `le serveur ne confirme jamais - delai depasse`() {
        fake.confirmUpdate = false
        upgrade()
        assertEquals(AssistantError.Timeout, errorOf { transcriber(handshakeTimeoutMs = 300).connect(LiveConfig()) })
    }

    @Test
    fun `erreur en cours de session - evenement Failed classe comme dans le chat`() = runBlocking {
        fake.onCommit = { ws ->
            ws.send("""{"type":"error","error":{"type":"insufficient_quota","code":"insufficient_quota","message":"quota"}}""")
        }
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        connection.commit()
        val failed = withTimeout(5_000) { connection.events.first { it is LiveEvent.Failed } } as LiveEvent.Failed
        assertEquals(AssistantError.CreditExhausted, failed.error)
        connection.close()
    }

    @Test
    fun `cle invalide signalee dans la session - cle refusee`() = runBlocking {
        fake.onCommit = { ws ->
            ws.send("""{"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key","message":"bad key"}}""")
        }
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        connection.commit()
        val failed = withTimeout(5_000) { connection.events.first { it is LiveEvent.Failed } } as LiveEvent.Failed
        assertEquals(AssistantError.KeyRejected, failed.error)
        connection.close()
    }

    @Test
    fun `tampon vide - le serveur le dit, l evenement garde le code`() = runBlocking {
        fake.onCommit = { ws ->
            ws.send("""{"type":"error","error":{"type":"invalid_request_error","code":"input_audio_buffer_commit_empty","message":"buffer too small"}}""")
        }
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        connection.commit()
        val failed = withTimeout(5_000) { connection.events.first { it is LiveEvent.Failed } } as LiveEvent.Failed
        assertEquals(DictationFailure.NothingHeard, apiFailure(failed.error))
        connection.close()
    }

    @Test
    fun `coupure du serveur en cours de session - evenement Closed`() = runBlocking {
        fake.onCommit = { ws -> ws.close(1001, "bye") }
        upgrade()
        val connection = transcriber().connect(LiveConfig())
        connection.commit()
        val event = withTimeout(5_000) { connection.events.first { it is LiveEvent.Closed || it is LiveEvent.Failed } }
        assertTrue(event is LiveEvent.Closed || event is LiveEvent.Failed)
        connection.close()
    }

    @Test
    fun `adresse par defaut - wss api openai realtime`() {
        assertEquals("https://api.openai.com/v1/realtime", OkHttpLiveTranscriber.DEFAULT_URL)
    }
}
