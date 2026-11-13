package app.trackly.core.importing

import app.trackly.core.Fixture
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.UndoLast
import app.trackly.core.day
import app.trackly.core.importing.ParityBundle.replaceAt
import app.trackly.core.model.EntryId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Origin
import app.trackly.core.model.RecordState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reprise de l'historique : vérifiée, atomique, idempotente, sans réécrire les données de l'app. */
class ImportHistoryTest {
    private val lunch = EntryId("00000000-0000-4000-8000-000000000102")
    private val journal = "journal/2026-09-01.json"

    private fun imported(outcome: ImportOutcome): ImportReport {
        assertTrue("$outcome", outcome is ImportOutcome.Imported)
        return (outcome as ImportOutcome.Imported).report
    }

    private fun refused(outcome: ImportOutcome): ImportOutcome.Refused {
        assertTrue("$outcome", outcome is ImportOutcome.Refused)
        return outcome as ImportOutcome.Refused
    }

    /** Export modifié : remplace un champ d'une entrée du 1er septembre (index dans `events`). */
    private fun withEvent(index: Int, field: String, value: JsonPrimitive) = ParityBundle.edited { root ->
        root.replaceAt(listOf("files", journal, "events", index.toString(), field)) { value } as JsonObject
    }

    @Test
    fun `le resume compte journees, produits, plats et donne l objectif actuel`() = runBlocking {
        val f = Fixture()
        val report = imported(HistoryImport.run(f.engine, ParityBundle.text))
        assertEquals(8, report.days)
        assertEquals(day("2026-09-01"), report.firstDay)
        assertEquals(day("2026-09-08"), report.lastDay)
        assertEquals(3, report.products)
        assertEquals(2, report.recipes)
        assertEquals(2, report.preferences)
        assertEquals(8, report.verifiedDays)
        assertEquals(day("2026-09-04"), report.currentGoal!!.since)
        assertEquals(2350.0, report.currentGoal!!.targets.kcal, 0.0)
        // Minimum de protéines repris avec l'objectif, à côté de l'idéal : 72,5 kg × 1,7 et 72,5 kg × 2,1.
        assertEquals(123.25, report.currentGoal!!.targets.proteinMinG!!, 0.0)
        assertEquals(152.25, report.currentGoal!!.targets.proteinG!!, 0.0)
        assertEquals(118.4, f.engine.daySummary(day("2026-09-01")).goal!!.proteinMinG!!, 0.0) // 74 kg × 1,6
        assertEquals(emptyList<String>(), report.anomalies)
        assertEquals(0, report.alreadyPresent)
        assertTrue(report.added > 0)
    }

    @Test
    fun `un minimum au-dessus de l ideal du profil n est pas repris`() {
        val text = ParityBundle.edited { root ->
            root.replaceAt(listOf("files", "profile.json", "goals", "0", "protein_ideal_g_per_kg")) { JsonPrimitive(1.5) } as JsonObject
        }
        val goals = BundleReader.read(text).goals.map { it.record }
        assertNull(goals.single { it.effectiveFrom == day("2026-09-01") }.targets.proteinMinG) // 74 × 1,5 < 74 × 1,6
        assertEquals(123.25, goals.single { it.effectiveFrom == day("2026-09-04") }.targets.proteinMinG!!, 0.0)
    }

    @Test
    fun `toutes les versions sont reprises, y compris supprimees`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)
        val history = f.engine.history(lunch)
        assertEquals(listOf(1, 2), history.map { it.version })
        assertEquals(700.0, history.last().food.nutrition.kcal, 0.0)
        assertTrue(history.all { it.origin == Origin.Import })
        val sandwich = f.engine.history(EntryId("00000000-0000-4000-8000-000000000203"))
        assertEquals(listOf(RecordState.Active, RecordState.Deleted), sandwich.map { it.state })
    }

    @Test
    fun `reimporter le meme fichier ne cree rien`() = runBlocking {
        val f = Fixture()
        val first = imported(HistoryImport.run(f.engine, ParityBundle.text))
        val sources = f.engine.importedSourceCount()
        val again = HistoryImport.run(f.engine, ParityBundle.text)
        assertTrue("$again", again is ImportOutcome.NothingNew)
        val report = (again as ImportOutcome.NothingNew).report
        assertEquals(0, report.added)
        assertEquals(first.added, report.alreadyPresent)
        assertEquals(sources, f.engine.importedSourceCount())
        // Le refus « rien de neuf » ne laisse pas de trace dans le journal.
        assertEquals(1, f.engine.observeRecentActions(10).first().size)
    }

    @Test
    fun `meme identifiant et version avec un contenu different bloque tout`() = runBlocking {
        val f = Fixture()
        imported(HistoryImport.run(f.engine, ParityBundle.text))
        val sources = f.engine.importedSourceCount()
        // Le libellé ne change pas les totaux : c'est bien le conflit qui bloque, pas le contrôle.
        val tampered = ParityBundle.edited { root ->
            val edited = root.replaceAt(listOf("files", journal, "events", "0", "label")) { JsonPrimitive("Porridge modifié") }
            // Une nouvelle version valide dans le même fichier n'est pas reprise non plus (tout ou rien).
            edited.replaceAt(listOf("files", journal, "events")) { events ->
                val v3 = events.jsonArray[2].jsonObject.let { JsonObject(it + ("version" to JsonPrimitive(3))) }
                JsonArray(events.jsonArray + v3)
            } as JsonObject
        }
        val outcome = refused(HistoryImport.run(f.engine, tampered))
        assertEquals(listOf("consommation « Porridge modifié » (2026-09-01) : même identifiant et même version, contenu différent"), outcome.details)
        assertEquals(listOf(1, 2), f.engine.history(lunch).map { it.version })
        assertEquals(sources, f.engine.importedSourceCount())
    }

    @Test
    fun `une version plus recente dans un nouvel export devient l etat courant`() = runBlocking {
        val f = Fixture()
        imported(HistoryImport.run(f.engine, ParityBundle.text))
        val newer = ParityBundle.edited { root ->
            root.replaceAt(listOf("files", journal, "events")) { events ->
                val deleted = events.jsonArray[2].jsonObject.let { JsonObject(it + ("version" to JsonPrimitive(3)) + ("state" to JsonPrimitive("deleted"))) }
                JsonArray(events.jsonArray + deleted)
            } as JsonObject
        }.let { text ->
            // Les totaux de contrôle de cet export ne sont plus ceux du jeu d'essai : on les retire.
            ParityBundle.run { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.let { JsonObject(it - "checks").toString() }
        }
        val report = imported(HistoryImport.run(f.engine, newer))
        assertEquals(1, report.added)
        assertTrue(report.anomalies.single().startsWith("Export sans totaux de contrôle"))
        assertEquals(RecordState.Deleted, f.engine.history(lunch).last().state)
    }

    @Test
    fun `les saisies faites dans l app ne sont jamais reecrites`() = runBlocking {
        val f = Fixture()
        // Objectif fixé dans l'app à la même date qu'un objectif de l'export.
        f.apply(SetGoal(GoalTargets(2000.0, 150.0, 200.0, 60.0), from = day("2026-09-04")))
        val report = imported(HistoryImport.run(f.engine, ParityBundle.text))
        assertEquals(listOf("objectif du 2026-09-04 déjà fixé dans l'app : conservé"), report.anomalies)
        assertEquals(2000.0, f.engine.daySummary(day("2026-09-04")).goal!!.kcal, 0.0)

        // Correction dans l'app d'une consommation reprise, puis nouvel import du même fichier.
        f.apply(CorrectConsumption(lunch, moment = Moment.Soir))
        assertTrue(HistoryImport.run(f.engine, ParityBundle.text) is ImportOutcome.NothingNew)
        val current = f.engine.history(lunch).last()
        assertEquals(3, current.version)
        assertEquals(Moment.Soir, current.moment)
    }

    @Test
    fun `une version ecrite dans l app avec la meme cle bloque la reprise`() = runBlocking {
        val f = Fixture()
        imported(HistoryImport.run(f.engine, ParityBundle.text))
        f.apply(DeleteConsumption(lunch)) // version 3 écrite par l'app
        val conflicting = ParityBundle.edited { root ->
            root.replaceAt(listOf("files", journal, "events")) { events ->
                val v3 = events.jsonArray[2].jsonObject.let { JsonObject(it + ("version" to JsonPrimitive(3)) + ("label" to JsonPrimitive("Riz"))) }
                JsonArray(events.jsonArray + v3)
            } as JsonObject
        }.let { JsonObject(kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject - "checks").toString() }
        val outcome = refused(HistoryImport.run(f.engine, conflicting))
        assertTrue(outcome.details.single().startsWith("consommation « Riz » (2026-09-01)"))
        assertEquals(RecordState.Deleted, f.engine.history(lunch).last().state)
    }

    @Test
    fun `la reprise ne s annule pas par Annuler`() = runBlocking {
        val f = Fixture()
        imported(HistoryImport.run(f.engine, ParityBundle.text))
        assertEquals(Rejection.NothingToUndo, f.reject(UndoLast))
        f.add("Pomme", 80.0, explicitDay = day("2026-09-08"))
        f.apply(UndoLast)
        assertEquals(Rejection.NothingToUndo, f.reject(UndoLast))
        assertEquals(78.0, f.engine.daySummary(day("2026-09-08")).energy.kcal!!, 0.0)
    }

    @Test
    fun `fichier invalide refuse avec un message clair, rien n est ecrit`() = runBlocking {
        val f = Fixture()
        assertEquals("Le fichier n'est pas un JSON lisible", refused(HistoryImport.run(f.engine, "pas du json")).details.single())
        assertEquals(
            "Ce fichier n'est pas un export Trackly (format « suivi-kcal-bundle » attendu)",
            refused(HistoryImport.run(f.engine, """{"format":"autre"}""")).details.single(),
        )
        val badKcal = withEvent(0, "nutrition", JsonPrimitive("beaucoup"))
        assertEquals(
            "$journal › events 1 : « nutrition » doit être un objet",
            refused(HistoryImport.run(f.engine, badKcal)).details.single(),
        )
        val wrongDay = withEvent(0, "day", JsonPrimitive("2026-09-02"))
        assertEquals(
            "$journal › events 1 : rattachée au 2026-09-02, pas au 2026-09-01",
            refused(HistoryImport.run(f.engine, wrongDay)).details.single(),
        )
        assertEquals(0, f.engine.importedSourceCount())
        assertEquals(null, f.engine.observeFirstDay().first())
    }

    @Test
    fun `le premier jour tient compte de l historique repris`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)
        assertEquals(day("2026-09-01"), f.engine.observeFirstDay().first())
        val days = f.engine.observeDays(day("2026-09-01"), day("2026-09-08")).first()
        assertEquals(2600.0, days[2].energy.kcal!!, 0.0)
    }
}
