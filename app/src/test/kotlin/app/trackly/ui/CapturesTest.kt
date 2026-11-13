package app.trackly.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.core.action.SetGoal
import app.trackly.core.model.GoalTargets
import app.trackly.importing.HistoryImportViewModel
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatTags
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence (Roborazzi). Enregistrer : `./gradlew recordRoborazziDebug` ;
 * comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class CapturesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ecranDuJour() {
        compose.setContent { TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/ecran-du-jour.png")
    }

    @Test
    fun ecranDuJourVide() {
        compose.setContent { TracklyRoot(engine = SampleData.emptyEngine(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/ecran-du-jour-vide.png")
    }

    @Test
    fun chatOuvert() {
        compose.setContent { TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/chat-ouvert.png")
    }

    @Test
    fun carteEnregistrement() {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
        runBlocking { engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) }
        val assistant = AssistantServices.inMemory(
            engine,
            ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"),
            InMemoryApiKeyStore("sk-test-cle-factice-0000000000"),
        )
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/carte-enregistrement.png")
    }

    @Test
    fun reglagesCle() {
        val engine = SampleData.engine()
        val assistant = AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore())
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-cle.png")
    }

    @Test
    fun panneauDeDeveloppement() {
        compose.setContent { TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onNodeWithText("Trackly").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/panneau-developpement.png")
    }

    @Test
    fun calendrier() {
        compose.setContent { TracklyRoot(engine = SampleData.engineWithHistory(), motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onNodeWithText("Lun. 2 nov.").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/calendrier.png")
    }

    @Test
    fun repriseResume() {
        val engine = SampleData.emptyEngine()
        val model = HistoryImportViewModel(engine)
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), importModel = model) }
        compose.waitForIdle()
        model.importFrom { SampleData.historyBundle }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("C’est fait.").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("src/test/screenshots/reprise-resume.png")
    }

    @Test
    fun repriseInvitation() {
        val engine = SampleData.emptyEngine()
        val model = HistoryImportViewModel(engine)
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), importModel = model) }
        compose.waitForIdle()
        model.open()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reprends ton historique").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("src/test/screenshots/reprise-invitation.png")
    }
}
