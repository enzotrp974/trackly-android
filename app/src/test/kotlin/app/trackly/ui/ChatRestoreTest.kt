package app.trackly.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import org.junit.Assert.assertEquals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.day.DayView
import app.trackly.testing.SampleData
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Android ferme l'app en arrière-plan avec le chat ouvert. Au retour, l'état sauvegardé rouvre le chat,
 * même si les journées mettent un instant à se relire (écran vide d'abord, comme au démarrage à froid).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatRestoreTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `chat ouvert retrouve apres la recreation, journees relues en retard comprises`() {
        val engine = SampleData.engine()
        val loaded = runBlocking {
            engine.observeDays(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")).first()
                .map { DayView.from(it, LocalDate.parse("2026-11-02")) }
        }
        var compositions = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            // Première vie : journées déjà là. Après la recréation : vides d'abord, puis relues (base lente au démarrage).
            val generation = remember { ++compositions }
            var ready by remember { mutableStateOf(generation == 1) }
            LaunchedEffect(Unit) {
                if (!ready) {
                    withFrameNanos { }
                    ready = true
                }
            }
            TracklyApp(motion = MotionPolicy(reduced = true), days = if (ready) loaded else emptyList())
        }
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()

        // Processus tué puis app rouverte : l'état sauvegardé est relu.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(2, compositions)

        compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
    }
}
