package app.trackly.assistant

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.tools.MealIdeasTool
import app.trackly.assistant.tools.ReadDayTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarState
import app.trackly.core.overview.DayOverviews
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/**
 * La couleur d'alerte des barres est pour les yeux de l'utilisateur seulement, comme le trait de moyenne : l'assistant ne
 * doit pas s'en servir pour le juger. Jaune et rouge n'entrent ni dans le contexte envoyé au modèle, ni dans ses
 * consignes, ni dans ses outils, ni dans leurs résultats. (La garde d'import de [AverageMarkPrivacyTest] reste la protection
 * de fond : aucun fichier de l'assistant ne connaît la vue du jour.)
 *
 * Le jour du test est dépassé sur les lipides (80 g sur 70) et sous le minimum de protéines au soir : le rouge existe bien ici.
 */
class BarStatePrivacyTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine() = SampleData.emptyEngine(SampleData.clock("2026-11-02T20:30")).also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0, proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun add(day: LocalDate, moment: Moment, kcal: Double, p: Double, c: Double, f: Double) {
                check(engine.execute(AddConsumptions(NewConsumption(Food("repas", Nutrition(kcal, p, c, f)), moment, explicitDay = day))) is ActionOutcome.Applied)
            }
            for (iso in listOf("2026-10-30", "2026-10-31", "2026-11-01")) {
                add(LocalDate.parse(iso), Moment.Matin, 500.0, 30.0, 70.0, 10.0)
                add(LocalDate.parse(iso), Moment.Soir, 800.0, 50.0, 100.0, 25.0)
            }
            add(monday, Moment.Matin, 900.0, 15.0, 100.0, 50.0)
            add(monday, Moment.Soir, 900.0, 20.0, 100.0, 30.0)
        }
    }

    /** Ce que la vue du jour calcule ici : sans lui, le test ne prouverait rien. */
    @Test
    fun `le rouge existe bien pour ces donnees`() = runBlocking {
        val engine = engine()
        val summaries = engine.daySummaries(LocalDate.parse("2026-10-20"), monday)
        val overview = DayOverviews.of(summaries.single { it.day == monday }, summaries)
        assertEquals(BarState.Bad, overview[Bar.Fat].state)
        assertEquals(BarState.Bad, overview[Bar.Protein].state)
    }

    @Test
    fun `ni le contexte, ni les consignes, ni les outils ne portent l etat des barres`() = runBlocking {
        val engine = engine()
        val now = engine.now()
        val toolContext = ToolContext(engine, now)
        val everything = buildList {
            add(ContextBuilder.default().build(ContextEnv(engine, now)))
            add(SystemPrompt.default().render())
            add(ToolRegistry.default().definitions().joinToString("\n"))
            add(ReadDayTool.execute(buildJsonObject { put("day", "2026-11-02") }, toolContext).output.toString())
            add(MealIdeasTool.execute(buildJsonObject { put("moment", "soir") }, toolContext).output.toString())
        }.joinToString("\n")

        for (word in listOf("limite dépassée", "minimum non atteint", "hors objectif", "overLimit", "over_limit", "BarState", "bar_state", "couleur de la barre")) {
            assertFalse("« $word » ne doit pas sortir de l'app", everything.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `aucun fichier de l assistant ne connait l etat des barres ni la regle du rouge`() {
        val files = java.io.File("src/main/kotlin/app/trackly/assistant").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals(true, files.size > 20)
        val names = listOf("BarState", "DayOverviews", "LimitAlerts", "exceeded(")
        val offenders = files.filter { f -> names.any { f.readText().contains(it) } }.map { it.name }
        assertEquals("ces fichiers de l'assistant touchent à l'état des barres : $offenders", emptyList<String>(), offenders)
    }
}
