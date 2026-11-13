package app.trackly.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
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
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.day.DayTags
import app.trackly.ui.day.MealTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Écran du jour (disposition « Barres longues ») : seuls les moments notés ont une ligne dans la carte des repas ;
 * le bilan reste à la même place, à la même taille, quel que soit le nombre de repas. Captures à 0, 1, 2 et 4 repas.
 * Enregistrer : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DayAdaptiveTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    private val repas = listOf(
        Triple(Moment.Matin, "Œufs brouillés, pain complet, café", listOf(520, 32, 48, 20)),
        Triple(Moment.Midi, "Salade sauce A, poulet", listOf(640, 44, 89, 20)),
        Triple(Moment.Snack, "Barre Nuvo, pomme 150 g", listOf(300, 20, 31, 8)),
        Triple(Moment.Soir, "Pâtes complètes, bolognaise, salade verte", listOf(770, 38, 102, 24)),
    )

    /** Objectif fixé et les [n] premiers moments notés aujourd'hui. */
    private fun jour(n: Int): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            for ((moment, label, v) in repas.take(n)) {
                val food = Food(label, Nutrition(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble()))
                check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = monday))) is ActionOutcome.Applied)
            }
        }
    }

    private fun ouvrir(engine: TracklyEngine, assistant: AssistantServices? = null) {
        compose.setContent {
            if (assistant != null) {
                TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant)
            } else {
                TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true))
            }
        }
        compose.waitForIdle()
    }

    private fun kickerLeft(): Float = compose.onNodeWithText("IL TE RESTE").getBoundsInRoot().left.value

    /** Haut de l'en-tête « IL TE RESTE », de la jauge et des macros : le bilan, qui ne doit pas bouger. */
    private fun bilan(): List<Float> = listOf(
        compose.onNodeWithText("IL TE RESTE").getBoundsInRoot().top.value,
        compose.onNodeWithText("consommées", substring = true).getBoundsInRoot().top.value,
        compose.onNodeWithTag(DayTags.MACROS).getBoundsInRoot().top.value,
        // Haut de la barre des lipides : « +N g », dessous, peut apparaître et pousser la carte des repas, pas le bilan.
        compose.onNodeWithTag(DayTags.macro("Fat")).getBoundsInRoot().top.value,
    )

    private fun ajouter(engine: TracklyEngine, n: Int) = runBlocking {
        for ((moment, label, v) in repas.take(n)) {
            val food = Food(label, Nutrition(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble()))
            check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = monday))) is ActionOutcome.Applied)
        }
    }

    /** Le corps du jour ne défile pas : tout tient. */
    private fun assertSansDefilement() {
        val scroll = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(hasTestTag(DayTags.BODY)),
        ).fetchSemanticsNode()
        assertEquals(0f, scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 0.5f)
    }

    @Test
    fun `le bilan reste a la meme place de zero a quatre repas`() {
        val engine = jour(0)
        ouvrir(engine)
        val vide = bilan()
        ajouter(engine, 4)
        compose.waitForIdle()
        for (moment in Moment.entries) compose.onNodeWithTag(DayTags.row(moment)).assertIsDisplayed()
        val plein = bilan()
        for (i in vide.indices) assertEquals("bilan déplacé : $vide → $plein", vide[i], plein[i], 0.5f)
        assertEquals(26f, kickerLeft(), 1f)
    }

    @Test
    fun `rien mange - la carte dit pas encore de repas, bilan a gauche et sans defilement`() {
        ouvrir(jour(0))
        compose.onNodeWithTag(DayTags.MEALS).assertIsDisplayed()
        compose.onNodeWithText("Pas encore de repas aujourd’hui").assertIsDisplayed()
        for (moment in Moment.entries) compose.onNodeWithTag(DayTags.row(moment)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Protéines : 0 sur 140 grammes").assertIsDisplayed()
        assertEquals(26f, kickerLeft(), 1f)
        assertSansDefilement()
    }

    /** Taille d'écran de référence : sans défiler, la carte de quatre repas finit au-dessus du champ de la barre du bas. */
    @Config(qualifiers = "w412dp-h868dp-xxhdpi")
    @Test
    fun `une ligne de repas fait 50 dp et la carte reste au-dessus de la barre du bas`() {
        ouvrir(jour(4))
        val ligne = compose.onNodeWithTag(DayTags.row(Moment.Midi)).getBoundsInRoot()
        assertEquals(50f, (ligne.bottom - ligne.top).value, 1f)
        val carte = compose.onNodeWithTag(DayTags.MEALS).getBoundsInRoot()
        val barre = compose.onNodeWithTag(DayTags.FIELD).getBoundsInRoot()
        assertTrue("carte ${carte.bottom} sous le champ ${barre.top}", carte.bottom <= barre.top)
    }

    @Test
    fun `un repas - une seule ligne, le bilan remonte a gauche`() {
        ouvrir(jour(1))
        compose.onNodeWithTag(DayTags.row(Moment.Matin)).assertIsDisplayed()
        for (moment in listOf(Moment.Midi, Moment.Snack, Moment.Soir)) compose.onNodeWithTag(DayTags.row(moment)).assertDoesNotExist()
        assertEquals(26f, kickerLeft(), 1f)
        assertSansDefilement()
    }

    /** Taille d'écran de référence (412 × 868 dp) : quatre repas y tiennent au-dessus de la barre du bas. */
    @Config(qualifiers = "w412dp-h868dp-xxhdpi")
    @Test
    fun `quatre repas - quatre lignes sans defilement`() {
        ouvrir(jour(4))
        for (moment in Moment.entries) compose.onNodeWithTag(DayTags.row(moment)).assertIsDisplayed()
        assertSansDefilement()
    }

    @Test
    fun `dernier repas supprime - la carte revient a sa phrase`() {
        ouvrir(jour(1))
        compose.onNodeWithText("Matin").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MealTags.DELETE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DayTags.row(Moment.Matin)).assertDoesNotExist()
        compose.onNodeWithText("Pas encore de repas aujourd’hui").assertIsDisplayed()
        assertEquals(26f, kickerLeft(), 1f)
    }

    @Test
    fun `une ligne garde l ajout a son moment - action TalkBack et plus de la feuille`() {
        ouvrir(jour(2))
        compose.onNodeWithTag(DayTags.row(Moment.Midi)).assert(
            SemanticsMatcher("action « Ajouter au midi »") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Ajouter au midi" } == true
            },
        )
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()
        val plus = compose.onNodeWithTag(MealTags.ADD).getBoundsInRoot()
        assertTrue((plus.right - plus.left).value >= 47.9f && (plus.bottom - plus.top).value >= 47.9f)
    }

    @Test
    fun `jour passe - le plus de la feuille preremplit ce jour et ce moment`() {
        val engine = SampleData.engine()
        ouvrir(engine, AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ajouter au midi").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INPUT).assertTextEquals("Pour dimanche 1er novembre, à midi : ")
    }

    @Test
    fun `avec animations - le premier repas arrive dans la carte sans deplacer le bilan`() {
        val engine = jour(0)
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = false)) }
        compose.waitForIdle()
        assertEquals(26f, kickerLeft(), 1f)
        val avant = bilan()

        runBlocking {
            val food = Food("Salade sauce A, poulet", Nutrition(640.0, 44.0, 89.0, 20.0))
            check(engine.execute(AddConsumptions(NewConsumption(food, Moment.Midi, explicitDay = monday))) is ActionOutcome.Applied)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DayTags.row(Moment.Midi)).assertIsDisplayed()
        assertEquals(26f, kickerLeft(), 1f)
        val apres = bilan()
        for (i in avant.indices) assertEquals("bilan déplacé : $avant → $apres", avant[i], apres[i], 0.5f)
    }

    // ───── Captures ─────

    @Test
    fun captureZeroRepas() {
        ouvrir(jour(0))
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-0-repas.png")
    }

    @Test
    fun captureUnRepas() {
        ouvrir(jour(1))
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-1-repas.png")
    }

    @Test
    fun captureDeuxRepas() {
        ouvrir(jour(2))
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-2-repas.png")
    }

    @Test
    fun captureQuatreRepas() {
        ouvrir(jour(4))
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-4-repas.png")
    }

    @Test
    fun captureZeroRepasPolice200() {
        compose.setContent { AvecPolice(2f) { TracklyRoot(engine = jour(0), motion = MotionPolicy(reduced = true)) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-0-repas-police-200.png")
    }
}
