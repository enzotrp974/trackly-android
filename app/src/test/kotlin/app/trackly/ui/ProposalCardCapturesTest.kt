package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.CardView
import app.trackly.ui.chat.ChatActions
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
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
 * Propositions de repas : « 4 propositions » donne une liste courte sans dosage ; l'idée choisie donne une
 * carte de proposition, de la famille de la carte d'enregistrement, avec « Noter » ; une valeur inconnue est dite ;
 * une proposition notée montre « Noté » et la carte d'enregistrement suit. Police à 200 % comprise.
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ProposalCardCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T17:40:00Z")

    private val pasta = ProposalCard(
        id = "prop-1",
        title = "Pâtes thon sauce tomate",
        moment = "soir",
        items = listOf(
            ProposalItem("Pâtes complètes", "100 g", 350.0, 13.0, 65.0, 2.5, productId = "prd-pates", amount = 100.0, unit = "g"),
            ProposalItem("Thon au naturel", "80 g", 88.0, 20.0, 0.0, 0.8, productId = "prd-thon", amount = 80.0, unit = "g"),
            ProposalItem("Sauce tomate", "60 g", unknown = "pas de fiche"),
        ),
    )

    private val bowl = ProposalCard(
        id = "prop-2",
        title = "Bowl poulet riz",
        moment = "soir",
        items = listOf(ProposalItem("Bowl poulet riz", "1 portion", 507.5, 52.1, 56.0, 6.0, recipeId = "rcp-bowl", amount = 1.0, unit = "portion")),
    )

    private val noted = RecordCard(
        actionId = 9,
        kind = RecordKind.Added,
        items = listOf(CardItem("e1", "Bowl poulet riz", "1 portion", "soir", "2026-11-02", 507.5, 52.1, 56.0, 6.0, recipeId = "rcp-bowl")),
        proposalId = "prop-2",
    )

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

    private fun message(id: Long, role: ChatRole, text: String, vararg cards: ChatCard) = ChatMessage(id, at, role, text, cards.toList())

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun quatrePropositionsUneListeCourte() {
        shell(ChatUiState(messages = listOf(message(1, ChatRole.User, ASK), message(2, ChatRole.Assistant, LIST))))
        compose.onNodeWithTag(ChatTags.REPLY_LIST, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(CardTags.PROPOSAL).assertDoesNotExist()
        val text = compose.onNodeWithTag(ChatTags.REPLY).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("\n") { it.text }
        assertFalse(text, text.contains("kcal") || text.contains("|"))
        capture("chat-propositions-liste")
    }

    @Test
    fun ideeChoisieUneCarteAvecNoter() {
        shell(
            ChatUiState(
                messages = listOf(
                    message(1, ChatRole.User, ASK),
                    message(2, ChatRole.Assistant, LIST),
                    message(3, ChatRole.User, "la première"),
                    message(4, ChatRole.Assistant, "Bon choix, ça rentre bien dans ce qu’il te reste.", pasta),
                ),
            ),
        )
        compose.onNodeWithTag(CardTags.PROPOSAL).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.PROPOSAL_NOTE).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.PROPOSAL_UNKNOWN).assertIsDisplayed()
        // TalkBack : ce qu'est la carte, et qu'elle ne compte pas encore ; la valeur inconnue est dite.
        compose.onNodeWithContentDescription("Proposition, Soir, Pâtes thon sauce tomate, au moins 438 kilocalories, pas encore comptée", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Sauce tomate, 60 g, valeur inconnue", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Noter la proposition Pâtes thon sauce tomate").assertExists()
        capture("chat-proposition-carte")
    }

    @Test
    fun propositionNoteeEtSaCarteDEnregistrement() {
        shell(
            ChatUiState(
                messages = listOf(
                    message(1, ChatRole.User, "je mange quoi ce soir ?"),
                    message(2, ChatRole.Assistant, "Un bowl poulet riz, tu as tout chez toi.", bowl),
                    message(3, ChatRole.Assistant, "", noted),
                ),
                notedProposals = setOf("prop-2"),
            ),
        )
        compose.onNodeWithTag(CardTags.PROPOSAL_NOTED).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.PROPOSAL_NOTE).assertDoesNotExist()
        compose.onNodeWithTag(CardTags.RECORD).assertIsDisplayed()
        capture("chat-proposition-notee")
    }

    @Test
    fun cartePolice200() {
        compose.setContent {
            AvecPolice(2f) {
                CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                    Box(Modifier.background(TracklyColors.Paper).padding(16.dp)) { CardView(pasta, false, false, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CardTags.PROPOSAL_NOTE).assertIsDisplayed()
        capture("carte-proposition-police-200")
    }

    @Test
    fun noterAppelleLAction() {
        var tapped: ChatCard? = null
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(
                    dateLabel = "Lundi 2 novembre",
                    state = ChatUiState(messages = listOf(message(1, ChatRole.Assistant, "", bowl))),
                    onBack = {},
                    actions = ChatActions(onNote = { tapped = it }),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CardTags.PROPOSAL_NOTE).performClick()
        compose.waitForIdle()
        assertEquals(bowl, tapped)
    }

    private companion object {
        const val ASK = "propose-moi 4 repas pour ce soir"
        const val LIST = "### 4 idées pour ce soir\n\n- **Pâtes thon sauce tomate**\n- **Bowl poulet riz**\n- **Omelette, pain complet et salade**\n- **Wrap au thon et crudités**\n\n" +
            "Moi je prendrais les pâtes au thon : tu as tout chez toi. Dis-moi laquelle te tente et je te la détaille."
    }
}
