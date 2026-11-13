package app.trackly.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatUiState
import app.trackly.dictation.DictationMode
import app.trackly.dictation.DictationPhase
import app.trackly.dictation.DictationUiState
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.chat.MicTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Dictée en direct avec le clavier ouvert : le texte, son animation et les boutons restent juste
 * au-dessus du clavier, à 100 % comme à 200 % de police. Clavier fermé, la mise en page est celle d'avant.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DictationKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val short = "Comme snack j’ai pris une barre Nuvo et une pomme"
    private val long = "Ce midi j’ai mangé deux cents grammes de riz, un steak haché de cent vingt-cinq grammes, " +
        "une salade verte avec de l’huile d’olive, un yaourt nature et une pomme, puis un café sans sucre. " +
        "Comme snack j’ai pris une barre Nuvo et une banane, cent cinquante grammes."

    private fun live(text: String) = DictationUiState(
        phase = DictationPhase.Listening,
        mode = DictationMode.Live,
        text = text,
        level = 0.18f,
        elapsedMs = 8_000,
    )

    private fun show(text: String, fontScale: Float) {
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                AvecPolice(fontScale) {
                    ChatShell(dateLabel = "Mardi 3 novembre", state = ChatUiState(), onBack = {}, dictation = DictationBinding(live(text)))
                }
            }
        }
        compose.waitForIdle()
    }

    /** Barres système d'un téléphone à boutons de geste, plus un clavier de `keyboardPx` pixels (0 : fermé). */
    private fun keyboard(keyboardPx: Int) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_PX))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (keyboardPx > 0) keyboardPx + NAV_PX else 0))
                .setVisible(WindowInsetsCompat.Type.ime(), keyboardPx > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun screenBottom() = bounds(MicTags.PANEL).bottom
    private fun sendLabelBottom() = compose.onNodeWithText("Envoyer").fetchSemanticsNode().boundsInRoot.bottom

    private fun assertEverythingAbove(keyboardTop: Float, where: String) {
        val text = bounds(MicTags.TEXT)
        val levels = compose.onNodeWithContentDescription("Niveau sonore").fetchSemanticsNode().boundsInRoot
        val status = bounds(MicTags.STATUS)
        assertTrue("$where : le texte dicté n'est pas posé (${text.height})", text.height > 0f)
        // La fin du texte est en vue : le défilement est tout en bas (sinon la dernière ligne serait coupée).
        val range = compose.onNodeWithTag(MicTags.SCROLL).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("$where : la fin du texte n'est pas en vue", range.maxValue(), range.value(), 0.5f)
        assertTrue("$where : le texte (${text.bottom}) passe sous le clavier ($keyboardTop)", text.bottom <= keyboardTop)
        assertTrue("$where : l'animation (${levels.bottom}) passe sous le clavier ($keyboardTop)", levels.bottom <= keyboardTop)
        assertTrue("$where : « Envoyer » (${sendLabelBottom()}) passe sous le clavier ($keyboardTop)", sendLabelBottom() <= keyboardTop)
        assertTrue("$where : le texte (${text.bottom}) recouvre l'animation (${levels.top})", text.bottom <= levels.top)
        assertTrue("$where : le texte (${text.top}) remonte sur l'état (${status.bottom})", text.top >= status.bottom)
    }

    private fun check(text: String, fontScale: Float, name: String) {
        show(text, fontScale)
        keyboard(0)
        val closedBottom = sendLabelBottom()
        // Clavier fermé : le panneau garde sa place d'avant (au-dessus de la barre de navigation seulement).
        assertTrue("clavier fermé : boutons sous la barre de navigation", closedBottom <= screenBottom() - NAV_PX)
        assertEverythingAbove(screenBottom() - NAV_PX, "clavier fermé, police ${fontScale * 100} %")
        compose.onNodeWithTag(MicTags.PANEL).captureRoboImage("src/test/screenshots/dictee-clavier-$name-ferme.png")

        keyboard(KEYBOARD_PX)
        val keyboardTop = screenBottom() - KEYBOARD_PX - NAV_PX
        assertEverythingAbove(keyboardTop, "clavier ouvert, police ${fontScale * 100} %")
        // Les boutons sont collés au clavier (marge du panneau seulement), pas flottants plus haut.
        assertTrue("« Envoyer » trop loin du clavier : ${keyboardTop - sendLabelBottom()} px", keyboardTop - sendLabelBottom() <= PANEL_MARGIN_PX)
        compose.onNodeWithTag(MicTags.PANEL).captureRoboImage("src/test/screenshots/dictee-clavier-$name-ouvert.png")

        // Le clavier se referme : retour exact à la mise en page d'avant.
        keyboard(0)
        assertEquals("le panneau ne revient pas à sa place après fermeture", closedBottom, sendLabelBottom(), 0.5f)
    }

    @Test fun `texte court, police normale`() = check(short, 1f, "100")

    @Test fun `texte court, police a 200 pour cent`() = check(short, 2f, "200")

    @Test fun `texte long qui defile, police a 200 pour cent`() = check(long, 2f, "200-long")

    private companion object {
        const val STATUS_PX = 72
        const val NAV_PX = 144
        const val KEYBOARD_PX = 900
        const val PANEL_MARGIN_PX = 16 * 3 + 6f
    }
}
