package app.trackly.call

import app.trackly.call.live.AudioLevels
import app.trackly.call.live.LaunchStep
import app.trackly.call.live.LiveTransport
import app.trackly.call.live.TransportEvent
import app.trackly.call.live.TransportException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Événements serveur fabriqués, au format de la doc GPT-Live (relue le 6/11/2026). */
object ServerEvents {
    fun started(id: String = "live_test") = buildJsonObject {
        put("type", "session.started")
        putJsonObject("session") { put("id", id) }
    }.toString()

    fun input(delta: String, startMs: Long = 0, endMs: Long = 0) = transcript("session.input_transcript.delta", delta, startMs, endMs)
    fun output(delta: String, startMs: Long = 0, endMs: Long = 0) = transcript("session.output_transcript.delta", delta, startMs, endMs)

    private fun transcript(type: String, delta: String, startMs: Long, endMs: Long) = buildJsonObject {
        put("type", type)
        put("event_id", "evt_$startMs")
        put("delta", delta)
        put("start_ms", startMs)
        put("end_ms", endMs)
    }.toString()

    fun usage(seconds: Double) = buildJsonObject {
        put("type", "session.usage.updated")
        putJsonObject("usage") { put("seconds", seconds) }
    }.toString()

    fun delegationCreated(id: String) = buildJsonObject {
        put("type", "session.delegation.created")
        put("event_id", "evt_$id")
        put("offset_ms", 1000)
        putJsonObject("delegation") {
            put("id", id)
            put("type", "delegation")
            put("target", "client")
        }
    }.toString()

    fun closed(reason: String, seconds: Double) = buildJsonObject {
        put("type", "session.closed")
        put("reason", reason)
        putJsonObject("usage") { put("seconds", seconds) }
    }.toString()

    /** [clientEventId] : la commande de l'app refusée (doc : « identifies the outgoing command that failed »). */
    fun error(code: String?, message: String, clientEventId: String? = null) = buildJsonObject {
        put("type", "error")
        put("event_id", "event_error")
        putJsonObject("error") {
            put("type", "invalid_request_error")
            put("code", code)
            put("message", message)
            if (clientEventId != null) put("client_event_id", clientEventId)
        }
    }.toString()
}

/** Faux transport des tests : enregistre ce qui part, laisse le test décider de ce qui arrive. Aucun réseau. */
class FakeLiveTransport(
    private val failWith: TransportException? = null,
    override val isFree: Boolean = false,
    /** Ouverture qui ne finit jamais (requête HTTP bloquée) : pour raccrocher pendant la connexion. */
    private val hangOnOpen: Boolean = false,
    /** Faux : la requête facturée n'est jamais partie (raccroché avant l'envoi). */
    override val requestSent: Boolean = true,
) : LiveTransport {
    val sent = mutableListOf<String>()
    var openedWith: JsonObject? = null
        private set
    var keyUsed: String? = null
        private set
    var prepared = 0
        private set
    var closed = false
        private set
    var micMuted = false
        private set
    var speakerMuted = false
        private set
    private var onEvent: ((TransportEvent) -> Unit)? = null
    override val levels = MutableStateFlow(AudioLevels())

    override suspend fun prepare(onStep: (LaunchStep, Long) -> Unit) {
        if (prepared > 0) return
        prepared++
        onStep(LaunchStep.Factory, 5)
        onStep(LaunchStep.Offer, 7)
        onStep(LaunchStep.Ice, 3)
    }

    override suspend fun open(session: JsonObject, apiKey: String, onEvent: (TransportEvent) -> Unit, onStep: (LaunchStep, Long) -> Unit) {
        prepare(onStep)
        failWith?.let { throw it }
        if (hangOnOpen) awaitCancellation()
        openedWith = session
        keyUsed = apiKey
        this.onEvent = onEvent
        onStep(LaunchStep.Http, 300)
        onEvent(TransportEvent.ChannelOpen)
    }

    fun emit(json: String) = onEvent?.invoke(TransportEvent.Message(json))
    fun drop(cause: String) = onEvent?.invoke(TransportEvent.Closed(cause))

    override fun send(json: String): Boolean {
        if (closed) return false
        sent += json
        return true
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
    }

    override fun close() {
        closed = true
    }

    fun sentTypes() = sent.map { Regex("\"type\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
    fun sentText() = sent.joinToString("\n")
}
