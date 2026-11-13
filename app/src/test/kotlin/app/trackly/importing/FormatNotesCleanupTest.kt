package app.trackly.importing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.recordedSince
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.context.RecentActionsContext
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.RememberFact
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.AgentMemory
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.DatabaseContents
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Nettoyage des notes de mise en forme au lancement, sur la vraie base Room. Notes inventées. */
@RunWith(AndroidJUnit4::class)
class FormatNotesCleanupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val engine = TracklyEngine(RoomTracklyStore(db), Clock.fixed(Instant.parse("2026-11-06T18:00:00Z"), ZoneOffset.UTC))
    private val cleanup = FormatNotesCleanup(engine, baseHasData = { DatabaseContents.hasData(db) })

    @After
    fun close() = db.close()

    private suspend fun remember(topic: String, text: String) = (engine.execute(RememberFact(topic, text)) as ActionOutcome.Applied).result

    private suspend fun notes() = AgentMemory.notes(engine.activePreferences()).map { it.trigger }

    @Test
    fun `au premier lancement la note de forme part, les gouts restent, et c est annulable`() = runBlocking {
        remember("Affichage", "L'application n'affiche pas le Markdown : écrire en texte brut.")
        remember("Goûts", "Aime le chocolat noir et les sauces.")

        val outcome = cleanup.runOnce() as ActionOutcome.Applied
        assertEquals(listOf("Goûts"), notes())

        // Lancement suivant : rien ne se refait.
        assertTrue(cleanup.runOnce() is ActionOutcome.Rejected)

        engine.execute(Undo(outcome.result.id)) as ActionOutcome.Applied
        assertEquals(listOf("Affichage", "Goûts"), notes())
        // L'annulation est respectée aux lancements suivants.
        assertTrue(cleanup.runOnce() is ActionOutcome.Rejected)
        assertEquals(listOf("Affichage", "Goûts"), notes())
    }

    @Test
    fun `rien a retirer - le passage est inscrit dans la base, une note de forme retenue ensuite n est jamais retiree`() = runBlocking {
        remember("Goûts", "Aime le chocolat noir.")
        val pass = cleanup.runOnce() as ActionOutcome.Applied
        assertTrue(pass.result.changes.isEmpty())

        remember("Tableaux", "Veut voir un tableau pour comparer.")
        // Même base, ou sa copie restaurée plus tard : le journal garde la trace du passage.
        assertTrue(cleanup.runOnce() is ActionOutcome.Rejected)
        assertEquals(listOf("Goûts", "Tableaux"), notes())
    }

    @Test
    fun `base vide - rien n est fait, le prechargement peut encore apporter des notes`() = runBlocking {
        assertNull(cleanup.runOnce())
        remember("Affichage", "Écrire en texte brut.")
        assertTrue(cleanup.runOnce() is ActionOutcome.Applied)
        assertTrue(notes().isEmpty())
    }

    @Test
    fun `le nettoyage ne compte ni comme un enregistrement du message, ni dans les dernieres actions s il n a rien retire`() = runBlocking {
        remember("Goûts", "Aime le chocolat noir.")
        // Lancement suivant, une heure après la note : un tour interrompu juste avant reste « à renvoyer ».
        val later = TracklyEngine(RoomTracklyStore(db), Clock.fixed(Instant.parse("2026-11-06T19:00:00Z"), ZoneOffset.UTC))
        val before = later.now()
        FormatNotesCleanup(later, baseHasData = { DatabaseContents.hasData(db) }).runOnce() as ActionOutcome.Applied
        assertFalse(later.recordedSince(before))
        val context = RecentActionsContext.render(ContextEnv(later, later.now()))!!
        assertFalse(context, context.contains("mise en forme"))
    }
}
