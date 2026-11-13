package app.trackly.core.review

import app.trackly.core.Fixture
import app.trackly.core.action.Rejection
import app.trackly.core.day
import app.trackly.core.model.GoalTargets
import app.trackly.core.action.SetGoal
import app.trackly.core.model.Moment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Période du bilan : du lendemain du dernier bilan jusqu'à aujourd'hui. Ce n'est plus une semaine du lundi au
 * dimanche : elle commence n'importe quel jour et dure de 1 jour à plusieurs semaines. Un ancien bilan (lundi-dimanche)
 * reste une période de 7 jours.
 */
class ReviewPeriodTest {
    private val f = Fixture("2026-09-14T09:00")

    @Test
    fun `une periode commence n importe quel jour et finit quand on veut`() {
        val period = WeekRange(day("2026-09-10"), day("2026-09-12"))
        assertEquals(listOf(day("2026-09-10"), day("2026-09-11"), day("2026-09-12")), period.days)
        assertTrue(day("2026-09-12") in period)
        assertFalse(day("2026-09-13") in period)
        assertFalse(day("2026-09-09") in period)
    }

    @Test
    fun `sans fin donnee, la periode garde sa duree d une semaine - les anciens bilans restent lisibles`() {
        val old = WeekRange(day("2026-09-07"))
        assertEquals(day("2026-09-13"), old.end)
        assertEquals(7, old.days.size)
    }

    @Test
    fun `une periode a l envers est refusee`() {
        assertTrue(runCatching { WeekRange(day("2026-09-12"), day("2026-09-10")) }.isFailure)
    }

    @Test
    fun `les informations d une periode acceptent les jours depuis son debut, y compris au-dela de sept jours`() = runBlocking {
        val start = day("2026-09-03") // un jeudi
        f.apply(DeclareWeekInfo(start, weighIns = listOf(WeighIn(day("2026-09-12"), 72.0), WeighIn(day("2026-09-03"), 72.6))))
        assertEquals(listOf(72.6, 72.0), f.engine.weekInfo(start)!!.weighIns.map { it.kg })
    }

    @Test
    fun `une pesee avant le debut de la periode est refusee`() = runBlocking {
        val start = day("2026-09-03")
        assertTrue(f.reject(DeclareWeekInfo(start, weighIns = listOf(WeighIn(day("2026-09-02"), 72.0)))) is Rejection.Invalid)
        assertNull(f.engine.weekInfo(start))
    }

    @Test
    fun `la couverture et les moyennes portent sur les jours de la periode seulement`() = runBlocking {
        f.apply(SetGoal(GoalTargets(2200.0), from = day("2026-09-01")))
        for (n in 10..12) f.add("Repas $n", 2400.0, Moment.Soir, 140.0, 250.0, 70.0, explicitDay = day("2026-09-%02d".format(n)))
        f.add("Avant", 9000.0, Moment.Soir, explicitDay = day("2026-09-09"))

        val figures = f.engine.weeklyFigures(WeekRange(day("2026-09-10"), day("2026-09-12")))

        assertEquals(3, figures.coverage.countedDays)
        assertEquals(0, figures.coverage.missing.size)
        assertEquals(2400.0, figures.intake.averageKcal!!, 1e-9)
        assertEquals(day("2026-09-12"), figures.cumulative.to)
    }

    @Test
    fun `les seances sont ramenees a la semaine - 7 seances en 14 jours valent 3,5 par semaine`() = runBlocking {
        f.add("Repas", 2500.0, Moment.Soir, explicitDay = day("2026-09-01"))
        val start = day("2026-09-01")
        f.apply(DeclareWeekInfo(start, sessions = listOf(SessionCount("musculation", 7))))

        val figures = f.engine.weeklyFigures(WeekRange(start, day("2026-09-14")))

        assertEquals(0.0, figures.expenditure.sessionsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `une seance en trois jours n est pas comptee comme une semaine entiere`() = runBlocking {
        val start = day("2026-09-10")
        f.add("Repas", 2500.0, Moment.Soir, explicitDay = start)
        f.apply(DeclareWeekInfo(start, sessions = listOf(SessionCount("musculation", 2))))

        val figures = f.engine.weeklyFigures(WeekRange(start, day("2026-09-12")))

        // 2 séances en 3 jours = 4,67 par semaine : au-dessus de la référence, jamais un manque de séances.
        assertTrue(figures.expenditure.sessionsAdjustmentKcal!! > 0.0)
    }

    @Test
    fun `des pas donnes pour chaque jour d une periode courte ne sont pas signales comme partiels`() = runBlocking {
        val start = day("2026-09-10")
        f.add("Repas", 2500.0, Moment.Soir, explicitDay = start)
        f.apply(DeclareWeekInfo(start, steps = (10..12).map { DaySteps(day("2026-09-$it"), 20_000) }))

        val figures = f.engine.weeklyFigures(WeekRange(start, day("2026-09-12")))

        assertFalse(MissingCode.StepsPartial in figures.missing.map { it.code })
    }

    @Test
    fun `des pas manquants sont comptes sur la duree de la periode`() = runBlocking {
        val start = day("2026-09-10")
        f.add("Repas", 2500.0, Moment.Soir, explicitDay = start)
        f.apply(DeclareWeekInfo(start, steps = listOf(DaySteps(day("2026-09-10"), 20_000))))

        val partial = f.engine.weeklyFigures(WeekRange(start, day("2026-09-12"))).missing.single { it.code == MissingCode.StepsPartial }

        assertTrue(partial.message, partial.message.contains("1 jour(s) sur 3"))
    }

    @Test
    fun `les moyennes hebdomadaires du poids se comptent par blocs de sept jours qui finissent avec la periode`() {
        val weights = WeightHistory.of(
            imported = emptyList(),
            declared = listOf(WeighIn(day("2026-09-12"), 71.0), WeighIn(day("2026-09-04"), 72.0)),
        )

        val means = weights.weeklyMeans(WeekRange(day("2026-09-03"), day("2026-09-12")), 2)

        // Blocs : 30 août au 5 septembre, puis 6 au 12 septembre. La pesée du 12 n'est pas perdue.
        assertEquals(listOf(72.0, 71.0), means.map { it.averageKg })
        assertNotNull(means.last().range)
        assertEquals(day("2026-09-12"), means.last().range.end)
    }
}
