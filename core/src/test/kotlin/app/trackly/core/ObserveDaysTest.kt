package app.trackly.core

import app.trackly.core.action.SetGoal
import app.trackly.core.action.UndoLast
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lecture continue des bilans, telle que l'écran du jour la consomme. */
class ObserveDaysTest {
    private val f = Fixture()

    @Test
    fun `un bilan par jour de la periode, recalcule apres chaque action`() = runTest {
        f.apply(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = day("2026-11-02")))
        f.add("Pancakes", 610.0, Moment.Matin, explicitDay = day("2026-11-01"))
        f.add("Salade", 640.0)

        val days = f.engine.observeDays(day("2026-11-01"), day("2026-11-02")).first()
        assertEquals(listOf(day("2026-11-01"), day("2026-11-02")), days.map { it.day })
        assertEquals(610.0, days[0].totals.kcal, 0.0)
        assertNull(days[0].goal)
        assertEquals(1960.0, days[1].remainingKcal!!, 0.0)

        f.apply(UndoLast)
        val after = f.engine.observeDays(day("2026-11-01"), day("2026-11-02")).first()
        assertEquals(0.0, after[1].totals.kcal, 0.0)
        assertEquals(day("2026-11-01"), f.engine.observeFirstDay().first())
    }

    @Test
    fun `le journal des actions liste la plus recente d abord`() = runTest {
        f.add("Riz", 400.0)
        f.add("Pomme", 80.0)
        f.apply(UndoLast)
        val titles = f.engine.observeRecentActions(10).first().map { it.title }
        assertEquals(listOf("Annulation : Ajout : Pomme (80 kcal)", "Ajout : Pomme (80 kcal)", "Ajout : Riz (400 kcal)"), titles)
    }
}
