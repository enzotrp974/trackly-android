package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.alerts.FixedNotificationsAllowed
import app.trackly.alerts.HomeAlertsSource
import app.trackly.backup.BackupStatus
import app.trackly.review.ReviewSettingsState
import app.trackly.testing.SampleData
import app.trackly.ui.day.DayAlert
import app.trackly.ui.day.DayTags
import app.trackly.ui.day.rememberDayAlert
import app.trackly.ui.day.rememberReviewDue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithContentDescription
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Alerte d'accueil sur l'écran du jour : une ligne discrète (copie Drive de plus de 36 h, notifications bloquées le lundi),
 * avec le même geste que les Réglages. Capture : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DayAlertTest {
    @get:Rule val compose = createComposeRule()

    private val monday = Instant.parse("2026-11-02T08:00:00Z")
    private val tuesday = Instant.parse("2026-11-03T08:00:00Z")
    private val staleLine = "Des changements attendent depuis plus de 36 heures d’être copiés sur Drive."

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private fun staleBackup(now: Instant) =
        BackupStatus(
            connected = true,
            accountEmail = "a@b.c",
            lastSuccessAt = now.minus(Duration.ofHours(50)),
            pending = true,
            unsavedSince = now.minus(Duration.ofHours(49)),
        )

    private fun source(
        now: Instant,
        backup: BackupStatus,
        review: ReviewSettingsState = ReviewSettingsState(),
        notifications: FixedNotificationsAllowed = FixedNotificationsAllowed(initial = true),
    ) = HomeAlertsSource(
        MutableStateFlow(backup), MutableStateFlow(review), notifications, clockAt(now), ZoneOffset.UTC,
        CoroutineScope(Dispatchers.Unconfined),
    )

    private fun ouvrir(alert: DayAlert?, fontScale: Float = 1f) {
        compose.setContent {
            AvecPolice(fontScale) { TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), dayAlert = alert) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `sans alerte, l ecran du jour n ajoute aucune ligne`() {
        ouvrir(null)
        assertTrue(compose.onAllNodes(hasTestTag(DayTags.ALERT)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `une alerte s affiche sous l en-tete et son geste est appele`() {
        var calls = 0
        ouvrir(DayAlert("Active les notifications pour l’invitation du lundi.", "Autoriser", onAction = { calls++ }))
        compose.onNodeWithTag(DayTags.ALERT).assertIsDisplayed()
        compose.onNodeWithText("Active les notifications pour l’invitation du lundi.").assertIsDisplayed()
        compose.onNodeWithTag(DayTags.ALERT_ACTION).performClick()
        assertEquals(1, calls)
    }

    @Test
    fun `sauvegarde ancienne - le geste ouvre les Reglages`() {
        val alerts = source(tuesday, staleBackup(tuesday))
        compose.setContent {
            TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), dayAlert = rememberDayAlert(alerts, FixedNotificationsAllowed(true)))
        }
        compose.waitForIdle()
        compose.onNodeWithText(staleLine).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(DayTags.ALERT_ACTION).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun `hors lundi, notifications bloquees - aucune ligne`() {
        val blocked = FixedNotificationsAllowed(initial = false)
        val alerts = source(tuesday, BackupStatus(), ReviewSettingsState(enabled = true), blocked)
        compose.setContent {
            TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), dayAlert = rememberDayAlert(alerts, blocked))
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(DayTags.ALERT)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `lundi, notifications bloquees - la ligne propose Autoriser et lance la demande`() {
        val blocked = FixedNotificationsAllowed(initial = false)
        val alerts = source(monday, BackupStatus(), ReviewSettingsState(enabled = true), blocked)
        compose.setContent {
            TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), dayAlert = rememberDayAlert(alerts, blocked))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Active les notifications pour l’invitation du lundi.").assertIsDisplayed()
        compose.onNodeWithText("Autoriser").assertIsDisplayed()
        assertFalse(blocked.asked)
        compose.onNodeWithTag(DayTags.ALERT_ACTION).performClick()
        assertTrue("la fenêtre d'Android a été demandée", blocked.asked)
    }

    @Test
    fun `capture - ligne d alerte sous l en-tete`() {
        ouvrir(DayAlert(staleLine, "Réglages", onAction = {}))
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-alerte.png")
    }

    @Test
    fun `capture - ligne d alerte avec police a 200 pour cent`() {
        ouvrir(DayAlert(staleLine, "Réglages", onAction = {}), fontScale = 2f)
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-alerte-police-200.png")
    }

    // ───── Bilan de la semaine ─────

    private fun reviewSource(
        now: Instant,
        reports: kotlinx.coroutines.flow.Flow<List<app.trackly.review.ReportInfo>>,
        backup: BackupStatus = BackupStatus(),
    ) = HomeAlertsSource(
        MutableStateFlow(backup), MutableStateFlow(ReviewSettingsState()), FixedNotificationsAllowed(true), clockAt(now), ZoneOffset.UTC,
        CoroutineScope(Dispatchers.Unconfined), reports,
    )

    @Test
    fun `bilan a faire - pastille sur Bilan, qui ouvre la conversation de bilan, puis disparait avec le rapport`() {
        val nav = app.trackly.ui.review.ReviewNav()
        val engine = kotlinx.coroutines.runBlocking { app.trackly.review.ReviewSample.engine() }
        val services = app.trackly.review.ReviewServices.inMemory(engine, app.trackly.review.RecordingReviewNotifier())
        val alerts = reviewSource(tuesday, services.reports.observeAll())
        compose.setContent {
            androidx.compose.foundation.layout.Box {
                val due by rememberReviewDue(alerts)
                TracklyRoot(
                    engine = SampleData.engine(), motion = MotionPolicy(reduced = true),
                    dayAlert = rememberDayAlert(alerts, FixedNotificationsAllowed(true)),
                    onOpenReview = { nav.start(app.trackly.ui.review.ReviewDestination.Conversation) },
                    reviewDue = due,
                )
                app.trackly.ui.review.ReviewHost(nav, services, MotionPolicy(reduced = true))
            }
        }
        compose.waitForIdle()
        // Plus de ligne « Bilan de la semaine », son rappel est la pastille du bouton « Bilan ».
        compose.onNodeWithText("Bilan de la semaine").assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasTestTag(DayTags.ALERT)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(DayTags.REVIEW_DUE, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(app.trackly.ui.review.ReviewTags.SCREEN).assertDoesNotExist()

        compose.onNodeWithContentDescription("Bilan, à faire").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(app.trackly.ui.review.ReviewTags.SCREEN).assertIsDisplayed()

        // Rapport produit : la pastille disparaît, le bouton reste.
        kotlinx.coroutines.runBlocking { services.reports.save(java.time.LocalDate.parse("2026-10-26"), tuesday, "<html></html>") }
        compose.waitForIdle()
        nav.back()
        compose.waitForIdle()
        compose.onNodeWithTag(DayTags.REVIEW_DUE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Bilan").assertIsDisplayed()
    }

    @Test
    fun `la ligne de sauvegarde et la pastille du bilan se montrent ensemble`() {
        val backup = MutableStateFlow(staleBackup(tuesday))
        val alerts = HomeAlertsSource(
            backup, MutableStateFlow(ReviewSettingsState()), FixedNotificationsAllowed(true), clockAt(tuesday), ZoneOffset.UTC,
            CoroutineScope(Dispatchers.Unconfined), MutableStateFlow(emptyList()),
        )
        compose.setContent {
            val due by rememberReviewDue(alerts)
            TracklyRoot(
                engine = SampleData.engine(), motion = MotionPolicy(reduced = true),
                dayAlert = rememberDayAlert(alerts, FixedNotificationsAllowed(true)), onOpenReview = {}, reviewDue = due,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText(staleLine).assertIsDisplayed()
        compose.onNodeWithTag(DayTags.REVIEW_DUE, useUnmergedTree = true).assertExists()

        backup.value = BackupStatus(connected = true, accountEmail = "a@b.c", lastSuccessAt = tuesday)
        compose.waitForIdle()
        compose.onNodeWithText(staleLine).assertDoesNotExist()
        compose.onNodeWithTag(DayTags.REVIEW_DUE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `capture - bilan a faire, pastille sur Bilan`() {
        compose.setContent {
            TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), onOpenReview = {}, reviewDue = true)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-bilan-a-faire.png")
    }
}
