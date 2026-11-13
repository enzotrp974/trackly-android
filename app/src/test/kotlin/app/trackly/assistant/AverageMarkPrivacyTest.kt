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
import app.trackly.core.overview.DayOverviews
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Le trait de moyenne de l'accueil est pour les yeux de l'utilisateur seulement : l'assistant ne doit pas s'en servir pour
 * le juger, l'utilisateur le regarde s'il en a envie. Il n'entre donc ni dans le contexte envoyé au modèle, ni dans
 * ses consignes, ni dans ses outils, ni dans leurs résultats.
 *
 * Les valeurs de l'historique sont choisies pour que la moyenne du trait (1 244 kcal, 76,6 / 145,4 / 39,7 g après le midi)
 * ne soit aucun total ni aucune moyenne par moment que le contexte affiche déjà : toute fuite se verrait.
 */
class AverageMarkPrivacyTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine() = SampleData.emptyEngine(SampleData.clock("2026-11-02T13:30")).also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun add(day: LocalDate, moment: Moment, kcal: Double, p: Double, c: Double, f: Double) {
                check(engine.execute(AddConsumptions(NewConsumption(Food("repas", Nutrition(kcal, p, c, f)), moment, explicitDay = day))) is ActionOutcome.Applied)
            }
            for (iso in listOf("2026-10-30", "2026-10-31", "2026-11-01")) {
                add(LocalDate.parse(iso), Moment.Matin, 411.0, 31.7, 63.3, 12.9)
                add(LocalDate.parse(iso), Moment.Midi, 833.0, 44.9, 82.1, 26.8)
            }
            add(monday, Moment.Matin, 300.0, 20.0, 40.0, 5.0)
            add(monday, Moment.Midi, 900.0, 50.0, 100.0, 30.0)
        }
    }

    /** Ce que la vue du jour calcule ici : sans lui, le test ne prouverait rien. */
    @Test
    fun `le trait existe bien pour ces donnees`() = runBlocking {
        val engine = engine()
        val summaries = engine.daySummaries(LocalDate.parse("2026-10-20"), monday)
        val overview = DayOverviews.of(summaries.single { it.day == monday }, summaries)
        assertEquals(1244.0, overview[Bar.Kcal].average!!, 1e-9)
        assertEquals(76.6, overview[Bar.Protein].average!!, 1e-9)
        assertEquals(145.4, overview[Bar.Carbs].average!!, 1e-9)
        assertEquals(39.7, overview[Bar.Fat].average!!, 1e-9)
    }

    @Test
    fun `ni le contexte, ni les consignes, ni les outils ne portent le trait`() = runBlocking {
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
            // Les identifiants (UUID hexadécimaux) peuvent contenir « 1244 » par hasard : on les retire avant de chercher.
            .replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<id>")

        for (leak in listOf("1244", "1 244", "1 244", "76,6", "76.6", "145,4", "145.4", "39,7", "39.7")) {
            assertFalse("« $leak » (valeur du trait de moyenne) ne doit pas sortir de l'app", everything.contains(leak))
        }
        // Ni le mot, ni l'idée d'une comparaison avec ses habitudes à ce stade de la journée.
        for (word in listOf("trait de moyenne", "trait pâle", "à ce stade de la journée", "average_mark")) {
            assertFalse("« $word » ne doit pas sortir de l'app", everything.contains(word, ignoreCase = true))
        }
    }

    /**
     * Garde de fond, indépendante des chiffres : aucun fichier de l'assistant ne connaît la vue du jour. Une fuite arrondie
     * (« 1 240 kcal ») ou par un autre outil ne passerait pas le test précédent, mais elle exigerait cet import.
     */
    @Test
    fun `aucun fichier de l assistant n importe la vue du jour`() {
        val root = java.io.File("src/main/kotlin/app/trackly/assistant")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources de l'assistant introuvables depuis ${java.io.File(".").absolutePath}", files.size > 20)
        val offenders = files.filter { it.readText().contains("core.overview") }.map { it.name }
        assertEquals("ces fichiers de l'assistant lisent la vue du jour : $offenders", emptyList<String>(), offenders)
    }
}
