package app.trackly.assistant.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.TurnKeepAlive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * « J'envoie et je pars » : pendant un tour, un travail WorkManager **accéléré** (`setExpedited`, prévu pour
 * « envoyer un message ») tourne dans le même processus et attend la fin du tour. Tant qu'il tourne, Android ne
 * fige pas l'app et lui laisse le réseau, même écran verrouillé ou app quittée. Le tour lui-même tourne dans la
 * portée de l'application ([ChatTurns]) : le travail ne fait que le garder en vie.
 *
 * Quota de travail accéléré épuisé : repli sur un travail ordinaire (`RUN_AS_NON_EXPEDITED_WORK_REQUEST`), qui
 * démarre dès que possible. `REPLACE` : un nouveau tour remplace le travail d'attente du précédent (l'annuler
 * n'annule que l'attente, jamais un tour).
 */
class WorkManagerKeepAlive(context: Context) : TurnKeepAlive {
    private val context = context.applicationContext

    override fun hold() {
        val request = OneTimeWorkRequestBuilder<TurnWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // Sans WorkManager (cas extrême), le tour se joue quand même, sans protection en arrière-plan.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
    }

    companion object {
        const val WORK_NAME = "trackly-tour"
    }
}

/** Travail qui garde l'app en vie jusqu'à la fin du tour en cours ; les tours viennent de l'application ([Host]). */
class TurnWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    interface Host {
        val chatTurns: ChatTurns
    }

    override suspend fun doWork(): Result {
        val host = applicationContext as? Host ?: return Result.success()
        // Processus relancé après coup : aucun tour en cours, le travail se termine aussitôt.
        withTimeoutOrNull(MAX_WAIT_MS) { host.chatTurns.awaitIdle() }
        return Result.success()
    }

    /** Avant Android 12 seulement : le travail accéléré devient un service au premier plan, avec cette notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(AndroidTurnNotifier.ID_WORKING, AndroidTurnNotifier.working(applicationContext))

    private companion object {
        /** Garde-fou : un tour (plusieurs requêtes de 3 min au plus) ne garde jamais l'app plus longtemps. */
        const val MAX_WAIT_MS = 10 * 60 * 1000L
    }
}
