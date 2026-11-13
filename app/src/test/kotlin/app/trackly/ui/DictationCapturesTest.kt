package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.openai.AssistantError
import app.trackly.dictation.DictationFailure
import app.trackly.dictation.DictationMode
import app.trackly.dictation.DictationPhase
import app.trackly.dictation.DictationUiState
import app.trackly.dictation.PermissionPrompt
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.settings.DictationSettingsSection
import app.trackly.ui.settings.DictationTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Captures de référence de la dictée : écran en direct, bandeau, permission, erreur, réglages. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DictationCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val thread = listOf(
        ChatMessage(1, Instant.EPOCH, ChatRole.User, "Ce matin deux œufs brouillés, du pain complet et un café"),
        ChatMessage(2, Instant.EPOCH, ChatRole.Assistant, "C’est noté pour ton petit-déjeuner."),
    )

    private fun chat(dictation: DictationUiState, messages: List<ChatMessage> = thread) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(
                    dateLabel = "Mardi 3 novembre",
                    state = ChatUiState(messages = messages),
                    onBack = {},
                    dictation = DictationBinding(dictation),
                )
            }
        }
        compose.waitForIdle()
    }

    private val live = DictationUiState(
        phase = DictationPhase.Listening,
        mode = DictationMode.Live,
        text = "Comme snack j’ai pris une barre Nuvo et une pomme, cent cinquante grammes",
        level = 0.18f,
        elapsedMs = 8_000,
    )

    @Test
    fun dicteeEnDirect() {
        chat(live)
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-direct.png")
    }

    @Test
    fun dicteeEnDirectConnexion() {
        chat(live.copy(text = "", connecting = true, elapsedMs = 1_000, level = 0.3f))
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-direct-connexion.png")
    }

    @Test
    fun dicteeApresArret() {
        chat(live.copy(mode = DictationMode.AfterStop, text = "", elapsedMs = 12_000, level = 0.16f))
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-apres-arret.png")
    }

    @Test
    fun dicteeMicroAutorisation() {
        chat(DictationUiState(phase = DictationPhase.Permission, permission = PermissionPrompt.Explain))
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-permission.png")
    }

    @Test
    fun dicteeMicroBloque() {
        chat(DictationUiState(phase = DictationPhase.Permission, permission = PermissionPrompt.Blocked))
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-permission-bloquee.png")
    }

    @Test
    fun dicteeErreurAvecTextePartiel() {
        chat(
            live.copy(
                phase = DictationPhase.Failed,
                failure = DictationFailure.Api(AssistantError.NoInternet),
                canRetry = true,
                level = 0f,
            ),
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/dictee-erreur.png")
    }

    @Test
    fun reglagesDictee() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                Box(Modifier.background(TracklyColors.PaperHi).padding(horizontal = TracklySpacing.Gutter, vertical = 12.dp)) {
                    DictationSettingsSection(DictationMode.Live) {}
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DictationTags.SECTION).captureRoboImage("src/test/screenshots/reglages-dictee.png")
    }
}
