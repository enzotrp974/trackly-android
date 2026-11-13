package app.trackly.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Captures du chat : carte annulée avec « Rétablir » et réponse barrée, carte « Rétabli »,
 * carte périmée, correction avant → après et hypothèses une par ligne, « Hors ligne »,
 * vignette de la photo envoyée.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatC1CapturesTest {
    @get:Rule val compose = createComposeRule()

    private val at = Instant.parse("2026-11-02T15:10:00Z")

    private fun snack(id: Long, kind: RecordKind = RecordKind.Added) = RecordCard(
        actionId = id,
        kind = kind,
        items = listOf(
            CardItem("e1", "Barre de céréales", "1 pièce", "snack", "2026-11-02", 120.0, 3.0, 20.0, 4.0),
            CardItem("e2", "Pomme", "150 g", "snack", "2026-11-02", 78.0, 0.4, 19.0, 0.3),
        ),
        assumptions = listOf("Barre de céréales au chocolat, format habituel"),
    )

    private fun show(state: ChatUiState, name: String) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(dateLabel = "Lundi 2 novembre", state = state, onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun annuleeRetablir() = show(
        ChatUiState(
            messages = listOf(
                ChatMessage(1, at, ChatRole.User, "comme snack j'ai pris une barre de céréales et une pomme"),
                ChatMessage(2, at, ChatRole.Assistant, "Snack enregistré. Il te reste 2 152 kcal aujourd’hui.", listOf(snack(5))),
            ),
            undone = setOf(5L),
        ),
        "carte-annulee-retablir",
    )

    @Test
    fun retablie() = show(
        ChatUiState(
            messages = listOf(
                ChatMessage(1, at, ChatRole.User, "remets-le"),
                ChatMessage(2, at, ChatRole.Assistant, "C’est remis. Il te reste 2 152 kcal aujourd’hui.", listOf(snack(9, RecordKind.Restored))),
            ),
        ),
        "carte-retablie",
    )

    @Test
    fun perimeeEtCorrection() {
        val midi = RecordCard(
            actionId = 11, kind = RecordKind.Added,
            items = listOf(CardItem("r1", "Riz basmati cuit", "200 g", "midi", "2026-11-02", 260.0, 5.4, 56.0, 0.6)),
            assumptions = listOf("Riz basmati assimilé au riz blanc cuit de ta fiche.", "Poids supposé cuit"),
        )
        val fixed = RecordCard(
            actionId = 12, kind = RecordKind.Corrected,
            items = listOf(CardItem("r1", "Riz basmati cuit", "250 g", "midi", "2026-11-02", 325.0, 6.8, 70.0, 0.8, beforeQuantity = "200 g", beforeKcal = 260.0)),
            assumptions = listOf("Riz basmati assimilé au riz blanc cuit de ta fiche.", "Poids supposé cuit"),
        )
        show(
            ChatUiState(
                messages = listOf(
                    ChatMessage(1, at, ChatRole.Assistant, "", listOf(midi)),
                    ChatMessage(2, at, ChatRole.User, "non, c'était 250 g de riz"),
                    ChatMessage(3, at, ChatRole.Assistant, "Corrigé. Il te reste 1 570 kcal aujourd’hui.", listOf(fixed)),
                ),
                superseded = mapOf(11L to "Corrigé depuis. Pour revenir en arrière, annule plutôt la correction."),
            ),
            "carte-perimee-et-correction",
        )
    }

    @Test
    fun horsLigneEtVignette() {
        val photo = Bitmap.createBitmap(192, 144, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(226, 220, 205))
            canvas.drawRect(40f, 30f, 150f, 120f, Paint().apply { color = Color.rgb(64, 84, 140) })
        }
        show(
            ChatUiState(
                messages = listOf(ChatMessage(1, at, ChatRole.User, "l’étiquette de ma barre\n[1 photo envoyée]")),
                thumbnails = mapOf(1L to listOf(photo)),
                offline = true,
            ),
            "chat-hors-ligne-et-vignette",
        )
    }
}
