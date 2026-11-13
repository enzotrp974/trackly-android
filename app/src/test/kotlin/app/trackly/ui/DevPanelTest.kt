package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.SampleData
import app.trackly.ui.dev.DevTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Saisie manuelle : les actions du moteur (ajouter, corriger, supprimer, annuler,
 * objectif) se voient aussitôt sur l'écran du jour.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DevPanelTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    private fun ouvrir(engine: TracklyEngine) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
    }

    private fun ouvrirPanneau() {
        compose.onNodeWithText("Trackly").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertIsDisplayed()
    }

    private fun fermerPanneau() {
        compose.onNodeWithContentDescription("Fermer la saisie manuelle").performClick()
        compose.waitForIdle()
    }

    private fun clic(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `aucune entree visible sur l ecran du jour, l appui long sur Trackly reste un raccourci cache`() {
        ouvrir(SampleData.engine())
        compose.onNodeWithText("Saisie manuelle").assertDoesNotExist()
        compose.onNodeWithText("hors ligne", substring = true, ignoreCase = true).assertDoesNotExist()

        ouvrirPanneau()

        compose.onNodeWithText("Saisie sans assistant").assertIsDisplayed()
        compose.onNodeWithText("hors ligne", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("sans crédit", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Panneau de développement").assertDoesNotExist()
        compose.onNodeWithText("en attendant l’assistant", substring = true).assertDoesNotExist()
    }

    @Test
    fun `ajouter puis annuler, visible sur l ecran du jour`() {
        val engine = SampleData.emptyEngine()
        ouvrir(engine)

        ouvrirPanneau()
        compose.onNodeWithTag(DevTags.LABEL).performTextInput("Pomme")
        compose.onNodeWithTag(DevTags.KCAL).performTextInput("80")
        compose.onNodeWithTag(DevTags.CARBS).performTextInput("21")
        clic("Snack")
        clic("Ajouter")
        compose.onNodeWithContentDescription("Fait — Ajout : Pomme (80 kcal)").assertIsDisplayed()
        fermerPanneau()

        compose.onNodeWithText("Pomme").assertIsDisplayed()
        // Total du jour et total du snack.
        assertEquals(2, compose.onAllNodesWithContentDescription("80").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Glucides : 21 grammes").assertIsDisplayed()

        ouvrirPanneau()
        clic("Annuler la dernière action")
        fermerPanneau()

        compose.onNodeWithText("Pomme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Glucides : 0 grammes").assertIsDisplayed()
        assertEquals(0.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.0)
    }

    @Test
    fun `une annulation se retablit avec Retablir, pas avec Annuler`() {
        val engine = SampleData.emptyEngine()
        ouvrir(engine)
        ouvrirPanneau()
        compose.onNodeWithTag(DevTags.LABEL).performTextInput("Pomme")
        compose.onNodeWithTag(DevTags.KCAL).performTextInput("80")
        clic("Ajouter")
        clic("Annuler la dernière action")
        compose.onNodeWithText("Rétablir").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rétablir").performClick()
        compose.waitForIdle()
        assertEquals(80.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.0)
    }

    @Test
    fun `corriger puis supprimer depuis le panneau`() {
        val engine = SampleData.engine()
        ouvrir(engine)

        ouvrirPanneau()
        compose.onNodeWithContentDescription("Corriger Salade sauce A, poulet").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.KCAL).performTextReplacement("700")
        clic("Enregistrer la correction")
        compose.onNodeWithContentDescription("Supprimer Barre Nuvo, pomme 150 g").performScrollTo().performClick()
        compose.waitForIdle()
        fermerPanneau()

        // 520 + 700 = 1 220 ; il reste 1 380.
        compose.onNodeWithContentDescription("1 380").assertIsDisplayed()
        compose.onNodeWithText("Barre Nuvo, pomme 150 g").assertDoesNotExist()
        val history = runBlocking {
            val salad = engine.daySummary(monday).consumptions.first { it.food.label.startsWith("Salade") }
            engine.history(salad.entryId)
        }
        assertEquals(listOf(640.0, 700.0), history.map { it.food.nutrition.kcal })
    }

    @Test
    fun `fixer un objectif depuis le panneau`() {
        ouvrir(SampleData.emptyEngine())

        ouvrirPanneau()
        compose.onNodeWithTag(DevTags.GOAL_KCAL).performScrollTo().performTextInput("2300")
        compose.onNodeWithTag(DevTags.GOAL_PROTEIN).performTextInput("130")
        clic("Fixer l’objectif")
        fermerPanneau()

        compose.onNodeWithText("IL TE RESTE").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 300").assertIsDisplayed()
        compose.onNodeWithContentDescription("Protéines : 0 sur 130 grammes").assertIsDisplayed()
        compose.onNodeWithText("SANS OBJECTIF").assertDoesNotExist()
    }

    @Test
    fun `une saisie invalide n atteint pas le moteur`() {
        val engine = SampleData.emptyEngine()
        ouvrir(engine)
        ouvrirPanneau()
        compose.onNodeWithTag(DevTags.LABEL).performTextInput("Pomme")
        clic("Ajouter")
        compose.onNodeWithContentDescription("Saisie refusée : Indique les kcal").assertIsDisplayed()
        assertEquals(0, runBlocking { engine.daySummary(monday).consumptions.size })
    }
}
