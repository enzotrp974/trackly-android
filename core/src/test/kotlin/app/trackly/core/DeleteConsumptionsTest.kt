package app.trackly.core

import app.trackly.core.action.DeleteConsumptions
import app.trackly.core.action.Rejection
import app.trackly.core.action.UndoLast
import app.trackly.core.model.Moment
import app.trackly.core.model.RecordState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Supprimer » un repas de l'écran du jour : plusieurs aliments, une seule action, une seule annulation. */
class DeleteConsumptionsTest {
    private val f = Fixture()

    @Test
    fun `supprime tous les aliments du repas et le total est recalcule`() = runTest {
        val riz = f.add("Riz", 400.0, Moment.Midi)
        val poulet = f.add("Poulet", 250.0, Moment.Midi)
        f.add("Pomme", 80.0, Moment.Snack)

        val result = f.apply(DeleteConsumptions(listOf(riz, poulet)))

        assertEquals(80.0, f.summary().totals.kcal, 0.0)
        assertEquals("Suppression : Riz, Poulet", result.title)
        assertEquals(listOf(RecordState.Active, RecordState.Deleted), f.engine.history(riz).map { it.state })
    }

    @Test
    fun `une seule annulation rend tout le repas`() = runTest {
        val riz = f.add("Riz", 400.0, Moment.Midi)
        val poulet = f.add("Poulet", 250.0, Moment.Midi)
        f.apply(DeleteConsumptions(listOf(riz, poulet)))

        f.apply(UndoLast)

        assertEquals(650.0, f.summary().totals.kcal, 0.0)
        assertEquals(listOf(RecordState.Active, RecordState.Deleted, RecordState.Active), f.engine.history(poulet).map { it.state })
    }

    @Test
    fun `tout est refuse si l un des aliments n est plus actif`() = runTest {
        val riz = f.add("Riz", 400.0, Moment.Midi)
        val poulet = f.add("Poulet", 250.0, Moment.Midi)
        f.apply(DeleteConsumptions(listOf(poulet)))

        val rejection = f.reject(DeleteConsumptions(listOf(riz, poulet)))

        assertTrue(rejection is Rejection.NotFound)
        assertEquals(400.0, f.summary().totals.kcal, 0.0)
    }
}
