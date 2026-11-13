package app.trackly.assistant.openai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transport HTTP réel : OkHttp, sans SDK. La clé est lue à chaque requête (jamais
 * journalisée) ; sans clé, rien n'est envoyé.
 */
class OkHttpResponsesTransport(
    private val client: OkHttpClient,
    private val apiKey: suspend () -> String?,
    /** Racine de l'API ; les tests pointent vers un MockWebServer local. */
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    /** Faux : le téléphone n'a aucun réseau (vérifié avant d'envoyer). */
    private val isOnline: () -> Boolean = { true },
) : ResponsesTransport {
    private val endpoint: HttpUrl = baseUrl.newBuilder().addPathSegment("responses").build()

    override suspend fun send(body: JsonObject): JsonObject {
        val request = request(body, streaming = false)
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AssistantException(classify(e), e)
        }
        response.use { r ->
            val text = try {
                r.body.string()
            } catch (e: IOException) {
                throw AssistantException(classify(e), e)
            }
            if (!r.isSuccessful) throw AssistantException(errorOf(r.code, r.header("Retry-After"), text))
            return parseBody(r.code, text)
        }
    }

    /**
     * Streaming SSE : le corps est lu ligne à ligne sur un fil d'OkHttp et passé à la coroutine par un canal ;
     * les événements vont à [listener] dès qu'ils arrivent. Une erreur HTTP (avant le flux) est classée comme
     * sans streaming ; une coupure après le début du flux lève [StreamInterruptedException]. Annuler la
     * coroutine annule la requête.
     */
    override suspend fun stream(body: JsonObject, listener: StreamListener): JsonObject {
        val call = client.newCall(request(body, streaming = true))
        val pieces = Channel<Piece>(Channel.UNLIMITED)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    pieces.close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            val json = r.body.contentType()?.subtype?.contains("json") == true
                            if (!r.isSuccessful || json) {
                                pieces.trySend(Piece.Whole(r.code, r.isSuccessful, r.header("Retry-After"), r.body.string()))
                            } else {
                                pieces.trySend(Piece.Started)
                                val source = r.body.source()
                                while (true) {
                                    val line = source.readUtf8Line() ?: break
                                    pieces.trySend(Piece.Line(line))
                                }
                            }
                            pieces.close()
                        } catch (e: IOException) {
                            pieces.close(e)
                        }
                    }
                }
            },
        )
        val decoder = SseDecoder()
        val reader = ResponseStream()
        var started = false
        try {
            for (piece in pieces) {
                when (piece) {
                    is Piece.Whole -> {
                        if (!piece.ok) throw AssistantException(errorOf(piece.status, piece.retryAfter, piece.text))
                        return parseBody(piece.status, piece.text) // serveur sans streaming : réponse entière
                    }
                    Piece.Started -> started = true
                    is Piece.Line -> {
                        for (message in decoder.feed(piece.text + "\n")) {
                            for (event in reader.accept(message)) listener.onEvent(event)
                        }
                        if (reader.finished) break
                    }
                }
            }
        } catch (e: IOException) {
            val error = classify(e)
            // Un délai dépassé (callTimeout) n'est pas une coupure : relancer sans streaming doublerait l'attente et le coût.
            if (!started || error == AssistantError.Timeout) throw AssistantException(error, e)
            if (!reader.finished) throw StreamInterruptedException(e)
        } finally {
            // Réponse finale reçue, erreur ou annulation : la connexion n'a plus d'usage.
            call.cancel()
        }
        return reader.result() ?: throw StreamInterruptedException()
    }

    private suspend fun request(body: JsonObject, streaming: Boolean): Request {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AssistantException(AssistantError.MissingKey)
        if (!isOnline()) throw AssistantException(AssistantError.NoInternet)
        val sent = if (!streaming) {
            body
        } else {
            // Pas de remplissage `obfuscation` dans les morceaux : moins d'octets sur le réseau mobile.
            JsonObject(body + mapOf("stream" to JsonPrimitive(true), "stream_options" to buildJsonObject { put("include_obfuscation", false) }))
        }
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .apply { if (streaming) header("Accept", "text/event-stream") }
            .post(sent.toString().toRequestBody(JSON))
            .build()
    }

    private fun parseBody(status: Int, text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject
            ?: throw AssistantException(AssistantError.Unexpected(status, "Réponse JSON inattendue"))
    } catch (e: SerializationException) {
        throw AssistantException(AssistantError.Unexpected(status, "Réponse illisible"), e)
    }

    /** Ce que le fil d'OkHttp transmet à la coroutine. */
    private sealed interface Piece {
        /** Corps entier : erreur HTTP, ou réponse JSON sans streaming. */
        class Whole(val status: Int, val ok: Boolean, val retryAfter: String?, val text: String) : Piece

        /** Statut 200 en `text/event-stream` : le flux commence. */
        data object Started : Piece

        class Line(val text: String) : Piece
    }

    companion object {
        /** Classe une réponse d'erreur HTTP d'OpenAI (corps JSON `{"error": {...}}`) ; partagé avec la dictée. */
        fun errorOf(status: Int, retryAfterHeader: String?, body: String): AssistantError {
            val error = try {
                (Json.parseToJsonElement(body) as? JsonObject)?.obj("error")
            } catch (e: SerializationException) {
                null
            }
            return OpenAiErrors.classify(
                status = status,
                code = error?.string("code"),
                type = error?.string("type"),
                retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull(),
                message = error?.string("message"),
            )
        }

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        private val JSON = "application/json".toMediaType()

        /** Délais adaptés à un modèle qui raisonne : la réponse peut prendre une minute. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        fun classify(e: IOException): AssistantError = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> AssistantError.NoInternet
            is SocketTimeoutException -> AssistantError.Timeout
            is InterruptedIOException -> if (e.message?.contains("timeout", ignoreCase = true) == true) AssistantError.Timeout else AssistantError.NoInternet
            else -> AssistantError.NoInternet
        }
    }
}

/** Exécute l'appel sans bloquer de thread ; l'annulation de la coroutine annule la requête. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
}
