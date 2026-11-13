package app.trackly.core

import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ParityBundle
import app.trackly.core.suggestion.MealSuggestions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Les macros restantes ne se déduisent pas d'un détail partiel quand la journée est estimée. */
class RemainingMacrosEstimateTest {
    @Test
    fun `journee estimee - calories restantes selon l estimation, macros restantes inconnues`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)
        val remaining = MealSuggestions.remaining(f.engine.daySummary(day("2026-09-03")))
        assertEquals(0.0, remaining.kcal!!, 0.0)
        assertNull(remaining.proteinG)
        assertNull(remaining.carbsG)
        assertNull(remaining.fatG)
        assertFalse(remaining.macrosComplete)
    }

    @Test
    fun `journee detaillee - macros restantes calculees comme avant`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)
        val remaining = MealSuggestions.remaining(f.engine.daySummary(day("2026-09-06")))
        assertEquals(2350.0 - 1500.0, remaining.kcal!!, 0.0)
        assertEquals(152.25 - 90.0, remaining.proteinG!!, 1e-6)
    }
}
