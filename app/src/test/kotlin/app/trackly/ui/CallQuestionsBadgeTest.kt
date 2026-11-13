package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.InMemoryUnseenQuestionsStore
import app.trackly.assistant.chat.Unseen
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.testing.SampleData
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.day.DayTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Pastille des questions après un appel : sur « Écrire à Trackly… », le chemin qui ouvre le chat de l'accueil, dans
 * le style de la pastille du « Bilan », lue par TalkBack. Elle disparaît quand l'utilisateur ouvre le chat, et ne survit pas au
 * fil du jour où les questions ont été écrites. Capture : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class CallQuestionsBadgeTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.parse("2026-11-02")
    private val engine = SampleData.engine()

    private fun ouvrir(unseen: UnseenQuestions) {
        val assistant = AssistantServices.inMemory(engine, unseenQuestions = unseen)
        // Avec le bouton d'appel, comme sur le téléphone : la pastille ne le recouvre pas.
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant, onCall = {}) }
        compose.waitForIdle()
    }

    @Test
    fun `questions laissees apres l appel - pastille sur le champ, lue par TalkBack, effacee a l ouverture du chat`() {
        val unseen = UnseenQuestions()
        ouvrir(unseen)
        compose.onNodeWithTag(DayTags.CHAT_QUESTIONS, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Écrire à Trackly").assertIsDisplayed()

        // La relecture vient d'écrire deux questions dans le fil du jour.
        compose.runOnIdle { unseen.raise(2, monday) }
        compose.waitForIdle()
        compose.onNodeWithTag(DayTags.CHAT_QUESTIONS, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Écrire à Trackly, 2 questions t’attendent").assertIsDisplayed()

        compose.onNodeWithTag(DayTags.FIELD).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INPUT).assertExists()
        assertNull("vues dès l'ouverture du chat", unseen.state.value)
        compose.onNodeWithTag(DayTags.CHAT_QUESTIONS, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `pastille gardee apres un arret d Android, mais pas au-dela du fil du jour`() {
        val store = InMemoryUnseenQuestionsStore()
        UnseenQuestions(store).raise(1, monday)
        // Relance de l'app : la pastille est relue.
        assertEquals(Unseen(monday, 1), UnseenQuestions(store).state.value)
        assertEquals(1, UnseenQuestions.count(Unseen(monday, 1), monday))
        // Le lendemain (passé 5 h), le fil est neuf : plus de pastille.
        assertEquals(0, UnseenQuestions.count(Unseen(monday, 1), monday.plusDays(1)))
        // Deux relectures le même jour s'additionnent ; un autre jour repart de zéro.
        val unseen = UnseenQuestions(store)
        unseen.raise(2, monday)
        assertEquals(3, unseen.state.value!!.count)
        unseen.raise(1, monday.plusDays(1))
        assertEquals(Unseen(monday.plusDays(1), 1), unseen.state.value)
        unseen.seen()
        assertNull(UnseenQuestions(store).state.value)
    }

    @Test
    fun `une question - TalkBack le dit au singulier`() {
        ouvrir(UnseenQuestions().also { it.raise(1, monday) })
        compose.onNodeWithContentDescription("Écrire à Trackly, une question t’attend").assertIsDisplayed()
    }

    @Test
    fun `capture - question apres l appel, pastille sur le champ`() {
        ouvrir(UnseenQuestions().also { it.raise(1, monday) })
        compose.onRoot().captureRoboImage("src/test/screenshots/accueil-question-appel.png")
    }
}
