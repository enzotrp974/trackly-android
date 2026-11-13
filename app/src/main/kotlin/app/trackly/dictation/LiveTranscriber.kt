package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.obj
import app.trackly.assistant.openai.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Base64

/** Réglages d'une session de transcription en direct. */
data class LiveConfig(
    val model: String = DictationMode.Live.model,
    val languages: List<String> = listOf("fr"),
    val keywords: List<String> = emptyList(),
    /**
     * Réglage de délai (`minimal` à `xhigh`). Non envoyé par défaut : la référence d'OpenAI le réserve
     * à `gpt-realtime-whisper`, le guide dit le contraire. Un rejet de `session.update` pourrait
     * laisser la session mal configurée, donc on attend de l'avoir essayé sur le téléphone.
     */
    val delay: String? = null,
)

/** Ce que le serveur renvoie pendant la session. */
sealed interface LiveEvent {
    /** Morceau de texte (provisoire) du tour [itemId]. */
    data class Delta(val itemId: String, val text: String) : LiveEvent

    /** Le tampon audio a été validé : le tour [itemId] sera transcrit. */
    data class Committed(val itemId: String) : LiveEvent

    /** Texte final du tour [itemId] ; [seconds] si `usage.type = "duration"`. */
    data class Completed(val itemId: String, val text: String, val seconds: Double?) : LiveEvent

    /** La session est en erreur ou la transcription d'un tour a échoué. */
    data class Failed(val error: AssistantError) : LiveEvent

    /** La connexion s'est fermée. */
    data object Closed : LiveEvent
}

/** Session ouverte et configurée. */
interface LiveConnection {
    val events: Flow<LiveEvent>

    /** Envoie un morceau de PCM 24 kHz mono 16 bits ; faux si la connexion est fermée. */
    fun sendAudio(pcm: ByteArray): Boolean

    /** Fin du tour : demande la transcription finale (`input_audio_buffer.commit`). */
    fun commit(): Boolean

    fun close()
}

fun interface LiveTranscriber {
    /** Ouvre la connexion, la configure, et rend la main quand le serveur a confirmé. @throws AssistantException */
    suspend fun connect(config: LiveConfig): LiveConnection
}

/**
 * Transcription en direct (WebSocket, OkHttp) :
 * `wss://api.openai.com/v1/realtime?intent=transcription`, sans `model` dans l'adresse. On attend
 * `session.created`, on envoie `session.update` (type `transcription`, PCM 24 kHz, `turn_detection: null`
 * car `gpt-live-transcribe` n'a pas de détection de silence côté serveur), puis on attend `session.updated`.
 */
class OkHttpLiveTranscriber(
    private val client: OkHttpClient,
    private val apiKey: suspend () -> String?,
    baseUrl: HttpUrl = DEFAULT_URL.toHttpUrl(),
    private val isOnline: () -> Boolean = { true },
    private val handshakeTimeoutMs: Long = 10_000,
) : LiveTranscriber {
    private val endpoint: HttpUrl = baseUrl.newBuilder().setQueryParameter("intent", "transcription").build()

    override suspend fun connect(config: LiveConfig): LiveConnection {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AssistantException(AssistantError.MissingKey)
        if (!isOnline()) throw AssistantException(AssistantError.NoInternet)
        val ready = CompletableDeferred<Unit>()
        val events = Channel<LiveEvent>(Channel.UNLIMITED)
        val listener = Listener(config, ready, events)
        val socket = client.newWebSocket(Request.Builder().url(endpoint).header("Authorization", "Bearer $key").build(), listener)
        try {
            withTimeoutOrNull(handshakeTimeoutMs) { ready.await() }
                ?: throw AssistantException(AssistantError.Timeout)
        } catch (e: Throwable) {
            // Échec, délai dépassé ou annulation : la connexion ne doit pas rester ouverte.
            socket.cancel()
            throw e
        }
        return Connection(socket, events)
    }

    private class Connection(private val socket: WebSocket, private val channel: Channel<LiveEvent>) : LiveConnection {
        override val events: Flow<LiveEvent> = channel.receiveAsFlow()

        override fun sendAudio(pcm: ByteArray): Boolean = socket.send(
            buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", Base64.getEncoder().encodeToString(pcm))
            }.toString(),
        )

        override fun commit(): Boolean = socket.send("""{"type":"input_audio_buffer.commit"}""")

        override fun close() {
            socket.close(NORMAL_CLOSURE, "fin de la dictée")
            channel.close()
        }
    }

    private class Listener(
        private val config: LiveConfig,
        private val ready: CompletableDeferred<Unit>,
        private val events: Channel<LiveEvent>,
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return
            when (message.string("type")) {
                "session.created" -> webSocket.send(sessionUpdate(config).toString())
                "session.updated" -> ready.complete(Unit)
                "input_audio_buffer.committed" -> message.string("item_id")?.let { events.trySend(LiveEvent.Committed(it)) }
                "conversation.item.input_audio_transcription.delta" ->
                    events.trySend(LiveEvent.Delta(message.string("item_id").orEmpty(), message.string("delta").orEmpty()))
                "conversation.item.input_audio_transcription.completed" ->
                    events.trySend(
                        LiveEvent.Completed(
                            itemId = message.string("item_id").orEmpty(),
                            text = message.string("transcript").orEmpty(),
                            seconds = message.obj("usage")?.let(::durationSeconds),
                        ),
                    )
                "conversation.item.input_audio_transcription.failed" ->
                    fail(webSocket, errorOf(message.obj("error")))
                "error" -> fail(webSocket, errorOf(message.obj("error")))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended()

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            ended()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = if (response != null) {
                val body = try {
                    response.body.string()
                } catch (e: IOException) {
                    ""
                }
                OkHttpResponsesTransport.errorOf(response.code, response.header("Retry-After"), body)
            } else {
                (t as? IOException)?.let(OkHttpResponsesTransport::classify) ?: AssistantError.Unexpected(null, t.message)
            }
            fail(webSocket, error)
        }

        private fun fail(webSocket: WebSocket, error: AssistantError) {
            if (!ready.isCompleted) {
                ready.completeExceptionally(AssistantException(error))
                webSocket.cancel()
            } else {
                events.trySend(LiveEvent.Failed(error))
            }
            events.close()
        }

        private fun ended() {
            if (!ready.isCompleted) ready.completeExceptionally(AssistantException(AssistantError.NoInternet))
            events.trySend(LiveEvent.Closed)
            events.close()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://api.openai.com/v1/realtime"

        /**
         * Client pour la WebSocket : aucun délai de lecture ni d'appel (le serveur se tait pendant les
         * silences, une dictée peut durer plusieurs minutes), mais un ping toutes les 15 s pour
         * détecter une liaison coupée.
         */
        fun defaultClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        private const val NORMAL_CLOSURE = 1000

        /** Message `session.update` (forme GA) pour une session de transcription. */
        fun sessionUpdate(config: LiveConfig): JsonObject = buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", app.trackly.dictation.audio.Pcm.RATE)
                        }
                        putJsonObject("transcription") {
                            put("model", config.model)
                            putJsonArray("languages") { config.languages.forEach { add(JsonPrimitive(it)) } }
                            // `keywords` demande au moins un élément : absent s'il n'y en a aucun.
                            if (config.keywords.isNotEmpty()) putJsonArray("keywords") { config.keywords.forEach { add(JsonPrimitive(it)) } }
                            config.delay?.let { put("delay", it) }
                        }
                        // Pas de détection de silence côté serveur : c'est l'app qui envoie le commit.
                        put("turn_detection", JsonNull)
                    }
                }
            }
        }

        /** Erreur d'un événement `error` : clé invalide, crédit épuisé, plafond… même classification que le chat. */
        internal fun errorOf(error: JsonObject?): AssistantError {
            val code = error?.string("code")
            val type = error?.string("type")
            val message = error?.string("message")
            return when {
                code == "invalid_api_key" || type == "authentication_error" -> AssistantError.KeyRejected
                else -> when (val classified = app.trackly.assistant.openai.OpenAiErrors.classify(0, code, type, null, message)) {
                    is AssistantError.Unexpected -> AssistantError.Unexpected(null, code ?: message)
                    else -> classified
                }
            }
        }
    }
}

