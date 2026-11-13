package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.LiveReply
import app.trackly.assistant.turn.TurnStatus
import app.trackly.testing.SampleData
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Attente visible pendant un tour (streaming) : l'étape en cours (« Je cherche sur Internet… »), puis le texte
 * qui arrive sous la carte déjà enregistrée. Captures de référence Roborazzi ; et le chat ouvert par la notification.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class StreamingCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T15:10:00Z")

    private val snack = RecordCard(
        actionId = 12,
        kind = RecordKind.Added,
        items = listOf(
            CardItem("e1", "Barre protéinée Nuvo", "55 g", "snack", "2026-11-02", 222.0, 19.6, 12.0, 7.7),
            CardItem("e2", "Pomme", "150 g", "snack", "2026-11-02", 78.0, 0.4, 19.0, 0.3),
        ),
    )

    private fun shell(state: ChatUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun rechercheInternetEnCours() {
        shell(
            ChatUiState(
                messages = listOf(ChatMessage(1, at, ChatRole.User, "un Big Mac au McDo de la gare")),
                thinking = true,
                live = LiveReply(status = TurnStatus.Searching),
            ),
        )

        compose.onNodeWithTag(ChatTags.THINKING).assertIsDisplayed()
        compose.onNodeWithText("Je cherche sur Internet…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Je cherche sur Internet…").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-recherche-en-cours.png")
    }

    @Test
    fun texteEnCoursSousLaCarte() {
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "comme snack j'ai pris une barre Nuvo et une pomme, 150 g"),
                    ChatMessage(2, at, ChatRole.Assistant, "", listOf(snack)),
                ),
                thinking = true,
                // Texte en cours, avec une citation pas encore terminée : jamais visible.
                live = LiveReply(status = TurnStatus.Thinking, text = "C’est noté pour ton snack. Il te reste 2 300 kcal ([fatsecret", messageId = 2),
            ),
        )

        compose.onNodeWithTag(CardTags.RECORD).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.LIVE_REPLY).assertIsDisplayed()
        compose.onNodeWithText("C’est noté pour ton snack. Il te reste 2 300 kcal").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.THINKING).assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-texte-en-cours.png")
    }

    @Test
    fun enregistrementEnCours() {
        shell(
            ChatUiState(
                messages = listOf(ChatMessage(1, at, ChatRole.User, "comme snack j'ai pris une barre Nuvo")),
                thinking = true,
                live = LiveReply(status = TurnStatus.Recording),
            ),
        )
        compose.onNodeWithText("J’enregistre…").assertIsDisplayed()
    }

    @Test
    fun laNotificationOuvreLeChat() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
        val assistant = AssistantServices.inMemory(engine)
        compose.setContent {
            TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant, openChatRequest = 1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
    }
}
