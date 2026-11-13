package app.trackly.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/** Sauvegarde Drive de bout en bout : vraie base Room sur fichier, faux Drive. */
@RunWith(AndroidJUnit4::class)
class BackupRunnerTest {
    private val f = BackupFixture()

    @After
    fun close() = f.close()

    @Test
    fun `envoie une copie datee, verifiee, dans le dossier Trackly`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        f.clock.instant = Instant.parse("2026-11-03T02:12:05Z") // 03:12:05 heure locale

        val result = f.runner.backUp()

        assertTrue(result.toString(), result is BackupResult.Success)
        val uploaded = f.drive.childrenOf(folder.id).single()
        assertEquals("trackly-2026-11-03_03-12-05.db", uploaded.name)
        assertEquals(BackupFiles.KIND_DATABASE, uploaded.appProperties[BackupFiles.KIND_KEY])
        assertEquals(TracklyDatabase.VERSION.toString(), uploaded.appProperties[BackupFiles.SCHEMA_KEY])
        assertEquals(DatabaseFilesSha.of(uploaded.content), uploaded.appProperties[BackupFiles.SHA256_KEY])
        // Relecture après écriture : métadonnées relues après l'envoi.
        assertTrue(f.drive.requests.any { it.method == "GET" && it.url.pathSegments.lastOrNull() == uploaded.id })
        // Statut visible dans les réglages.
        assertEquals(Instant.parse("2026-11-03T02:12:05Z"), f.status.status.value.lastSuccessAt)
        assertEquals(uploaded.name, f.status.status.value.lastSuccessName)
        // La copie locale de travail n'est pas gardée.
        assertFalse(f.status.status.value.failing)
    }

    @Test
    fun `la copie envoyee contient toute la base, y compris les tables inconnues`() = runBlocking<Unit> {
        f.seed()
        // Table ajoutée par une future version : la copie de fichier la prend sans rien savoir d'elle.
        f.database.openHelper.writableDatabase.execSQL("CREATE TABLE future_table (x TEXT)")
        f.database.openHelper.writableDatabase.execSQL("INSERT INTO future_table VALUES ('conservé')")
        val folder = f.connectWithFolder()

        f.runner.backUp()

        val copy = kotlin.io.path.createTempFile(suffix = ".db").toFile().apply { writeBytes(f.drive.childrenOf(folder.id).single().content) }
        android.database.sqlite.SQLiteDatabase.openDatabase(copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT x FROM future_table", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("conservé", c.getString(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM consumption_versions", null).use { c ->
                c.moveToFirst()
                assertEquals(3, c.getInt(0))
            }
        }
        copy.delete()
    }

    @Test
    fun `copie sous verrou d ecriture si VACUUM INTO n est pas disponible`() = runBlocking<Unit> {
        f.seed()
        val snapshot = DatabaseSnapshotter({ f.database }, forceLockedCopy = true).snapshot(java.io.File(f.context.cacheDir, "verrou.db"))
        val info = DatabaseFiles.inspect(snapshot.file)!!
        assertTrue(info.intact)
        assertEquals(TracklyDatabase.VERSION, info.schemaVersion)
        assertFalse(java.io.File(snapshot.file.path + "-wal").exists())
        snapshot.file.delete()
    }

    @Test
    fun `rotation limitee aux sauvegardes Trackly reconnues`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        val now = Instant.parse("2026-11-03T02:00:00Z")
        f.clock.instant = now
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE)
        // 9 copies d'hier (une par heure) : avec la neuve, ce sont les 10 plus récentes.
        repeat(9) { h -> f.drive.addFile("trackly-2026-11-02_0$h-00-00.db", folder.id, byteArrayOf(9), props, now.minus(Duration.ofHours(12L + h))) }
        val old = f.drive.addFile("trackly-2026-10-01_03-00-00.db", folder.id, byteArrayOf(1), props, now.minus(Duration.ofDays(33)))
        val recent = f.drive.addFile("trackly-2026-10-10_03-00-00.db", folder.id, byteArrayOf(2), props, now.minus(Duration.ofDays(24)))
        // Ni marqués, ni nommés comme une sauvegarde : jamais touchés, même anciens.
        val foreignName = f.drive.addFile("notes.db", folder.id, byteArrayOf(3), props, now.minus(Duration.ofDays(90)))
        val unmarked = f.drive.addFile("trackly-2026-09-01_03-00-00.db", folder.id, byteArrayOf(4), emptyMap(), now.minus(Duration.ofDays(60)))
        val elsewhere = f.drive.addFile("trackly-2026-09-02_03-00-00.db", "autre-dossier", byteArrayOf(5), props, now.minus(Duration.ofDays(60)))

        val result = f.runner.backUp() as BackupResult.Success

        assertEquals(1, result.trashedOld)
        assertTrue(f.drive.files[old.id]!!.trashed)
        assertFalse(f.drive.files[recent.id]!!.trashed)
        assertFalse(f.drive.files[foreignName.id]!!.trashed)
        assertFalse(f.drive.files[unmarked.id]!!.trashed)
        assertFalse(f.drive.files[elsewhere.id]!!.trashed)
        // Mise à la corbeille, jamais de suppression définitive.
        assertTrue(f.drive.requests.none { it.method == "DELETE" })
        assertEquals(15, f.drive.files.values.count { !it.trashed })
    }

    @Test
    fun `relecture differente - echec et fichier envoye retire`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        f.drive.corruptNextUpload = true

        val result = f.runner.backUp() as BackupResult.Failure

        assertEquals(FailureKind.Verification, result.kind)
        assertTrue(f.drive.childrenOf(folder.id).single().trashed)
        assertTrue(f.status.status.value.failing)
        assertNull(f.status.status.value.lastSuccessAt)
    }

    @Test
    fun `dossier introuvable - rien n est ecrit et rien n est cree`() = runBlocking<Unit> {
        f.seed()
        f.status.markConnected(null)
        val other = f.drive.addFolder("Trackly", emptyMap()) // créé à la main : pas de marque, ignoré

        val result = f.runner.backUp() as BackupResult.Failure

        assertEquals(FailureKind.FolderMissing, result.kind)
        assertEquals(1, f.drive.files.size)
        assertTrue(f.drive.childrenOf(other.id).isEmpty())
        assertTrue(f.drive.requests.none { it.method == "POST" })
        assertTrue(f.status.status.value.needsReconnect)
    }

    @Test
    fun `dossier memorise mis a la corbeille - retrouve un autre dossier Trackly par la recherche`() = runBlocking<Unit> {
        f.seed()
        val trashed = f.connectWithFolder().also { it.trashed = true }
        val found = f.drive.addFolder("Trackly", BackupFiles.folderProperties())

        assertTrue(f.runner.backUp() is BackupResult.Success)

        assertTrue(f.drive.childrenOf(trashed.id).isEmpty())
        assertEquals(1, f.drive.childrenOf(found.id).size)
        assertEquals(found.id, f.status.folderId)
    }

    @Test
    fun `apres reinstallation le dossier est retrouve par la recherche`() = runBlocking<Unit> {
        f.seed()
        f.status.markConnected(null)
        val folder = f.drive.addFolder("Trackly", BackupFiles.folderProperties())
        assertNull(f.status.folderId)

        assertTrue(f.runner.backUp() is BackupResult.Success)
        assertEquals(folder.id, f.status.folderId)
    }

    @Test
    fun `reconnexion demandee - echec signale, aucune ecriture`() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        f.auth.needsUserAction()

        val result = f.runner.backUp() as BackupResult.Failure

        assertEquals(FailureKind.NeedsReconnect, result.kind)
        assertNotNull(result.userAction)
        assertTrue(f.status.status.value.needsReconnect)
        assertTrue(f.drive.requests.isEmpty())
    }

    @Test
    fun `Drive indisponible - erreur passagere`() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        f.drive.failWith = 503
        val result = f.runner.backUp() as BackupResult.Failure
        assertEquals(FailureKind.Network, result.kind)
        assertTrue(result.retryable)
    }

    @Test
    fun `sans connexion Google - rien n est tente`() = runBlocking<Unit> {
        val result = f.runner.backUp() as BackupResult.Failure
        assertEquals(FailureKind.NotConnected, result.kind)
        assertEquals(0, f.auth.authorizeCalls)
    }
}

/** SHA-256 en hexadécimal d'un contenu en mémoire. */
object DatabaseFilesSha {
    fun of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
