package app.trackly.review

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.trackly.MainActivity
import app.trackly.R
import app.trackly.core.review.WeekRange

/** Notifications du bilan du lundi, sur leur propre canal « Bilan du lundi ». */
class AndroidReviewNotifier(context: Context) : ReviewNotifier {
    private val context = context.applicationContext

    override fun invite(week: WeekRange) {
        post(
            ID_INVITATION, "C'est lundi : fais ton bilan",
            "Donne tes pesées, tes pas et tes séances depuis ton dernier bilan, du ${ReportFormat.weekLabel(week)}.",
            open(MainActivity.OPEN_REVIEW, null),
            actionLabel = START_LABEL,
        )
    }

    override fun remind(week: WeekRange) {
        post(
            ID_REMINDER, "Ton bilan t'attend",
            "Il ne prend que quelques minutes. Tu peux le faire maintenant avec ce que tu as.",
            open(MainActivity.OPEN_REVIEW, null),
            actionLabel = START_LABEL,
        )
    }

    override fun reportReady(report: ReportInfo) = post(
        ID_READY, "Ton bilan est prêt",
        "Touche pour lire le bilan du ${ReportFormat.weekLabel(report.period)}.",
        open(MainActivity.OPEN_REPORT, report.id),
    )

    private fun open(target: String, reportId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, target)
            .apply { if (reportId != null) putExtra(MainActivity.EXTRA_REPORT_ID, reportId) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Un code de requête distinct par cible : le rapport ouvert est celui de CETTE notification.
        val code = if (reportId != null) (REQUEST_REPORT_BASE + reportId.toInt()) else REQUEST_REVIEW
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Vrai si la notification est partie. */
    private fun post(id: Int, title: String, text: String, pending: PendingIntent, actionLabel: String? = null): Boolean {
        ensureChannel(context)
        if (!canPost()) return false
        // Canal « Bilan du lundi » coupé par l'utilisateur : `notify` ne lève rien mais rien ne s'affiche.
        if (NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .apply { if (actionLabel != null) addAction(0, actionLabel, pending) }
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : le bilan reste accessible depuis les Réglages.
            false
        }
    }

    private fun canPost(): Boolean = canPost(context)

    companion object {
        const val CHANNEL = "bilan"

        /** Bouton de l'invitation et de la relance : ouvre la conversation de bilan. */
        const val START_LABEL = "Faire mon bilan"
        private const val ID_INVITATION = 1401
        private const val ID_REMINDER = 1402
        private const val ID_READY = 1403
        private const val REQUEST_REVIEW = 1410
        private const val REQUEST_REPORT_BASE = 1500

        /** Le bilan est ouvert (toucher ou bouton) : l'invitation et la relance n'ont plus lieu d'être. */
        fun dismissInvitations(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ID_INVITATION)
            manager.cancel(ID_REMINDER)
        }

        /** Les notifications sont-elles permises (permission Android 13+ et réglage système) ? */
        fun canPost(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }

        /** Crée le canal « Bilan du lundi » (sans effet s'il existe déjà). */
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Bilan du lundi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Invitation et relance du bilan hebdomadaire, et bilan prêt"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
