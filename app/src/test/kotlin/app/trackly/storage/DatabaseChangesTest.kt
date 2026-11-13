package app.trackly.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.backup.BackupFixture
import app.trackly.storage.db.DatabaseChanges
import app.trackly.storage.db.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Écoute des écritures dans la base : toutes les tables, rafales regroupées. */
@RunWith(AndroidJUnit4::class)
class DatabaseChangesTest {
    private val f = BackupFixture()

    @After
    fun close() = f.close()

    @Test
    fun `toutes les tables d une base neuve sont ecoutees`() {
        val sqlite = f.database.openHelper.readableDatabase
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }.filterNot { it in setOf("android_metadata", "room_master_table", "sqlite_sequence") || it.startsWith("sqlite_") }
        assertEquals(tables.toSet(), DatabaseChanges.TABLES.toSet())
    }

    @Test
    fun `une ecriture du moteur ou du chat signale un changement, une rafale n en donne qu un`() = runBlocking<Unit> {
        val changes = AtomicInteger()
        val job = launch(Dispatchers.IO) { DatabaseChanges.watch(f.database) { changes.incrementAndGet() } }
        delay(300) // écoute en place

        f.seed() // 4 actions à la suite
        withTimeout(5_000) { while (changes.get() == 0) delay(20) }
        delay(500)
        assertEquals("rafale regroupée", 1, changes.get())

        // Un message du chat seul, sans action du moteur : aussi un changement à copier.
        delay(DatabaseChanges.MIN_GAP_MS)
        val seen = changes.get()
        f.database.chat().insert(ChatMessageEntity(createdAt = 0L, role = "user", text = "Bonjour", cards = "[]"))
        withTimeout(5_000) { while (changes.get() == seen) delay(20) }
        assertTrue(changes.get() > seen)
        job.cancel()
    }
}
