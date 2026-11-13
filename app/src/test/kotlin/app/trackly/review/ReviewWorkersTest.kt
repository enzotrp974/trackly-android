package app.trackly.review

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.trackly.backup.MutableClock
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Application de test : les rappels du bilan branchés sur un faux notifieur et une horloge réglable. */
class ReviewTestApplication : Application(), ReviewReminderWorker.Host {
    val clock = MutableClock(Instant.parse("2026-11-02T08:00:00Z")) // lundi 2 novembre, 9 h (heure locale)
    val reports = InMemoryReportRepository()
    val notifier = RecordingReviewNotifier()
    val settings = InMemoryReviewSettings()
    var reschedules = 0

    override val reviewReminders: ReviewReminders by lazy { ReviewReminders(ReviewPeriods(reports, InMemoryReviewHistory()), notifier, settings, clock) }

    override fun rescheduleReview() {
        reschedules++
    }
}

/** Invitation du lundi et relance du soir, testées avec `work-testing` (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(application = ReviewTestApplication::class)
class ReviewWorkersTest {
    private val app: ReviewTestApplication = ApplicationProvider.getApplicationContext()

    private fun worker(kind: ReminderKind) = TestListenableWorkerBuilder<ReviewReminderWorker>(app as Context)
        .setInputData(Data.Builder().putString("rappel", kind.code).build())
        .build()

    private fun at(local: String) {
        app.clock.instant = ZonedDateTime.parse("$local[Europe/Zurich]").toInstant()
    }

    @Before
    fun reset() {
        app.notifier.invitations.clear()
        app.notifier.reminders.clear()
        app.reschedules = 0
        app.settings.setEnabled(true)
        app.settings.setInviteTime(LocalTime.of(9, 0))
    }

    @Test
    fun `le lundi a 9 h - l invitation est envoyee pour la periode depuis le dernier bilan`() = runBlocking {
        at("2026-11-02T09:02:00+01:00")
        app.reports.save(LocalDate.parse("2026-10-19"), app.clock.instant(), "<html></html>") // dernier bilan : semaine du 19 au 25 octobre

        val result = worker(ReminderKind.Invitation).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(LocalDate.parse("2026-10-26") to LocalDate.parse("2026-11-02")), app.notifier.invitations.map { it.start to it.end })
        assertTrue(app.notifier.reminders.isEmpty())
        assertEquals(1, app.reschedules) // recalé sur le lundi suivant
    }

    @Test
    fun `un bilan fait le dimanche dispense de l invitation du lundi, un bilan fait le samedi non`() = runBlocking {
        at("2026-11-02T09:02:00+01:00")
        app.reports.save(LocalDate.parse("2026-10-26"), app.clock.instant(), "<html></html>", LocalDate.parse("2026-10-31"))
        worker(ReminderKind.Invitation).doWork()
        assertEquals(listOf(LocalDate.parse("2026-11-01")), app.notifier.invitations.map { it.start }) // dimanche et lundi restent à faire

        app.notifier.invitations.clear()
        app.reports.save(LocalDate.parse("2026-10-26"), app.clock.instant(), "<html></html>", LocalDate.parse("2026-11-01"))
        worker(ReminderKind.Invitation).doWork()
        assertTrue(app.notifier.invitations.isEmpty())
    }

    @Test
    fun `le soir - la relance est envoyee seulement si le bilan de la semaine n est pas fait`() = runBlocking {
        at("2026-11-02T19:05:00+01:00")

        worker(ReminderKind.Reminder).doWork()
        assertEquals(1, app.notifier.reminders.size)

        // Bilan fait (un rapport existe pour cette semaine) : plus de relance.
        app.reports.save(LocalDate.parse("2026-10-26"), app.clock.instant(), "<html></html>")
        worker(ReminderKind.Reminder).doWork()
        assertEquals(1, app.notifier.reminders.size)
        assertEquals(2, app.reschedules)
    }

    @Test
    fun `un bilan d une autre semaine ne dispense pas de celui-ci`() = runBlocking {
        at("2026-11-02T19:05:00+01:00")
        app.reports.save(LocalDate.parse("2026-10-19"), app.clock.instant(), "<html></html>")
        worker(ReminderKind.Reminder).doWork()
        assertEquals(1, app.notifier.reminders.size)
    }

    @Test
    fun `invitation deja faite - pas d invitation non plus`() = runBlocking {
        at("2026-11-02T09:00:00+01:00")
        app.reports.save(LocalDate.parse("2026-10-26"), app.clock.instant(), "<html></html>")
        worker(ReminderKind.Invitation).doWork()
        assertTrue(app.notifier.invitations.isEmpty())
    }

    @Test
    fun `rappels desactives - rien n est envoye`() = runBlocking {
        app.settings.setEnabled(false)
        at("2026-11-02T09:00:00+01:00")
        worker(ReminderKind.Invitation).doWork()
        at("2026-11-02T19:00:00+01:00")
        worker(ReminderKind.Reminder).doWork()
        assertTrue(app.notifier.invitations.isEmpty() && app.notifier.reminders.isEmpty())
    }

    @Test
    fun `execution tres en retard - pas de rappel perime`() = runBlocking {
        // Le téléphone dormait : le travail du lundi s'exécute le mercredi.
        at("2026-11-04T10:00:00+01:00")
        worker(ReminderKind.Invitation).doWork()
        at("2026-11-04T20:00:00+01:00")
        worker(ReminderKind.Reminder).doWork()
        assertTrue(app.notifier.invitations.isEmpty() && app.notifier.reminders.isEmpty())
        assertEquals(2, app.reschedules) // mais la suite est replanifiée
    }

    @Test
    fun `une heure d invitation modifiee est respectee`() = runBlocking {
        app.settings.setInviteTime(LocalTime.of(7, 30))
        at("2026-11-02T07:35:00+01:00")
        worker(ReminderKind.Invitation).doWork()
        assertEquals(1, app.notifier.invitations.size)
    }

    @Test
    fun `type de rappel inconnu - echec`() = runBlocking {
        val result = TestListenableWorkerBuilder<ReviewReminderWorker>(app as Context).build().doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }
}

/** Planification : deux travaux périodiques nommés, recalés sur le lundi à l'heure choisie. */
@RunWith(AndroidJUnit4::class)
class ReviewScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = TrackingDay.ZONE

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun info(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().single()
    private fun next(info: WorkInfo) = Instant.ofEpochMilli(info.nextScheduleTimeMillis).atZone(zone)

    @Test
    fun `deux travaux, le lundi suivant a 9 h et a 19 h`() {
        val clock = MutableClock(Instant.parse("2026-11-03T11:00:00Z")) // mardi 3 novembre
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState())
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState()) // idempotent

        val invitation = info(ReviewSchedule.INVITATION_WORK)
        val reminder = info(ReviewSchedule.REMINDER_WORK)
        assertEquals(WorkInfo.State.ENQUEUED, invitation.state)
        assertEquals(Duration.ofDays(7).toMillis(), invitation.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(ZonedDateTime.parse("2026-11-09T09:00+01:00[Europe/Zurich]"), next(invitation))
        assertEquals(ZonedDateTime.parse("2026-11-09T19:00+01:00[Europe/Zurich]"), next(reminder))
    }

    @Test
    fun `heure modifiee - l invitation est replanifiee sans doublon`() {
        val clock = MutableClock(Instant.parse("2026-11-03T11:00:00Z"))
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState())
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState(inviteTime = LocalTime.of(7, 30)))

        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReviewSchedule.INVITATION_WORK).get().size)
        assertEquals(ZonedDateTime.parse("2026-11-09T07:30+01:00[Europe/Zurich]"), next(info(ReviewSchedule.INVITATION_WORK)))
        assertEquals(ZonedDateTime.parse("2026-11-09T19:00+01:00[Europe/Zurich]"), next(info(ReviewSchedule.REMINDER_WORK)))
    }

    @Test
    fun `rappels desactives - les deux travaux sont annules`() {
        val clock = MutableClock(Instant.parse("2026-11-03T11:00:00Z"))
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState())
        ReviewSchedule.schedule(context, clock, zone, ReviewSettingsState(enabled = false))
        for (name in listOf(ReviewSchedule.INVITATION_WORK, ReviewSchedule.REMINDER_WORK)) {
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
        }
    }

    @Test
    fun `calendrier - prochain lundi, jamais le jour meme passe`() {
        fun next(iso: String, at: LocalTime) = ReviewCalendar.nextRun(Instant.parse(iso), zone, at).toString()
        // Lundi 8 h (heure locale) avant 9 h : le jour même.
        assertEquals("2026-11-02T09:00+01:00[Europe/Zurich]", next("2026-11-02T07:00:00Z", LocalTime.of(9, 0)))
        // Lundi 9 h 01 : le lundi suivant.
        assertEquals("2026-11-09T09:00+01:00[Europe/Zurich]", next("2026-11-02T08:01:00Z", LocalTime.of(9, 0)))
        // Dimanche soir : le lendemain.
        assertEquals("2026-11-02T09:00+01:00[Europe/Zurich]", next("2026-11-01T20:00:00Z", LocalTime.of(9, 0)))
        // Changement d'heure (29 mars 2027 : passage à l'heure d'été) : toujours 9 h locale.
        assertEquals("2027-03-29T09:00+02:00[Europe/Zurich]", next("2027-03-25T12:00:00Z", LocalTime.of(9, 0)))
    }

    @Test
    fun `reglages - heure ramenee dans la plage, avant la relance du soir`() {
        assertEquals(LocalTime.of(5, 0), LocalTime.of(3, 0).coerceInvite())
        assertEquals(LocalTime.of(17, 30), LocalTime.of(20, 0).coerceInvite())
        assertEquals(LocalTime.of(9, 0), LocalTime.of(9, 0).coerceInvite())
        assertFalse(ReviewSettingsState.LATEST.isAfter(REMINDER_TIME))
        val prefs = SharedReviewSettings(context)
        prefs.setInviteTime(LocalTime.of(8, 30))
        prefs.setEnabled(false)
        val again = SharedReviewSettings(context) // relu après redémarrage
        assertEquals(LocalTime.of(8, 30), again.state.value.inviteTime)
        assertFalse(again.state.value.enabled)
    }
}
