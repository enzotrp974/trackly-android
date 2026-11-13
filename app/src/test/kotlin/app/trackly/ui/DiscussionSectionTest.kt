package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.core.time.TrackingDay
import app.trackly.testing.SampleData
import app.trackly.ui.settings.DiscussionSettingsRoute
import app.trackly.ui.settings.DiscussionTags
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Réglages › Assistant › « Effacer la discussion » : confirmation, puis le fil du jour est masqué et les repas restent. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DiscussionSectionTest {
    @get:Rule val compose = createComposeRule()

    private val clock = SampleData.clock("2026-11-02T12:30")
    private val engine = SampleData.engine(clock)
    private val history = InMemoryChatHistory()
    private val thread = DayThread(history) { clock.instant() }
    private val today = TrackingDay.of(clock.instant())

    private fun open() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                SettingsScreen(
                    InMemoryApiKeyStore(),
                    onClose = {},
                    groups = SettingsGroups(model = { DiscussionSettingsRoute(thread) }),
                    startPage = SettingsPage.Model,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun seedChat() = runBlocking {
        history.append(clock.instant(), ChatRole.User, "une pomme")
        history.append(clock.instant(), ChatRole.Assistant, "C’est noté.")
    }

    @Test
    fun `le bouton Effacer la discussion est dans les reglages`() {
        open()
        compose.onNodeWithTag(DiscussionTags.CLEAR).assertIsDisplayed()
        compose.onNodeWithText("Effacer la discussion").assertIsDisplayed()
    }

    @Test
    fun `toucher le bouton demande confirmation sans rien effacer`() {
        seedChat()
        open()

        compose.onNodeWithTag(DiscussionTags.CLEAR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(DiscussionTags.CONFIRM).assertIsDisplayed()
        compose.onNodeWithText("Tes repas ne sont pas touchés.", substring = true).assertIsDisplayed()
        assertEquals(2, runBlocking { history.last(today, 10).size })
    }

    @Test
    fun `garder renonce a effacer`() {
        seedChat()
        open()
        compose.onNodeWithTag(DiscussionTags.CLEAR).performClick()

        compose.onNodeWithTag(DiscussionTags.KEEP).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(DiscussionTags.CLEAR).assertIsDisplayed()
        assertEquals(2, runBlocking { history.last(today, 10).size })
    }

    @Test
    fun `confirmer masque le fil du jour et laisse les repas intacts`() {
        seedChat()
        val before = runBlocking { engine.daySummary(today) }
        open()
        compose.onNodeWithTag(DiscussionTags.CLEAR).performClick()

        compose.onNodeWithTag(DiscussionTags.CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals(0, runBlocking { history.last(today, 10).size })
        assertEquals(before, runBlocking { engine.daySummary(today) })
        compose.onNodeWithTag(DiscussionTags.DONE).assertIsDisplayed()
        compose.onNodeWithText("Discussion effacée. Tes repas n’ont pas été touchés.").assertIsDisplayed()
    }

    @Test
    fun `sans discussion a effacer, le dit simplement`() {
        open()
        compose.onNodeWithTag(DiscussionTags.CLEAR).performClick()

        compose.onNodeWithTag(DiscussionTags.CONFIRM).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Il n’y avait rien à effacer.").assertIsDisplayed()
    }

    @Test
    fun `une panne de la base a l effacement ne ferme pas l app, elle est dite`() {
        val failing = object : app.trackly.assistant.chat.ChatHistory by history {
            override suspend fun clear(day: java.time.LocalDate, at: java.time.Instant): Int = throw java.io.IOException("disque plein")
        }
        val brokenThread = DayThread(failing) { clock.instant() }
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                SettingsScreen(
                    InMemoryApiKeyStore(),
                    onClose = {},
                    groups = SettingsGroups(model = { DiscussionSettingsRoute(brokenThread) }),
                    startPage = SettingsPage.Model,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DiscussionTags.CLEAR).performClick()

        compose.onNodeWithTag(DiscussionTags.CONFIRM).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Impossible d’effacer la discussion pour l’instant. Rien n’a été changé.").assertIsDisplayed()
    }
}
