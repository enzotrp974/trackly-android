package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.LiveReply
import app.trackly.assistant.turn.TurnStatus
import app.trackly.review.ReviewSample
import app.trackly.review.ReviewUiState
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.review.ReviewScreen
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
import java.time.Instant

/**
 * Réponses libres mises en page : « Propose-moi 4 repas » sans `|||`, un tableau large qui défile dans son
 * bloc, une réponse en cours, la police à 200 %, le fil du bilan ; un repas déclaré garde sa carte.
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class MarkdownCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T17:40:00Z")

    private fun shell(state: ChatUiState, fontScale: Float = 1f) {
        compose.setContent {
            AvecPolice(fontScale) {
                CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                    ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun chat(vararg reply: Pair<String, String>) = ChatUiState(
        messages = reply.flatMapIndexed { index, (ask, answer) ->
            listOf(ChatMessage(index * 2L + 1, at, ChatRole.User, ask), ChatMessage(index * 2L + 2, at, ChatRole.Assistant, answer))
        },
    )

    /** Texte lu du nœud de la réponse (descendants fusionnés). */
    private fun replyText(tag: String = ChatTags.REPLY): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("\n") { it.text }

    @Test
    fun propositionsAvecMacros() {
        shell(chat(ASK to PROPOSALS))
        compose.onNodeWithTag(ChatTags.REPLY_HEADING, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.REPLY_LIST, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ChatTags.REPLY_TABLE, useUnmergedTree = true).assertExists()
        val text = replyText()
        assertFalse(text, text.contains("|") || text.contains("**") || text.contains("###"))
        assertTrue(text, text.contains("Pâtes thon sauce tomate"))
        // Un tableau qui tient dans la bulle n'a pas de rail de défilement.
        compose.onNodeWithTag(ChatTags.REPLY_TABLE_TRACK, useUnmergedTree = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-propositions-markdown.png")
    }

    @Test
    fun propositionsPolice200() {
        shell(chat(ASK to PROPOSALS), fontScale = 2f)
        assertBubbleInsideScreen()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-propositions-markdown-police-200.png")
    }

    @Test
    fun tableauLargeDefileDansSonBloc() {
        shell(chat("compare-moi ces 4 plats en détail" to WIDE))
        assertBubbleInsideScreen()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-tableau-large.png")

        val bubble = compose.onNodeWithTag(ChatTags.REPLY).getBoundsInRoot()
        // Le tableau dit à TalkBack qu'il défile, et lit chaque valeur avec son en-tête.
        val table = compose.onNodeWithTag(ChatTags.REPLY_TABLE, useUnmergedTree = true)
        val range = { table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange] }
        assertTrue(range().maxValue() > 0f)
        assertEquals(0f, range().value())
        val spoken = table.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(spoken, spoken.contains("Pâtes thon sauce tomate : kcal 650, Protéines 42 g, Glucides 80 g"))
        compose.onNodeWithTag(ChatTags.REPLY_TABLE_TRACK, useUnmergedTree = true).assertExists()
        table.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("le tableau n'a pas défilé", range().value() > 0f)
        // Le tableau a défilé, la bulle (et donc la page) n'a pas bougé.
        assertEquals(bubble, compose.onNodeWithTag(ChatTags.REPLY).getBoundsInRoot())
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-tableau-large-defile.png")
    }

    @Test
    fun tableauLargePolice200() {
        shell(chat("compare-moi ces 4 plats en détail" to WIDE), fontScale = 2f)
        assertBubbleInsideScreen()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-tableau-large-police-200.png")
    }

    @Test
    fun reponseEnCoursTableauInacheve() {
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, ASK),
                    ChatMessage(2, at, ChatRole.Assistant, ""),
                ),
                thinking = true,
                live = LiveReply(status = TurnStatus.Thinking, text = PROPOSALS.substringBefore("| Omelette") + "| Omelette | 52", messageId = 2),
            ),
        )
        compose.onNodeWithTag(ChatTags.LIVE_REPLY).assertIsDisplayed()
        val text = replyText(ChatTags.LIVE_REPLY)
        assertFalse(text, text.contains("|") || text.contains("**"))
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-markdown-en-cours.png")
    }

    @Test
    fun repasDeclareGardeSaCarte() {
        val snack = RecordCard(
            actionId = 12,
            kind = RecordKind.Added,
            items = listOf(
                CardItem("e1", "Barre protéinée Nuvo", "55 g", "snack", "2026-11-02", 222.0, 19.6, 12.0, 7.7),
                CardItem("e2", "Pomme", "150 g", "snack", "2026-11-02", 78.0, 0.4, 19.0, 0.3),
            ),
        )
        shell(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.User, "comme snack j'ai pris une barre Nuvo et une pomme, 150 g"),
                    ChatMessage(2, at, ChatRole.Assistant, "C’est noté pour ton snack. Il te reste **2 300 kcal** aujourd’hui.", listOf(snack)),
                ),
            ),
        )
        compose.onNodeWithTag(CardTags.RECORD).assertIsDisplayed()
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", replyText())
        // Une phrase garde la bulle étroite d'avant : seules les réponses structurées prennent toute la largeur.
        val width = compose.onNodeWithTag(ChatTags.REPLY).getBoundsInRoot().let { it.right - it.left }
        assertTrue("bulle trop large : $width", width <= 300.dp)
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-repas-declare-carte.png")
    }

    @Test
    fun filDuBilan() {
        val messages = listOf(
            ChatMessage(1, at, ChatRole.User, "c'est quoi mes jours les plus chargés en kcal cette semaine ?"),
            ChatMessage(
                2, at, ChatRole.Assistant,
                """
                ### Tes 3 jours les plus hauts
                | Jour | kcal | Écart |
                |---|---|---|
                | Jeudi 29 | 2 480 | +180 |
                | Samedi 31 | 2 410 | +110 |
                | Dimanche 1er | 2 350 | +50 |

                Le reste de la semaine est **sous l'objectif**.
                """.trimIndent(),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ReviewScreen(state = ReviewUiState(period = ReviewSample.week, messages = messages), onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.REPLY_TABLE, useUnmergedTree = true).assertExists()
        assertFalse(replyText().contains("|"))
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-conversation-markdown.png")
    }

    private fun assertBubbleInsideScreen() {
        val root = compose.onRoot().getBoundsInRoot()
        val bubble = compose.onNodeWithTag(ChatTags.REPLY).getBoundsInRoot()
        assertTrue("la bulle dépasse l'écran : $bubble dans $root", bubble.left >= root.left && bubble.right <= root.right)
    }

    private companion object {
        const val ASK = "Propose-moi 4 repas pour ce soir, avec les macros"

        val PROPOSALS = """
            ### 4 idées pour ce soir
            Il te reste **1 150 kcal** et **62 g** de protéines.

            1. **Pâtes thon sauce tomate** : rapide et très protéiné
            2. **Omelette et pommes de terre** : simple, rassasiant
            3. **Poulet, riz, brocolis** : le classique
            4. **Wrap poulet crudités** : plus léger

            | Repas | kcal | P | G | L |
            |---|---|---|---|---|
            | Pâtes thon | 650 | 42 g | 80 g | 12 g |
            | Omelette | 520 | 28 g | 40 g | 26 g |
            | Poulet riz | 610 | 45 g | 75 g | 10 g |
            | Wrap poulet | 480 | 35 g | 45 g | 15 g |

            Dis-moi celle qui te tente.
        """.trimIndent()

        val WIDE = """
            | Repas | kcal | Protéines | Glucides | Lipides | Fibres | Sel | Coût |
            |---|---:|---:|---:|---:|---:|---:|---:|
            | Pâtes thon sauce tomate | 650 | 42 g | 80 g | 12 g | 6 g | 1,8 g | 2,10 € |
            | Omelette et pommes de terre | 520 | 28 g | 40 g | 26 g | 4 g | 1,2 g | 1,60 € |
            | Poulet, riz, brocolis | 610 | 45 g | 75 g | 10 g | 5 g | 0,9 g | 3,20 € |
            | Wrap poulet crudités | 480 | 35 g | 45 g | 15 g | 4 g | 1,5 g | 2,80 € |

            Le plus protéiné pour le prix : **les pâtes thon**.
        """.trimIndent()
    }
}
