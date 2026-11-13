package app.trackly.core.forecast

import app.trackly.core.day
import app.trackly.core.goal.WeightTarget
import app.trackly.core.model.GoalTargets
import app.trackly.core.review.ReviewParams
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeighIn
import app.trackly.core.review.WeightHistory
import app.trackly.core.review.WeightTrends
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DayEnergy
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.MacroTotal
import app.trackly.core.summary.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Prévision d'arrivée : courbes de poids construites à la main, sans base ni réseau. Les pesées sont fictives.
 *
 * Toutes les courbes ont 4 pesées par semaine (lundi, mercredi, vendredi, dimanche) avec un petit écart fixe
 * (+0,2, -0,2, +0,1, -0,1 kg, moyenne nulle), pour ressembler à une balance sans ajouter de hasard.
 */
class ArrivalForecastTest {
    private val params = ReviewParams.Default
    private val tau = params.kcalPerKg / params.expenditureKcalPerKg

    /** Dimanche du bilan. */
    private val asOf = day("2026-12-13")

    private val wobble = listOf(0.2, -0.2, 0.1, -0.1)
    private val weighDays = listOf(0L, 2L, 4L, 6L)

    /** Pesées des [weeks] semaines finissant avec celle de [end], poids donné par [kgAt] (jours depuis le premier lundi). */
    private fun curve(weeks: Int, end: LocalDate = asOf, noise: Boolean = true, kgAt: (Long) -> Double): WeightHistory {
        val firstMonday = WeekRange.containing(end).start.minusWeeks(weeks - 1L)
        val list = (0 until weeks).flatMap { w ->
            weighDays.mapIndexed { i, d ->
                val offset = w * 7L + d
                WeighIn(firstMonday.plusDays(offset), kgAt(offset) + if (noise) wobble[i] else 0.0)
            }
        }.filter { !it.day.isAfter(end) }
        return WeightHistory.of(emptyList(), list)
    }

    /**
     * Poids jour par jour d'une personne qui mange [intakeKcal] chaque jour, simulé pas à pas avec la dépense qui baisse
     * avec le poids (3 000 kcal à 70 kg, 30 kcal de moins par kilo perdu, 7 700 kcal par kilo). C'est la courbe
     * « normale » : la perte ralentit d'elle-même.
     */
    private fun simulate(startKg: Double, intakeKcal: Double, days: Int): List<Double> {
        val out = ArrayList<Double>(days + 1)
        var w = startKg
        repeat(days + 1) {
            out += w
            val expenditure = 3000.0 + 30.0 * (w - 70.0)
            w -= (expenditure - intakeKcal) / 7700.0
        }
        return out
    }

    private fun counted(day: LocalDate, kcal: Double, goalKcal: Double? = null): DaySummary {
        val totals = Totals(kcal, MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true), MacroTotal(0.0, true))
        return DaySummary(day, goalKcal?.let { GoalTargets(it) }, emptyList(), totals, DayEnergy.detailed(totals), DayCoverage.Complete)
    }

    private fun meals(from: LocalDate, to: LocalDate, kcal: Double) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { counted(it, kcal) }.toList()

    private fun forecast(
        weights: WeightHistory,
        days: List<DaySummary> = emptyList(),
        target: WeightTarget = WeightTarget(61.0, day("2027-03-01")),
        at: LocalDate = asOf,
    ) = ArrivalForecasts.compute(at, target, weights, days, params)

    private fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)

    // ───── Perte régulière ─────

    @Test
    fun `perte reguliere - date mesuree, en avance, un peu apres la date en ligne droite`() {
        // 66 kg le lundi 2 novembre, puis -0,5 kg par semaine pendant 6 semaines : environ 63 kg au 13 décembre.
        val f = forecast(curve(6) { 66.0 - 0.5 * it / 7 })

        assertEquals(ForecastStatus.Measured, f.status)
        assertEquals(PaceSource.WeighIns, f.paceSource)
        assertEquals(6, f.weighedWeeks)
        assertEquals(63.0, f.currentKg!!, 0.15)
        assertEquals(-0.5, f.paceKgPerWeek!!, 0.05)
        assertTrue(f.paceLowKgPerWeek!! < f.paceKgPerWeek!! && f.paceKgPerWeek!! < f.paceHighKgPerWeek!!)

        // 2 kg à perdre à 0,5 kg par semaine : 28 jours en ligne droite, un peu plus avec le ralentissement.
        val arrival = f.arrival!!
        val straight = asOf.plusDays((((f.currentKg!! - 61.0) / 0.5) * 7).toLong())
        assertTrue("arrivée $arrival, ligne droite $straight", !arrival.isBefore(straight))
        assertTrue(daysBetween(straight, arrival) <= 7)
        assertTrue(f.earliest!!.isBefore(arrival) && arrival.isBefore(f.latest!!))
        assertEquals(ArrivalVerdict.Early, f.verdict) // même la date la plus tardive tombe avant le 1er mars
        assertTrue(f.hasMeasuredDate)

        // Le ralentissement est dans la courbe : le rythme à l'arrivée est plus lent qu'aujourd'hui.
        assertTrue(abs(f.paceAtArrivalKgPerWeek!!) < abs(f.paceKgPerWeek!!))
    }

    @Test
    fun `la courbe attendue part du poids actuel et passe par le poids vise a la date estimee`() {
        val f = forecast(curve(6) { 66.0 - 0.5 * it / 7 }, target = WeightTarget(58.0, day("2027-06-01")))
        val first = f.expectedCurve.first()
        assertEquals(asOf, first.day)
        assertEquals(f.currentKg!!, first.kg, 1e-9)
        assertTrue(f.expectedCurve.zipWithNext().all { (a, b) -> daysBetween(a.day, b.day) in 1..7 && b.kg < a.kg })
        assertTrue(f.expectedCurve.all { it.lowKg <= it.kg && it.kg <= it.highKg })
        val nearArrival = f.expectedCurve.minBy { abs(daysBetween(it.day, f.arrival!!)) }
        assertEquals(58.0, nearArrival.kg, 0.1)

        // Chemin à temps : finit pile au poids visé à la date visée, et demande aujourd'hui plus que la ligne droite.
        val last = f.onTimeCurve.last()
        assertEquals(day("2027-06-01"), last.day)
        assertEquals(58.0, last.kg, 1e-6)
        val straightRequired = (58.0 - f.currentKg!!) / (daysBetween(asOf, day("2027-06-01")) / 7.0)
        assertTrue(f.requiredKgPerWeek!! < straightRequired)
    }

    @Test
    fun `avec les repas reels, la depense mesuree corrige la reference`() {
        // -0,5 kg par semaine en mangeant 2 400 kcal : dépense réelle environ 2 400 + 7 700 x 0,5 / 7 = 2 950 kcal.
        val weights = curve(6) { 66.0 - 0.5 * it / 7 }
        val f = forecast(weights, meals(day("2026-11-02"), asOf, 2400.0))
        val m = f.measuredExpenditure!!
        assertEquals(42, m.countedDays)
        assertEquals(2950.0, m.kcalPerDay, 60.0)
        assertTrue(m.uncertaintyKcal in 50.0..400.0)
        // Modèle au poids moyen de la fenêtre (environ 64,5 kg), comme la dépense déduite.
        assertEquals(ArrivalForecasts.model(64.5, params), m.modelKcalPerDay, 10.0)

        // Trop peu de jours comptés : pas de dépense mesurée, la prévision reste celle des pesées.
        val few = forecast(weights, meals(day("2026-12-07"), asOf, 2400.0))
        assertNull(few.measuredExpenditure)
        assertEquals(f.arrival, few.arrival)
    }

    // ───── Ralentissement attendu ─────

    @Test
    fun `ralentissement attendu - la date reste stable de bilan en bilan et rien ne devient une alerte`() {
        // 70 kg le lundi 7 septembre, 2 350 kcal par jour : la perte passe d'environ 0,59 à 0,36 kg par semaine.
        val start = day("2026-09-07")
        val path = simulate(70.0, 2350.0, 220)
        val trueArrival = start.plusDays(path.indexOfFirst { it <= 61.0 }.toLong())
        val target = WeightTarget(61.0, trueArrival.plusDays(10))

        val sundays = listOf(6, 10, 14, 18).map { start.plusWeeks(it.toLong()).minusDays(1) }
        val forecasts = sundays.map { sunday ->
            val weeks = 6
            val firstMonday = WeekRange.containing(sunday).start.minusWeeks(weeks - 1L)
            val shift = daysBetween(start, firstMonday)
            forecast(curve(weeks, end = sunday) { path[(shift + it).toInt()] }, target = target, at = sunday)
        }

        // Le rythme mesuré ralentit vraiment…
        val paces = forecasts.map { it.paceKgPerWeek!! }
        assertTrue("rythmes $paces", abs(paces.last()) < abs(paces.first()) - 0.1)
        // … mais c'est prévu : jamais de stagnation ni de retard, et la date bouge à peine.
        for (f in forecasts) {
            assertEquals("bilan du ${f.asOf}", ForecastStatus.Measured, f.status)
            assertTrue("bilan du ${f.asOf} : ${f.verdict}", f.verdict == ArrivalVerdict.Early || f.verdict == ArrivalVerdict.LikelyOnTime)
            assertTrue("bilan du ${f.asOf} : ${f.arrival} au lieu de $trueArrival", abs(daysBetween(trueArrival, f.arrival!!)) <= 7)
        }

        // Pour comparer : une ligne droite sur la tendance de 4 semaines repousse la date de plus de deux semaines.
        val straight = sundays.map { sunday ->
            val firstMonday = WeekRange.containing(sunday).start.minusWeeks(5)
            val shift = daysBetween(start, firstMonday)
            val history = curve(6, end = sunday) { path[(shift + it).toInt()] }
            val trend = WeightTrends.fit(history.weeklyMeans(WeekRange.containing(sunday), 4), params)!!
            val now = history.average(WeekRange.containing(sunday))!!
            sunday.plusDays(((now - 61.0) / -trend.slopeKgPerWeek * 7).toLong())
        }
        assertTrue("dates en ligne droite $straight", daysBetween(straight.first(), straight.last()) > 14)
    }

    // ───── Vraie stagnation ─────

    @Test
    fun `vraie stagnation - poids stable mesure avec precision, pas de date, en retard`() {
        val f = forecast(curve(6) { 64.0 })
        assertEquals(ForecastStatus.Stalled, f.status)
        assertEquals(0.0, f.paceKgPerWeek!!, 0.05)
        assertNull(f.arrival)
        assertNull(f.latest)
        assertEquals(ArrivalVerdict.Late, f.verdict)
        assertFalse(f.hasMeasuredDate)
    }

    @Test
    fun `stagnation apres une perte - date repoussee, jamais en avance`() {
        // 3 semaines à -0,6 kg, puis 3 semaines à 64,2 kg.
        val stalled = forecast(curve(6) { d -> if (d < 21) 66.0 - 0.6 * d / 7 else 64.2 })
        val regular = forecast(curve(6) { 66.0 - 0.6 * it / 7 })
        assertTrue(abs(stalled.paceKgPerWeek!!) < 0.35)
        assertTrue(stalled.verdict != ArrivalVerdict.Early)
        assertTrue(stalled.arrival == null || stalled.arrival!!.isAfter(regular.arrival!!.plusDays(14)))
    }

    @Test
    fun `peu de semaines bien pesees ne suffisent pas pour dire stagnation`() {
        // Même poids stable, mais 3 semaines seulement : la fourchette est trop large pour l'affirmer.
        val f = forecast(curve(3) { 64.0 })
        assertEquals(ForecastStatus.Measured, f.status)
        assertTrue((f.paceHighKgPerWeek!! - f.paceLowKgPerWeek!!) / 2 > params.trendPrecisionKgPerWeek)
        assertNull(f.arrival)
    }

    // ───── Reprise ─────

    @Test
    fun `reprise - le poids remonte de facon sure, pas de date`() {
        val f = forecast(curve(6) { 63.0 + 0.4 * it / 7 })
        assertEquals(ForecastStatus.MovingAway, f.status)
        assertTrue(f.paceLowKgPerWeek!! > 0)
        assertNull(f.arrival)
        assertNull(f.earliest)
        assertNull(f.plateauKg)
        assertEquals(ArrivalVerdict.Late, f.verdict)
    }

    @Test
    fun `remontee apres une perte - aucune date annoncee`() {
        val f = forecast(curve(6) { d -> if (d < 21) 66.0 - 0.5 * d / 7 else 64.5 + 0.5 * (d - 21) / 7 })
        assertTrue("statut ${f.status}", f.status == ForecastStatus.Stalled || f.status == ForecastStatus.MovingAway || f.arrival == null)
        assertNull(f.arrival)
        assertTrue(f.verdict == ArrivalVerdict.Late || f.verdict == ArrivalVerdict.LikelyLate)
    }

    // ───── Peu de pesées ─────

    @Test
    fun `aucune pesee - rien n est dit`() {
        val f = forecast(WeightHistory.Empty, meals(day("2026-11-16"), asOf, 2400.0))
        assertEquals(ForecastStatus.NoWeighIn, f.status)
        assertNull(f.currentKg)
        assertNull(f.arrival)
        assertNull(f.verdict)
        assertTrue(f.expectedCurve.isEmpty())
    }

    @Test
    fun `pesees trop anciennes - hors de la fenetre, rien n est suppose`() {
        val old = curve(3, end = day("2026-10-04")) { 66.0 }
        assertEquals(ForecastStatus.NoWeighIn, forecast(old).status)
    }

    @Test
    fun `deux semaines pesees et des repas - estimation theorique, jamais une date ferme`() {
        // 64 kg, 2 400 kcal par jour : dépense de référence à 64 kg = 3 000 - 6 x 30 = 2 820 kcal, déficit 420 kcal.
        val f = forecast(curve(2, noise = false) { 64.0 }, meals(day("2026-11-23"), asOf, 2400.0))
        assertEquals(ForecastStatus.Provisional, f.status)
        assertEquals(PaceSource.MealsAndReference, f.paceSource)
        assertNull(f.verdict)
        assertFalse(f.hasMeasuredDate)
        assertEquals(-420.0 / 7700 * 7, f.paceKgPerWeek!!, 1e-9)
        // Marge de la référence : ± 300 kcal par jour.
        assertEquals(300.0 / 7700 * 7, f.paceHighKgPerWeek!! - f.paceKgPerWeek!!, 1e-9)
        val arrival = f.arrival!!
        assertTrue(f.earliest!!.isBefore(arrival))
        assertTrue(f.latest == null || f.latest!!.isAfter(arrival))
        assertTrue(f.assumptions.any { "pas une date ferme" in it })
    }

    @Test
    fun `une pesee et pas de repas - rythme suppose depuis l objectif calorique, signale`() {
        val one = WeightHistory.of(emptyList(), listOf(WeighIn(day("2026-12-10"), 64.0)))
        val f = forecast(one, listOf(counted(day("2026-12-12"), 900.0, goalKcal = 2300.0).copy(coverage = DayCoverage.Partial)))
        assertEquals(ForecastStatus.Provisional, f.status)
        assertEquals(PaceSource.CalorieGoalAndReference, f.paceSource)
        assertEquals((2300.0 - 2820.0) / 7700 * 7, f.paceKgPerWeek!!, 1e-9)
        assertNull(f.verdict)
    }

    @Test
    fun `une pesee seule, sans repas ni objectif - pas de date`() {
        val one = WeightHistory.of(emptyList(), listOf(WeighIn(day("2026-12-10"), 64.0)))
        val f = forecast(one)
        assertEquals(ForecastStatus.Provisional, f.status)
        assertEquals(PaceSource.None, f.paceSource)
        assertEquals(64.0, f.currentKg!!, 1e-9)
        assertNull(f.arrival)
        assertNull(f.paceKgPerWeek)
        assertNotNull(f.requiredKgPerWeek)
    }

    @Test
    fun `a ces repas la perte s arrete avant le poids vise - pas de date, palier donne`() {
        // 64 kg, 2 750 kcal : déficit de 70 kcal, la dépense rejoint les apports vers 61,67 kg.
        val f = forecast(curve(1, noise = false) { 64.0 }, meals(day("2026-11-23"), asOf, 2750.0))
        assertNull(f.arrival)
        assertEquals(64.0 - 70.0 / 30.0, f.plateauKg!!, 1e-9)
    }

    // ───── Objectif atteint, date passée, calcul ─────

    @Test
    fun `objectif atteint ou depasse`() {
        assertEquals(ForecastStatus.Reached, forecast(curve(6) { 61.02 }).status)
        assertEquals(ForecastStatus.Reached, forecast(curve(6) { 63.0 - 0.5 * it / 7 }).status) // finit vers 60 kg
    }

    @Test
    fun `objectif depasse et tenu - reste atteint, aucun rythme a tenir`() {
        val f = forecast(curve(6) { 60.5 })
        assertEquals(ForecastStatus.Reached, f.status)
        assertNull(f.requiredKgPerWeek)
        assertTrue(f.onTimeCurve.isEmpty())
        // Même chose avec une seule pesée.
        val one = forecast(WeightHistory.of(emptyList(), listOf(WeighIn(day("2026-12-10"), 60.5))))
        assertEquals(ForecastStatus.Reached, one.status)
        assertNull(one.requiredKgPerWeek)
    }

    @Test
    fun `lente arrivee tout pres du but - ni stagnation ni retard`() {
        // 61,6 kg puis -0,08 kg par semaine : le poids visé tombe dans quelques semaines, bien avant le 1er mars.
        val f = forecast(curve(6, noise = false) { 61.6 - 0.08 * it / 7 })
        assertEquals(ForecastStatus.Measured, f.status)
        assertNotNull(f.arrival)
        assertTrue(f.arrival!!.isBefore(day("2027-03-01")))
        assertTrue("verdict ${f.verdict}", f.verdict == ArrivalVerdict.Early || f.verdict == ArrivalVerdict.LikelyOnTime)
    }

    @Test
    fun `OBJ-03 - lente arrivee calculee mais apres la date visee - date mesuree en retard, jamais pas d arrivee`() {
        // Environ 61,2 kg aujourd'hui, -0,08 kg par semaine vers 61 kg : arrivée dans deux à trois semaines, date visée dans 7 jours.
        val f = forecast(curve(6, noise = false) { 61.7 - 0.08 * it / 7 }, target = WeightTarget(61.0, asOf.plusDays(7)))
        assertEquals(61.2, f.currentKg!!, 0.1)
        assertNotNull(f.arrival)
        assertTrue("arrivée ${f.arrival}", f.arrival!!.isAfter(asOf.plusDays(7)))
        assertEquals(ForecastStatus.Measured, f.status)
        assertTrue(f.hasMeasuredDate)
        assertTrue("verdict ${f.verdict}", f.verdict == ArrivalVerdict.LikelyLate || f.verdict == ArrivalVerdict.Late)
    }

    @Test
    fun `semaines pesees mais aucune recente - pas de date mesuree`() {
        // Pesées il y a 5, 4 et 3 semaines, rien depuis : pas de date ferme sur une courbe qui s'arrête.
        val old = WeightHistory.of(emptyList(), curve(6) { 66.0 - 0.5 * it / 7 }.all.filter { it.day.isBefore(day("2026-11-23")) })
        val f = forecast(old, meals(day("2026-11-16"), asOf, 2400.0))
        assertEquals(ForecastStatus.Provisional, f.status)
        assertFalse(f.hasMeasuredDate)
        assertNull(f.arrival) // dernière pesée de plus de 14 jours : pas de point de départ
        assertEquals(day("2026-11-22"), f.currentKgDay)
        assertNull(f.requiredKgPerWeek)
    }

    @Test
    fun `estimation partie d une pesee recente - datee de cette pesee`() {
        val one = WeightHistory.of(emptyList(), listOf(WeighIn(day("2026-12-03"), 64.0)))
        val f = forecast(one, meals(day("2026-11-23"), asOf, 2400.0))
        assertEquals(day("2026-12-03"), f.currentKgDay)
        assertNotNull(f.arrival)
    }

    @Test
    fun `arrivee au-dela de trois ans - pas de date, pas de palier avant la cible`() {
        // 64 kg, 2 729 kcal : déficit de 91 kcal, la perte ralentirait jusqu'à 60,97 kg ; 61 kg dans plus de 3 ans.
        val f = forecast(curve(1, noise = false) { 64.0 }, meals(day("2026-11-23"), asOf, 2729.0))
        assertNull(f.arrival)
        assertNull(f.plateauKg)
    }

    @Test
    fun `date visee passee - signalee, la date estimee reste calculee`() {
        val f = forecast(curve(6) { 66.0 - 0.5 * it / 7 }, target = WeightTarget(61.0, day("2026-12-01")))
        assertTrue(f.targetDatePassed)
        assertEquals(ArrivalVerdict.Late, f.verdict)
        assertNotNull(f.arrival)
        assertTrue(f.onTimeCurve.isEmpty())
    }

    @Test
    fun `prise de poids visee - meme calcul dans l autre sens`() {
        val f = forecast(curve(6) { 58.0 + 0.3 * it / 7 }, target = WeightTarget(62.0, day("2027-06-01"), losing = false))
        assertEquals(ForecastStatus.Measured, f.status)
        assertTrue(f.paceKgPerWeek!! > 0)
        assertTrue(f.arrival!!.isAfter(asOf))
    }

    @Test
    fun `temps pour arriver - forme ralentie, symetrique, jamais au-dela du palier`() {
        // Ligne droite pour un petit écart, un peu plus long pour un grand.
        assertEquals(7.0, ArrivalForecasts.daysToReach(64.0, 63.9, -0.1 / 7, tau)!!, 0.15)
        val long = ArrivalForecasts.daysToReach(70.0, 61.0, -0.6 / 7, tau)!!
        assertTrue(long > 9 / 0.6 * 7)
        assertEquals(long, ArrivalForecasts.daysToReach(61.0, 70.0, 0.6 / 7, tau)!!, 1e-9)
        assertNull(ArrivalForecasts.daysToReach(64.0, 61.0, 0.1, tau)) // mauvais sens
        assertNull(ArrivalForecasts.daysToReach(64.0, 61.0, -2.0 / tau, tau)) // palier à 62 kg
        // Accord avec la simulation pas à pas.
        val path = simulate(70.0, 2350.0, 200)
        val simulated = path.indexOfFirst { it <= 61.0 }
        val rate0 = -(3000.0 - 2350.0) / 7700.0
        assertEquals(simulated.toDouble(), ArrivalForecasts.daysToReach(70.0, 61.0, rate0, tau)!!, 1.5)
    }

    @Test
    fun `les hypotheses disent le ralentissement attendu et la reference`() {
        val text = forecast(curve(6) { 66.0 - 0.5 * it / 7 }).assumptions.joinToString("\n")
        assertTrue("3 000 kcal" in text.replace(' ', ' '))
        assertTrue("70,0 kg" in text)
        assertTrue("30 kcal" in text)
        assertTrue("pas une alerte" in text)
        assertTrue("masse grasse" in text)
    }
}
