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
import app.trackly.testing.SampleData
import app.trackly.ui.day.AverageMarkKey
import app.trackly.ui.day.DayTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Trait pâle de la moyenne, vu de l'écran du jour. Le trait est un dessin : les tests lisent sa position dans la
 * propriété personnalisée [AverageMarkKey] (muette pour TalkBack), pas dans une description lue à voix haute.
 * Objectif 2 300 kcal, 135 / 280 / 70 g ; lundi 2 novembre 2026.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h868dp-xxhdpi")
class AverageMarkTest {
    @get:Rule val compose = createComposeRule()

    /** [habitude] jours d'historique (du 1er novembre vers l'arrière) avec petit-déjeuner 500 kcal / 30 / 60 / 10 g ; puis aujourd'hui. */
    private fun engine(habitude: Int, aujourdhui: List<Moment>): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun ajouter(day: String, moment: Moment, kcal: Double, p: Double, c: Double, f: Double) {
                val food = Food("repas", Nutrition(kcal, p, c, f))
                check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = LocalDate.parse(day)))) is ActionOutcome.Applied)
            }
            for (back in 1..habitude) ajouter(LocalDate.parse("2026-11-02").minusDays(back.toLong()).toString(), Moment.Matin, 500.0, 30.0, 60.0, 10.0)
            for (moment in aujourdhui) ajouter("2026-11-02", moment, 300.0, 20.0, 40.0, 5.0)
        }
    }

    private fun ouvrir(engine: TracklyEngine) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
    }

    private fun hasMark() = SemanticsMatcher.keyIsDefined(AverageMarkKey)

    private fun marks() = compose.onAllNodes(hasMark()).fetchSemanticsNodes().map { it.config[AverageMarkKey] }

    private fun markOf(tag: String): Float? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(AverageMarkKey)

    @Test
    fun `apres le petit dejeuner - chaque barre a son trait`() {
        ouvrir(engine(habitude = 3, aujourdhui = listOf(Moment.Matin)))
        // Protéines 30 / 135 ; glucides 60 / 280 ; lipides 10 / 70 : chaque trait sur sa propre barre.
        assertEquals(30f / 135f, markOf(DayTags.macro("Protein"))!!, 1e-4f)
        assertEquals(60f / 280f, markOf(DayTags.macro("Carbs"))!!, 1e-4f)
        assertEquals(10f / 70f, markOf(DayTags.macro("Fat"))!!, 1e-4f)
        // Jauge : 500 / 2 300, le seul trait hors des macros.
        val others = marks().filter { m -> listOf(30f / 135f, 60f / 280f, 10f / 70f).none { kotlin.math.abs(it - m) < 1e-4f } }
        assertEquals(listOf(500f / 2300f), others)
        assertEquals(4, marks().size)
    }

    @Test
    fun `journee vide - aucun trait, meme avec de l historique`() {
        ouvrir(engine(habitude = 5, aujourdhui = emptyList()))
        assertEquals(emptyList<Float>(), marks())
    }

    @Test
    fun `moins de 3 jours d historique - aucun trait`() {
        ouvrir(engine(habitude = 2, aujourdhui = listOf(Moment.Matin)))
        assertEquals(emptyList<Float>(), marks())
    }

    @Test
    fun `un jour passe n a pas de trait`() {
        ouvrir(engine(habitude = 4, aujourdhui = listOf(Moment.Matin)))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Float>(), marks())
    }

    @Test
    fun `le trait n est pas annonce - la description de la barre ne le dit pas`() {
        ouvrir(engine(habitude = 3, aujourdhui = listOf(Moment.Matin)))
        val described = compose.onNodeWithTag(DayTags.macro("Protein")).fetchSemanticsNode().config
        assertTrue(described.contains(AverageMarkKey))
        // La phrase de la barre est celle d'avant (le chiffre de l'odomètre s'y ajoute à la fusion) : rien sur le trait.
        val spoken = described[SemanticsProperties.ContentDescription]
        assertEquals("Protéines : 20 sur 135 grammes", spoken.first())
        assertTrue(spoken.toString(), spoken.none { it.contains("moyenne", ignoreCase = true) || it.contains("habitu", ignoreCase = true) })
        assertNull(described.getOrNull(SemanticsProperties.StateDescription))
    }
}
