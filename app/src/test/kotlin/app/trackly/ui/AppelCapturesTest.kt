package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.call.AudioRoute
import app.trackly.call.CallEcho
import app.trackly.call.CallMetrics
import app.trackly.call.CallPhase
import app.trackly.call.CallReflection
import app.trackly.call.CallUi
import app.trackly.call.EndReason
import app.trackly.call.ReflectionTiming
import app.trackly.call.live.AudioLevels
import app.trackly.call.live.LaunchStep
import app.trackly.testing.SampleData
import app.trackly.ui.call.CallScreen
import app.trackly.ui.call.CallTags
import app.trackly.ui.day.DayTags
import app.trackly.ui.settings.CallRecordsView
import app.trackly.ui.settings.CallSettingsSection
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Captures de l'appel : bouton d'appel de la barre du bas, écran d'appel (connexion, écoute, voix, réflexion,
 * micro coupé, clé absente) et Réglages › Appel. Données fictives seulement. Même taille d'écran que les captures de l'accueil.
 * Enregistrer : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h868dp-xxhdpi")
class AppelCapturesTest {
    @get:Rule val compose = createComposeRule()

    private fun accueil(name: String, fontScale: Float = 1f): Int {
        var calls = 0
        compose.setContent {
            AvecPolice(fontScale) {
                TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), onOpenReview = {}, onCall = { calls++ })
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        compose.onNodeWithTag(DayTags.CALL).performClick()
        return calls
    }

    @Test
    fun accueilAvecBoutonAppel() {
        assertEquals(1, accueil("appel-accueil"))
        compose.onNodeWithContentDescription("Appeler Trackly").assertIsDisplayed()
    }

    @Test fun accueilAvecBoutonAppelPolice200() {
        accueil("appel-accueil-police-200", fontScale = 2f)
    }

    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @Test fun accueilAvecBoutonAppelPetitEcran() {
        accueil("appel-accueil-petit-ecran")
    }

    private fun ecran(name: String, ui: CallUi, levels: AudioLevels = AudioLevels()) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                CallScreen(ui = ui, levels = mutableStateOf(levels), onMute = {}, onSpeaker = {}, onHangUp = {}, onClose = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun connexion() = ecran("appel-connexion", CallUi(phase = CallPhase.Connecting))

    @Test fun ecoute() = ecran("appel-ecoute", CallUi(phase = CallPhase.Live, elapsedMs = 3_000))

    @Test fun utilisateurParle() = ecran("appel-utilisateur-parle", CallUi(phase = CallPhase.Live, elapsedMs = 42_000), AudioLevels(mic = 0.12f, ai = 0f))

    @Test fun voixParle() = ecran("appel-voix-parle", CallUi(phase = CallPhase.Live, elapsedMs = 47_000), AudioLevels(mic = 0f, ai = 0.09f))

    @Test fun reflexion() = ecran("appel-reflexion", CallUi(phase = CallPhase.Live, elapsedMs = 61_000, thinking = true))

    @Test fun muetEcouteurs() = ecran("appel-muet-ecouteurs", CallUi(phase = CallPhase.Live, elapsedMs = 75_000, micMuted = true, route = AudioRoute.Bluetooth))

    @Test fun cleAbsente() = ecran("appel-cle-absente", CallUi(phase = CallPhase.Ended, endReason = EndReason.MissingKey, status = EndReason.MissingKey.label))

    @Test fun termine() {
        ecran("appel-termine", CallUi(phase = CallPhase.Ended, elapsedMs = 95_000, endReason = EndReason.Goodbye, status = EndReason.Goodbye.label))
        compose.onNodeWithTag(CallTags.CLOSE).assertIsDisplayed()
    }

    private fun reglages(name: String, records: CallRecordsView) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                Column(
                    Modifier
                        .background(TracklyColors.Paper)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = TracklySpacing.Gutter),
                ) {
                    CallSettingsSection(CallReflection.Medium, CallEcho.WebRtc, records, onReflection = {}, onEcho = {}, onCopy = { _, _, _ -> })
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    @Test fun reglagesSansAppel() = reglages("appel-reglages-vide", CallRecordsView(null, emptyList()))

    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    @Test fun reglagesApresUnAppel() = reglages(
        "appel-reglages",
        CallRecordsView(
            "Dernier appel Trackly\n\nToi : exemple fictif",
            listOf(
                CallMetrics(
                    Instant.parse("2026-11-06T12:00:00Z"), prepared = true,
                    steps = listOf(LaunchStep.Http to 420L, LaunchStep.Started to 780L), beforeTap = emptySet(),
                    listenMs = 780, reflections = listOf(ReflectionTiming(CallReflection.Medium, 2_400, true)),
                    endReason = EndReason.Goodbye.code, seconds = 95,
                ),
            ),
        ),
    )
}
