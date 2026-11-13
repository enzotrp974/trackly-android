package app.trackly.core.review

import app.trackly.core.day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Poids de la semaine, tendance et statut de la trajectoire (bilan du lundi) : moteur pur, sans base.
 * Semaine du bilan : 7 au 13 septembre 2026 ; objectif 61 kg au 1er mars 2027 (environ 24,1 semaines restantes,
 * donc environ -0,46 kg par semaine pour qui pèse 72 kg).
 */
class WeeklyTrendTest {
    private val week = WeekRange(day("2026-09-07"))
    private val params = ReviewParams.Default

    /** Pesées d'une semaine (décalage en semaines avant la semaine du bilan), une par jour à partir du lundi. */
    private fun weighWeek(weeksBack: Int, vararg kg: Double): List<WeighIn> {
        val start = week.start.minusWeeks(weeksBack.toLong())
        return kg.mapIndexed { i, value -> WeighIn(start.plusDays(i.toLong()), value) }
    }

    private fun history(vararg weeks: List<WeighIn>) = WeightHistory.of(emptyList(), weeks.toList().flatten())

    private fun compute(history: WeightHistory) =
        WeeklyReview.compute(week, emptyList(), null, history, target = app.trackly.core.goal.WeightTarget(61.0, java.time.LocalDate.of(2027, 3, 1))).trajectory

    /** 7 pesées autour de [mean], sans écart (moyenne exacte). */
    private fun steady(weeksBack: Int, mean: Double) = weighWeek(weeksBack, *DoubleArray(7) { mean })

    // ───── Poids de la semaine ─────

    @Test
    fun `le poids de la semaine est la moyenne des pesees, avec leur nombre`() {
        val t = compute(history(weighWeek(0, 72.4, 71.8, 72.0)))
        assertEquals((72.4 + 71.8 + 72.0) / 3, t.weekAverageKg!!, 1e-9)
        assertEquals(3, t.weighInCount)
        assertEquals(WeighInReliability.Enough, t.reliability)
    }

    @Test
    fun `une seule pesee est signalee comme peu fiable, deux comme indicatives`() {
        val one = WeeklyReview.compute(week, emptyList(), null, history(weighWeek(0, 73.0)))
        assertEquals(WeighInReliability.Single, one.trajectory.reliability)
        assertTrue(one.missing.single { it.code == MissingCode.FewWeighIns }.message.contains("peu fiable"))

        val two = WeeklyReview.compute(week, emptyList(), null, history(weighWeek(0, 73.0, 72.0)))
        assertEquals(WeighInReliability.Few, two.trajectory.reliability)
        assertTrue(two.missing.any { it.code == MissingCode.FewWeighIns })

        val three = WeeklyReview.compute(week, emptyList(), null, history(weighWeek(0, 73.0, 72.0, 72.5)))
        assertFalse(three.missing.any { it.code == MissingCode.FewWeighIns })
    }

    // ───── Pente et fourchette ─────

    @Test
    fun `pente et fourchette sur des moyennes bruitees - la pente vraie est dans la fourchette`() {
        // Perte vraie de 0,4 kg par semaine depuis 73,2 ; écarts de -0,25 à +0,3 kg sur chaque moyenne.
        val noise = listOf(0.25, -0.20, 0.30, -0.15)
        val weeks = (0..3).map { i ->
            WeekMean(WeekRange(week.start.minusWeeks((3 - i).toLong())), 73.2 - 0.4 * i + noise[i], 7)
        }

        val trend = WeightTrends.fit(weeks, params)!!

        assertTrue("pente ${trend.slopeKgPerWeek}", trend.slopeKgPerWeek < 0)
        assertTrue(trend.lowKgPerWeek < -0.4 && -0.4 < trend.highKgPerWeek)
        // 4 semaines de même poids : moindres carrés ordinaires, erreur type bruit / racine(5), fourchette +- 2 erreurs types.
        val noiseKg = WeightTrends.weekNoiseKg(7, params)
        assertEquals(2 * noiseKg / sqrt(5.0), trend.halfWidthKgPerWeek, 1e-9)
        // Pente par les formules classiques sur les 4 points.
        val ys = (0..3).map { 73.2 - 0.4 * it + noise[it] }
        val xs = listOf(0.0, 1.0, 2.0, 3.0)
        val expected = xs.indices.sumOf { (xs[it] - 1.5) * (ys[it] - ys.average()) } / 5.0
        assertEquals(expected, trend.slopeKgPerWeek, 1e-9)
    }

    @Test
    fun `une pesee isolee pese moins dans la tendance qu une semaine bien pesee`() {
        val base = listOf(WeekMean(WeekRange(week.start.minusWeeks(3)), 73.5, 7), WeekMean(WeekRange(week.start.minusWeeks(2)), 73.0, 7), WeekMean(WeekRange(week.start.minusWeeks(1)), 72.5, 7))
        val outlierSingle = WeightTrends.fit(base + WeekMean(week, 74.0, 1), params)!!
        val outlierWell = WeightTrends.fit(base + WeekMean(week, 74.0, 7), params)!!
        // Vraie pente -0,5 ; la valeur aberrante la fausse moins quand elle ne repose que sur une pesée.
        assertTrue(abs(outlierSingle.slopeKgPerWeek + 0.5) < abs(outlierWell.slopeKgPerWeek + 0.5))
        assertTrue(outlierSingle.halfWidthKgPerWeek > 0.0)
    }

    @Test
    fun `moins de trois semaines pesees - aucune tendance`() {
        val two = listOf(WeekMean(WeekRange(week.start.minusWeeks(1)), 72.5, 7), WeekMean(week, 72.0, 7))
        assertNull(WeightTrends.fit(two, params))
        assertNull(WeightTrends.fit(emptyList(), params))
    }

    @Test
    fun `une semaine sans pesee dans la fenetre est sautee, sans rien supposer`() {
        // Semaines -3, -1 et 0 pesées ; la semaine -2 n'a aucune pesée.
        val t = compute(history(steady(3, 73.5), steady(1, 72.5), steady(0, 72.0)))
        assertEquals(3, t.weighedWeeks)
        val trend = t.trend!!
        assertEquals(listOf(-3, -1, 0), trend.weeks.map { (java.time.temporal.ChronoUnit.WEEKS.between(week.start, it.range.start)).toInt() })
        assertEquals(-0.5, trend.slopeKgPerWeek, 1e-9)
    }

    // ───── Statut ─────

    @Test
    fun `quatre semaines bien pesees au rythme necessaire - dans le rythme`() {
        val t = compute(history(steady(3, 73.5), steady(2, 73.0), steady(1, 72.5), steady(0, 72.0)))
        val required = (61.0 - 72.0) / t.weeksRemaining
        assertEquals(required, t.requiredKgPerWeek!!, 1e-9)
        assertEquals(-0.5, t.trend!!.slopeKgPerWeek, 1e-9)
        assertEquals(TrajectoryStatus.OnTrack, t.status)
    }

    @Test
    fun `quatre semaines bien pesees a poids stable - plus lent que le rythme necessaire`() {
        val t = compute(history(steady(3, 72.0), steady(2, 72.0), steady(1, 72.0), steady(0, 72.0)))
        assertEquals(0.0, t.trend!!.slopeKgPerWeek, 1e-9)
        assertEquals(TrajectoryStatus.Slower, t.status)
    }

    @Test
    fun `quatre semaines bien pesees nettement plus vite que necessaire - plus rapide`() {
        val t = compute(history(steady(3, 75.0), steady(2, 74.0), steady(1, 73.0), steady(0, 72.0)))
        assertEquals(TrajectoryStatus.Faster, t.status)
    }

    @Test
    fun `trois semaines a trois pesees - fourchette trop large, trop tot pour conclure`() {
        // Pente de -0,5 comme nécessaire, mais sur 3 semaines de 3 pesées la fourchette dépasse 0,6 kg par semaine.
        val t = compute(history(weighWeek(2, 73.0, 73.0, 73.0), weighWeek(1, 72.5, 72.5, 72.5), weighWeek(0, 72.0, 72.0, 72.0)))
        assertNotNull(t.trend)
        assertTrue(t.trend!!.halfWidthKgPerWeek > params.trendPrecisionKgPerWeek)
        assertEquals(TrajectoryStatus.TooEarly, t.status)
    }

    @Test
    fun `l ecart au rythme dans le bruit ne devient pas un constat`() {
        // Trois semaines à 3 pesées, poids stable : écart de 0,46 kg par semaine, fourchette de plus de 1 kg de large.
        val t = compute(history(weighWeek(2, 72.0, 72.0, 72.0), weighWeek(1, 72.0, 72.0, 72.0), weighWeek(0, 72.0, 72.0, 72.0)))
        assertTrue(abs(t.trend!!.slopeKgPerWeek - t.requiredKgPerWeek!!) < t.trend!!.halfWidthKgPerWeek)
        assertEquals(TrajectoryStatus.TooEarly, t.status)
    }

    @Test
    fun `une pesee seule apres plusieurs semaines pesees n invente pas de tendance fiable`() {
        val t = compute(history(steady(2, 73.0), steady(1, 72.5), weighWeek(0, 72.0)))
        assertEquals(WeighInReliability.Single, t.reliability)
        assertNotNull(t.trend)
        assertEquals(TrajectoryStatus.TooEarly, t.status)
    }

    @Test
    fun `sans pesee cette semaine - inconnue, meme si les semaines d avant sont pesees`() {
        val t = compute(history(steady(3, 73.5), steady(2, 73.0), steady(1, 72.5)))
        assertEquals(TrajectoryStatus.Unknown, t.status)
        assertNull(t.trend)
        assertNull(t.weekAverageKg)
    }

    @Test
    fun `date de l objectif depassee`() {
        val late = WeekRange(day("2027-03-08"))
        val h = WeightHistory.of(emptyList(), (0..2).flatMap { b -> (0..6).map { d -> WeighIn(late.start.minusWeeks(b.toLong()).plusDays(d.toLong()), 70.0) } })
        assertEquals(TrajectoryStatus.PassedDate, WeeklyReview.compute(late, emptyList(), null, h).trajectory.status)
    }

    @Test
    fun `plus rapide est du cote du chemin a parcourir - prise de poids visee`() {
        // Objectif au-dessus du poids : le sens attendu est la hausse.
        val gain = app.trackly.core.goal.WeightTarget(80.0, app.trackly.core.goal.WeightTarget.Default.date)
        val h = history(steady(3, 70.0), steady(2, 70.0), steady(1, 70.0), steady(0, 70.0))
        val t = WeeklyReview.compute(week, emptyList(), null, h, params, gain).trajectory
        assertTrue(t.requiredKgPerWeek!! > 0)
        assertEquals(TrajectoryStatus.Slower, t.status)
    }
}
