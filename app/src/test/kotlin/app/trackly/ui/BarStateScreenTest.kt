package app.trackly.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.overview.BarState
import app.trackly.testing.SampleData
import app.trackly.ui.day.BarStateKey
import app.trackly.ui.day.DayTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Couleur d'alerte des barres de l'accueil, vue de l'écran du jour. La couleur est un dessin : les tests lisent
 * l'état dans la propriété [BarStateKey] et la phrase que TalkBack lit. Objectif 2 300 kcal, 135 g de protéines (minimum
 * 112 g), 280 g de glucides, limite de 70 g de lipides ; lundi 2 novembre 2026.
 *
 * Jour habituel : matin 500 kcal / 30 / 70 / 10, midi 700 / 40 / 80 / 20, snack 200 / 5 / 30 / 5, soir 800 / 50 / 100 / 25.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h868dp-xxhdpi")
class BarStateScreenTest {
    @get:Rule val compose = createComposeRule()

    private data class Meal(val moment: Moment, val kcal: Double, val p: Double, val c: Double, val f: Double)

    private val usual = listOf(
        Meal(Moment.Matin, 500.0, 30.0, 70.0, 10.0),
        Meal(Moment.Midi, 700.0, 40.0, 80.0, 20.0),
        Meal(Moment.Snack, 200.0, 5.0, 30.0, 5.0),
        Meal(Moment.Soir, 800.0, 50.0, 100.0, 25.0),
    )

    /** Trois jours habituels (30 et 31 octobre, 1er novembre), puis les repas de [today] le 2 novembre et de [yesterday] le 1er. */
    private fun engine(today: List<Meal>, yesterday: List<Meal> = usual): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0, proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun add(day: String, m: Meal) {
                val food = Food("repas", Nutrition(m.kcal, m.p, m.c, m.f))
                check(engine.execute(AddConsumptions(NewConsumption(food, m.moment, explicitDay = LocalDate.parse(day)))) is ActionOutcome.Applied)
            }
            for (day in listOf("2026-10-30", "2026-10-31")) usual.forEach { add(day, it) }
            yesterday.forEach { add("2026-11-01", it) }
            today.forEach { add("2026-11-02", it) }
        }
    }

    private fun open(engine: TracklyEngine) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
    }

    private fun stateOf(tag: String): BarState? = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(BarStateKey)

    private fun spoken(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    /** Les nœuds qui portent un état, gauche des macros comprise. */
    private fun statesOnScreen() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(BarStateKey)).fetchSemanticsNodes().map { it.config[BarStateKey] }

    private val morning = Meal(Moment.Matin, 500.0, 30.0, 70.0, 10.0)
    private val lunch = Meal(Moment.Midi, 700.0, 40.0, 80.0, 20.0)

    @Test
    fun `la tablette de chocolat fait depasser les lipides - la barre est rouge et dit la limite depassee`() {
        // Snack : 540 kcal et 45 g de lipides, soit 75 g sur 70.
        open(engine(listOf(morning, lunch, Meal(Moment.Snack, 540.0, 6.0, 60.0, 45.0))))
        assertEquals(BarState.Bad, stateOf(DayTags.macro("Fat")))
        val phrase = spoken(DayTags.macro("Fat"))
        assertEquals("Lipides : 75 sur 70 grammes, 5 de plus, limite dépassée", phrase)
    }

    @Test
    fun `des lipides qui depasseront avec le soir habituel - la barre est jaune, et le texte reste celui d avant`() {
        open(engine(listOf(morning, lunch, Meal(Moment.Snack, 540.0, 6.0, 60.0, 35.0))))
        assertEquals(BarState.Warn, stateOf(DayTags.macro("Fat")))
        assertEquals("Lipides : 65 sur 70 grammes, attention", spoken(DayTags.macro("Fat")))
    }

    @Test
    fun `les glucides ne changent jamais de couleur, meme tres au-dessus de la cible`() {
        open(engine(listOf(morning, lunch, Meal(Moment.Snack, 540.0, 6.0, 400.0, 5.0))))
        assertNull(stateOf(DayTags.macro("Carbs")))
        assertEquals("Glucides : 550 sur 280 grammes, 270 de plus", spoken(DayTags.macro("Carbs")))
    }

    @Test
    fun `une barre normale se lit comme avant - aucun etat, aucun mot de plus`() {
        open(engine(listOf(morning)))
        assertTrue(statesOnScreen().isEmpty())
        assertEquals("Protéines : 30 sur 135 grammes", spoken(DayTags.macro("Protein")))
    }

    @Test
    fun `journee vide - aucune couleur`() {
        open(engine(emptyList()))
        assertTrue(statesOnScreen().isEmpty())
    }

    @Test
    fun `le soir est note sous le minimum - la barre des proteines est rouge, sans montrer le minimum`() {
        val evening = Meal(Moment.Soir, 600.0, 20.0, 60.0, 15.0)
        open(engine(listOf(morning, evening)))
        assertEquals(BarState.Bad, stateOf(DayTags.macro("Protein")))
        val phrase = spoken(DayTags.macro("Protein"))
        assertEquals("Protéines : 50 sur 135 grammes, minimum non atteint", phrase)
        // L'écran ne donne jamais la valeur du minimum : c'est l'idéal (135 g) qui est la cible affichée.
        assertFalse(phrase.contains("112"))
    }

    @Test
    fun `des proteines sous le minimum avant le soir - jaune seulement si les repas habituels ne suffisent pas`() {
        open(engine(listOf(Meal(Moment.Matin, 300.0, 5.0, 40.0, 5.0))))
        assertEquals(BarState.Warn, stateOf(DayTags.macro("Protein")))
        assertEquals("Protéines : 5 sur 135 grammes, attention", spoken(DayTags.macro("Protein")))
    }

    @Test
    fun `la jauge des calories dit son etat quand il y en a un`() {
        open(engine(listOf(Meal(Moment.Matin, 1200.0, 30.0, 70.0, 10.0), Meal(Moment.Midi, 1300.0, 40.0, 80.0, 20.0))))
        // Dite d'une traite, chiffre compris, comme les barres de macros.
        compose.onNodeWithContentDescription("Calories : 2500 consommées sur 2300, limite dépassée").assertExists()
        val gauge = compose.onAllNodes(SemanticsMatcher.keyIsDefined(BarStateKey)).fetchSemanticsNodes()
        assertTrue(gauge.any { it.config[BarStateKey] == BarState.Bad })
    }

    @Test
    fun `la jauge normale ne dit rien de plus`() {
        open(engine(listOf(morning)))
        assertTrue(statesOnScreen().isEmpty())
        compose.onNodeWithContentDescription("Calories : 500 consommées sur 2300, attention").assertDoesNotExist()
    }

    @Test
    fun `hier - pas de jaune de prevision, le rouge seulement si la limite l est`() {
        val warnYesterday = listOf(morning, lunch, Meal(Moment.Snack, 540.0, 6.0, 60.0, 35.0))
        open(engine(today = emptyList(), yesterday = warnYesterday))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        assertTrue("un jour passé n'a pas de jaune : ${statesOnScreen()}", statesOnScreen().isEmpty())
    }

    @Test
    fun `hier - une limite depassee reste rouge`() {
        val overYesterday = listOf(morning, lunch, Meal(Moment.Snack, 540.0, 6.0, 60.0, 45.0))
        open(engine(today = emptyList(), yesterday = overYesterday))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        assertEquals(BarState.Bad, stateOf(DayTags.macro("Fat")))
    }
}
