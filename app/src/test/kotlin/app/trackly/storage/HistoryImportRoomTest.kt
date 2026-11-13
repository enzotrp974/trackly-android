package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.BundleReader
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ParityCheck
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reprise dans la vraie base Room : même fichier de parité que le moteur Python
 * (`tests/fixtures/parity/bundle.json`), totaux identiques après passage par le stockage.
 */
@RunWith(AndroidJUnit4::class)
class HistoryImportRoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val store = RoomTracklyStore(db)
    private val engine = TracklyEngine(store, Clock.fixed(Instant.parse("2026-11-02T12:30:00Z"), ZoneOffset.UTC))
    private val text = checkNotNull(javaClass.getResource("/bundle.json")).readText()
    private val bundle = BundleReader.read(text)

    @After
    fun close() = db.close()

    @Test
    fun `apres reprise dans Room, chaque jour a les totaux du moteur Python`() = runBlocking {
        assertTrue(HistoryImport.run(engine, text) is ImportOutcome.Imported)
        for ((day, expected) in checkNotNull(bundle.checks)) {
            assertEquals("$day", emptyList<String>(), ParityCheck.compare(expected, engine.daySummary(day)))
        }
        val days = engine.observeDays(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-08")).first()
        for (summary in days) {
            assertEquals("${summary.day}", emptyList<String>(), ParityCheck.compare(bundle.checks!!.getValue(summary.day), summary))
        }
        assertEquals(LocalDate.parse("2026-09-01"), engine.observeFirstDay().first())
    }

    @Test
    fun `produits, plats et preferences font l aller-retour sans perte`() = runBlocking {
        HistoryImport.run(engine, text)
        val stored = store.transaction { Triple(products.allCurrent(), recipes.allCurrent(), preferences.allCurrent()) }
        fun <T> sameContent(expected: List<T>, actual: List<T>, normalize: (T) -> T) =
            assertEquals(expected.map(normalize).toSet(), actual.map(normalize).toSet())
        sameContent(bundle.products.map { it.record }, stored.first) { it.traced(1, Instant.EPOCH, null) }
        val latestRecipes = bundle.recipes.map { it.record }.groupBy { it.recipeId }.values.map { v -> v.maxBy { it.version } }
        sameContent(latestRecipes, stored.second) { it.traced(1, Instant.EPOCH, null) }
        sameContent(bundle.preferences.map { it.record }, stored.third) { it.traced(1, Instant.EPOCH, null) }
        assertTrue(stored.first.first { it.productId == "barre-cereales-essai" }.nutrients.fatG == null)
    }

    @Test
    fun `reimport idempotent et conflit sans aucune ecriture`() = runBlocking {
        HistoryImport.run(engine, text)
        val sources = engine.importedSourceCount()
        assertTrue(HistoryImport.run(engine, text) is ImportOutcome.NothingNew)
        val tampered = text.replaceFirst("\"label\": \"Porridge\"", "\"label\": \"Porridge modifié\"")
        val refused = HistoryImport.run(engine, tampered)
        assertTrue("$refused", refused is ImportOutcome.Refused)
        assertEquals(sources, engine.importedSourceCount())
        assertEquals(1, engine.observeRecentActions(10).first().size)
    }
}
