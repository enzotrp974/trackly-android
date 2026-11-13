package app.trackly.core.review

import app.trackly.core.day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Perte observée par semaine, au début puis récemment : pur, courbes de poids construites à la main (pesées fictives). */
class ObservedPaceTest {
    private val asOf = day("2026-11-06")

    /** Trois pesées par bloc de sept jours qui finit le [asOf], sur [weeks] semaines ; [kgAt] donne le poids en jours depuis la première pesée. */
    private fun weights(weeks: Int, kgAt: (Int) -> Double): List<WeighIn> {
        val first = asOf.minusDays(7L * weeks - 1)
        return (0 until weeks).flatMap { w -> listOf(1, 3, 5).map { d -> w * 7 + d } }
            .map { offset -> WeighIn(first.plusDays(offset.toLong()), Math.round(kgAt(offset) * 100) / 100.0) }
    }

    private fun compute(w: List<WeighIn>, losing: Boolean = true, params: ReviewParams = ReviewParams.Default) =
        ObservedPaces.compute(w, asOf, losing, params)

    @Test
    fun `perte reguliere - rien n a ralenti`() {
        val pace = compute(weights(7) { 70.0 - 0.4 * it / 7 })!!
        assertEquals(0.4, pace.earlyKgPerWeek, 0.01)
        assertEquals(0.4, pace.recentKgPerWeek, 0.01)
        assertFalse(pace.slowed)
        assertFalse(pace.slowdownExpected)
    }

    @Test
    fun `une perte qui ralentit comme le modele - ralentissement observe et attendu`() {
        // Dépense qui baisse très vite avec le poids (120 kcal par kilo, tau de 64 jours) : le modèle attend un net ralentissement,
        // et la courbe simulée avec ce même modèle le montre.
        val params = ReviewParams(expenditureKcalPerKg = 120.0)
        var w = 70.0
        val curve = (0..49).map { t -> w.also { w -= (3000.0 + 120.0 * (w - 70.0) - 2300.0) / 7700.0 } }
        val pace = compute(weights(7) { curve[it] }, params = params)!!
        assertTrue("début ${pace.earlyKgPerWeek}, récent ${pace.recentKgPerWeek}", pace.slowed)
        assertTrue(pace.recentKgPerWeek < pace.earlyKgPerWeek)
        assertEquals(pace.observedRatio, pace.expectedRatio, 0.1)
        assertTrue(pace.slowdownExpected)
    }

    @Test
    fun `un rythme qui s effondre n est pas un ralentissement attendu`() {
        // 0,5 kg par semaine pendant trois semaines, puis presque plus rien : bien plus fort que ce que le modèle attend.
        val pace = compute(weights(6) { if (it < 21) 70.0 - 0.5 * it / 7 else 68.5 - 0.05 * (it - 21) / 7 })!!
        assertTrue(pace.slowed)
        assertTrue("part attendue ${pace.expectedRatio}", pace.expectedRatio > 0.8)
        assertFalse(pace.slowdownExpected)
    }

    @Test
    fun `moins de six moyennes hebdomadaires - pas de comparaison`() {
        assertNull(compute(weights(5) { 70.0 - 0.4 * it / 7 }))
    }

    @Test
    fun `un rythme du debut qui ne va pas vers l objectif - pas de comparaison`() {
        assertNull(compute(weights(7) { 70.0 + 0.4 * it / 7 }))
    }

    @Test
    fun `vers une prise de poids - le sens est celui de l objectif`() {
        val gain = weights(7) { if (it < 21) 60.0 + 0.5 * it / 7 else 61.5 + 0.2 * (it - 21) / 7 }
        val pace = compute(gain, losing = false)!!
        assertEquals(0.5, pace.earlyKgPerWeek, 0.05)
        assertTrue(pace.slowed)
        assertNull(compute(gain, losing = true))
    }

    @Test
    fun `une pesee apres la fin de la periode n est pas lue`() {
        val base = weights(7) { 70.0 - 0.4 * it / 7 }
        val after = base + WeighIn(asOf.plusDays(2), 90.0)
        assertEquals(compute(base)!!.recentKgPerWeek, compute(after)!!.recentKgPerWeek, 1e-9)
    }

    @Test
    fun `le debut de la fenetre est date`() {
        val pace = compute(weights(7) { 70.0 - 0.4 * it / 7 })!!
        assertNotNull(pace.earlyFrom)
        assertTrue(pace.earlyFrom.isBefore(asOf.minusDays(30)))
        assertTrue(LocalDate.of(2026, 9, 1).isBefore(pace.earlyFrom))
    }
}
