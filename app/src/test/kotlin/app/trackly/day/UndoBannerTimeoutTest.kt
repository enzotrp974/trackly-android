package app.trackly.day

import android.os.Build
import android.view.accessibility.AccessibilityManager
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Le bandeau « Annuler » suit le délai d'accessibilité d'Android (10 s au minimum). */
@OptIn(ExperimentalCoroutinesApi::class)
class UndoBannerTimeoutTest {
    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private suspend fun entryOf(engine: app.trackly.core.engine.TracklyEngine): String {
        val outcome = engine.execute(AddConsumptions(NewConsumption(Food("Pomme", Nutrition(78.0, 0.4, 19.0, 0.3)), Moment.Snack))) as ActionOutcome.Applied
        return outcome.result.consumptionChanges.single().after.entryId.value
    }

    @Test
    fun `sans reglage d accessibilite, le bandeau disparait apres 10 secondes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = SampleData.emptyEngine()
        val model = DayViewModel(engine)
        val entry = entryOf(engine)

        model.deleteMeal(listOf(entry), "Pomme")
        runCurrent()
        assertNotNull(model.undoOffer.value)
        advanceTimeBy(9_900)
        assertNotNull(model.undoOffer.value)
        advanceTimeBy(200)
        assertNull(model.undoOffer.value)
    }

    @Test
    fun `avec un delai recommande de 30 secondes, le bandeau reste 30 secondes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = SampleData.emptyEngine()
        val asked = mutableListOf<Long>()
        val model = DayViewModel(engine, undoTimeout = { default -> asked += default; 30_000L })
        val entry = entryOf(engine)

        model.deleteMeal(listOf(entry), "Pomme")
        runCurrent()
        advanceTimeBy(29_000)
        assertNotNull("toujours là après 29 s", model.undoOffer.value)
        advanceTimeBy(2_000)
        assertNull(model.undoOffer.value)
        assertEquals("le délai par défaut de 10 s est demandé à Android", listOf(10_000L), asked)
    }

    @Test
    fun `API 29 et plus - FLAG_CONTENT_CONTROLS et la duree d Android`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val timeout = AndroidRecommendedTimeout(sdk = Build.VERSION_CODES.Q) { original, flags -> calls += original to flags; 25_000 }
        assertEquals(25_000L, timeout.forControls(10_000))
        assertEquals(listOf(10_000 to AccessibilityManager.FLAG_CONTENT_CONTROLS), calls)
    }

    @Test
    fun `avant l API 29 - repli a 10 secondes, sans interroger Android`() {
        var asked = false
        val timeout = AndroidRecommendedTimeout(sdk = Build.VERSION_CODES.P) { _, _ -> asked = true; 25_000 }
        assertEquals(10_000L, timeout.forControls(10_000))
        assertEquals(false, asked)
    }

    @Test
    fun `sans gestionnaire d accessibilite ou reponse invalide - 10 secondes`() {
        assertEquals(10_000L, AndroidRecommendedTimeout(sdk = 34, recommended = null).forControls(10_000))
        assertEquals(10_000L, AndroidRecommendedTimeout(sdk = 34) { _, _ -> 0 }.forControls(10_000))
        assertEquals(10_000L, AndroidRecommendedTimeout(sdk = 34) { _, _ -> error("boom") }.forControls(10_000))
    }
}
