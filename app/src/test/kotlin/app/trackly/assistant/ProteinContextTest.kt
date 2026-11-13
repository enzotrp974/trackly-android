package app.trackly.assistant

import app.trackly.assistant.context.TrackingDayContext
import app.trackly.core.model.GoalTargets
import app.trackly.core.summary.DayEnergy
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.MacroTotal
import app.trackly.core.summary.Totals
import app.trackly.assistant.tools.MealIdeasTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Ligne « Protéines » du contexte : l'écart au minimum et à l'idéal, calculé par l'app, jamais par le modèle. */
class ProteinContextTest {
    private val day = LocalDate.parse("2026-11-03")
    private val goal = GoalTargets(2600.0, 140.0, 352.0, 70.0, proteinMinG = 112.0)

    private fun summary(protein: Double, complete: Boolean = true, goal: GoalTargets? = this.goal): DaySummary {
        val none = MacroTotal(0.0, true)
        val totals = Totals(1000.0, MacroTotal(protein, complete), none, none, none)
        return DaySummary(day, goal, emptyList(), totals, DayEnergy.detailed(totals))
    }

    @Test
    fun `sous le minimum - les deux ecarts`() = assertEquals(
        "Protéines : 45 g mangés ; il manque 67 g pour le minimum (112 g, plancher) et 95 g pour l'idéal (140 g, objectif).",
        TrackingDayContext.proteinLine(summary(45.0)),
    )

    @Test
    fun `minimum atteint - seulement l ideal`() = assertEquals(
        "Protéines : 120 g mangés ; minimum atteint ; il manque 20 g pour l'idéal (140 g, objectif).",
        TrackingDayContext.proteinLine(summary(120.0)),
    )

    @Test
    fun `ideal atteint`() = assertEquals("Protéines : 150 g mangés ; idéal atteint (140 g).", TrackingDayContext.proteinLine(summary(150.0)))

    @Test
    fun `macro inconnue sur un aliment - au moins mange, au plus manquant`() = assertEquals(
        "Protéines : au moins 45 g mangés ; il manque au plus 67 g pour le minimum (112 g, plancher) et 95 g pour l'idéal (140 g, objectif).",
        TrackingDayContext.proteinLine(summary(45.0, complete = false)),
    )

    @Test
    fun `sans minimum enregistre - seulement l ideal`() = assertEquals(
        "Protéines : 45 g mangés ; il manque 95 g pour l'idéal (140 g, objectif).",
        TrackingDayContext.proteinLine(summary(45.0, goal = goal.copy(proteinMinG = null))),
    )

    @Test
    fun `sans objectif ni ideal - rien`() {
        assertNull(TrackingDayContext.proteinLine(summary(45.0, goal = null)))
        assertNull(TrackingDayContext.proteinLine(summary(45.0, goal = GoalTargets(2600.0))))
    }

    @Test
    fun `get_meal_ideas donne aussi l ecart au minimum`() = runBlocking {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-03T19:00"))
        engine.execute(SetGoal(goal, from = day)) as ActionOutcome.Applied
        val remaining = MealIdeasTool.execute(buildJsonObject { put("moment", "soir") }, ToolContext(engine, engine.now())).output["remaining"]!!.jsonObject
        assertEquals(112.0, remaining["protein_to_minimum_g"]!!.jsonPrimitive.double, 0.0)
        assertEquals(140.0, remaining["protein_g"]!!.jsonPrimitive.double, 0.0)
    }
}
