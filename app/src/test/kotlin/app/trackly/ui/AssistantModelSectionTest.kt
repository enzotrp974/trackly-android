package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.model.InMemoryAssistantModelSettings
import app.trackly.assistant.openai.AssistantModel
import app.trackly.ui.settings.AssistantModelSettingsRoute
import app.trackly.ui.settings.AssistantModelTags
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Réglages › Modèle de l'assistant : Sol par défaut, Terra au toucher, réglage retenu. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class AssistantModelSectionTest {
    @get:Rule val compose = createComposeRule()

    private val settings = InMemoryAssistantModelSettings()

    private fun open() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                SettingsScreen(
                    InMemoryApiKeyStore(),
                    onClose = {},
                    groups = SettingsGroups(model = { AssistantModelSettingsRoute(settings) }),
                    startPage = SettingsPage.Model,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun isSelected(model: AssistantModel) =
        compose.onNodeWithTag(AssistantModelTags.option(model)).fetchSemanticsNode().config[SemanticsProperties.Selected]

    @Test
    fun `GPT-6-1 Sol est choisi par defaut`() {
        open()
        compose.onNodeWithText("GPT-6.1 Sol (par défaut)").assertIsDisplayed()
        compose.onNodeWithText("GPT-6.1 Terra").assertIsDisplayed()
        assertEquals(true, isSelected(AssistantModel.Sol))
        assertEquals(false, isSelected(AssistantModel.Terra))
        compose.onNodeWithText("2 $ en entrée, 10 $ en sortie, par million de jetons").assertIsDisplayed()
        compose.onNodeWithText("1 $ en entrée, 5 $ en sortie, par million de jetons").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-modele.png")
    }

    @Test
    fun `toucher Terra change le reglage, toucher Sol le remet`() {
        open()
        compose.onNodeWithTag(AssistantModelTags.option(AssistantModel.Terra)).performClick()
        compose.waitForIdle()
        assertEquals(AssistantModel.Terra, settings.model.value)
        assertEquals("gpt-6.1-terra", settings.currentCode())
        assertEquals(true, isSelected(AssistantModel.Terra))

        compose.onNodeWithTag(AssistantModelTags.option(AssistantModel.Sol)).performClick()
        compose.waitForIdle()
        assertEquals("gpt-6.1-sol", settings.currentCode())
    }
}
