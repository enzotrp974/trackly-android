package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.notice
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.net.FixedConnectivity
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.core.engine.TracklyEngine
import app.trackly.dictation.DictationFailure
import app.trackly.dictation.DictationPhase
import app.trackly.dictation.DictationUiState
import app.trackly.dictation.notice
import app.trackly.testing.SampleData
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.chat.MicTags
import app.trackly.ui.dev.DevTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Saisie manuelle en plan B : proposée dans le chat seulement faute de réseau (bannière Hors ligne,
 * message parti sans connexion, dictée sans connexion), jamais quand tout marche ni pour une erreur de clé.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ManualFallbackTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    private fun failing(error: AssistantError) = ResponsesTransport { throw AssistantException(error) }

    private fun chat(engine: TracklyEngine, assistant: AssistantServices) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    private fun envoyer() {
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("une pomme")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatTags.ERROR)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun `seule l absence de reseau est un cas reseau`() {
        val errors = listOf(
            AssistantError.MissingKey, AssistantError.NoInternet, AssistantError.Timeout, AssistantError.KeyRejected,
            AssistantError.Forbidden, AssistantError.CreditExhausted, AssistantError.SpendLimitReached,
        )
        assertEquals(listOf(AssistantError.NoInternet), errors.filter { it.notice().network })
        assertTrue(DictationFailure.Api(AssistantError.NoInternet).notice().network)
        assertFalse(DictationFailure.Api(AssistantError.KeyRejected).notice().network)
        assertFalse(DictationFailure.NothingHeard.notice().network)
        assertFalse(DictationFailure.MicUnavailable.notice().network)
    }

    @Test
    fun `tout marche - jamais proposee`() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, keyStore = key))
        compose.onNodeWithTag(ChatTags.COMPOSER).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.MANUAL, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Saisir à la main").assertDoesNotExist()
    }

    @Test
    fun `hors ligne - la banniere la propose, elle ouvre le panneau et le retour ramene au chat`() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, keyStore = key, connectivity = FixedConnectivity(false)))
        compose.onNodeWithTag(ChatTags.OFFLINE).assertIsDisplayed()

        compose.onNodeWithTag(ChatTags.MANUAL, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertIsDisplayed()
        // Le chat, caché derrière, sort de l'arbre d'accessibilité.
        compose.onNodeWithContentDescription("Retour au journal").assertDoesNotExist()

        compose.onNodeWithContentDescription("Fermer la saisie manuelle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertDoesNotExist()
        compose.onNodeWithTag(ChatTags.COMPOSER).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.OFFLINE).assertIsDisplayed()
    }

    @Test
    fun `le retour systeme ferme le panneau et ramene au chat`() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, keyStore = key, connectivity = FixedConnectivity(false)))
        compose.onNodeWithTag(ChatTags.MANUAL, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertDoesNotExist()
        compose.onNodeWithTag(ChatTags.COMPOSER).assertIsDisplayed()
    }

    @Test
    fun `message parti sans connexion - proposee sous l erreur`() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, failing(AssistantError.NoInternet), key))
        envoyer()
        compose.onNodeWithText("Pas de connexion Internet").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.MANUAL, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertIsDisplayed()
    }

    @Test
    fun `cle refusee - pas un cas reseau`() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, failing(AssistantError.KeyRejected), key))
        envoyer()
        compose.onNodeWithText("Clé OpenAI refusée").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.MANUAL, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun dictee(failure: DictationFailure, onManual: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(
                    dateLabel = "Lundi 2 novembre",
                    state = ChatUiState(),
                    onBack = {},
                    dictation = DictationBinding(DictationUiState(phase = DictationPhase.Failed, failure = failure, canRetry = true)),
                    onManualEntry = onManual,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `dictee sans connexion - proposee, et appelle le plan B`() {
        var opened = 0
        dictee(DictationFailure.Api(AssistantError.NoInternet)) { opened++ }
        compose.onNodeWithTag(MicTags.MANUAL, useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `dictee sans rien entendu - pas proposee`() {
        dictee(DictationFailure.NothingHeard)
        compose.onNodeWithTag(MicTags.MANUAL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `dictee cle refusee - pas proposee`() {
        dictee(DictationFailure.Api(AssistantError.KeyRejected))
        compose.onNodeWithTag(MicTags.MANUAL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun captureHorsLigne() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, keyStore = key, connectivity = FixedConnectivity(false)))
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-hors-ligne-saisie-manuelle.png")
    }

    @Test
    fun captureErreurReseau() {
        val engine = SampleData.engine()
        chat(engine, AssistantServices.inMemory(engine, failing(AssistantError.NoInternet), key))
        envoyer()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-erreur-reseau-saisie-manuelle.png")
    }
}
