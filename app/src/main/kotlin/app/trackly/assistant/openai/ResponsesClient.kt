package app.trackly.assistant.openai

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * Envoie une requête `POST /v1/responses` et renvoie le JSON de la réponse.
 * Lève [AssistantException] en cas d'échec, déjà classé. Implémentations : HTTP réel
 * ([OkHttpResponsesTransport]) et faux transports des tests (aucun appel réseau).
 */
fun interface ResponsesTransport {
    suspend fun send(body: JsonObject): JsonObject

    /**
     * Même requête en streaming (`stream: true`) : les événements utiles vont à [listener] au fil de l'eau,
     * la valeur rendue est la réponse finale (celle de `response.completed`), de même forme que [send].
     * Lève [StreamInterruptedException] si le flux s'arrête avant l'événement final. Par défaut (faux
     * transports des tests), pas de streaming : la réponse complète, sans événement.
     */
    suspend fun stream(body: JsonObject, listener: StreamListener): JsonObject = send(body)
}

/** Essais automatiques sur les erreurs temporaires (429 de débit, 5xx). */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
) {
    fun delayFor(attempt: Int, retryAfterSeconds: Long?, random: Random = Random.Default): Long {
        retryAfterSeconds?.let { return (it * 1_000).coerceIn(0, maxDelayMs) }
        val exp = baseDelayMs shl attempt.coerceAtMost(10)
        return (exp + random.nextLong(0, baseDelayMs / 2 + 1)).coerceAtMost(maxDelayMs)
    }

    companion object {
        val None = RetryPolicy(maxRetries = 0)
    }
}

/**
 * Client de la Responses API : construit la requête, réessaie les erreurs temporaires
 * (jamais la clé refusée ni le crédit épuisé), lit la réponse.
 */
class ResponsesClient(
    private val transport: ResponsesTransport,
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Avec [listener], la réponse arrive en streaming et ses étapes sont signalées au fil de l'eau. Si le flux
     * est coupé avant la fin, la même requête repart **une fois** sans streaming (repli) : la réponse rendue est
     * toujours complète, et rien n'a pu être exécuté entre-temps (les outils ne le sont que sur une réponse
     * complète, par l'interpréteur). Essais automatiques et classement des erreurs : identiques dans les deux cas.
     */
    suspend fun create(request: ResponsesRequest, listener: StreamListener? = null): ModelResponse {
        val body = request.toJson()
        var attempt = 0
        var streaming = listener != null
        while (true) {
            try {
                val json = if (streaming && listener != null) {
                    try {
                        transport.stream(body, listener)
                    } catch (e: StreamInterruptedException) {
                        streaming = false
                        listener.onEvent(StreamEvent.Restarted)
                        continue
                    }
                } else {
                    transport.send(body)
                }
                val response = ModelResponse.parse(json)
                if (response.status == "failed") {
                    throw AssistantException(AssistantError.ServerError(500))
                }
                return response
            } catch (e: AssistantException) {
                val error = e.error
                if (!OpenAiErrors.isTransient(error) || attempt >= retry.maxRetries) throw e
                sleep(retry.delayFor(attempt, (error as? AssistantError.RateLimited)?.retryAfterSeconds))
                // Le texte déjà montré pour l'essai raté est oublié.
                listener?.onEvent(StreamEvent.Restarted)
                attempt++
            }
        }
    }
}
