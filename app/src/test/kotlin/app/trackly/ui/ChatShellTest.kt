package app.trackly.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import androidx.compose.ui.test.onNodeWithText
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** Défaut du prototype : la bulle du chat passait sous l'en-tête. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatShellTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `aucune bulle ne passe sous l en-tete quand le fil defile`() {
        val messages = List(14) {
            ChatMessage(it + 1L, Instant.EPOCH, ChatRole.User, "Comme snack j’ai pris une barre Nuvo et une pomme, 150 g (message ${it + 1})")
        }
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = ChatUiState(messages = messages), onBack = {})
            }
        }
        val headerBottom = compose.onNodeWithTag(ChatTags.HEADER).fetchSemanticsNode().boundsInRoot.bottom
        for (index in listOf(3, 7, messages.lastIndex)) {
            compose.onNodeWithTag(ChatTags.THREAD).performScrollToIndex(index)
            compose.waitForIdle()
            val bubbles = compose.onAllNodesWithTag(ChatTags.BUBBLE).fetchSemanticsNodes()
            assertTrue(bubbles.isNotEmpty())
            for (bubble in bubbles) {
                val visible = bubble.boundsInRoot
                if (visible.height <= 0f) continue
                assertTrue(
                    "bulle visible à ${visible.top}, sous l’en-tête qui finit à $headerBottom",
                    visible.top >= headerBottom - 0.5f,
                )
            }
        }
    }

    @Test
    fun `la carte accorde l unite au pluriel, meme celle d une carte deja enregistree`() {
        val card = RecordCard(
            actionId = 5, kind = RecordKind.Added,
            items = listOf(
                CardItem("e1", "Œufs brouillés", "2 pièce", "matin", "2026-11-02", 188.0, 16.0, 1.0, 14.0),
                CardItem("e2", "Tartine", "1 pièce", "matin", "2026-11-02", 100.0, 3.0, 18.0, 1.0),
            ),
        )
        val messages = listOf(ChatMessage(1, Instant.EPOCH, ChatRole.Assistant, "C’est noté.", listOf(card)))
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = ChatUiState(messages = messages), onBack = {})
            }
        }
        compose.onNodeWithText("2 pièces", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("1 pièce", substring = true).fetchSemanticsNode()
    }
}
