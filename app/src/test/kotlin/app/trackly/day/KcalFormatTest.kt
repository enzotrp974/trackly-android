package app.trackly.day

import org.junit.Assert.assertEquals
import org.junit.Test

class KcalFormatTest {
    @Test
    fun `milliers separes par une espace fine insecable`() {
        assertEquals("1 140", formatKcal(1140))
        assertEquals("12 600", formatKcal(12600))
        assertEquals("980", formatKcal(980))
        assertEquals("0", formatKcal(0))
    }
}
