package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.Correcting
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.testing.SampleData
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.EffortTags
import app.trackly.ui.settings.UsageSettingsSection
import app.trackly.ui.settings.UsageTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.usage.MonthCost
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

/** Captures de référence du sélecteur de raisonnement et du coût du mois. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class CoutCapturesTest {
    @get:Rule val compose = createComposeRule()

    private fun openChat() {
        compose.setContent { TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    @Test
    fun selecteurOuvert() {
        openChat()
        compose.onNodeWithTag(EffortTags.CHIP).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-raisonnement-ouvert.png")
    }

    @Test
    fun selecteurArme() {
        openChat()
        compose.onNodeWithTag(EffortTags.CHIP).performClick()
        compose.onNodeWithTag(EffortTags.option(ReasoningEffort.XHigh)).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-raisonnement-arme.png")
    }

    /** Le rappel « Je corrige : … » et le sélecteur de raisonnement, ensemble au-dessus de la saisie. */
    @Test
    fun correctionEtRaisonnement() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(
                    dateLabel = "Lundi 2 novembre",
                    state = ChatUiState(correcting = Correcting(CorrectionFocus(1L, listOf("e1")), "Barre protéinée Nuvo chocolat")),
                    onBack = {},
                    effort = ReasoningEffort.High,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-correction-et-raisonnement.png")
    }

    @Test
    fun coutDuMois() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                Box(Modifier.background(TracklyColors.PaperHi).padding(horizontal = TracklySpacing.Gutter, vertical = 12.dp)) {
                    UsageSettingsSection(
                        MonthCost(
                            month = YearMonth.of(2026, 11),
                            text = 31_240_000, search = 1_500_000, transcription = 420_000,
                            exchanges = 212, requests = 340, dictations = 38,
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(UsageTags.SECTION).captureRoboImage("src/test/screenshots/reglages-cout.png")
    }
}
