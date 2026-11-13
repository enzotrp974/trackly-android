package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.Correcting
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.ui.chat.ChatShell
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
 * Captures de la fin du fil : la carte rétablie en entier au-dessus de la saisie, et le chat ouvert par « Corriger »
 * avec le rappel (la dernière carte reste lisible). Les quantités s'accordent (« 2 pièces »).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatEndCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T15:10:00Z")

    private val matin = RecordCard(
        actionId = 5,
        kind = RecordKind.Added,
        items = listOf(
            CardItem("e1", "Œufs brouillés", "2 pièces", "matin", "2026-11-02", 188.0, 16.2, 1.0, 14.0),
            CardItem("e2", "Tartine de pain complet", "40 g", "matin", "2026-11-02", 100.0, 3.0, 17.0, 1.0),
        ),
        assumptions = listOf("Deux œufs moyens, avec 5 g de beurre pour la cuisson.", "Une tranche de 40 g, sans garniture supplémentaire."),
    )

    /** Un fil assez long pour défiler : la carte et sa phrase finissent juste au-dessus de la saisie. */
    private fun thread(correcting: Correcting? = null) = ChatUiState(
        messages = (1..3).flatMap { n ->
            listOf(
                ChatMessage(n * 2L - 1, at, ChatRole.User, "Hier, un yaourt nature et une pomme (message $n)"),
                ChatMessage(n * 2L, at, ChatRole.Assistant, "C’est noté, il te reste de la marge."),
            )
        } + listOf(
            ChatMessage(7, at, ChatRole.User, "2 œufs brouillés et une tartine de pain complet ce matin"),
            ChatMessage(8, at, ChatRole.Assistant, "Matin enregistré. Il te reste 1 143 kcal aujourd’hui.", listOf(matin)),
        ),
        correcting = correcting,
    )

    private fun show(state: ChatUiState, name: String) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Mardi 3 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun retabliFinVisible() = show(thread(), "chat-retabli-fin-visible")

    @Test
    fun corrigerRappelFinVisible() = show(
        thread(Correcting(CorrectionFocus(actionId = null, entryIds = listOf("e1", "e2")), "le matin (Œufs brouillés, Tartine de pain complet)")),
        "chat-corriger-rappel-fin-visible",
    )
}
