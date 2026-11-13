package app.trackly.core.review

import app.trackly.core.day
import app.trackly.core.forecast.ArrivalVerdict
import app.trackly.core.forecast.ForecastStatus
import app.trackly.core.goal.TemporaryGoals
import app.trackly.core.goal.WeightTarget
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DayEnergy
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.MacroTotal
import app.trackly.core.summary.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Conseils chiffrés du bilan : calculés par l'application depuis la prévision de poids et le dépassement de la période.
 * Courbes et repas fictifs, construits à la main.
 */
class CatchUpAdviceTest {
    private val params = ReviewParams.Default
    private val goalKcal = 2350.0
    private val goals = listOf(goal(day("2026-01-01"), goalKcal))

    /** Un exemple : « tu as dépassé de 400 kcal la semaine dernière ». */
    private val userGaps = listOf(290.0, 220.0, -40.0, -30.0, 10.0, -40.0, -10.0)
    private val onGoal = List(7) { 0.0 }

    // Même courbe que le test « ralentissement attendu » de la prévision : 70 kg le 7 septembre, 2 350 kcal par jour, la perte passe
    // d'environ 0,59 à 0,36 kg par semaine.
    private val start = day("2026-09-07")
    private val path = simulate(70.0, 2350.0, 220)
    private val trueArrival = start.plusDays(path.indexOfFirst { it <= 61.0 }.toLong())
    private val sundays = listOf(6, 10, 14, 18).map { start.plusWeeks(it.toLong()).minusDays(1) }

    @Test
    fun `ralentissement attendu - aucun conseil, ni baisse ni sport, meme avec 400 kcal au-dessus`() {
        val target = WeightTarget(61.0, trueArrival.plusDays(10))
        for (sunday in sundays) {
            for (gaps in listOf(onGoal, userGaps)) {
                val figures = figures(sunday, target, gaps)
                assertEquals(ForecastStatus.Measured, figures.forecast.status)
                assertEquals("bilan du $sunday", CatchUpResult.None(NoAdviceReason.OnTrack), CatchUpAdvices.compute(figures, goals, sunday))
            }
        }
    }

    @Test
    fun `en retard par le seul rythme - ni baisse ni pas, seulement la date`() {
        val target = WeightTarget(61.0, trueArrival.minusDays(35))
        val sunday = sundays[1]
        val figures = figures(sunday, target, onGoal)
        assertTrue(figures.forecast.verdict in setOf(ArrivalVerdict.LikelyLate, ArrivalVerdict.Late))

        val advice = CatchUpAdvices.compute(figures, goals, sunday) as CatchUpResult.Advice
        assertNull(advice.plan)
        assertNull(advice.steps)
        assertNull(advice.mix)
        assertEquals(figures.forecast.arrival, advice.newDate)
    }

    @Test
    fun `en retard avec 400 kcal de depassement - moins 100 pendant 4 jours des demain, puis retour`() {
        val sunday = sundays[1]
        val figures = figures(sunday, WeightTarget(61.0, trueArrival.minusDays(35)), userGaps)

        val advice = CatchUpAdvices.compute(figures, goals, sunday) as CatchUpResult.Advice
        assertEquals(400.0, advice.overshootKcal!!, 0.01)
        val plan = advice.plan!!
        assertEquals(sunday.plusDays(1), plan.start)
        assertEquals(4, plan.days)
        assertEquals(100.0, plan.dropKcal, 0.0)
        assertEquals(2250.0, plan.temporaryKcal, 0.0)
        assertEquals(sunday.plusDays(5), plan.returnOn)

        // Le même effort en pas, au poids actuel (aucun pas donné : on part de la référence).
        val steps = advice.steps!!
        assertEquals(figures.forecast.currentKg!!, steps.weightKg, 0.0)
        assertEquals(params.referenceSteps, steps.fromSteps, 0.0)
        assertTrue("${steps.kcalPerDay}", abs(steps.kcalPerDay - 100.0) <= 10.0)
        assertEquals(0, steps.extraSteps % CatchUpAdvices.STEPS_ROUNDING)

        // Le mélange : moitié calories, moitié pas.
        assertEquals(50.0, advice.mix!!.plan.dropKcal, 0.0)
        assertTrue(advice.mix!!.steps.extraSteps < steps.extraSteps)
    }

    @Test
    fun `la duree choisie par l IA reste dans les bornes et l app recalcule la baisse`() {
        val sunday = sundays[1]
        val figures = figures(sunday, WeightTarget(61.0, trueArrival.minusDays(35)), userGaps)
        val seven = (CatchUpAdvices.compute(figures, goals, sunday, days = 7) as CatchUpResult.Advice).plan!!
        assertEquals(7, seven.days)
        assertEquals(60.0, seven.dropKcal, 0.0) // 400 / 7 = 57, arrondi à 10
        val tooLong = (CatchUpAdvices.compute(figures, goals, sunday, days = 30) as CatchUpResult.Advice).plan!!
        assertEquals(TemporaryGoals.MAX_DAYS, tooLong.days)
        val oneDay = (CatchUpAdvices.compute(figures, goals, sunday, days = 1) as CatchUpResult.Advice).plan!!
        assertEquals(200.0, oneDay.dropKcal, 0.0) // 400 en un jour : plafonné à 200
    }

    @Test
    fun `deja applique - plus de baisse ni de pas, jamais de double rattrapage`() {
        val sunday = sundays[1]
        val figures = figures(sunday, WeightTarget(61.0, trueArrival.minusDays(35)), userGaps)
        val advice = CatchUpAdvices.compute(figures, goals, sunday, inProgress = true) as CatchUpResult.Advice
        assertTrue(advice.inProgress)
        assertNull(advice.plan)
        assertNull(advice.steps)
        assertNull(advice.mix)
    }

    @Test
    fun `petit depassement - pas d objectif temporaire, seulement la date`() {
        val sunday = sundays[1]
        val figures = figures(sunday, WeightTarget(61.0, trueArrival.minusDays(35)), listOf(60.0, 50.0, 0.0, 0.0, 30.0, 0.0, 0.0))
        val advice = CatchUpAdvices.compute(figures, goals, sunday) as CatchUpResult.Advice
        assertEquals(140.0, advice.overshootKcal!!, 0.01)
        assertNull(advice.plan)
        assertNull(advice.steps)
        assertNotNull(advice.newDate)
    }

    @Test
    fun `un objectif deja prevu sur ces jours bloque l objectif temporaire`() {
        val sunday = sundays[1]
        val figures = figures(sunday, WeightTarget(61.0, trueArrival.minusDays(35)), userGaps)
        val planned = goals + goal(sunday.plusDays(3), 2200.0)
        val advice = CatchUpAdvices.compute(figures, planned, sunday) as CatchUpResult.Advice
        assertNull(advice.plan)
        assertTrue(advice.planBlocked!!, advice.planBlocked!!.contains(sunday.plusDays(3).toString()))
    }

    @Test
    fun `sans date ferme, objectif de prise ou date passee - aucun conseil`() {
        val sunday = sundays[1]
        val few = WeightHistory.of(emptyList(), listOf(WeighIn(sunday.minusDays(1), 66.0)))
        val provisional = WeeklyReview.compute(range(sunday), days(sunday, userGaps), null, few, params, WeightTarget(61.0, trueArrival.minusDays(35)))
        assertEquals(CatchUpResult.None(NoAdviceReason.NoFirmDate), CatchUpAdvices.compute(provisional, goals, sunday))

        val gain = figures(sunday, WeightTarget(80.0, sunday.plusDays(60), losing = false), userGaps)
        assertEquals(CatchUpResult.None(NoAdviceReason.GainGoal), CatchUpAdvices.compute(gain, goals, sunday))

        val passed = figures(sunday, WeightTarget(61.0, sunday.minusDays(1)), userGaps)
        assertEquals(CatchUpResult.None(NoAdviceReason.TargetDatePassed), CatchUpAdvices.compute(passed, goals, sunday))
    }

    // ───── Plan et limites de sécurité ─────

    @Test
    fun `plan - un gros depassement n est rattrape qu en partie, jamais plus de 200 par jour ni 7 jours`() {
        val plan = CatchUpAdvices.plan(3000.0, 2300.0, day("2026-11-07"))!!
        assertEquals(7, plan.days)
        assertEquals(200.0, plan.dropKcal, 0.0)
        assertEquals(1400.0, plan.totalKcal, 0.0)
    }

    @Test
    fun `plan - la baisse reste sous 10 pourcent de l objectif et au-dessus du plancher`() {
        assertEquals(180.0, CatchUpAdvices.plan(3000.0, 1800.0, day("2026-11-07"))!!.dropKcal, 0.0)
        // 1 600 kcal : 10 % = 160, mais le plancher de 1 500 n'en laisse que 100.
        assertEquals(100.0, CatchUpAdvices.plan(3000.0, 1600.0, day("2026-11-07"))!!.dropKcal, 0.0)
        assertNull(CatchUpAdvices.plan(3000.0, 1510.0, day("2026-11-07")))
    }

    // ───── Pas au poids actuel ─────

    @Test
    fun `coût des pas - suit le poids, moitie au-dessus de la reference`() {
        // Sous la référence : 0,5 kcal par kg et par 1 000 pas.
        assertEquals(35.0, StepsCost.kcalFor(1000.0, 15_000.0, 70.0, params), 1e-9)
        assertEquals(30.0, StepsCost.kcalFor(1000.0, 15_000.0, 60.0, params), 1e-9)
        // Au-dessus : la moitié seulement.
        assertEquals(15.0, StepsCost.kcalFor(1000.0, 20_000.0, 60.0, params), 1e-9)
        // À cheval : 1 000 pas sous la référence (35 kcal), puis le reste à 17,5 kcal par 1 000.
        assertEquals(1000.0 + 65.0 / 17.5 * 1000.0, StepsCost.extraStepsFor(100.0, 19_000.0, 70.0, params), 1e-6)
        assertEquals(100.0, StepsCost.kcalFor(StepsCost.extraStepsFor(100.0, 19_000.0, 70.0, params), 19_000.0, 70.0, params), 1e-9)
    }

    @Test
    fun `conseil en pas - au poids actuel, plus leger donc plus de pas, et rien d irrealiste`() {
        val activity = Activity(16_000.0, StepsSource.Average, 0, null)
        val heavy = CatchUpAdvices.steps(100.0, 4, 70.0, activity, params)!!
        val light = CatchUpAdvices.steps(100.0, 4, 60.0, activity, params)!!
        assertEquals(3000, heavy.extraSteps) // 100 / 35 par 1 000 = 2 857, arrondi à 500
        assertEquals(3500, light.extraSteps) // 100 / 30 par 1 000 = 3 333
        assertTrue(heavy.fromKnown)
        // 200 kcal au-dessus de 20 000 pas à 60 kg : plus de 13 000 pas, pas un conseil réaliste.
        assertNull(CatchUpAdvices.steps(200.0, 1, 60.0, Activity(20_000.0, StepsSource.Average, 0, null), params))
    }

    // ───── Aides ─────

    private fun goal(from: LocalDate, kcal: Double) =
        Goal(from, 1, RecordState.Active, GoalTargets(kcal, 140.0, (kcal - 140 * 4 - 70 * 9) / 4, 70.0), Instant.EPOCH, null)

    private fun range(sunday: LocalDate) = WeekRange(sunday.minusDays(6), sunday)

    private fun counted(day: LocalDate, kcal: Double): DaySummary {
        val totals = Totals(kcal, MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true))
        return DaySummary(day, GoalTargets(goalKcal), emptyList(), totals, DayEnergy.detailed(totals), DayCoverage.Complete)
    }

    private fun days(sunday: LocalDate, gaps: List<Double>) = range(sunday).days.mapIndexed { i, d -> counted(d, goalKcal + gaps[i]) }

    /** Pesées de la courbe (4 par semaine, 6 semaines jusqu'au [sunday]) et repas de la période. */
    private fun figures(sunday: LocalDate, target: WeightTarget, gaps: List<Double>): WeeklyFigures {
        val firstMonday = WeekRange.containing(sunday).start.minusWeeks(5)
        val shift = java.time.temporal.ChronoUnit.DAYS.between(start, firstMonday)
        val wobble = listOf(0.2, -0.2, 0.1, -0.1)
        val weighIns = (0 until 6).flatMap { w ->
            listOf(0L, 2L, 4L, 6L).mapIndexed { i, d -> w * 7L + d to wobble[i] }
        }.map { (offset, noise) -> WeighIn(firstMonday.plusDays(offset), path[(shift + offset).toInt()] + noise) }
            .filter { !it.day.isAfter(sunday) }
        return WeeklyReview.compute(range(sunday), days(sunday, gaps), null, WeightHistory.of(emptyList(), weighIns), params, target)
    }

    private fun simulate(startKg: Double, intakeKcal: Double, days: Int): List<Double> {
        val out = ArrayList<Double>(days + 1)
        var w = startKg
        repeat(days + 1) {
            out += w
            w -= (3000.0 + 30.0 * (w - 70.0) - intakeKcal) / 7700.0
        }
        return out
    }
}
