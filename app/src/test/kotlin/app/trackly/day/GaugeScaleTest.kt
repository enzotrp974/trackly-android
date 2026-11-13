package app.trackly.day

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeScaleTest {
    @Test
    fun `une graduation tous les 100 kcal, une grande tous les 500`() {
        val scale = GaugeScale.of(goalKcal = 2600)
        assertEquals(27, scale.ticks.size)
        assertEquals(0f, scale.ticks.first().fraction)
        assertEquals(1f, scale.ticks.last().fraction)
        assertEquals(listOf(0, 5, 10, 15, 20, 25), scale.ticks.withIndex().filter { it.value.major }.map { it.index })
    }

    @Test
    fun `les reperes chiffres s arretent avant la borne finale`() {
        val scale = GaugeScale.of(goalKcal = 2600)
        assertEquals(listOf(0, 1000, 2000), scale.labels.map { it.kcal })
        assertEquals(1000f / 2600f, scale.labels[1].fraction, 1e-6f)
        val proche = GaugeScale.of(goalKcal = 2100)
        assertEquals(listOf(0, 1000), proche.labels.map { it.kcal })
    }

    @Test
    fun `remplissage plafonne a l objectif`() {
        assertEquals(0.5f, GaugeScale.fill(consumedKcal = 1300, goalKcal = 2600), 1e-6f)
        assertEquals(1f, GaugeScale.fill(consumedKcal = 3000, goalKcal = 2600), 1e-6f)
        assertEquals(0f, GaugeScale.fill(consumedKcal = 0, goalKcal = 2600), 1e-6f)
    }

    @Test
    fun `graduations allumees jusqu a la consommation`() {
        assertEquals(15, GaugeScale.of(goalKcal = 2600).litTicks(consumedKcal = 1460))
        assertEquals(1, GaugeScale.of(goalKcal = 2600).litTicks(consumedKcal = 0))
    }
}
