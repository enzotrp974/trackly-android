package app.trackly.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** Application de test : la sauvegarde de nuit branchée sur le faux Drive. */
class BackupTestApplication : Application(), BackupWorker.Host, AutoBackupWorker.Host {
    lateinit var fixture: BackupFixture
    val notifier = RecordingNotifier()
    var reschedules = 0
    override val nightlyBackup: NightlyBackup by lazy { NightlyBackup(fixture.runner, notifier, fixture.status, fixture.clock) }
    override val autoBackup: AutoBackup by lazy {
        AutoBackup(fixture.runner, fixture.status, notifier, FakeAutoScheduling(fixture.clock), fixture.clock)
    }
    override fun rescheduleBackup() {
        reschedules++
    }
}

class RecordingNotifier : BackupNotifier {
    val reconnect = mutableListOf<String>()
    val failing = mutableListOf<String>()
    override fun askToReconnect(message: String) {
        reconnect += message
    }
    override fun reportFailing(message: String) {
        failing += message
    }
}

/** Travail de nuit testé avec `work-testing` (Robolectric), sur une vraie base et le faux Drive. */
@RunWith(AndroidJUnit4::class)
@Config(application = BackupTestApplication::class)
class BackupWorkerTest {
    private val app: BackupTestApplication = ApplicationProvider.getApplicationContext()
    private lateinit var f: BackupFixture

    @Before
    fun setUp() = runBlocking {
        f = BackupFixture()
        app.fixture = f
        f.seed()
    }

    @After
    fun close() = f.close()

    private fun worker(attempt: Int = 0) =
        TestListenableWorkerBuilder<BackupWorker>(app as Context).setRunAttemptCount(attempt).build()

    @Test
    fun `la nuit - sauvegarde envoyee, travail reussi et recale`() = runBlocking<Unit> {
        val folder = f.connectWithFolder()
        val result = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, f.drive.childrenOf(folder.id).size)
        assertEquals(1, app.reschedules)
    }

    @Test
    fun `interaction Google impossible en arriere-plan - notification reconnecte Google`() = runBlocking<Unit> {
        f.connectWithFolder()
        f.auth.needsUserAction()
        val result = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, app.notifier.reconnect.size)
        assertTrue(f.status.status.value.needsReconnect)
    }

    @Test
    fun `dossier introuvable - notification au lieu d un echec silencieux`() = runBlocking<Unit> {
        f.status.markConnected(null)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, app.notifier.reconnect.size)
    }

    @Test
    fun `Drive indisponible - nouvel essai, puis alerte si rien depuis 2 jours`() = runBlocking<Unit> {
        f.connectWithFolder()
        f.drive.failWith = 503
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(0, app.reschedules)
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 3).doWork())
        assertTrue("premier échec : pas encore « depuis 2 jours »", app.notifier.failing.isEmpty())
        f.clock.instant = f.clock.instant.plus(Duration.ofHours(49))
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 3).doWork())
        assertEquals(1, app.notifier.failing.size)
    }

    @Test
    fun `sans connexion Google - rien a faire`() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(f.drive.requests.isEmpty())
    }

    @Test
    fun `la nuit sans changement depuis la derniere copie - rien ne part`() = runBlocking<Unit> {
        val folder = f.connectWithFolder()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        f.drive.requests.clear()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(f.drive.requests.isEmpty())
        assertEquals(1, f.drive.childrenOf(folder.id).size)
        assertEquals(2, app.reschedules)
    }

    @Test
    fun `travail automatique - la copie part depuis l arriere-plan`() = runBlocking<Unit> {
        val folder = f.connectWithFolder()
        val result = TestListenableWorkerBuilder<AutoBackupWorker>(app as Context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, f.drive.childrenOf(folder.id).size)
    }
}

/** Travail unique de la sauvegarde automatique : un seul à la fois, différé, réseau requis. */
@RunWith(AndroidJUnit4::class)
class AutoBackupScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `plusieurs changements - un seul travail programme, quelques minutes plus tard`() {
        val scheduling = AutoBackupSchedule.scheduling(context)
        val before = System.currentTimeMillis()
        scheduling.request(AutoBackupPolicy.QUIET)
        scheduling.request(AutoBackupPolicy.QUIET)
        scheduling.request(AutoBackupPolicy.QUIET)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutoBackupSchedule.WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        val delay = info.nextScheduleTimeMillis - before
        assertTrue("délai : $delay ms", delay in Duration.ofMinutes(2).toMillis()..Duration.ofMinutes(4).toMillis())

        scheduling.cancel()
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutoBackupSchedule.WORK_NAME).get()
        assertTrue(after.all { it.state == WorkInfo.State.CANCELLED })
    }
}

/** Planification : un seul travail périodique, réseau requis, vers 3 h. */
@RunWith(AndroidJUnit4::class)
class BackupScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `un seul travail periodique avec reseau requis`() {
        val clock = MutableClock(Instant.parse("2026-11-02T11:30:00Z"))
        BackupSchedule.schedule(context, clock, TrackingDay.ZONE)
        BackupSchedule.schedule(context, clock, TrackingDay.ZONE) // idempotent

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupSchedule.WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(Duration.ofHours(24).toMillis(), info.periodicityInfo!!.repeatIntervalMillis)
        // Prochaine exécution visée : 3 h du matin, heure locale.
        val next = Instant.ofEpochMilli(info.nextScheduleTimeMillis).atZone(TrackingDay.ZONE)
        assertEquals(ZonedDateTime.parse("2026-11-03T03:00+01:00[Europe/Zurich]"), next)

        BackupSchedule.cancel(context)
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupSchedule.WORK_NAME).get()
        assertTrue(after.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `prochaine nuit - jamais deux fois la meme nuit`() {
        fun next(iso: String) = BackupSchedule.nextRun(Instant.parse(iso), TrackingDay.ZONE).toString()
        // 12 h 30 → 3 h le lendemain.
        assertEquals("2026-11-03T03:00+01:00[Europe/Zurich]", next("2026-11-02T11:30:00Z"))
        // 1 h 00 → 3 h le même matin.
        assertEquals("2026-11-03T03:00+01:00[Europe/Zurich]", next("2026-11-03T00:00:00Z"))
        // 2 h 58, à la fin d'une exécution → nuit suivante.
        assertEquals("2026-11-04T03:00+01:00[Europe/Zurich]", next("2026-11-03T01:58:00Z"))
        // Changement d'heure (29 mars 2026) : toujours 3 h locale.
        assertEquals("2026-03-29T03:00+02:00[Europe/Zurich]", next("2026-03-28T20:00:00Z"))
    }
}

/** Application de test : la sauvegarde automatique branchée sur le vrai WorkManager (de test) et le faux Drive. */
class AutoBackupChainApplication : Application(), AutoBackupWorker.Host {
    lateinit var fixture: BackupFixture
    var maxRetries = 3
    val notifier = RecordingNotifier()
    override val autoBackup: AutoBackup by lazy {
        AutoBackup(fixture.runner, fixture.status, notifier, AutoBackupSchedule.scheduling(this), fixture.clock, maxRetries = maxRetries)
    }
}

/**
 * La chaîne réelle du travail automatique dans WorkManager (`WorkManagerTestInitHelper` + `TestDriver`) : `KEEP` à
 * chaque changement, puis la suite chaînée par `APPEND_OR_REPLACE` depuis le passage en cours. Le délai de la suite
 * compte depuis la fin de ce passage, et une série ne donne qu'une copie.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = AutoBackupChainApplication::class)
class AutoBackupChainTest {
    private val app: AutoBackupChainApplication = ApplicationProvider.getApplicationContext()
    private lateinit var f: BackupFixture
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(app)
        f = BackupFixture()
        app.fixture = f
        f.seed()
        f.drive.now = f.clock.instant
    }

    @After
    fun close() = f.close()

    private fun chain(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(AutoBackupSchedule.WORK_NAME).get()

    /** Lance un passage comme WorkManager à son heure, et attend sa fin (un `CoroutineWorker` finit hors du fil de test). */
    private fun runNow(info: WorkInfo) = runBlocking {
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        driver.setAllConstraintsMet(info.id)
        driver.setInitialDelayMet(info.id)
        withTimeout(20_000) {
            while (!workManager.getWorkInfoById(info.id).get()!!.state.isFinished) delay(10)
        }
    }

    /** Un essai du passage, comme WorkManager à son heure ; attend qu'il soit fini ou reprogrammé pour un nouvel essai. */
    private fun runAttempt(id: java.util.UUID) = runBlocking {
        val before = workManager.getWorkInfoById(id).get()!!.runAttemptCount
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        withTimeout(20_000) {
            while (true) {
                val info = workManager.getWorkInfoById(id).get()!!
                if (info.state.isFinished || (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > before)) break
                delay(10)
            }
        }
    }

    @Test
    fun `suite chainee pendant les essais d une copie en echec - elle passe quand meme apres le dernier essai (SAUV-05)`() {
        app.maxRetries = 1
        f.connectWithFolder()
        val auto = app.autoBackup
        auto.onChange()
        val first = chain().single()
        f.drive.failWith = 503
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(3))
        f.drive.now = f.clock.instant

        runAttempt(first.id) // Drive indisponible : nouvel essai plus tard.
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(first.id).get()!!.state)
        // Un repas noté pendant l'attente du dernier essai : sa suite est chaînée derrière le passage.
        auto.onChange()
        val next = chain().single { it.id != first.id }
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(3))
        f.drive.now = f.clock.instant
        runAttempt(first.id) // dernier essai, toujours en échec

        assertTrue("l'échec reste dans l'état de la sauvegarde", f.status.status.value.lastFailureAt != null)
        val chained = workManager.getWorkInfoById(next.id).get()!!
        assertTrue("la suite n'échoue pas avec le passage précédent : ${chained.state}", chained.state == WorkInfo.State.ENQUEUED)
        f.drive.failWith = null
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(3))
        f.drive.now = f.clock.instant
        runNow(chained)
        assertEquals(1, f.drive.uploads())
        assertFalse(f.status.status.value.pending)
        assertTrue("puis plus rien", chain().all { it.state.isFinished })
    }

    @Test
    fun `deux changements a 2 minutes d intervalle - une seule copie, 3 minutes apres le second`() {
        val folder = f.connectWithFolder()
        val auto = app.autoBackup
        auto.onChange() // premier repas, t = 0
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(2))
        auto.onChange() // second repas, t = 2 min : KEEP, toujours un seul travail
        val first = chain().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)

        // t = 3 min : le premier passage tourne, voit un changement récent et chaîne la suite sans rien envoyer.
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(1))
        runNow(first)
        val endOfFirst = System.currentTimeMillis()
        assertEquals(0, f.drive.uploads())
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(first.id).get()!!.state)
        val next = chain().single { it.id != first.id }
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        // Délai de la suite : 2 minutes (fin du calme), comptées depuis la fin du passage précédent.
        val delay = next.nextScheduleTimeMillis - endOfFirst
        assertTrue("délai : $delay ms", delay in Duration.ofMinutes(2).minusSeconds(5).toMillis()..Duration.ofMinutes(2).plusSeconds(1).toMillis())

        // t = 5 min, 3 minutes après le second repas : la copie part, une seule.
        f.clock.instant = f.clock.instant.plus(Duration.ofMinutes(2))
        f.drive.now = f.clock.instant
        runNow(next)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(next.id).get()!!.state)
        assertEquals(1, f.drive.uploads())
        assertEquals(1, f.drive.childrenOf(folder.id).size)
        assertFalse(f.status.status.value.needsBackup)
        assertTrue("plus rien en attente", chain().all { it.state.isFinished })
    }
}
