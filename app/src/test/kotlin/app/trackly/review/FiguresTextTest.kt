package app.trackly.review

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.testing.SampleData
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiguresTextTest {
    @Test
    fun `objectif existant mais aucun jour comparable - le texte ne dit pas qu aucun objectif n existait`() = runBlocking {
        // Objectif de 2 350 kcal toute la semaine, mais un seul jour partiel : aucun jour complet à comparer.
        val engine = SampleData.emptyEngine(ReviewSample.clock())
        check(engine.execute(SetGoal(GoalTargets(2350.0, 150.0, 296.0, 62.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        check(engine.execute(AddConsumptions(NewConsumption(Food("Petit-déjeuner seul", Nutrition(600.0, 30.0, 70.0, 20.0)), Moment.Matin, explicitDay = LocalDate.parse("2026-10-31")))) is ActionOutcome.Applied)
        val figures = engine.weeklyFigures(ReviewSample.week)
        val text = FiguresText.render(figures)
        assertFalse(text, text.contains("aucun en vigueur"))
        assertTrue(text, text.contains("Objectif calorique : ${ReportFormat.int(2350.0)} kcal par jour en vigueur sur la période"))
        assertTrue(text, text.contains("aucun jour complet"))
    }

    @Test
    fun `aucun objectif du tout - le texte le dit`() = runBlocking {
        val engine = SampleData.emptyEngine(ReviewSample.clock())
        check(engine.execute(AddConsumptions(NewConsumption(Food("Repas", Nutrition(2000.0, 100.0, 250.0, 60.0)), Moment.Soir, explicitDay = LocalDate.parse("2026-10-27")))) is ActionOutcome.Applied)
        val text = FiguresText.render(engine.weeklyFigures(ReviewSample.week))
        assertTrue(text, text.contains("Objectif calorique : aucun en vigueur sur ces jours."))
    }

    @Test
    fun `objectif et jours complets - ecart habituel`() = runBlocking {
        val text = FiguresText.render(ReviewSample.engine().weeklyFigures(ReviewSample.week))
        assertTrue(text, text.contains("Objectif calorique : ${ReportFormat.int(2600.0)} kcal par jour ; écart moyen"))
    }
}
