package app.trackly.call

import app.trackly.assistant.turn.HistoryMessage
import app.trackly.call.live.ClientEvents
import app.trackly.call.live.LiveEvent
import app.trackly.call.live.LiveEvents
import app.trackly.call.live.Redact
import app.trackly.call.live.SessionRequest
import app.trackly.call.live.WebRtcLiveTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format des échanges avec GPT-Live, d'après la doc relue le 6/11/2026. */
class LiveProtocolTest {
    @Test
    fun `session - voix Brise, delegation client, fil du jour en historique`() {
        val history = listOf(
            HistoryMessage(HistoryMessage.Role.User, "Ce matin, skyr et banane"),
            HistoryMessage(HistoryMessage.Role.Assistant, "Noté : 320 kcal."),
        )
        val s = SessionRequest.session(CallInstructions.VOICE, history)
        assertEquals("gpt-live-1", s["model"]!!.jsonPrimitive.content)
        assertEquals("brise", s["audio"]!!.jsonObject["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("client", s["delegation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val input = s["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), input.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("input_text", input[0]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("output_text", input[1]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Ce matin, skyr et banane", input[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse("pas de modèle dans l'URL", SessionRequest.ENDPOINT.contains("?"))
    }

    @Test
    fun `historique borne, et absent quand le fil est vide`() {
        val long = (1..30).map { HistoryMessage(HistoryMessage.Role.User, "m$it " + "x".repeat(2_000)) }
        val input = SessionRequest.session("", long)["input"]!!.jsonArray
        assertEquals(SessionRequest.MAX_HISTORY, input.size)
        assertTrue(input.last().jsonObject.toString().contains("m30"))
        assertEquals(SessionRequest.MAX_MESSAGE_CHARS, input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.length)
        assertNull(SessionRequest.session("", emptyList())["input"])
    }

    @Test
    fun `corps de la requete - offre et canal pre-negocie dans le corps`() {
        val body = SessionRequest.body(SessionRequest.session("x", emptyList()), "v=0 offre")
        val transport = body["transport"]!!.jsonObject
        assertEquals("webrtc", transport["type"]!!.jsonPrimitive.content)
        assertEquals("v=0 offre", transport["sdp"]!!.jsonPrimitive.content)
        assertEquals(4, transport["dcid"]!!.jsonPrimitive.int)
    }

    @Test
    fun `evenements client`() {
        val c = Json.parseToJsonElement(ClientEvents.commentary("Quelle taille ?", "item_1")).jsonObject
        assertEquals("session.commentary.append", c["type"]!!.jsonPrimitive.content)
        assertEquals("item_1", c["delegation_id"]!!.jsonPrimitive.content)
        assertEquals("Quelle taille ?", c["content"]!!.jsonPrimitive.content)
        val i = Json.parseToJsonElement(ClientEvents.instructions("Stop")).jsonObject
        assertEquals("session.instructions.append", i["type"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, i["delegation_id"])
        assertEquals("session.thinking.append", Json.parseToJsonElement(ClientEvents.thinking("x", "d")).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("session.close", Json.parseToJsonElement(ClientEvents.close()).jsonObject["type"]!!.jsonPrimitive.content)
        val long = Json.parseToJsonElement(ClientEvents.commentary("a".repeat(5_000))).jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(ClientEvents.MAX_CONTENT_CHARS, long.length)
    }

    @Test
    fun `evenements serveur`() {
        assertEquals(LiveEvent.Started("live_1"), LiveEvents.parse(ServerEvents.started("live_1")))
        assertEquals(LiveEvent.InputTranscript("Une pomme", 10, 20), LiveEvents.parse(ServerEvents.input("Une pomme", 10, 20)))
        assertEquals(LiveEvent.DelegationCreated("item_9"), LiveEvents.parse(ServerEvents.delegationCreated("item_9")))
        assertEquals(LiveEvent.Usage(12.5), LiveEvents.parse(ServerEvents.usage(12.5)))
        assertEquals(LiveEvent.Closed("close_requested", 30.0), LiveEvents.parse(ServerEvents.closed("close_requested", 30.0)))
        assertEquals(LiveEvent.Error("bad", "oups"), LiveEvents.parse(ServerEvents.error("bad", "oups")))
        assertEquals(LiveEvent.Error("immutable_field_update", "non", "event_update"), LiveEvents.parse(ServerEvents.error("immutable_field_update", "non", "event_update")))
        assertEquals(LiveEvent.Ack("session.commentary.appended"), LiveEvents.parse("""{"type":"session.commentary.appended"}"""))
        assertNull(LiveEvents.parse("pas du json"))
    }

    @Test
    fun `erreurs HTTP sans cle`() {
        val m = WebRtcLiveTransport.httpMessage(401, """{"error":{"message":"Incorrect API key provided: sk-proj-abc***xyz"}}""")
        assertEquals("Clé refusée par OpenAI (401). Vérifie-la dans Réglages › Clé OpenAI.", m)
        assertFalse(m.contains("sk-proj"))
        assertEquals("sk-… fin", Redact.secrets("sk-abcdef fin"))
        assertEquals("Erreur du serveur (500)", WebRtcLiveTransport.httpMessage(500, "<html>"))
        val other = WebRtcLiveTransport.httpMessage(403, """{"error":{"message":"No access for sk-proj-abcdef"}}""")
        assertFalse(other.contains("sk-proj"))
    }

    @Test
    fun `essais WARP`() {
        assertEquals(
            "WebRTC-ForceDtls13/Enabled/WebRTC-Sctp-Snap/Enabled/WebRTC-IceHandshakeDtls/Enabled/",
            WebRtcLiveTransport.WARP_FIELD_TRIALS,
        )
    }

    @Test
    fun `consignes - une question a la fois, aucun chiffre, ne note rien`() {
        val v = CallInstructions.VOICE
        assertTrue(v.contains("une seule à la fois"))
        assertTrue(v.contains("Tu ne notes rien toi-même"))
        assertTrue(v.contains("Aucun récapitulatif"))
        assertTrue(v.contains("Ne coupe jamais "))
        assertTrue(v.contains("<|SpawnThinking|>"))
        assertTrue(v.contains("Ne dis jamais qu'un repas est noté"))
        assertFalse(v.contains("noté pour le chat"))
    }
}
