package app.trackly.call.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Événements serveur de GPT-Live utiles à l'appel (doc relue le 6/11/2026 : guides live-conversations et
 * live-delegation). Les temps `startMs`/`endMs` sont comptés depuis le début de la session, côté serveur.
 */
sealed interface LiveEvent {
    data class Started(val sessionId: String?) : LiveEvent
    data class InputTranscript(val delta: String, val startMs: Long?, val endMs: Long?) : LiveEvent
    data class OutputTranscript(val delta: String, val startMs: Long?, val endMs: Long?) : LiveEvent

    /** Secondes facturées cumulées : chaque valeur remplace la précédente (« Do not sum the snapshots »). */
    data class Usage(val seconds: Double) : LiveEvent

    /**
     * La voix demande une réflexion (délégation `client`). L'événement ne porte aucun texte : l'app reconstruit la
     * demande depuis les transcriptions, et répond avec cet [id] comme `delegation_id`.
     */
    data class DelegationCreated(val id: String) : LiveEvent
    data class Closed(val reason: String?, val seconds: Double?) : LiveEvent

    /**
     * Erreur du serveur. [clientEventId] : la commande de l'app refusée (doc GPT-Live, « Handle rejected commands » :
     * « When present, `error.client_event_id` identifies the outgoing command that failed »).
     */
    data class Error(val code: String?, val message: String?, val clientEventId: String? = null) : LiveEvent

    /** Accusé de réception d'un événement client (`*.appended`, `session.updated`…). */
    data class Ack(val type: String) : LiveEvent
    data class Other(val type: String) : LiveEvent
}

object LiveEvents {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): LiveEvent? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val type = obj.str("type") ?: return null
        return when (type) {
            "session.started" -> LiveEvent.Started(obj.obj("session")?.str("id"))
            "session.input_transcript.delta" ->
                LiveEvent.InputTranscript(obj.str("delta").orEmpty(), obj.long("start_ms"), obj.long("end_ms"))
            "session.output_transcript.delta" ->
                LiveEvent.OutputTranscript(obj.str("delta").orEmpty(), obj.long("start_ms"), obj.long("end_ms"))
            "session.usage.updated" -> obj.obj("usage")?.double("seconds")?.let(LiveEvent::Usage) ?: LiveEvent.Other(type)
            "session.delegation.created" -> obj.obj("delegation")?.str("id")?.let(LiveEvent::DelegationCreated) ?: LiveEvent.Other(type)
            "session.closed" -> LiveEvent.Closed(obj.str("reason"), obj.obj("usage")?.double("seconds"))
            "error" -> {
                val e = obj.obj("error")
                LiveEvent.Error(e?.str("code"), e?.str("message"), e?.str("client_event_id"))
            }
            else -> if (type.endsWith(".appended") || type == "session.updated" || type.startsWith("session.input_audio.")) {
                LiveEvent.Ack(type)
            } else {
                LiveEvent.Other(type)
            }
        }
    }

    private fun JsonObject.el(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }
    private fun JsonObject.obj(key: String): JsonObject? = el(key) as? JsonObject
    private fun JsonObject.str(key: String): String? = (el(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? =
        (el(key) as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() }
    private fun JsonObject.double(key: String): Double? =
        (el(key) as? JsonPrimitive)?.takeIf { it.booleanOrNull == null }?.doubleOrNull
}

/** Retire tout ce qui ressemble à une clé OpenAI (même masquée) d'un texte affiché. */
object Redact {
    private val KEY = Regex("sk-[A-Za-z0-9_*\\-.]{2,}")

    fun secrets(text: String): String = KEY.replace(text, "sk-…")
}
