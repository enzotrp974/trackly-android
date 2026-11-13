package app.trackly.assistant

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponseStream
import app.trackly.assistant.openai.SseDecoder
import app.trackly.assistant.openai.SseMessage
import app.trackly.assistant.openai.StreamEvent
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SseStreams
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Décodage SSE et lecture des événements de la Responses API, sans réseau. */
class SseDecoderTest {
    private fun decodeIn(chunks: List<String>): List<SseMessage> {
        val decoder = SseDecoder()
        return chunks.flatMap(decoder::feed) + decoder.finish()
    }

    /** Coupe [text] en morceaux de tailles aléatoires (1 à [max] caractères). */
    private fun randomChunks(text: String, random: Random, max: Int = 9): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val n = random.nextInt(1, max + 1).coerceAtMost(text.length - i)
            out += text.substring(i, i + n)
            i += n
        }
        return out
    }

    @Test
    fun `messages complets quel que soit le decoupage - commentaires ignores, donnees sur plusieurs lignes`() {
        val stream = ": ouverture\n\nevent: a\ndata: {\"x\":1}\n\n:keep-alive\nevent: b\ndata: ligne 1\ndata: ligne 2\nid: 7\nretry: 100\n\ndata:sans espace\n\n"
        val expected = listOf(SseMessage("a", "{\"x\":1}"), SseMessage("b", "ligne 1\nligne 2"), SseMessage(null, "sans espace"))

        assertEquals(expected, decodeIn(listOf(stream)))
        assertEquals(expected, decodeIn(stream.map { it.toString() })) // caractère par caractère
        val random = Random(42)
        repeat(200) { assertEquals(expected, decodeIn(randomChunks(stream, random))) }
    }

    @Test
    fun `fins de ligne CRLF et CR, meme coupees entre deux morceaux`() {
        val expected = listOf(SseMessage("a", "1"), SseMessage("b", "2"))
        assertEquals(expected, decodeIn(listOf("event: a\r\ndata: 1\r\n\r\nevent: b\rdata: 2\r\r")))
        assertEquals(expected, decodeIn(listOf("event: a\r", "\ndata: 1\r", "\n\r", "\nevent: b\r", "data: 2\r", "\r")))
    }

    @Test
    fun `un message sans ligne vide finale est abandonne`() {
        assertEquals(listOf(SseMessage("a", "1")), decodeIn(listOf("event: a\ndata: 1\n\nevent: b\ndata: 2")))
    }

    @Test
    fun `flux d une reponse decoupe au hasard - texte, outils, recherche web, reponse finale identique`() {
        val response = OpenAiFixtures.json("recherche-web-reponse.json")
        val sse = SseStreams.of(response, done = true)
        val random = Random(7)
        repeat(50) {
            val reader = ResponseStream()
            val events = decodeIn(randomChunks(sse, random, max = 40)).flatMap(reader::accept)
            assertTrue(reader.finished)
            assertEquals(response, reader.result())
            assertEquals(StreamEvent.SearchingWeb, events.first())
            val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.delta }
            assertEquals(response["output"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content, text)
        }
    }

    @Test
    fun `appel d outil - nom annonce des le debut, sans attendre les arguments`() {
        val reader = ResponseStream()
        val events = decodeIn(listOf(SseStreams.of(OpenAiFixtures.json("snack-1-appel-outil.json")))).flatMap(reader::accept)
        assertEquals(listOf(StreamEvent.Thinking, StreamEvent.CallingTool("record_consumptions")), events)
    }

    @Test
    fun `DONE et types inconnus ignores, sortie reconstruite si la reponse finale n en a pas`() {
        val reader = ResponseStream()
        val item = """{"type":"message","id":"m","role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}"""
        val messages = listOf(
            SseMessage("response.nouveau_type", """{"type":"response.nouveau_type","x":1}"""),
            SseMessage(null, "pas du json"),
            SseMessage("response.output_item.done", """{"type":"response.output_item.done","output_index":0,"item":$item}"""),
            SseMessage("response.completed", """{"type":"response.completed","response":{"id":"r","status":"completed"}}"""),
            SseMessage(null, "[DONE]"),
        )
        assertTrue(messages.flatMap(reader::accept).isEmpty())
        assertEquals("ok", reader.result()!!["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `flux coupe avant la fin - aucune reponse finale`() {
        val reader = ResponseStream()
        decodeIn(listOf(SseStreams.of(OpenAiFixtures.json("snack-1-appel-outil.json"), cutBefore = "response.completed"))).forEach { reader.accept(it) }
        assertFalse(reader.finished)
        assertNull(reader.result())
    }

    @Test
    fun `erreur en cours de flux - classee comme une erreur HTTP`() {
        fun errorOf(code: String): AssistantError {
            val reader = ResponseStream()
            try {
                decodeIn(listOf(SseStreams.errorMidStream(code))).forEach { reader.accept(it) }
            } catch (e: AssistantException) {
                return e.error
            }
            fail("une erreur était attendue")
            error("inaccessible")
        }
        assertEquals(AssistantError.CreditExhausted, errorOf("insufficient_quota"))
        assertEquals(AssistantError.SpendLimitReached, errorOf("project_spend_limit_exceeded"))
        assertEquals(AssistantError.RateLimited(null), errorOf("rate_limit_exceeded"))
        assertEquals(AssistantError.ServerError(500), errorOf("server_error"))
        assertEquals(AssistantError.KeyRejected, errorOf("invalid_api_key"))
    }
}
