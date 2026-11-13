package app.trackly.core

import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.UndoLast
import app.trackly.core.model.GoalTargets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Objectifs datés : le bilan d'un jour utilise l'objectif en vigueur ce jour-là. */
class GoalTest {
    private val f = Fixture()
    private val base = GoalTargets(kcal = 2600.0, proteinG = 140.0, carbsG = 352.0, fatG = 70.0)
    private val higher = GoalTargets(kcal = 2800.0, proteinG = 150.0, carbsG = 380.0, fatG = 75.0)

    @Test
    fun `sans objectif, le bilan n invente pas de calories restantes`() = runTest {
        f.add("Pomme", 80.0)
        val s = f.summary()
        assertNull(s.goal)
        assertNull(s.remainingKcal)
    }

    @Test
    fun `chaque jour garde l objectif en vigueur ce jour-la`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(higher, from = day("2026-11-02")))

        assertNull(f.summary("2026-09-30").goal)
        assertEquals(base, f.summary("2026-10-01").goal)
        assertEquals(base, f.summary("2026-11-01").goal)
        assertEquals(higher, f.summary("2026-11-02").goal)
        assertEquals(higher, f.summary("2026-12-25").goal)
    }

    @Test
    fun `par defaut l objectif part du jour de suivi en cours, meme avant 5 h`() = runTest {
        f.clock.setLocal("2026-11-03T01:00")
        val result = f.apply(SetGoal(base))
        assertEquals(day("2026-11-02"), result.goalChanges.single().after.effectiveFrom)
        assertEquals(base, f.summary("2026-11-02").goal)
        assertNull(f.summary("2026-11-01").goal)
    }

    @Test
    fun `calories restantes = objectif moins consommations actives`() = runTest {
        f.apply(SetGoal(base))
        f.add("Riz", 1460.0)
        assertEquals(1140.0, f.summary().remainingKcal!!, 0.0)
        f.add("Raclette", 1290.0)
        assertEquals(-150.0, f.summary().remainingKcal!!, 0.0)
    }

    @Test
    fun `changer l objectif du meme jour cree une version, annulable`() = runTest {
        f.apply(SetGoal(base))
        val change = f.apply(SetGoal(higher)).goalChanges.single()
        assertEquals(2, change.after.version)
        assertEquals(higher, f.summary().goal)

        f.apply(UndoLast)
        assertEquals(base, f.summary().goal)
        val goals = f.store.observeGoals().first()
        assertEquals(3, goals.single().version)
    }

    @Test
    fun `annuler le premier objectif revient a aucun objectif`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(higher))
        f.apply(UndoLast)
        assertEquals(base, f.summary().goal)
        f.apply(UndoLast)
        assertNull(f.summary().goal)
    }

    @Test
    fun `un objectif identique est refuse`() = runTest {
        f.apply(SetGoal(base))
        assertEquals(Rejection.NoChange, f.reject(SetGoal(base)))
    }

    @Test
    fun `un objectif invalide est refuse`() {
        val error = runCatching { GoalTargets(kcal = 0.0) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, error!!::class)
    }
}
