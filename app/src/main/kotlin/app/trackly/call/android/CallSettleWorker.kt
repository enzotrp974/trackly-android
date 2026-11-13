package app.trackly.call.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.trackly.assistant.background.AndroidTurnNotifier
import app.trackly.call.CallServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Après le raccroché, la dernière réflexion de l'appel peut encore tourner (elle note le repas dans le chat du jour), puis
 * la relecture après l'appel : ce travail WorkManager accéléré garde l'app en vie jusqu'à leur fin, comme pour un
 * tour du chat. Le micro est fermé.
 */
class CallSettleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    interface Host {
        val callServices: CallServices
    }

    override suspend fun doWork(): Result {
        val host = applicationContext as? Host ?: return Result.success()
        // Relancé par Android dans un nouveau processus : la relecture restée en attente reprend ici, sans attendre l'ouverture.
        runCatching { host.callServices.resumeReview(hold = false) }
        withTimeoutOrNull(MAX_WAIT_MS) { host.callServices.settling.first { !it } }
        return Result.success()
    }

    /** Avant Android 12 seulement : le travail accéléré devient un service au premier plan, avec cette notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(AndroidTurnNotifier.ID_WORKING, AndroidTurnNotifier.working(applicationContext))

    companion object {
        const val WORK_NAME = "trackly-appel-reflexion"

        /** Au-delà du délai d'une réflexion puis de la relecture (2 min chacune) : jamais plus longtemps. */
        private const val MAX_WAIT_MS = 5 * 60 * 1000L

        fun hold(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallSettleWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            runCatching { WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
        }
    }
}
