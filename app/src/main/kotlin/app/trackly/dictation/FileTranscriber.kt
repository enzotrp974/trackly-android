package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.await
import app.trackly.assistant.openai.obj
import app.trackly.assistant.openai.string
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Réglages d'une transcription d'un fichier audio. */
data class FileTranscriptionConfig(
    val model: String = DictationMode.AfterStop.model,
    /** Langues attendues (`languages`, au pluriel : `language` n'existe pas avec ces modèles). */
    val languages: List<String> = listOf("fr"),
    val keywords: List<String> = emptyList(),
)

/** Texte transcrit, et la durée facturée si la réponse la donne (`usage.type = "duration"`). */
data class FileTranscription(val text: String, val seconds: Double?)

/** Transcription d'un enregistrement entier (mode « après l'arrêt »). */
fun interface FileTranscriber {
    /** @throws AssistantException si l'appel échoue (même classification que le chat). */
    suspend fun transcribe(wav: ByteArray, config: FileTranscriptionConfig): FileTranscription
}

/**
 * `POST /v1/audio/transcriptions`, formulaire multipart : `model`, `file` (WAV nommé `dictee.wav`,
 * extension et type de contenu cohérents comme le recommande la doc), `keywords[]`, `languages[]`.
 * WAV 24 kHz mono 16 bits : environ 2,9 Mo par minute, donc 8 minutes au maximum sous la limite de 25 Mo.
 */
class OkHttpFileTranscriber(
    private val client: OkHttpClient,
    private val apiKey: suspend () -> String?,
    baseUrl: HttpUrl = OkHttpResponsesTransport.DEFAULT_BASE_URL.toHttpUrl(),
    private val isOnline: () -> Boolean = { true },
) : FileTranscriber {
    private val endpoint: HttpUrl = baseUrl.newBuilder().addPathSegments("audio/transcriptions").build()

    override suspend fun transcribe(wav: ByteArray, config: FileTranscriptionConfig): FileTranscription {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AssistantException(AssistantError.MissingKey)
        if (!isOnline()) throw AssistantException(AssistantError.NoInternet)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("file", FILE_NAME, wav.toRequestBody(WAV))
        for (language in config.languages) form.addFormDataPart("languages[]", language)
        for (keyword in config.keywords) form.addFormDataPart("keywords[]", keyword)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(form.build())
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AssistantException(OkHttpResponsesTransport.classify(e), e)
        }
        response.use { r ->
            val body = try {
                r.body.string()
            } catch (e: IOException) {
                throw AssistantException(OkHttpResponsesTransport.classify(e), e)
            }
            if (!r.isSuccessful) throw AssistantException(OkHttpResponsesTransport.errorOf(r.code, r.header("Retry-After"), body))
            return parse(body, r.code)
        }
    }

    private fun parse(body: String, status: Int): FileTranscription {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw AssistantException(AssistantError.Unexpected(status, "Réponse illisible"))
        val text = json.string("text") ?: throw AssistantException(AssistantError.Unexpected(status, "Réponse sans texte"))
        return FileTranscription(text.trim(), json.obj("usage")?.let(::durationSeconds))
    }

    companion object {
        const val FILE_NAME = "dictee.wav"
        private val WAV = "audio/wav".toMediaType()
    }
}

/** Durée facturée d'un bloc `usage`, si c'est `{"type":"duration","seconds":…}` (sinon, des jetons : on mesure nous-mêmes). */
internal fun durationSeconds(usage: JsonObject): Double? {
    if (usage.string("type") != "duration") return null
    return (usage["seconds"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }
}
