package app.trackly.core.goal

import app.trackly.core.Fixture
import app.trackly.core.action.Rejection
import app.trackly.core.action.Undo
import app.trackly.core.day
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeighIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Objectif de poids : enregistré en versions, modifiable, annulable, utilisé par la prévision. */
class WeightGoalTest {
    private fun fixture() = Fixture("2026-12-14T09:00")

    @Test
    fun `sans objectif enregistre - 60 kg au 1er mars 2027`() = runBlocking {
        val f = fixture()
        assertEquals(WeightTarget(60.0, day("2027-03-01")), f.engine.weightTarget())
    }

    @Test
    fun `changer l objectif puis annuler revient a l objectif d avant`() = runBlocking {
        val f = fixture()
        val first = f.apply(SetWeightGoal(60.0, day("2027-04-01")))
        assertEquals(WeightTarget(60.0, day("2027-04-01")), f.engine.weightTarget())
        assertEquals(WeightTarget.Default to WeightTarget(60.0, day("2027-04-01")), SetWeightGoal.change(first.changes))
        assertEquals("Objectif de poids : 60 kg au 2027-04-01", first.title)

        val second = f.apply(SetWeightGoal(62.5, day("2027-03-15")))
        assertEquals(WeightTarget(62.5, day("2027-03-15")), f.engine.weightTarget())

        val undo = f.apply(Undo(second.id))
        assertEquals(WeightTarget(60.0, day("2027-04-01")), f.engine.weightTarget())
        assertEquals(WeightTarget(62.5, day("2027-03-15")) to WeightTarget(60.0, day("2027-04-01")), SetWeightGoal.change(undo.changes))
    }

    @Test
    fun `annuler le premier objectif fixe revient a l objectif par defaut`() = runBlocking {
        val f = fixture()
        val first = f.apply(SetWeightGoal(60.0, day("2027-04-01")))
        f.apply(Undo(first.id))
        assertEquals(WeightTarget.Default, f.engine.weightTarget()) // version « deleted » : rien n'est effacé
    }

    @Test
    fun `meme objectif, date passee, poids ou date absurde - refuses, rien n est ecrit`() = runBlocking {
        val f = fixture()
        assertTrue(f.reject(SetWeightGoal(60.0, day("2027-03-01"))) is Rejection.NoChange)
        assertTrue(f.reject(SetWeightGoal(60.0, day("2026-12-14"))) is Rejection.Invalid) // aujourd'hui n'est pas « après »
        assertTrue(f.reject(SetWeightGoal(60.0, day("2026-11-01"))) is Rejection.Invalid)
        assertTrue(f.reject(SetWeightGoal(10.0, day("2027-03-01"))) is Rejection.Invalid)
        assertTrue(f.reject(SetWeightGoal(60.0, day("2032-01-01"))) is Rejection.Invalid)
        assertEquals(WeightTarget.Default, f.engine.weightTarget())
    }

    @Test
    fun `le sens de l objectif vient de la derniere pesee - prise si le poids vise est au-dessus`() = runBlocking {
        val f = fixture()
        f.apply(DeclareWeekInfo(day("2026-12-07"), weighIns = listOf(WeighIn(day("2026-12-08"), 64.0))))
        f.apply(SetWeightGoal(66.0, day("2027-05-01")))
        assertEquals(WeightTarget(66.0, day("2027-05-01"), losing = false), f.engine.weightTarget())
        f.apply(SetWeightGoal(60.0, day("2027-05-01")))
        assertTrue(f.engine.weightTarget().losing)
    }

    @Test
    fun `le bilan et la prevision suivent l objectif enregistre, pas une valeur en dur`() = runBlocking {
        val f = fixture()
        f.apply(DeclareWeekInfo(day("2026-12-07"), weighIns = listOf(WeighIn(day("2026-12-08"), 64.0))))
        f.apply(SetWeightGoal(59.0, day("2027-05-01")))
        val figures = f.engine.weeklyFigures(WeekRange(day("2026-12-07")))
        assertEquals(59.0, figures.trajectory.targetKg, 1e-9)
        assertEquals(day("2027-05-01"), figures.trajectory.targetDate)
        assertEquals(WeightTarget(59.0, day("2027-05-01")), figures.forecast.target)
        assertEquals(64.0, figures.forecast.currentKg!!, 1e-9)
    }
}
