package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.Correcting
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Fin du fil jamais cachée sous la saisie, dans deux cas relevés à la vérification : la carte qui s'allonge
 * après « Rétablir », et le rappel « Je corrige : … » qui agrandit la saisie.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatEndVisibleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val NEW_REPLY = "Rien d’autre à noter pour ce midi, bonne fin de journée."
    private val LAST_REPLY = "Snack enregistré."
    private val at = Instant.parse("2026-11-02T15:10:00Z")
    private var state by mutableStateOf(ChatUiState())

    private fun snack(id: Long) = RecordCard(
        actionId = id,
        kind = RecordKind.Added,
        items = listOf(
            CardItem("e1", "Barre de céréales", "1 pièce", "snack", "2026-11-02", 120.0, 3.0, 20.0, 4.0),
            CardItem("e2", "Pomme", "150 g", "snack", "2026-11-02", 78.0, 0.4, 19.0, 0.3),
        ),
        assumptions = listOf("Barre de céréales au chocolat, format habituel", "Pomme moyenne, poids supposé"),
    )

    /** Un fil assez long pour défiler, qui finit par une carte suivie de sa phrase. */
    private fun thread(undone: Set<Long> = emptySet(), correcting: Correcting? = null): ChatUiState {
        val before = (1..12).flatMap { n ->
            listOf(
                ChatMessage(n * 2L - 1, at, ChatRole.User, "Message $n : une pomme et un yaourt nature ce midi"),
                ChatMessage(n * 2L, at, ChatRole.Assistant, "C’est noté pour le message $n, il te reste de la marge aujourd’hui."),
            )
        }
        return ChatUiState(
            messages = before + listOf(
                ChatMessage(25, at, ChatRole.User, "comme snack j'ai pris une barre de céréales et une pomme"),
                ChatMessage(26, at, ChatRole.Assistant, "$LAST_REPLY Il te reste 2 152 kcal aujourd’hui.", listOf(snack(5))),
            ),
            undone = undone,
            correcting = correcting,
        )
    }

    private fun correcting() = Correcting(
        CorrectionFocus(actionId = null, entryIds = listOf("e1", "e2")),
        "le matin (Œufs brouillés, Tartine de pain complet)",
    )

    private fun show(reduced: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = reduced)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    private fun composerTop() = compose.onNodeWithTag(ChatTags.COMPOSER).fetchSemanticsNode().boundsInRoot.top

    /** Bas de la dernière phrase du fil (celle de l'assistant, barrée ou non) : posée à l'écran, pas seulement composée. */
    private fun threadEnd(lastReply: String): Float {
        val bounds = compose.onNodeWithText(lastReply, substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("la dernière phrase du fil n'est pas posée à l'écran ($bounds)", bounds.height > 0f)
        return bounds.bottom
    }

    private fun assertEndAboveComposer(where: String, lastReply: String = LAST_REPLY) {
        compose.waitForIdle()
        // Le fil doit vraiment défiler, sinon le test ne prouve rien.
        assertTrue("le fil tient en entier à l'écran", compose.onAllNodesWithText("Message 1 :", substring = true).fetchSemanticsNodes().isEmpty())
        val end = threadEnd(lastReply)
        val top = composerTop()
        assertTrue("$where : la fin du fil ($end) passe sous la saisie qui commence à $top", end <= top + 0.5f)
    }

    @Test
    fun `apres Retablir la carte qui s allonge reste visible en entier`() {
        state = thread(undone = setOf(5L))
        show()
        assertEndAboveComposer("fil au départ, carte annulée")
        compose.onAllNodesWithTag(CardTags.UNDONE).fetchSemanticsNodes().let { assertEquals(1, it.size) }

        state = thread(undone = emptySet())
        compose.waitForIdle()

        compose.onAllNodesWithTag(CardTags.RECORD).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        assertEndAboveComposer("après Rétablir")
    }

    @Test
    fun `apres Retablir le fil ne bouge pas si on lisait plus haut`() {
        state = thread(undone = setOf(5L))
        show()
        // Un vrai geste : l'utilisateur remonte un peu le fil pour relire.
        compose.onNodeWithTag(ChatTags.THREAD).performTouchInput { swipeDown(startY = height * 0.45f, endY = height * 0.55f) }
        compose.waitForIdle()
        val firstBefore = compose.onAllNodesWithTag(ChatTags.BUBBLE).fetchSemanticsNodes().first().boundsInRoot.top

        state = thread(undone = emptySet())
        compose.waitForIdle()

        val firstAfter = compose.onAllNodesWithTag(ChatTags.BUBBLE).fetchSemanticsNodes().first().boundsInRoot.top
        assertEquals("le fil a défilé alors qu'on lisait plus haut", firstBefore, firstAfter, 0.5f)
        assertEquals("la carte devait être rétablie", 1, compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().size)
    }

    @Test
    fun `le rappel de correction qui apparait puis disparait ne cache pas la fin du fil`() {
        state = thread()
        show()
        assertEndAboveComposer("fil au départ")
        val topWithout = composerTop()

        state = thread(correcting = correcting())
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.CORRECTING).fetchSemanticsNode()
        assertTrue("le rappel devrait agrandir la saisie", composerTop() < topWithout)
        assertEndAboveComposer("rappel affiché")

        state = thread()
        compose.waitForIdle()
        assertEndAboveComposer("rappel retiré")
    }

    @Test
    fun `chat ouvert par Corriger avec le rappel deja la, la fin du fil est visible`() {
        state = thread(correcting = correcting())
        show(reduced = false)
        compose.onNodeWithTag(ChatTags.CORRECTING).fetchSemanticsNode()
        assertEndAboveComposer("ouverture avec le rappel")
    }

    /** Le clavier monte image par image (animation d'Android) : la saisie grandit à chaque image. */
    private fun keyboardHeight(px: Int) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), px > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    @Test
    fun `le clavier qui monte par etapes avec le rappel de correction garde la fin du fil visible`() {
        state = thread()
        show()
        state = thread(correcting = correcting())
        compose.waitForIdle()
        var previousTop = composerTop()
        for (px in listOf(150, 400, 700, 950)) {
            keyboardHeight(px)
            assertTrue("le clavier simulé ne soulève pas la saisie", composerTop() < previousTop)
            previousTop = composerTop()
            assertEndAboveComposer("clavier à $px px")
        }
        keyboardHeight(0)
        assertEndAboveComposer("clavier refermé")
    }

    @Test
    fun `le clavier qui monte image par image avec le rappel garde la fin du fil visible`() {
        state = thread()
        show(reduced = false)
        state = thread(correcting = correcting())
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (px in 30..950 step 40) {
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        assertEndAboveComposer("clavier monté image par image")
    }

    @Test
    fun `le rappel qui arrive pendant que le fil descend encore ne cache pas la fin`() {
        state = thread()
        show(reduced = false)
        compose.mainClock.autoAdvance = false
        // Une réponse arrive : le fil descend en s'animant ; au milieu, le rappel de correction apparaît.
        state = thread().let { it.copy(messages = it.messages + ChatMessage(27, at, ChatRole.Assistant, NEW_REPLY)) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        state = state.copy(correcting = correcting())
        for (i in 1..4) compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        assertEndAboveComposer("rappel apparu pendant le défilement", NEW_REPLY)
    }

    @Test
    fun `chat ouvert par Corriger, clavier qui monte pendant l ouverture, fin du fil visible`() {
        state = thread(correcting = correcting())
        compose.mainClock.autoAdvance = false
        show(reduced = false)
        for (px in 0..960 step 60) {
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                    .setVisible(WindowInsetsCompat.Type.ime(), px > 0)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        assertEndAboveComposer("ouverture par Corriger avec le clavier")
    }
}
