package app.trackly.core.importing

import app.trackly.core.Fixture
import app.trackly.core.day
import app.trackly.core.importing.ParityBundle.replaceAt
import app.trackly.core.summary.EnergyStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parité de reprise : les totaux par jour du moteur Kotlin, après import du jeu d'essai
 * synthétique partagé, égalent ceux du moteur Python (`checks` de l'export). Ce test échoue si le moteur Kotlin diverge.
 */
class HistoryParityTest {
    private val bundle = BundleReader.read(ParityBundle.text)
    private val checks = checkNotNull(bundle.checks)

    @Test
    fun `le jeu d essai couvre toutes les journees controlees`() {
        assertEquals(8, checks.size)
        assertEquals(checks.keys, bundle.days.map { it.day }.toSet())
    }

    @Test
    fun `apres import, chaque jour a les totaux du moteur Python`() = runBlocking {
        val f = Fixture()
        val outcome = HistoryImport.run(f.engine, ParityBundle.text)
        assertTrue("$outcome", outcome is ImportOutcome.Imported)
        for ((date, expected) in checks) {
            val summary = f.engine.daySummary(date)
            assertEquals("$date", emptyList<String>(), ParityCheck.compare(expected, summary))
        }
    }

    @Test
    fun `cas notables du jeu d essai`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)
        // Versions multiples et fourchette d'estimation.
        val first = f.engine.daySummary(day("2026-09-01"))
        assertEquals(2101.5, first.energy.kcal!!, 1e-9)
        assertEquals(2001.5, first.energy.kcalLow, 1e-9)
        assertEquals(listOf("Porridge", "Riz et poulet", "Yaourt nature", "Pizza au restaurant"), first.consumptions.map { it.food.label })
        // Suppression et macros inconnues : lipides incomplets, minimum connu conservé.
        val second = f.engine.daySummary(day("2026-09-02"))
        assertEquals(3, second.consumptions.size)
        assertNull(second.totals.fat.value)
        assertEquals(8.35, second.totals.fat.known, 1e-9)
        // Estimation journalière corrigée : elle remplace l'ancien agrégat, macros inconnues.
        val third = f.engine.daySummary(day("2026-09-03"))
        assertEquals(EnergyStatus.Estimate, third.energy.status)
        assertEquals(2600.0, third.energy.kcal!!, 0.0)
        assertEquals(listOf("Pain et confiture"), third.consumptions.map { it.food.label })
        // Borne minimale : pas de total, le minimum connu du détail l'emporte.
        val fourth = f.engine.daySummary(day("2026-09-04"))
        assertEquals(EnergyStatus.LowerBound, fourth.energy.status)
        assertNull(fourth.energy.kcal)
        assertEquals(1550.0, fourth.energy.kcalLow, 0.0)
        // Changement d'objectif au 4.
        assertEquals(2600.0, first.goal!!.kcal, 0.0)
        assertEquals(2350.0, fourth.goal!!.kcal, 0.0)
        assertEquals(61.62, fourth.goal!!.fatG!!, 0.0)
        assertEquals(EnergyStatus.Unknown, f.engine.daySummary(day("2026-09-05")).energy.status)
        assertEquals(EnergyStatus.Detailed, f.engine.daySummary(day("2026-09-06")).energy.status)
    }

    @Test
    fun `un ecart avec le moteur Python est detecte et refuse l import`() = runBlocking {
        val tampered = ParityBundle.edited { root ->
            root.replaceAt(listOf("checks", "days", "2026-09-01", "totals", "kcal")) { JsonPrimitive(2101.6) }.let { it as kotlinx.serialization.json.JsonObject }
        }
        val differences = ParityCheck.differences(BundleReader.read(tampered))
        assertEquals(listOf("2026-09-01 : kcal attendu 2101.6, calculé 2101.5"), differences)

        val f = Fixture()
        val outcome = HistoryImport.run(f.engine, tampered)
        assertTrue("$outcome", outcome is ImportOutcome.Refused)
        assertEquals(differences, (outcome as ImportOutcome.Refused).details)
        assertEquals(0, f.engine.importedSourceCount())
    }

    @Test
    fun `arrondi identique a Python`() {
        assertEquals(61.62, pythonRound2(72.5 * 0.85), 0.0)
        assertEquals(0.12, pythonRound2(0.125), 0.0)
        assertEquals(2.67, pythonRound2(2.675), 0.0) // 2.675 vaut en réalité 2.67499999…
    }
}
