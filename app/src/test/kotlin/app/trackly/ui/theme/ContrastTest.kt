package app.trackly.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contraste AA (4,5:1) sur tous les textes. */
class ContrastTest {
    private fun assertAA(fg: Color, bg: Color, nom: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$nom : contraste ${"%.2f".format(ratio)} < 4,5", ratio >= 4.5)
    }

    @Test
    fun `textes AA sur porcelaine et feuille`() {
        for ((bg, fond) in listOf(TracklyColors.Paper to "porcelaine", TracklyColors.PaperHi to "feuille")) {
            assertAA(TracklyColors.Ink, bg, "encre / $fond")
            assertAA(TracklyColors.Ink2, bg, "encre 2 / $fond")
            assertAA(TracklyColors.Ink3, bg, "encre 3 / $fond")
            assertAA(TracklyColors.Cobalt, bg, "cobalt / $fond")
        }
    }

    @Test
    fun `textes AA sur les aplats`() {
        assertAA(TracklyColors.Cobalt, TracklyColors.Mist, "cobalt / brume")
        assertAA(TracklyColors.PaperHi, TracklyColors.Cobalt, "feuille / cobalt")
        assertAA(TracklyColors.PaperHi, TracklyColors.Ink, "feuille / encre")
    }

    @Test
    fun `formule WCAG`() {
        val r = contrastRatio(Color.Black, Color.White)
        assertTrue(r > 20.99 && r < 21.01)
    }
}
