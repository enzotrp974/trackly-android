package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.dictation.audio.Pcm
import app.trackly.dictation.audio.Wav
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Le client « après l'arrêt » (`gpt-transcribe`) face à un serveur local : formulaire multipart et erreurs. */
class FileTranscriberTest {
    private val server = MockWebServer()
    private var key: String? = "sk-test-cle-factice-0000000000"
    private var online = true

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun transcriber() = OkHttpFileTranscriber(OkHttpClient(), apiKey = { key }, baseUrl = server.url("/v1/"), isOnline = { online })

    private val wav = Wav.wrap(ByteArray(Pcm.RATE * 2)) // 1 s de silence

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

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
    fun `requete - POST v1 audio transcriptions, multipart, cle en Bearer`() = runBlocking {
        server.enqueue(json("""{"text":"Une barre PRO.","languages":[{"code":"fr"}]}"""))

        val result = transcriber().transcribe(wav, FileTranscriptionConfig(keywords = listOf("PRO", "Barre protéinée PRO chocolat")))

        assertEquals("Une barre PRO.", result.text)
        assertNull(result.seconds)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        assertEquals("Bearer sk-test-cle-factice-0000000000", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
    }

    @Test
    fun `formulaire - modele, fichier wav nomme avec extension, languages et keywords en liste`() = runBlocking {
        server.enqueue(json("""{"text":"ok"}"""))
        transcriber().transcribe(wav, FileTranscriptionConfig(keywords = listOf("PRO", "Skyr nature")))

        val body = server.takeRequest().body!!.utf8Lossy()
        assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-transcribe\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"dictee.wav\"\r\nContent-Type: audio/wav"))
        assertTrue(body.contains("RIFF"))
        assertTrue(body.contains("name=\"languages[]\"\r\n\r\nfr\r\n"))
        assertTrue(body.contains("name=\"keywords[]\"\r\n\r\nPRO\r\n"))
        assertTrue(body.contains("name=\"keywords[]\"\r\n\r\nSkyr nature\r\n"))
        // `language` (singulier) n'existe pas avec ce modèle.
        assertFalse(body.contains("name=\"language\""))
    }

    @Test
    fun `sans mot-cle, aucun champ keywords`() = runBlocking {
        server.enqueue(json("""{"text":"ok"}"""))
        transcriber().transcribe(wav, FileTranscriptionConfig())
        assertFalse(server.takeRequest().body!!.utf8Lossy().contains("keywords[]"))
    }

    @Test
    fun `usage de type duree - les secondes facturees sont lues`() = runBlocking {
        server.enqueue(json("""{"text":"Bonjour","usage":{"type":"duration","seconds":3.25}}"""))
        assertEquals(3.25, transcriber().transcribe(wav, FileTranscriptionConfig()).seconds!!, 0.0001)
    }

    @Test
    fun `usage en jetons - pas de duree`() = runBlocking {
        server.enqueue(json("""{"text":"Bonjour","usage":{"type":"tokens","input_tokens":10,"output_tokens":3,"total_tokens":13}}"""))
        assertNull(transcriber().transcribe(wav, FileTranscriptionConfig()).seconds)
    }

    @Test
    fun `sans cle ou sans reseau, rien n est envoye`() {
        key = null
        assertEquals(AssistantError.MissingKey, errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) })
        key = "sk-test-cle-factice-0000000000"
        online = false
        assertEquals(AssistantError.NoInternet, errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `401 - cle refusee`() {
        server.enqueue(json("""{"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}""", 401))
        assertEquals(AssistantError.KeyRejected, errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) })
    }

    @Test
    fun `429 credit epuise - distinct du debit`() {
        server.enqueue(json("""{"error":{"message":"quota","type":"insufficient_quota","code":"insufficient_quota"}}""", 429))
        assertEquals(AssistantError.CreditExhausted, errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) })
        server.enqueue(json("""{"error":{"message":"slow down","type":"requests","code":"rate_limit_exceeded"}}""", 429))
        assertTrue(errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) } is AssistantError.RateLimited)
    }

    @Test
    fun `reponse sans texte - erreur inattendue`() {
        server.enqueue(json("""{"languages":[]}"""))
        assertTrue(errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) } is AssistantError.Unexpected)
    }

    @Test
    fun `serveur injoignable - pas d Internet`() {
        server.close()
        assertEquals(AssistantError.NoInternet, errorOf { transcriber().transcribe(wav, FileTranscriptionConfig()) })
    }

    private fun okio.ByteString.utf8Lossy(): String = string(Charsets.ISO_8859_1)
}
