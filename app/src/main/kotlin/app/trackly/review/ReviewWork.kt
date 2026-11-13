package app.trackly.review

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Planification des rappels du lundi : **deux** travaux périodiques (7 jours), nommés, un par rappel,
 * recalés chaque fois sur le prochain lundi à l'heure voulue (`setNextScheduleTimeOverride`, comme la
 * sauvegarde de nuit). `UPDATE` garde un seul travail par rappel et n'interrompt pas une exécution en cours.
 */
object ReviewSchedule {
    const val INVITATION_WORK = "trackly-bilan-invitation"
    const val REMINDER_WORK = "trackly-bilan-relance"
    internal const val KEY_KIND = "rappel"

    fun workName(kind: ReminderKind) = if (kind == ReminderKind.Invitation) INVITATION_WORK else REMINDER_WORK

    fun schedule(context: Context, clock: Clock, zone: ZoneId, settings: ReviewSettingsState) {
        if (!settings.enabled) {
            cancel(context)
            return
        }
        schedule(context, ReminderKind.Invitation, ReviewCalendar.nextRun(clock.instant(), zone, settings.inviteTime))
        schedule(context, ReminderKind.Reminder, ReviewCalendar.nextRun(clock.instant(), zone, REMINDER_TIME))
    }

    private fun schedule(context: Context, kind: ReminderKind, next: java.time.ZonedDateTime) {
        val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString(KEY_KIND, kind.code).build())
            .setNextScheduleTimeOverride(next.toInstant().toEpochMilli())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(workName(kind), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(INVITATION_WORK)
        manager.cancelUniqueWork(REMINDER_WORK)
    }
}

/**
 * Travail WorkManager d'un rappel du lundi : une coquille mince autour de [ReviewReminders], fournie
 * par l'application ([Host]). Le type de rappel vient des données d'entrée.
 */
class ReviewReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    interface Host {
        val reviewReminders: ReviewReminders

        /** Recale les deux rappels sur le lundi suivant. */
        fun rescheduleReview()
    }

    override suspend fun doWork(): Result {
        val host = applicationContext as? Host ?: return Result.failure()
        val kind = ReminderKind.fromCode(inputData.getString(ReviewSchedule.KEY_KIND)) ?: return Result.failure()
        runCatching { host.reviewReminders.run(kind) }
        runCatching { host.rescheduleReview() }
        return Result.success()
    }
}
