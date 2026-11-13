package app.trackly.call.live

import app.trackly.assistant.turn.HistoryMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Corps de `POST https://api.openai.com/v1/live/sessions` (doc relue le 6/11/2026) :
 * `{"session": {...}, "transport": {"type": "webrtc", "sdp": "<offre>", "dcid": 4}}`. Le modèle va dans `session`,
 * jamais dans l'URL ; `dcid` (canal pré-négocié, WARP) va dans le corps, jamais dans l'URL. La clé ne figure jamais
 * dans le corps (en-tête HTTP seulement).
 */
object SessionRequest {
    const val ENDPOINT = "https://api.openai.com/v1/live/sessions"
    const val VOICE_MODEL = "gpt-live-1"

    /** Voix retenue après essai. Elle ne peut pas changer en cours d'appel. */
    const val VOICE = "brise"

    /** Identifiant du canal de données pré-négocié (WARP) : le même côté téléphone et dans `transport.dcid`. */
    const val DATA_CHANNEL_ID = 4

    /** Historique initial : au plus [MAX_HISTORY] messages du fil du jour, chacun coupé à [MAX_MESSAGE_CHARS]. */
    const val MAX_HISTORY = 12
    const val MAX_MESSAGE_CHARS = 700

    /**
     * Configuration de la session : voix, consignes, délégation `client` (l'app pilote la réflexion elle-même) et
     * [history], les derniers messages du **fil du jour** (jamais le fil du bilan), pour que la voix sache où en est
     * la journée.
     */
    fun session(instructions: String, history: List<HistoryMessage>): JsonObject = buildJsonObject {
        put("model", VOICE_MODEL)
        put("instructions", instructions)
        putJsonObject("audio") {
            putJsonObject("output") { put("voice", VOICE) }
        }
        putJsonObject("delegation") { put("type", "client") }
        val items = history.filter { it.text.isNotBlank() }.takeLast(MAX_HISTORY)
        if (items.isNotEmpty()) {
            putJsonArray("input") {
                for (m in items) {
                    addJsonObject {
                        put("type", "message")
                        val user = m.role == HistoryMessage.Role.User
                        put("role", if (user) "user" else "assistant")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", if (user) "input_text" else "output_text")
                                put("text", m.text.take(MAX_MESSAGE_CHARS))
                            }
                        }
                    }
                }
            }
        }
    }

    fun body(session: JsonObject, sdpOffer: String, dataChannelId: Int? = DATA_CHANNEL_ID): JsonObject = buildJsonObject {
        put("session", session)
        putJsonObject("transport") {
            put("type", "webrtc")
            put("sdp", sdpOffer)
            dataChannelId?.let { put("dcid", it) }
        }
    }
}

/** Événements client envoyés sur le canal de données. */
object ClientEvents {
    /** Une réponse ajoutée reste sous 500 jetons (limite de la doc) : on coupe bien avant. */
    const val MAX_CONTENT_CHARS = 1_200

    private fun event(type: String, content: String, delegationId: String?, id: String) = buildJsonObject {
        put("type", type)
        put("event_id", id)
        put("delegation_id", JsonPrimitive(delegationId))
        put("content", content.take(MAX_CONTENT_CHARS))
    }.toString()

    private fun newId() = "evt_" + UUID.randomUUID().toString().replace("-", "").take(20)

    /** Texte que la voix doit dire (elle peut le reformuler). [delegationId] : la réflexion à laquelle il répond. */
    fun commentary(content: String, delegationId: String? = null, id: String = newId()) =
        event("session.commentary.append", content, delegationId, id)

    /** Contexte silencieux : la voix s'en sert plus tard, sans le dire tout de suite. */
    fun thinking(content: String, delegationId: String? = null, id: String = newId()) =
        event("session.thinking.append", content, delegationId, id)

    /** Consigne ajoutée en cours d'appel ; la seule qui puisse interrompre la parole en cours. */
    fun instructions(content: String, id: String = newId()) = event("session.instructions.append", content, null, id)

    fun close(id: String = newId()) = buildJsonObject {
        put("type", "session.close")
        put("event_id", id)
    }.toString()
}
