package app.trackly.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.backup.drive.AuthorizerTokenSource
import app.trackly.backup.drive.DriveAuthException
import app.trackly.backup.drive.DriveHttpException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Client REST Drive contre un faux Drive local (MockWebServer), sans réseau réel. */
@RunWith(AndroidJUnit4::class)
class DriveApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val drive = FakeDrive()
    private val auth = FakeAuthorizer(drive)

    @After
    fun close() = drive.shutdown()

    private fun api(multipartLimit: Long = 5L * 1024 * 1024) = drive.api(AuthorizerTokenSource(auth), multipartLimit)

    private fun file(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun `cree un dossier marque puis le relit`() = runBlocking<Unit> {
        val created = api().createFolder("Trackly", mapOf("trackly_role" to "backups"))
        val reread = api().getFile(created.id)!!
        assertTrue(reread.isFolder)
        assertEquals("Trackly", reread.name)
        assertEquals("backups", reread.appProperties["trackly_role"])
        assertEquals("Bearer jeton-1", drive.requests.first().headers["Authorization"])
    }

    @Test
    fun `envoi multipart puis relecture taille et md5`() = runBlocking<Unit> {
        val folder = drive.addFolder("Trackly", emptyMap())
        val bytes = ByteArray(3000) { (it % 251).toByte() }
        val uploaded = api().upload("trackly-2026-11-03_03-00-00.db", folder.id, BackupFiles.MIME, mapOf("k" to "v"), file(bytes))
        val reread = api().getFile(uploaded.id)!!
        assertEquals(3000L, reread.size)
        assertEquals(FakeDrive.md5Of(bytes), reread.md5Checksum)
        assertEquals(listOf(folder.id), reread.parents)
        assertEquals("v", reread.appProperties["k"])
        assertEquals("multipart", drive.requests.first { it.method == "POST" }.url.queryParameter("uploadType"))
    }

    @Test
    fun `gros fichier envoye en resumable`() = runBlocking<Unit> {
        val folder = drive.addFolder("Trackly", emptyMap())
        val bytes = ByteArray(10_000) { (it % 7).toByte() }
        val uploaded = api(multipartLimit = 1024).upload("gros.db", folder.id, BackupFiles.MIME, emptyMap(), file(bytes))
        assertEquals(10_000L, uploaded.size)
        assertTrue(drive.files[uploaded.id]!!.content.contentEquals(bytes))
        assertEquals("resumable", drive.requests.first { it.method == "POST" }.url.queryParameter("uploadType"))
        assertEquals("PUT", drive.requests.last().method)
    }

    @Test
    fun `recherche sur plusieurs pages`() = runBlocking<Unit> {
        val folder = drive.addFolder("Trackly", emptyMap())
        repeat(5) { drive.addFile("f$it.db", folder.id, byteArrayOf(it.toByte()), mapOf("trackly_kind" to "db_backup")) }
        val found = api().search(BackupFiles.backupsQuery(folder.id))
        assertEquals(5, found.size)
        assertTrue(drive.requests.count { it.url.queryParameter("pageToken") != null } >= 2)
    }

    @Test
    fun `telechargement et mise a la corbeille`() = runBlocking<Unit> {
        val folder = drive.addFolder("Trackly", emptyMap())
        val stored = drive.addFile("a.db", folder.id, "contenu".toByteArray())
        val target = tmp.newFile()
        api().download(stored.id, target)
        assertEquals("contenu", target.readText())

        api().trash(stored.id)
        assertTrue(drive.files[stored.id]!!.trashed)
        assertEquals("PATCH", drive.requests.last().method)
    }

    @Test
    fun `fichier absent vaut null`() = runBlocking<Unit> {
        assertNull(api().getFile("inexistant"))
    }

    @Test
    fun `jeton refuse une fois - renouvele puis requete rejouee`() = runBlocking<Unit> {
        val tokens = AuthorizerTokenSource(auth)
        val api = drive.api(tokens)
        api.search(BackupFiles.FOLDER_QUERY) // jeton-1 en cache
        drive.validToken = "jeton-2" // jeton-1 expiré côté Drive
        val result = api.search(BackupFiles.FOLDER_QUERY)
        assertTrue(result.isEmpty())
        assertEquals(listOf("jeton-1"), auth.cleared)
        assertEquals("Bearer jeton-2", drive.requests.last().headers["Authorization"])
    }

    @Test
    fun `reconnexion demandee au renouvellement - erreur d autorisation`() = runBlocking<Unit> {
        val tokens = AuthorizerTokenSource(auth)
        val api = drive.api(tokens)
        api.search(BackupFiles.FOLDER_QUERY)
        drive.validToken = "jeton-2"
        auth.needsUserAction()
        try {
            api.search(BackupFiles.FOLDER_QUERY)
            fail("une reconnexion était attendue")
        } catch (e: DriveAuthException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }

    @Test
    fun `erreur serveur signalee comme passagere`() = runBlocking<Unit> {
        drive.failWith = 503
        try {
            api().search(BackupFiles.FOLDER_QUERY)
            fail("erreur attendue")
        } catch (e: DriveHttpException) {
            assertEquals(503, e.code)
            assertTrue(e.transient)
        }
    }
}
