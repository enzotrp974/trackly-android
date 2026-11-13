package app.trackly.backup.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Métadonnées utiles d'un fichier Drive. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String?,
    val parents: List<String> = emptyList(),
    val size: Long? = null,
    val md5Checksum: String? = null,
    val createdTime: Instant? = null,
    val trashed: Boolean = false,
    val appProperties: Map<String, String> = emptyMap(),
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        internal const val FIELDS = "id,name,mimeType,parents,size,md5Checksum,createdTime,trashed,appProperties"

        internal fun from(json: JSONObject): DriveFile = DriveFile(
            id = json.getString("id"),
            name = json.optString("name"),
            mimeType = json.optString("mimeType").ifEmpty { null },
            parents = json.optJSONArray("parents")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            // Drive renvoie `size` (int64) sous forme de chaîne.
            size = json.optString("size").toLongOrNull(),
            md5Checksum = json.optString("md5Checksum").ifEmpty { null },
            createdTime = json.optString("createdTime").ifEmpty { null }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            trashed = json.optBoolean("trashed", false),
            appProperties = json.optJSONObject("appProperties")?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            }.orEmpty(),
        )
    }
}

/** Réponse d'erreur de l'API Drive (ou réponse illisible). */
class DriveHttpException(val code: Int, message: String, val reason: String? = null) : IOException("Drive $code : $message") {
    /** Erreur passagère : réessayer plus tard (quota, serveur). */
    val transient: Boolean
        get() = code == 429 || code >= 500 ||
            (code == 403 && reason in setOf("rateLimitExceeded", "userRateLimitExceeded"))
}

/** Fournit le jeton d'accès Drive (valable 1 h) et le renouvelle s'il est refusé. */
interface TokenSource {
    suspend fun token(): String

    /** Le jeton [stale] a été refusé (401) : l'oublier et en obtenir un nouveau. */
    suspend fun renew(stale: String): String
}

/**
 * Client minimal de l'API REST Drive v3 (sans la bibliothèque Java Google), limité à ce dont
 * la sauvegarde a besoin. Avec la portée `drive.file`, il ne voit que les fichiers créés par Trackly.
 *
 * Un 401 déclenche un seul renouvellement du jeton puis une seule nouvelle tentative : la requête
 * refusée n'a pas été exécutée, la rejouer est sans risque.
 */
class DriveApi(
    private val tokens: TokenSource,
    private val http: OkHttpClient = defaultClient(),
    private val apiBase: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
    private val uploadBase: HttpUrl = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl(),
    /** Au-delà, envoi « resumable » plutôt que « multipart » (doc Drive : multipart jusqu'à 5 Mo). */
    private val multipartLimitBytes: Long = 5L * 1024 * 1024,
) {
    /** Métadonnées d'un fichier, ou `null` s'il n'existe pas (ou plus) pour Trackly. */
    suspend fun getFile(id: String): DriveFile? {
        val url = apiBase.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", DriveFile.FIELDS).build()
        return try {
            DriveFile.from(json(Request.Builder().url(url).get()))
        } catch (e: DriveHttpException) {
            if (e.code == 404) null else throw e
        }
    }

    /** Recherche (`q`), toutes pages comprises. */
    suspend fun search(query: String, orderBy: String? = null): List<DriveFile> {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = apiBase.newBuilder().addPathSegment("files")
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,files(${DriveFile.FIELDS})")
                .apply {
                    if (orderBy != null) addQueryParameter("orderBy", orderBy)
                    if (pageToken != null) addQueryParameter("pageToken", pageToken)
                }
                .build()
            val page = json(Request.Builder().url(url).get())
            val files = page.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) all += DriveFile.from(files.getJSONObject(i))
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return all
    }

    /** Crée un dossier à la racine de « Mon Drive ». */
    suspend fun createFolder(name: String, appProperties: Map<String, String>): DriveFile {
        val url = apiBase.newBuilder().addPathSegment("files").addQueryParameter("fields", DriveFile.FIELDS).build()
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", DriveFile.FOLDER_MIME)
            .put("appProperties", JSONObject(appProperties))
        return DriveFile.from(json(Request.Builder().url(url).post(body.toString().toRequestBody(JSON))))
    }

    /** Envoie un nouveau fichier dans [parentId] (jamais d'écrasement d'un fichier existant). */
    suspend fun upload(
        name: String,
        parentId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: File,
    ): DriveFile {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray(listOf(parentId)))
            .put("mimeType", mimeType)
            .put("appProperties", JSONObject(appProperties))
            .toString()
        val media = mimeType.toMediaType()
        return if (content.length() <= multipartLimitBytes) {
            val url = uploadBase.newBuilder().addPathSegment("files")
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", DriveFile.FIELDS).build()
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody(JSON))
                .addPart(content.asRequestBody(media))
                .build()
            DriveFile.from(json(Request.Builder().url(url).post(body)))
        } else {
            // Envoi « resumable » : ouverture d'une session, puis le contenu en une requête.
            val url = uploadBase.newBuilder().addPathSegment("files")
                .addQueryParameter("uploadType", "resumable")
                .addQueryParameter("fields", DriveFile.FIELDS).build()
            val session = execute(
                Request.Builder().url(url)
                    .header("X-Upload-Content-Type", mimeType)
                    .header("X-Upload-Content-Length", content.length().toString())
                    .post(metadata.toRequestBody(JSON)),
            ) { response -> response.header("Location") ?: throw DriveHttpException(response.code, "session d'envoi sans adresse") }
            DriveFile.from(json(Request.Builder().url(session).put(content.asRequestBody(media))))
        }
    }

    /** Télécharge le contenu d'un fichier dans [target]. */
    suspend fun download(id: String, target: File) {
        val url = apiBase.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("alt", "media").build()
        execute(Request.Builder().url(url).get()) { response ->
            val body = response.body
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    /** Pose des propriétés privées sur un fichier créé par Trackly ; renvoie le fichier relu. */
    suspend fun setAppProperties(id: String, appProperties: Map<String, String>): DriveFile {
        val url = apiBase.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", DriveFile.FIELDS).build()
        val body = JSONObject().put("appProperties", JSONObject(appProperties)).toString()
        return DriveFile.from(json(Request.Builder().url(url).patch(body.toRequestBody(JSON))))
    }

    /** Met un fichier à la corbeille de Drive (récupérable), sans le supprimer définitivement. */
    suspend fun trash(id: String) {
        val url = apiBase.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", "id,trashed").build()
        val result = json(Request.Builder().url(url).patch(JSONObject().put("trashed", true).toString().toRequestBody(JSON)))
        if (!result.optBoolean("trashed", false)) throw DriveHttpException(200, "mise à la corbeille non confirmée")
    }

    private suspend fun json(request: Request.Builder): JSONObject = execute(request) { response ->
        val text = response.body.string()
        try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw DriveHttpException(response.code, "réponse illisible")
        }
    }

    private suspend fun <T> execute(request: Request.Builder, read: (Response) -> T): T {
        var token = tokens.token()
        repeat(2) { attempt ->
            val call = request.header("Authorization", "Bearer $token").build()
            val outcome = withContext(Dispatchers.IO) {
                http.newCall(call).execute().use { response ->
                    when {
                        response.code == 401 && attempt == 0 -> null
                        response.isSuccessful -> Result.success(read(response))
                        else -> Result.failure(errorOf(response))
                    }
                }
            }
            if (outcome != null) return outcome.getOrThrow()
            token = tokens.renew(token)
        }
        error("inatteignable")
    }

    private fun errorOf(response: Response): DriveHttpException {
        val text = runCatching { response.body.string() }.getOrDefault("")
        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifEmpty { null }
        val message = error?.optString("message")?.ifEmpty { null } ?: response.message.ifEmpty { "erreur" }
        return DriveHttpException(response.code, message, reason)
    }

    companion object {
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Échappe une valeur pour une requête Drive (`q`). */
        fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}
