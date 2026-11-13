package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * CHAT-06 : un tableau très long, gardé dans l'historique, ne fait jamais planter le chat à sa réouverture. Plus de
 * 262 143 px de haut (la borne d'un `Constraints` étroit) : la réponse s'affiche, en tableau propre, sans `|`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class MarkdownLongTableTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T17:40:00Z")

    private val longTable = buildString {
        appendLine("Voici le détail jour par jour :")
        appendLine()
        appendLine("| Jour | kcal | Protéines |")
        appendLine("|---|---:|---:|")
        repeat(4_000) { appendLine("| Jour ${it + 1} | ${1800 + it % 400} | ${90 + it % 50} g |") }
    }

    @Test
    fun `tableau tres long dans l historique - le chat s ouvre sans planter`() {
        val state = ChatUiState(
            messages = listOf(
                ChatMessage(1, at, ChatRole.User, "donne-moi tout le détail"),
                ChatMessage(2, at, ChatRole.Assistant, longTable),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()

        val table = compose.onNodeWithTag(ChatTags.REPLY_TABLE, useUnmergedTree = true).fetchSemanticsNode()
        // Le cas de Sol : plus haut que ce qu'un `Constraints` étroit peut porter.
        assertTrue("hauteur ${table.size}", table.size.height > 262_143)
        val text = compose.onNodeWithTag(ChatTags.REPLY).fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("\n") { it.text }
        assertTrue(text, text.contains("Voici le détail"))
        assertFalse(text, text.contains("|"))
    }

    @Test
    fun `citation tres longue dans l historique - le chat s ouvre sans planter`() {
        val quote = (1..5_000).joinToString("\n") { "> Ligne $it de la citation." }
        val state = ChatUiState(
            messages = listOf(
                ChatMessage(1, at, ChatRole.User, "cite-moi tout"),
                ChatMessage(2, at, ChatRole.Assistant, "Voici :\n\n$quote"),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()

        val reply = compose.onNodeWithTag(ChatTags.REPLY).fetchSemanticsNode()
        assertTrue("hauteur ${reply.size}", reply.size.height > 262_143)
        val text = reply.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("\n") { it.text }
        assertFalse(text, text.contains("> Ligne"))
    }
}
