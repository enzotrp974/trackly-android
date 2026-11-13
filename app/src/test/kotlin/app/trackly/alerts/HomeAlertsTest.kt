package app.trackly.alerts

import app.trackly.backup.BackupStatus
import app.trackly.core.time.TrackingDay
import app.trackly.review.ReviewSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Alertes discrètes : changements pas copiés sur Drive depuis 36 h, notifications bloquées (le lundi sur l'écran du jour). */
class HomeAlertsTest {
    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private val connected = BackupStatus(connected = true, accountEmail = "a@b.c", lastSuccessAt = now.minus(Duration.ofHours(10)))

    /** Des changements attendent d'être copiés depuis [hours]. */
    private fun waiting(hours: Long, seconds: Long = 0) =
        connected.copy(pending = true, unsavedSince = now.minus(Duration.ofHours(hours)).minusSeconds(seconds))

    @Test
    fun `changements recents pas encore copies - rien a signaler`() {
        assertNull(HomeAlerts.staleBackup(connected, now))
        assertNull(HomeAlerts.staleBackup(waiting(36), now)) // 36 h pile : pas encore
    }

    @Test
    fun `rien de change depuis la derniere copie - jamais d alerte, meme ancienne`() {
        assertNull(HomeAlerts.staleBackup(connected.copy(lastSuccessAt = now.minus(Duration.ofDays(9))), now))
    }

    @Test
    fun `changements pas copies depuis plus de 36 heures alors que Drive est connecte - alerte`() {
        val line = HomeAlerts.staleBackup(waiting(36, seconds = 1), now)
        assertNotNull(line)
        assertEquals(AlertKind.StaleBackup, line!!.kind)
        assertTrue(line.text, line.text.contains("36 heures"))
    }

    @Test
    fun `Drive non connecte - rien a surveiller`() {
        assertNull(HomeAlerts.staleBackup(BackupStatus(connected = false, lastSuccessAt = now.minus(Duration.ofDays(9))), now))
    }

    @Test
    fun `connecte mais jamais reussi - alerte seulement si des essais ont echoue`() {
        assertNull(HomeAlerts.staleBackup(BackupStatus(connected = true), now))
        val line = HomeAlerts.staleBackup(BackupStatus(connected = true, lastFailureAt = now.minus(Duration.ofHours(2))), now)
        assertEquals("Aucune sauvegarde Drive n’a réussi.", line!!.text)
    }

    @Test
    fun `rappels voulus mais notifications bloquees - alerte, sinon rien`() {
        assertNotNull(HomeAlerts.notificationsOff(ReviewSettingsState(enabled = true), allowed = false))
        assertNull(HomeAlerts.notificationsOff(ReviewSettingsState(enabled = true), allowed = true))
        assertNull(HomeAlerts.notificationsOff(ReviewSettingsState(enabled = false), allowed = false))
    }

    @Test
    fun `l ecran du jour ne montre la ligne des notifications que le lundi, la sauvegarde toujours`() {
        val mondayInstant = Instant.parse("2026-11-02T08:00:00Z")
        val monday = HomeAlerts.of(connected.copy(pending = true, unsavedSince = mondayInstant.minus(Duration.ofHours(50))), ReviewSettingsState(), notificationsAllowed = false, now = ZonedDateTime.ofInstant(mondayInstant, TrackingDay.ZONE))
        val stale = waiting(50)
        assertTrue(monday.isMonday)
        assertEquals(listOf(AlertKind.StaleBackup, AlertKind.NotificationsOff), monday.forDayScreen.map { it.kind })

        val tuesday = HomeAlerts.of(stale, ReviewSettingsState(), notificationsAllowed = false, now = ZonedDateTime.ofInstant(now, TrackingDay.ZONE))
        assertFalse(tuesday.isMonday)
        assertEquals(listOf(AlertKind.StaleBackup), tuesday.forDayScreen.map { it.kind })
        // Les Réglages, eux, signalent tout, tous les jours.
        assertEquals(listOf(AlertKind.StaleBackup, AlertKind.NotificationsOff), tuesday.forSettings.map { it.kind })
    }

    @Test
    fun `la source recalcule quand la sauvegarde, la permission ou l heure changent`() {
        val backup = MutableStateFlow(waiting(10))
        val review = MutableStateFlow(ReviewSettingsState())
        val permission = FixedNotificationsAllowed(initial = true)
        var instant = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = instant
        }
        val source = HomeAlertsSource(backup, review, permission, clock, TrackingDay.ZONE, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(source.alerts.value.forSettings.isEmpty())

        instant = now.plus(Duration.ofHours(30)) // changements en attente depuis 40 h
        source.refresh()
        assertEquals(listOf(AlertKind.StaleBackup), source.alerts.value.forSettings.map { it.kind })

        backup.value = connected.copy(lastSuccessAt = instant)
        assertTrue(source.alerts.value.forSettings.isEmpty())

        permission.set(false)
        assertEquals(listOf(AlertKind.NotificationsOff), source.alerts.value.forSettings.map { it.kind })
        review.value = ReviewSettingsState(enabled = false)
        assertTrue(source.alerts.value.forSettings.isEmpty())
    }

    // ───── Bilan de la semaine ─────

    private val mondayInvite = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, TrackingDay.ZONE)
    private val week = java.time.LocalDate.parse("2026-10-26")

    private fun report(start: String, end: String? = null) = app.trackly.review.ReportInfo(
        1, java.time.LocalDate.parse(start), Instant.EPOCH, end?.let(java.time.LocalDate::parse) ?: java.time.LocalDate.parse(start).plusDays(6),
    )

    @Test
    fun `bilan a faire - une pastille sur Bilan, plus de ligne a l accueil, absent des Reglages`() {
        val stale = connected.copy(pending = true, unsavedSince = mondayInvite.toInstant().minus(Duration.ofHours(50)))
        val alerts = HomeAlerts.of(stale, ReviewSettingsState(), notificationsAllowed = true, now = mondayInvite, reports = emptyList())
        // Le rappel du bilan est la pastille du bouton « Bilan » ; la ligne de l'accueil garde la sauvegarde.
        assertEquals(listOf(AlertKind.StaleBackup), alerts.forDayScreen.map { it.kind })
        assertEquals(listOf(AlertKind.StaleBackup), alerts.forSettings.map { it.kind })
        assertTrue(alerts.reviewDue)

        val only = HomeAlerts.of(connected, ReviewSettingsState(), notificationsAllowed = true, now = mondayInvite, reports = emptyList())
        assertTrue(only.forDayScreen.isEmpty())
        assertTrue(only.reviewDue)
        assertEquals(AlertKind.ReviewDue, only.review?.kind)
    }

    @Test
    fun `bilan - rien avant l invitation, rien une fois fait, rien si les rappels sont coupes, rien si inconnu`() {
        val early = mondayInvite.minusMinutes(1)
        assertNull(HomeAlerts.of(connected, ReviewSettingsState(), true, early, listOf(report("2026-10-19"))).review)
        assertNull(HomeAlerts.of(connected, ReviewSettingsState(), true, mondayInvite, listOf(report("2026-10-26"))).review)
        assertNull(HomeAlerts.of(connected, ReviewSettingsState(enabled = false), true, mondayInvite, emptyList()).review)
        assertNull(HomeAlerts.of(connected, ReviewSettingsState(), true, mondayInvite).review)
    }

    @Test
    fun `la pastille du bilan disparait des que le rapport est produit`() {
        val reports = MutableStateFlow<List<app.trackly.review.ReportInfo>>(emptyList())
        val clock = Clock.fixed(mondayInvite.toInstant().plus(Duration.ofDays(2)), ZoneOffset.UTC)
        val source = HomeAlertsSource(
            MutableStateFlow(BackupStatus()), MutableStateFlow(ReviewSettingsState()), FixedNotificationsAllowed(true), clock,
            TrackingDay.ZONE, CoroutineScope(Dispatchers.Unconfined), reports,
        )
        assertTrue(source.alerts.value.reviewDue)
        assertTrue(source.alerts.value.forDayScreen.isEmpty())

        reports.value = listOf(app.trackly.review.ReportInfo(1, week, mondayInvite.toInstant().plus(Duration.ofDays(2))))
        assertTrue(!source.alerts.value.reviewDue)
    }
}
