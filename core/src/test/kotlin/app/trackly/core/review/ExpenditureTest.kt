package app.trackly.core.review

import app.trackly.core.day
import app.trackly.core.model.RecordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sqrt

/** Dépense estimée : ancrée sur la référence, pas proportionnels au poids, séances par type, fourchette. */
class ExpenditureTest {
    private val week = WeekRange(day("2026-09-07"))

    private fun info(steps: Int? = 20_000, sessions: List<SessionCount>? = null) = WeekInfo(
        weekStart = week.start, version = 1, state = RecordState.Active,
        stepsAverage = steps, sessions = sessions, recordedAt = Instant.EPOCH, actionId = null,
    )

    private fun expenditure(info: WeekInfo?, weights: WeightHistory = WeightHistory.Empty) =
        WeeklyReview.compute(week, emptyList(), info, weights).expenditure

    private fun weighed(kg: Double, weeksBack: Int = 0) =
        WeightHistory.of(emptyList(), listOf(WeighIn(week.start.minusWeeks(weeksBack.toLong()).plusDays(1), kg)))

    @Test
    fun `aucune information - la reference, fourchette de 300 kcal, rien d invente`() {
        val e = expenditure(null)
        assertEquals(3000.0, e.kcalPerDay, 0.0)
        assertNull(e.stepsAdjustmentKcal)
        assertNull(e.sessionsAdjustmentKcal)
        assertEquals(2700.0, e.lowKcal, 1e-9)
        assertEquals(3300.0, e.highKcal, 1e-9)
    }

    @Test
    fun `a 20 000 pas, aucun ajustement des pas, 3 seances ne retirent qu une demi-seance`() {
        val e = expenditure(info(20_000, listOf(SessionCount("musculation", 3))))
        assertEquals(0.0, e.stepsAdjustmentKcal!!, 1e-9)
        assertEquals(3000.0 - 125.0 / 7, e.kcalPerDay, 1e-9)
    }

    @Test
    fun `le cout des pas suit le poids de la semaine`() {
        val light = expenditure(info(15_000), weighed(61.0))
        val heavy = expenditure(info(15_000), weighed(72.0))
        assertEquals(-5 * 0.5 * 61.0, light.stepsAdjustmentKcal!!, 1e-9)
        assertEquals(-5 * 0.5 * 72.0, heavy.stepsAdjustmentKcal!!, 1e-9)
        assertEquals(WeightBasis.WeekAverage, light.weightBasis)
        assertEquals(61.0, light.weightKg, 1e-9)
    }

    @Test
    fun `sans pesee cette semaine, le dernier poids connu sert, sans aucune pesee une valeur de travail`() {
        val last = expenditure(info(15_000), weighed(68.0, weeksBack = 2))
        assertEquals(WeightBasis.LastKnown, last.weightBasis)
        assertEquals(-5 * 0.5 * 68.0, last.stepsAdjustmentKcal!!, 1e-9)

        val none = expenditure(info(15_000))
        assertEquals(WeightBasis.Default, none.weightBasis)
        assertEquals(ReviewParams.Default.defaultWeightKg, none.weightKg, 0.0)
        assertTrue(none.assumptions.any { it.contains("n'est pas ton poids") })
    }

    @Test
    fun `une pesee future ne sert pas de dernier poids connu`() {
        val future = WeightHistory.of(emptyList(), listOf(WeighIn(week.end.plusDays(3), 55.0)))
        assertEquals(WeightBasis.Default, expenditure(info(15_000), future).weightBasis)
    }

    @Test
    fun `au-dessus de 20 000 pas seule la moitie est comptee`() {
        val e = expenditure(info(26_000), weighed(70.0))
        assertEquals(6 * 0.5 * 0.5 * 70.0, e.stepsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `une course n ajoute rien quand les pas sont donnes - pas de double comptage`() {
        val withRuns = expenditure(info(20_000, listOf(SessionCount("musculation", 3), SessionCount("course à pied", 2))))
        val withoutRuns = expenditure(info(20_000, listOf(SessionCount("musculation", 3))))
        assertEquals(withoutRuns.sessionsAdjustmentKcal!!, withRuns.sessionsAdjustmentKcal!!, 1e-9)
        assertEquals((3 - 3.5) * 250 / 7, withRuns.sessionsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `sans les pas, une course est comptee comme une seance et le rapport le dit`() {
        val e = expenditure(info(null, listOf(SessionCount("musculation", 3), SessionCount("course", 2))))
        assertNull(e.stepsAdjustmentKcal)
        assertEquals((5 - 3.5) * 250 / 7, e.sessionsAdjustmentKcal!!, 1e-9)
        assertTrue(e.assumptions.any { it.contains("Pas non fournis") })
    }

    @Test
    fun `un type inconnu est compte comme de la musculation`() {
        val e = expenditure(info(20_000, listOf(SessionCount("yoga", 2), SessionCount("musculation", 2))))
        assertEquals((4 - 3.5) * 250 / 7, e.sessionsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `reconnaissance des types de seance`() {
        assertEquals(SessionKind.Strength, SessionKinds.of("Musculation"))
        assertEquals(SessionKind.Strength, SessionKinds.of("renfo jambes"))
        assertEquals(SessionKind.OnFoot, SessionKinds.of("Course à pied"))
        assertEquals(SessionKind.OnFoot, SessionKinds.of("running"))
        assertEquals(SessionKind.OnFoot, SessionKinds.of("marche sportive"))
        assertEquals(SessionKind.OnFoot, SessionKinds.of("Randonnée"))
        assertEquals(SessionKind.Other, SessionKinds.of("yoga"))
        assertEquals(SessionKind.Other, SessionKinds.of("prune"))
    }

    @Test
    fun `fourchette - reference, pas et seances en quadrature`() {
        val e = expenditure(info(15_000, listOf(SessionCount("musculation", 2))), weighed(70.0))
        val stepsU = 175.0 * 0.25
        val sessionsU = 1.5 * 100.0 / 7
        val expected = sqrt(300.0 * 300 + stepsU * stepsU + sessionsU * sessionsU)
        assertEquals(expected, e.uncertaintyKcal, 1e-9)
        assertEquals(e.kcalPerDay - expected, e.lowKcal, 1e-9)
        assertTrue(e.uncertaintyKcal > 300.0)
    }

    @Test
    fun `l hypothese est ecrite avec ses incertitudes`() {
        val text = expenditure(info(15_000, listOf(SessionCount("musculation", 3))), weighed(72.0)).assumptions.joinToString(" ")
        assertTrue(text.contains("jamais été mesurée"))
        assertTrue(text.contains("hypothèse de l'application"))
        assertTrue(text.contains("poids moyen de la semaine"))
        assertTrue(text.contains("±"))
        assertFalse(text.contains("40 kcal"))
    }

    @Test
    fun `le poids moyen de plusieurs pesees est ecrit avec une decimale et une virgule`() {
        val days = listOf(1L to 72.4, 3L to 72.1, 5L to 72.6)
        val history = WeightHistory.of(emptyList(), days.map { (d, kg) -> WeighIn(week.start.plusDays(d), kg) })
        val e = expenditure(info(15_000), history)
        val text = e.assumptions.joinToString(" ")
        assertTrue(text, text.contains("poids moyen de la semaine, 72,4 kg"))
        assertFalse(text, Regex("\\d,\\d{2,}").containsMatchIn(text.replace("±", "")))
    }

    @Test
    fun `une moyenne qui derive en virgule flottante reste a une decimale`() {
        val history = WeightHistory.of(
            emptyList(),
            listOf(WeighIn(week.start.plusDays(1), 72.0), WeighIn(week.start.plusDays(2), 72.1)),
        )
        val text = expenditure(info(15_000), history).assumptions.joinToString(" ")
        assertTrue(text, text.contains("72,1 kg"))
        assertFalse(text, text.contains("72,05"))
    }
}
