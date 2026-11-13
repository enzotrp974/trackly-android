package app.trackly.review

import app.trackly.core.forecast.ArrivalForecasts
import app.trackly.core.goal.WeightTarget
import app.trackly.core.review.WeighIn
import app.trackly.core.review.WeightHistory
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DayEnergy
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.MacroTotal
import app.trackly.core.summary.Totals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Texte de la prévision d'arrivée envoyé au modèle : chiffres de l'app, aucune date quand il n'y en a pas de ferme. */
class ForecastTextTest {
    private val asOf = LocalDate.parse("2026-12-13")

    /** 4 pesées par semaine pendant [weeks] semaines finissant le [asOf]. */
    private fun curve(weeks: Int, kgAt: (Long) -> Double): WeightHistory {
        val first = LocalDate.parse("2026-12-07").minusWeeks(weeks - 1L)
        return WeightHistory.of(
            emptyList(),
            (0 until weeks).flatMap { w -> listOf(0L, 2L, 4L, 6L).map { d -> WeighIn(first.plusDays(w * 7 + d), kgAt(w * 7 + d)) } },
        )
    }

    private fun meals(kcal: Double) = (0L until 21L).map { back ->
        val totals = Totals(kcal, MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true))
        DaySummary(asOf.minusDays(back), null, emptyList(), totals, DayEnergy.detailed(totals), DayCoverage.Complete)
    }

    private fun text(weights: WeightHistory, days: List<DaySummary> = emptyList(), target: WeightTarget = WeightTarget(61.0, LocalDate.parse("2027-03-01"))) =
        FiguresText.forecast(ArrivalForecasts.compute(asOf, target, weights, days))

    @Test
    fun `mesuree - date, marge, verdict et ralentissement normal`() {
        val t = text(curve(6) { 66.0 - 0.5 * it / 7 })
        assertTrue(t, t.contains("Date d'arrivée estimée : vers le "))
        assertTrue(t, t.contains("au plus tôt le "))
        assertTrue(t, t.contains("Verdict : en avance ou à l'heure"))
        assertTrue(t, t.contains("Ralentissement attendu : à repas égaux, le rythme passe d'environ 0,4"))
        assertTrue(t, t.contains("NORMALE"))
    }

    @Test
    fun `sans assez de pesees - ordre de grandeur en mois, jamais de jour`() {
        val t = text(curve(2) { 64.0 }, meals(2400.0))
        assertTrue(t, t.contains("aucune date ferme"))
        assertTrue(t, Regex("Ordre de grandeur théorique \\(référence moins les repas comptés\\) : (entre|vers|pas avant) ").containsMatchIn(t))
        assertFalse(t, t.contains("Date d'arrivée estimée"))
        assertFalse(t, t.contains("Verdict"))
        assertFalse(t, Regex("vers le \\d").containsMatchIn(t))
    }

    @Test
    fun `objectif atteint - aucun rythme a tenir`() {
        val t = text(curve(6) { 60.5 })
        assertTrue(t, t.contains("Objectif atteint."))
        assertFalse(t, t.contains("Rythme qu'il faudrait"))
    }

    @Test
    fun `stagnation - dite, sans date`() {
        val t = text(curve(6) { 64.0 })
        assertTrue(t, t.contains("Poids stable sur les dernières semaines"))
        assertTrue(t, t.contains("pas d'arrivée à ce rythme"))
    }

    @Test
    fun `date passee - signalee`() {
        val t = text(curve(6) { 66.0 - 0.5 * it / 7 }, target = WeightTarget(61.0, LocalDate.parse("2026-12-01")))
        assertTrue(t, t.contains("La date visée est passée."))
    }

    @Test
    fun `palier avant la cible - dit avec la bonne source`() {
        val t = text(WeightHistory.of(emptyList(), listOf(WeighIn(LocalDate.parse("2026-12-10"), 64.0))), meals(2750.0))
        assertTrue(t, t.contains("Aux apports actuels, la perte s'arrêterait vers 61,7 kg, avant l'objectif."))
    }
}
