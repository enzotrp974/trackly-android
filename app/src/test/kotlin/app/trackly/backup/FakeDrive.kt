package app.trackly.backup

import android.app.PendingIntent
import app.trackly.backup.drive.DriveApi
import app.trackly.backup.drive.DriveAuthorization
import app.trackly.backup.drive.DriveAuthorizer
import app.trackly.backup.drive.TokenSource
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Faux Google Drive (API REST v3) sur MockWebServer, en mémoire : création de dossier, envoi
 * multipart et resumable, métadonnées, recherche paginée, téléchargement, mise à la corbeille.
 * Il refuse tout jeton autre que [validToken] (401), comme Drive avec un jeton expiré.
 * Aucun appel réseau réel.
 */
class FakeDrive : Dispatcher() {
    data class StoredFile(
        val id: String,
        var name: String,
        val mimeType: String,
        val parents: List<String>,
        var content: ByteArray,
        val createdTime: Instant,
        var trashed: Boolean = false,
        val appProperties: MutableMap<String, String> = mutableMapOf(),
        /** Somme renvoyée par Drive (différente du contenu pour simuler une corruption). */
        var reportedMd5: String? = null,
    ) {
        fun md5(): String = reportedMd5 ?: md5Of(content)
    }

    val server = MockWebServer()
    val files = linkedMapOf<String, StoredFile>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var validToken = "jeton-1"
    @Volatile var now: Instant = Instant.parse("2026-11-03T02:00:00Z")

    /** Taille des pages de recherche (petite, pour exercer la pagination). */
    var pageSize = 2

    /** Simule Drive qui ignore `appProperties` à la création d'un dossier. */
    var ignoreFolderPropertiesOnCreate = false

    /** Le prochain envoi est « abîmé » : Drive renvoie une autre somme MD5. */
    var corruptNextUpload = false

    /** Appelé à la réception de chaque envoi, avant la réponse : de quoi simuler un changement pendant l'envoi. */
    @Volatile var onUpload: (() -> Unit)? = null

    /** Code d'erreur renvoyé par les prochaines requêtes (null = normal). */
    @Volatile var failWith: Int? = null

    private var nextId = 1
    private val sessions = mutableMapOf<String, JSONObject>()

    init {
        server.dispatcher = this
        server.start()
    }

    fun api(tokens: TokenSource, multipartLimit: Long = 5L * 1024 * 1024) = DriveApi(
        tokens = tokens,
        apiBase = server.url("/drive/v3/"),
        uploadBase = server.url("/upload/drive/v3/"),
        multipartLimitBytes = multipartLimit,
    )

    fun shutdown() = server.close()

    // ───── Préparation des scénarios ─────

    fun addFolder(name: String, properties: Map<String, String>, createdTime: Instant = now, trashed: Boolean = false): StoredFile =
        store(name, "application/vnd.google-apps.folder", emptyList(), ByteArray(0), properties, createdTime).also { it.trashed = trashed }

    fun addFile(
        name: String,
        parent: String,
        content: ByteArray,
        properties: Map<String, String> = emptyMap(),
        createdTime: Instant = now,
        mimeType: String = BackupFiles.MIME,
    ): StoredFile = store(name, mimeType, listOf(parent), content, properties, createdTime)

    fun folders() = files.values.filter { it.mimeType == "application/vnd.google-apps.folder" }

    fun childrenOf(folderId: String) = files.values.filter { folderId in it.parents }

    /** Envois reçus (simple ou en plusieurs fois). */
    fun uploads() = requests.count { it.url.pathSegments.firstOrNull() == "upload" && it.method == "POST" }

    // ───── Dispatcher ─────

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        failWith?.let { return error(it, "backendError") }
        if (request.headers["Authorization"] != "Bearer $validToken") return error(401, "authError")
        val url = request.url
        val segments = url.pathSegments
        val method = request.method
        return synchronized(this) {
            when {
                segments.take(3) == listOf("upload", "drive", "v3") && method == "POST" -> startUpload(request, url)
                segments.take(2) == listOf("upload", "session") && method == "PUT" -> finishUpload(segments[2], request.body)
                segments.take(3) == listOf("drive", "v3", "files") && segments.size == 3 && method == "GET" -> search(url)
                segments.take(3) == listOf("drive", "v3", "files") && segments.size == 3 && method == "POST" -> createFolder(request)
                segments.take(3) == listOf("drive", "v3", "files") && segments.size == 4 -> {
                    val file = files[segments[3]] ?: return@synchronized error(404, "notFound")
                    when {
                        method == "GET" && url.queryParameter("alt") == "media" ->
                            MockResponse.Builder().code(200).body(Buffer().write(file.content)).build()
                        method == "GET" -> json(meta(file))
                        method == "PATCH" -> patch(file, request)
                        else -> error(405, "badMethod")
                    }
                }
                else -> error(404, "notFound")
            }
        }
    }

    private fun startUpload(request: RecordedRequest, url: HttpUrl): MockResponse =
        when (url.queryParameter("uploadType")) {
            "multipart" -> {
                val (metadata, content) = parseMultipart(request)
                json(meta(fileFromMetadata(metadata, content)))
            }
            "resumable" -> {
                val id = "session-${nextId++}"
                sessions[id] = JSONObject(request.body!!.utf8())
                MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/session/$id").toString()).build()
            }
            else -> error(400, "badUploadType")
        }

    private fun finishUpload(session: String, body: ByteString?): MockResponse {
        val metadata = sessions.remove(session) ?: return error(404, "notFound")
        return json(meta(fileFromMetadata(metadata, body?.toByteArray() ?: ByteArray(0))))
    }

    private fun fileFromMetadata(metadata: JSONObject, content: ByteArray): StoredFile {
        onUpload?.invoke()
        val parents = metadata.optJSONArray("parents")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val file = store(metadata.getString("name"), metadata.optString("mimeType", "application/octet-stream"), parents, content, props(metadata), now)
        if (corruptNextUpload) {
            file.reportedMd5 = "00000000000000000000000000000000"
            corruptNextUpload = false
        }
        return file
    }

    private fun createFolder(request: RecordedRequest): MockResponse {
        val metadata = JSONObject(request.body!!.utf8())
        val properties = if (ignoreFolderPropertiesOnCreate) emptyMap() else props(metadata)
        val folder = store(metadata.getString("name"), metadata.getString("mimeType"), emptyList(), ByteArray(0), properties, now)
        return json(meta(folder))
    }

    private fun patch(file: StoredFile, request: RecordedRequest): MockResponse {
        val body = JSONObject(request.body!!.utf8())
        if (body.has("trashed")) file.trashed = body.getBoolean("trashed")
        body.optJSONObject("appProperties")?.let { o -> o.keys().forEach { k -> file.appProperties[k] = o.getString(k) } }
        return json(meta(file))
    }

    private fun search(url: HttpUrl): MockResponse {
        val q = url.queryParameter("q").orEmpty()
        val matching = files.values.filter { matches(it, q) }
        val start = url.queryParameter("pageToken")?.toInt() ?: 0
        val page = matching.drop(start).take(pageSize)
        val result = JSONObject().put("files", JSONArray(page.map { meta(it) }))
        if (start + pageSize < matching.size) result.put("nextPageToken", (start + pageSize).toString())
        return json(result)
    }

    /** Sous-ensemble de la syntaxe `q` utilisé par Trackly. */
    private fun matches(file: StoredFile, q: String): Boolean {
        Regex("""mimeType = '([^']+)'""").find(q)?.let { if (file.mimeType != it.groupValues[1]) return false }
        if ("trashed = false" in q && file.trashed) return false
        Regex("""'([^']+)' in parents""").find(q)?.let { if (it.groupValues[1] !in file.parents) return false }
        Regex("""appProperties has \{ key='([^']+)' and value='([^']+)' \}""").findAll(q).forEach {
            if (file.appProperties[it.groupValues[1]] != it.groupValues[2]) return false
        }
        return true
    }

    private fun store(
        name: String,
        mimeType: String,
        parents: List<String>,
        content: ByteArray,
        properties: Map<String, String>,
        createdTime: Instant,
    ): StoredFile {
        val file = StoredFile("f${nextId++}", name, mimeType, parents, content, createdTime, appProperties = properties.toMutableMap())
        files[file.id] = file
        return file
    }

    private fun meta(file: StoredFile): JSONObject = JSONObject()
        .put("id", file.id)
        .put("name", file.name)
        .put("mimeType", file.mimeType)
        .put("parents", JSONArray(file.parents))
        .put("createdTime", file.createdTime.toString())
        .put("trashed", file.trashed)
        .put("appProperties", JSONObject(file.appProperties as Map<*, *>))
        .apply {
            if (file.mimeType != "application/vnd.google-apps.folder") {
                put("size", file.content.size.toString())
                put("md5Checksum", file.md5())
            }
        }

    private fun props(metadata: JSONObject): Map<String, String> =
        metadata.optJSONObject("appProperties")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()

    private fun parseMultipart(request: RecordedRequest): Pair<JSONObject, ByteArray> {
        val contentType = request.headers["Content-Type"]!!
        require(contentType.startsWith("multipart/related")) { "type d'envoi inattendu : $contentType" }
        val boundary = contentType.substringAfter("boundary=").trim('"')
        val body = request.body!!.toByteArray()
        val delimiter = "--$boundary".toByteArray()
        val parts = mutableListOf<ByteArray>()
        var index = indexOf(body, delimiter, 0)
        while (index >= 0) {
            val start = index + delimiter.size
            if (body.size >= start + 2 && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) break
            val next = indexOf(body, delimiter, start)
            parts += body.copyOfRange(start + 2, next - 2) // retire les CRLF autour de la partie
            index = next
        }
        fun split(part: ByteArray): Pair<String, ByteArray> {
            val sep = indexOf(part, "\r\n\r\n".toByteArray(), 0)
            return String(part, 0, sep) to part.copyOfRange(sep + 4, part.size)
        }
        val (metaHeaders, metaBody) = split(parts[0])
        require("application/json" in metaHeaders) { "les métadonnées doivent venir en premier, en JSON" }
        val (_, content) = split(parts[1])
        return JSONObject(String(metaBody)) to content
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun json(body: JSONObject) =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json; charset=UTF-8").body(body.toString()).build()

    private fun error(code: Int, reason: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json; charset=UTF-8")
        .body(JSONObject().put("error", JSONObject().put("code", code).put("message", reason).put("errors", JSONArray().put(JSONObject().put("reason", reason)))).toString())
        .build()

    companion object {
        fun md5Of(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Faux `AuthorizationClient` : jeton immédiat, ou demande d'écran Google, ou échec. */
class FakeAuthorizer(private val drive: FakeDrive?) : DriveAuthorizer {
    var next: () -> DriveAuthorization = { DriveAuthorization.Granted(drive?.validToken ?: "jeton", "test@example.com") }
    val cleared = mutableListOf<String>()
    var authorizeCalls = 0

    override suspend fun authorize(): DriveAuthorization {
        authorizeCalls++
        return next()
    }

    override suspend fun clearToken(token: String) {
        cleared += token
    }

    fun needsUserAction(pendingIntent: PendingIntent? = null) {
        next = { DriveAuthorization.NeedsUserAction(pendingIntent) }
    }

    fun granted() {
        next = { DriveAuthorization.Granted(drive?.validToken ?: "jeton", "test@example.com") }
    }
}
