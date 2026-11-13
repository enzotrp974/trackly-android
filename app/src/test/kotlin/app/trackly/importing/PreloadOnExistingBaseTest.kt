package app.trackly.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.backup.DatabaseFiles
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.setup.InMemorySetupPrefs
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.DatabaseContents
import app.trackly.storage.db.DatabaseFactory
import app.trackly.storage.db.TracklyDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Le préchargement embarqué dans l'APK face à une vraie base Room sur fichier : il ne remplit qu'une base vide.
 * Une base déjà remplie (saisie dans l'app, mise à jour par-dessus, ou remise par Android Auto Backup, qui ne
 * rend pas les préférences) n'est jamais touchée. Jeu d'essai fictif (fichier de parité du dépôt).
 */
@RunWith(AndroidJUnit4::class)
class PreloadOnExistingBaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-11-05T12:30:00Z"), ZoneOffset.UTC)
    private val file: File = context.getDatabasePath("prechargement-test.db").also { DatabaseFiles.deleteWithSides(it) }
    private val embedded = checkNotNull(javaClass.getResource("/bundle.json")).readText()
    private var database: TracklyDatabase = open()
    private val engine get() = TracklyEngine(RoomTracklyStore(database), clock)

    private fun open(): TracklyDatabase = DatabaseFactory.builder(context, file.absolutePath).build()

    /** Connaissances de départ fictives, appliquées pour de vrai par le moteur. */
    private val knowledge = """{"memories":[{"id":"memoire-essai","topic":"habitudes","text":"Café sans sucre le matin."}],""" +
        """"terms":[{"heard":"skir","correct":"skyr"}],"stock":["Riz basmati"]}"""

    /**
     * Comme l'app : reprise et connaissances par le moteur, base consultée telle qu'elle est sur le disque.
     * [import] et [seed] se remplacent pour simuler un refus ou un arrêt en cours de route.
     */
    private fun preload(
        prefs: InMemorySetupPrefs,
        gate: PreloadGate = PreloadGate { true },
        seeded: MutableList<Unit> = mutableListOf(),
        import: suspend (String) -> ImportOutcome = { text -> HistoryImport.run(engine, text) },
        seed: suspend () -> Boolean = { applyKnowledgeSeed(engine, knowledge) },
        importOnEmptyBase: (suspend (String) -> ImportOutcome?)? = null,
    ) = PreloadedHistory(
        read = { embedded },
        import = import,
        prefs = prefs,
        seed = {
            seeded += Unit
            seed()
        },
        baseHasData = { DatabaseContents.hasData(database) },
        gate = gate,
        importOnEmptyBase = importOnEmptyBase,
    )

    /** Comme l'app : la base relue vide dans la transaction même de la reprise. */
    private val guarded: suspend (String) -> ImportOutcome? = { text ->
        database.withTransaction { if (DatabaseContents.hasData(database)) null else HistoryImport.run(engine, text) }
    }

    /** Une journée saisie dans l'app, sans rapport avec l'historique embarqué. */
    private suspend fun typeOneMeal() {
        val pomme = NewConsumption(Food("Pomme", Nutrition(80.0, 0.4, 19.0, 0.2)), Moment.Snack, explicitDay = LocalDate.parse("2026-11-04"))
        assertTrue(engine.execute(AddConsumptions(pomme)) is ActionOutcome.Applied)
    }

    /** Ce qu'on compare avant et après : tout le contenu visible de la base. */
    private suspend fun snapshot(): List<Any?> = listOf(
        rowCounts(),
        engine.observeRecentActions(1_000).first().map { it.id to it.title },
        engine.importedSourceCount(),
        engine.observeFirstDay().first(),
        engine.daySummary(LocalDate.parse("2026-11-04")),
        engine.daySummary(LocalDate.parse("2026-09-01")),
    )

    /** Nombre de lignes de chaque table : rien d'ajouté nulle part, pas seulement dans ce que l'écran montre. */
    private fun rowCounts(): Map<String, Long> {
        val sqlite = database.openHelper.readableDatabase
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { name -> sqlite.query("SELECT COUNT(*) FROM `" + name + "`").use { c -> c.moveToFirst(); c.getLong(0) } }
    }

    @After
    fun close() {
        database.close()
        DatabaseFiles.deleteWithSides(file)
    }

    @Test
    fun `base vide - le prechargement a lieu, une seule fois`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertFalse(DatabaseContents.hasData(database))

        assertTrue(preload(prefs).runOnce())

        assertTrue(prefs.historyPreloaded)
        assertTrue(engine.importedSourceCount() > 0)
        val after = snapshot()
        assertFalse(preload(prefs).runOnce())
        assertEquals(after, snapshot())
    }

    @Test
    fun `base remplie dans l'app - jamais de prechargement, marque comme fait`() = runBlocking {
        typeOneMeal()
        val before = snapshot()
        val prefs = InMemorySetupPrefs()
        val seeded = mutableListOf<Unit>()

        assertFalse(preload(prefs, seeded = seeded).runOnce())

        assertEquals(before, snapshot())
        assertEquals(0, engine.importedSourceCount())
        assertTrue("connaissances non ajoutées non plus", seeded.isEmpty())
        assertTrue(prefs.historyPreloaded)
    }

    @Test
    fun `base remise par Auto Backup sans preferences - rien ne s'y ajoute`() = runBlocking {
        // Première vie de l'app : préchargement, puis un repas noté.
        assertTrue(preload(InMemorySetupPrefs()).runOnce())
        typeOneMeal()
        database.close()
        // Réinstallation : Android remet le fichier de la base, mais pas les préférences.
        database = open()
        val before = snapshot()
        val fresh = InMemorySetupPrefs()
        val seeded = mutableListOf<Unit>()

        assertEquals(PreloadDecision.BaseInUse, preload(fresh, seeded = seeded).decide())
        assertFalse(preload(fresh, seeded = seeded).runOnce())

        assertEquals(before, snapshot())
        assertTrue(seeded.isEmpty())
        assertTrue(fresh.historyPreloaded)
    }

    @Test
    fun `mise a jour par-dessus - sans effet sur les donnees`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertTrue(preload(prefs).runOnce())
        typeOneMeal()
        database.close()
        // Nouvel APK installé par-dessus : même base, mêmes préférences.
        database = open()
        val before = snapshot()

        assertEquals(PreloadDecision.AlreadyDone, preload(prefs).decide())
        assertFalse(preload(prefs).runOnce())

        assertEquals(before, snapshot())
    }

    @Test
    fun `base vide en attente - rien n'est ecrit ni marque, le lancement suivant redecide`() = runBlocking {
        val prefs = InMemorySetupPrefs()

        assertFalse(preload(prefs, gate = PreloadGate { false }).runOnce())

        assertFalse(DatabaseContents.hasData(database))
        assertFalse(prefs.historyPreloaded)
        assertTrue(preload(prefs).runOnce())
    }

    @Test
    fun `base remplie pendant l'attente - le prechargement ne s'y ajoute pas`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        val gate = PreloadGate {
            typeOneMeal() // L'utilisateur note un repas pendant que la porte attend.
            true
        }

        assertEquals(PreloadDecision.BaseInUse, preload(prefs, gate = gate).decide())
        assertEquals(0, engine.importedSourceCount())
    }

    @Test
    fun `reprise verifiee dans sa transaction - base vide, elle a lieu`() = runBlocking {
        val prefs = InMemorySetupPrefs()

        assertTrue(preload(prefs, importOnEmptyBase = guarded).runOnce())

        assertTrue(engine.importedSourceCount() > 0)
        assertTrue(prefs.historyPreloaded)
    }

    /** Fenêtre fermée : une saisie arrivée après la dernière relecture, juste avant l'écriture. */
    @Test
    fun `saisie entre la derniere relecture et la reprise - rien ne s'y ajoute`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        val seeded = mutableListOf<Unit>()
        val late: suspend (String) -> ImportOutcome? = { text ->
            typeOneMeal()
            guarded(text)
        }

        assertFalse(preload(prefs, seeded = seeded, importOnEmptyBase = late).runOnce())

        assertEquals(0, engine.importedSourceCount())
        assertTrue("connaissances non ajoutées non plus", seeded.isEmpty())
        assertTrue(prefs.historyPreloaded)
        assertEquals(1, engine.daySummary(LocalDate.parse("2026-11-04")).consumptions.size)
    }

    @Test
    fun `reprise interrompue puis reprise - la base non vide de son fait ne la bloque pas`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertFalse(preload(prefs, import = { ImportOutcome.Refused("Erreur passagère") }, importOnEmptyBase = { ImportOutcome.Refused("Erreur passagère") }).runOnce())
        assertTrue(DatabaseContents.hasData(database))

        assertTrue(preload(prefs, importOnEmptyBase = guarded).runOnce())

        assertTrue(engine.importedSourceCount() > 0)
    }

    @Test
    fun `historique refuse, connaissances passees - la reprise est retentee au lancement suivant`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertFalse(preload(prefs, import = { ImportOutcome.Refused("Erreur passagère") }).runOnce())
        assertTrue("les connaissances ont écrit dans la base", DatabaseContents.hasData(database))
        assertFalse(prefs.historyPreloaded)

        assertTrue(preload(prefs).runOnce())

        assertTrue(engine.importedSourceCount() > 0)
        assertTrue(prefs.historyPreloaded)
    }

    @Test
    fun `arret entre l'historique et les connaissances - le lancement suivant finit le travail`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertFalse(preload(prefs, seed = { false }).runOnce())
        val sources = engine.importedSourceCount()
        assertTrue(sources > 0)

        val seeded = mutableListOf<Unit>()
        assertTrue(preload(prefs, seeded = seeded).runOnce())

        assertEquals(1, seeded.size)
        assertEquals("historique non doublé", sources, engine.importedSourceCount())
        assertTrue(prefs.historyPreloaded)
    }

    @Test
    fun `restauration apres un prechargement interrompu - plus rien ne s'y ajoute`() = runBlocking {
        val prefs = InMemorySetupPrefs()
        assertFalse(preload(prefs, import = { ImportOutcome.Refused("Erreur passagère") }).runOnce())
        // Une sauvegarde remplace la base (redémarrage de restauration) : l'app le signale au préchargement.
        preload(prefs).onBaseReplaced()

        assertEquals(PreloadDecision.AlreadyDone, preload(prefs).decide())
    }
}
