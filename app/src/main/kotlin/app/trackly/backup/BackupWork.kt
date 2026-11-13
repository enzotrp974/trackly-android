package app.trackly.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Planification de la sauvegarde de nuit : **un seul** travail périodique (24 h), nommé, avec
 * réseau requis et batterie non faible. L'heure visée (3 h, heure locale) n'est qu'approximative :
 * Android regroupe les tâches de fond (Doze, mise en veille Samsung).
 */
object BackupSchedule {
    const val WORK_NAME = "trackly-sauvegarde-nuit"
    val NIGHT: LocalTime = LocalTime.of(3, 0)

    /** Prochaine heure de nuit à au moins une heure de [now] (pas de double passage la même nuit). */
    fun nextRun(now: Instant, zone: ZoneId, at: LocalTime = NIGHT): ZonedDateTime {
        val earliest = now.plus(Duration.ofHours(1)).atZone(zone)
        var next = earliest.with(at)
        if (next.isBefore(earliest)) next = next.plusDays(1).with(at)
        return next
    }

    /**
     * Planifie (ou replanifie) le travail. Idempotent : `UPDATE` garde un seul travail et
     * n'interrompt pas une exécution en cours ; la prochaine exécution est recalée sur la nuit.
     */
    fun schedule(context: Context, clock: Clock, zone: ZoneId) {
        val next = nextRun(clock.instant(), zone)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setNextScheduleTimeOverride(next.toInstant().toEpochMilli())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

/** Alertes de la sauvegarde (implémentation Android : [AndroidBackupNotifier]). */
interface BackupNotifier {
    /** Google demande une action : « Reconnecte Google Drive », ouvre Réglages › Sauvegarde. */
    fun askToReconnect(message: String)

    /** Aucune sauvegarde réussie depuis longtemps. */
    fun reportFailing(message: String)
}

/**
 * Suite à donner au résultat d'une sauvegarde lancée en arrière-plan (nuit ou automatique). Une interaction Google
 * impossible en arrière-plan devient la notification « Reconnecte Google Drive », jamais un échec silencieux. Une
 * panne passagère est réessayée, puis signalée seulement si Drive a du retard depuis [staleAfter]. Un refus du
 * garde-fou se lit dans l'état de la sauvegarde, sans notification.
 *
 * Essais épuisés, ou action de l'utilisateur attendue : le travail **finit en succès**. L'échec reste dans
 * l'état de la sauvegarde ([BackupStatusStore.recordFailure], fait par la copie) et dans la notification ; un
 * `failure()` ferait échouer aussi la suite chaînée derrière ce passage par un changement arrivé entre-temps.
 */
internal class BackgroundOutcome(
    private val notifier: BackupNotifier,
    private val status: BackupStatusStore,
    private val clock: Clock,
    private val maxRetries: Int,
    private val staleAfter: Duration,
) {
    fun of(result: BackupResult, attempt: Int): ListenableWorker.Result = when (result) {
        is BackupResult.Success -> ListenableWorker.Result.success()
        is BackupResult.Failure -> when (result.kind) {
            // Rien à sauvegarder tant que Drive n'est pas connecté.
            FailureKind.NotConnected -> ListenableWorker.Result.success()
            FailureKind.Refused -> ListenableWorker.Result.success()
            FailureKind.NeedsReconnect, FailureKind.Misconfigured, FailureKind.FolderMissing -> {
                notifier.askToReconnect(result.message)
                ListenableWorker.Result.success()
            }
            else -> if (result.retryable && attempt < maxRetries) {
                ListenableWorker.Result.retry()
            } else {
                if (longFailing()) notifier.reportFailing(result.message)
                ListenableWorker.Result.success()
            }
        }
    }

    /**
     * La sauvegarde ne marche plus : les essais échouent depuis plus de [staleAfter] (compté depuis le premier échec,
     * jamais « depuis 2 jours » dès le premier) et des changements attendent encore.
     */
    private fun longFailing(): Boolean {
        val current = status.status.value
        val since = current.failingSince ?: return false
        return current.needsBackup && Duration.between(since, clock.instant()) > staleAfter
    }
}

/**
 * Logique de la sauvegarde de nuit, hors du `Worker` pour être testée simplement. Elle n'est plus qu'un filet :
 * la sauvegarde automatique copie la base quelques minutes après chaque changement. Sans changement depuis la
 * dernière copie réussie (signalé, ou vu en comparant la base à cette copie), rien ne part.
 */
class NightlyBackup(
    private val runner: BackupRunner,
    notifier: BackupNotifier,
    status: BackupStatusStore,
    clock: Clock,
    maxRetries: Int = 3,
    staleAfter: Duration = Duration.ofHours(48),
) {
    private val outcome = BackgroundOutcome(notifier, status, clock, maxRetries, staleAfter)

    suspend fun run(attempt: Int): ListenableWorker.Result {
        val result = runner.backUpIfNeeded(checkContents = true) ?: return ListenableWorker.Result.success()
        return outcome.of(result, attempt)
    }
}

/**
 * Travail WorkManager de la sauvegarde de nuit : une coquille mince autour de [NightlyBackup],
 * fourni par l'application ([Host]).
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    interface Host {
        val nightlyBackup: NightlyBackup

        /** Recale la prochaine exécution sur la nuit suivante. */
        fun rescheduleBackup()
    }

    override suspend fun doWork(): Result {
        val host = applicationContext as? Host ?: return Result.failure()
        val result = host.nightlyBackup.run(runAttemptCount)
        if (result !is Result.Retry) runCatching { host.rescheduleBackup() }
        return result
    }
}

// ───── Sauvegarde automatique, quelques minutes après chaque changement ─────

/** Planification du travail de la sauvegarde automatique (WorkManager en vrai, faux en test). */
interface AutoBackupScheduling {
    /** Programme le travail dans [delay], sauf s'il est déjà programmé ou en cours : il copiera aussi ce changement. */
    fun request(delay: Duration)

    /** Depuis le travail en cours : un nouveau passage dans [delay], après la fin de celui-ci. */
    fun followUp(delay: Duration)

    fun cancel()
}

/**
 * Quand copier. Une série de changements rapprochés ne donne qu'une copie : on attend [QUIET] sans changement,
 * mais jamais plus de [MAX_WAIT] après le premier changement pas encore copié (une longue conversation n'empêche pas
 * la copie).
 */
object AutoBackupPolicy {
    val QUIET: Duration = Duration.ofMinutes(3)
    val MAX_WAIT: Duration = Duration.ofMinutes(15)

    sealed interface Decision {
        /** Rien à copier, ou impossible sans l'utilisateur (Drive pas connecté, reconnexion demandée). */
        data object Skip : Decision

        /** Des changements arrivent encore : repasser dans [delay]. */
        data class Wait(val delay: Duration) : Decision

        data object BackUp : Decision
    }

    /** La sauvegarde automatique peut-elle tourner ? La reconnexion attend l'utilisateur (notification, réglages). */
    fun enabled(status: BackupStatus): Boolean = status.connected && !status.needsReconnect

    fun decide(status: BackupStatus, now: Instant): Decision {
        if (!enabled(status) || !status.needsBackup) return Decision.Skip
        val lastChange = status.lastChangeAt ?: return Decision.BackUp
        val quietFor = Duration.between(lastChange, now)
        val waitedFor = status.unsavedSince?.let { Duration.between(it, now) } ?: Duration.ZERO
        if (quietFor >= QUIET || waitedFor >= MAX_WAIT) return Decision.BackUp
        val untilQuiet = QUIET.minus(quietFor)
        val untilMax = MAX_WAIT.minus(waitedFor)
        return Decision.Wait(if (untilQuiet < untilMax) untilQuiet else untilMax)
    }
}

/**
 * Sauvegarde automatique : chaque changement de la base ([onChange]) programme un travail unique, qui copie la base
 * une fois le calme revenu ([AutoBackupPolicy]). Rien ne part sans changement. Le jeton Google est demandé sans écran ;
 * si Google veut un écran, c'est la notification « Reconnecte Google Drive », et la sauvegarde automatique s'arrête
 * jusqu'à la reconnexion.
 */
class AutoBackup(
    private val runner: BackupRunner,
    private val status: BackupStatusStore,
    notifier: BackupNotifier,
    private val scheduling: AutoBackupScheduling,
    private val clock: Clock,
    maxRetries: Int = 3,
    staleAfter: Duration = Duration.ofHours(48),
) {
    private val outcome = BackgroundOutcome(notifier, status, clock, maxRetries, staleAfter)

    /**
     * Le dernier passage commencé dans ce processus (identifiant du travail WorkManager, le même d'un essai à l'autre),
     * et celui derrière lequel une suite est déjà chaînée. Tant qu'un passage n'a pas de suite, WorkManager le tient
     * peut-être encore pour en cours, et `KEEP` absorberait la demande d'un changement : le premier
     * changement qui suit est donc chaîné derrière lui (`APPEND_OR_REPLACE` : derrière le passage s'il n'est pas fini,
     * passage neuf sinon), au délai de [AutoBackupPolicy] ; les suivants retrouvent `KEEP`. Une seule suite par passage :
     * au pire un passage de plus, qui ne copie rien, jamais de passages en boucle.
     */
    private var lastRun: Any? = null
    private var chainedBehind: Any? = null

    /** Chaîne une suite derrière [run] (par défaut, le dernier passage commencé), sauf s'il en a déjà une. */
    @Synchronized
    private fun chainBehind(run: Any? = null, delay: () -> Duration): Boolean {
        val target = run ?: lastRun
        if (target == null || chainedBehind == target) return false
        chainedBehind = target
        scheduling.followUp(delay())
        return true
    }

    @Synchronized
    private fun started(run: Any) {
        lastRun = run
    }

    /** Un changement vient d'être écrit dans la base. */
    fun onChange() {
        status.recordChange(clock.instant())
        val current = status.status.value
        val chained = AutoBackupPolicy.enabled(current) && current.needsBackup && chainBehind {
            (AutoBackupPolicy.decide(current, clock.instant()) as? AutoBackupPolicy.Decision.Wait)?.delay ?: Duration.ZERO
        }
        if (!chained) requestIfPending()
    }

    /** Programme une copie si des changements attendent (démarrage de l'app, connexion à Drive). */
    fun requestIfPending() {
        val current = status.status.value
        if (AutoBackupPolicy.enabled(current) && current.needsBackup) scheduling.request(AutoBackupPolicy.QUIET)
    }

    /** Un passage du travail ; [runId] : identifiant du travail WorkManager (le même pour ses nouveaux essais). */
    suspend fun run(attempt: Int, runId: Any = Any()): ListenableWorker.Result {
        started(runId)
        when (val decision = AutoBackupPolicy.decide(status.status.value, clock.instant())) {
            AutoBackupPolicy.Decision.Skip -> return ListenableWorker.Result.success()
            is AutoBackupPolicy.Decision.Wait -> {
                // Un changement arrivé depuis le début du passage a peut-être déjà chaîné la suite, au même délai ou plus tard.
                chainBehind(runId) { decision.delay }
                return ListenableWorker.Result.success()
            }
            AutoBackupPolicy.Decision.BackUp -> Unit
        }
        val result = runner.backUpIfNeeded(checkContents = false) ?: return ListenableWorker.Result.success()
        // Changements arrivés pendant l'envoi : une copie de plus, après un nouveau calme (sauf si l'un d'eux l'a chaînée).
        if (result is BackupResult.Success && status.status.value.pending) chainBehind(runId) { AutoBackupPolicy.QUIET }
        return outcome.of(result, attempt)
    }
}

/**
 * Le travail unique de la sauvegarde automatique. Un changement le programme sans jamais interrompre une copie en
 * cours (`KEEP`) ; la suite est chaînée derrière le passage en cours (`APPEND_OR_REPLACE`), son délai compte depuis la
 * fin de celui-ci.
 */
object AutoBackupSchedule {
    const val WORK_NAME = "trackly-sauvegarde-auto"

    fun scheduling(context: Context): AutoBackupScheduling = object : AutoBackupScheduling {
        override fun request(delay: Duration) = enqueue(context, delay, ExistingWorkPolicy.KEEP)
        override fun followUp(delay: Duration) = enqueue(context, delay, ExistingWorkPolicy.APPEND_OR_REPLACE)
        override fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private fun enqueue(context: Context, delay: Duration, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

/** Travail WorkManager de la sauvegarde automatique : une coquille mince autour de [AutoBackup]. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    interface Host {
        val autoBackup: AutoBackup
    }

    override suspend fun doWork(): Result {
        val host = applicationContext as? Host ?: return Result.failure()
        return host.autoBackup.run(runAttemptCount, id)
    }
}
