package app.trackly.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.storage.db.PreMigrationBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Copie de la base avant une migration. */
@RunWith(AndroidJUnit4::class)
class PreMigrationBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "backup-test").apply { deleteRecursively() }
    private val database = context.getDatabasePath("backup-source.db").apply { parentFile?.mkdirs(); delete() }

    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun createDatabase(version: Int) {
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
            db.execSQL("INSERT INTO t VALUES ('donnée')")
            db.version = version
        }
    }

    @Test
    fun `copie une base plus ancienne que le code, avec son contenu`() {
        createDatabase(1)
        val copy = PreMigrationBackup.backupIfNeeded(database, 2, dir, clockAt("2026-11-02T12:00:00Z"))
        assertNotNull(copy)
        assertEquals("backup-source-v1-20261102-120000.db", copy!!.name)
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT x FROM t", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("donnée", c.getString(0))
            }
        }
    }

    @Test
    fun `aucune copie sans migration en vue`() {
        assertNull(PreMigrationBackup.backupIfNeeded(database, 2, dir, clockAt("2026-11-02T12:00:00Z")))
        createDatabase(2)
        assertNull(PreMigrationBackup.backupIfNeeded(database, 2, dir, clockAt("2026-11-02T12:00:00Z")))
    }

    @Test
    fun `seules les copies les plus recentes sont gardees`() {
        createDatabase(1)
        for (hour in 10..14) {
            PreMigrationBackup.backupIfNeeded(database, 2, dir, clockAt("2026-11-02T$hour:00:00Z"))
        }
        val names = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(
            listOf("backup-source-v1-20261102-120000.db", "backup-source-v1-20261102-130000.db", "backup-source-v1-20261102-140000.db"),
            names,
        )
    }
}
