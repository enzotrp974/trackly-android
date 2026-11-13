package app.trackly.ui.review

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Graphique des protéines par jour (points). La valeur s'écrit sous le point ; pour une valeur basse (7 g
 * le jour en cours), elle tombait sur la ligne des jours et les deux libellés se superposaient. Le point le plus bas garde
 * maintenant la place de sa valeur au-dessus de l'axe.
 */
class DotValueLabelTest {
    @Test
    fun `point le plus bas - sa valeur reste au-dessus de l axe des jours`() {
        val axis = 200f
        val below = 15f
        val half = 6f
        val floor = dotsFloor(axis, below = below, halfText = half, gap = 2f)
        val labelBottom = floor + below + half
        assertTrue("bas de la valeur $labelBottom, axe $axis", labelBottom <= axis - 2f)
    }
}
