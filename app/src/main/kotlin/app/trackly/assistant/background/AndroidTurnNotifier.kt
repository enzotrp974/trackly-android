package app.trackly.assistant.background

import android.Manifest
import android.app.Notification
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
import app.trackly.assistant.chat.TurnNotifier
import app.trackly.assistant.chat.TurnSummary

/**
 * Notification discrète de fin de tour, sur son propre canal « Réponses de l'assistant » (importance basse :
 * ni son ni bandeau). Un toucher ouvre le chat. Rien n'est posté sans la permission de notification
 * (Android 13+) ou si l'utilisateur a coupé les notifications de l'app ou de ce canal.
 */
class AndroidTurnNotifier(context: Context) : TurnNotifier {
    private val context = context.applicationContext

    override fun turnFinished(summary: TurnSummary) {
        ensureChannel(context)
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summary.title)
            .apply { summary.text?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setContentIntent(openChat(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_REPLY, notification)
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : la réponse reste dans le chat.
        }
    }

    companion object {
        const val CHANNEL = "assistant"
        const val ID_REPLY = 1601
        const val ID_WORKING = 1602
        private const val REQUEST_CHAT = 1610

        /** Ouvre le chat ([MainActivity.OPEN_CHAT]). */
        fun openChat(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, REQUEST_CHAT, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Les notifications sont-elles permises (permission Android 13+, réglage de l'app et du canal) ? */
        fun canPost(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            return Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }

        /** Crée le canal « Réponses de l'assistant » (sans effet s'il existe déjà). */
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Réponses de l’assistant", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Message envoyé avant de quitter l’app : ce qui a été enregistré, ou la question en attente"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /** Notification de travail, montrée seulement avant Android 12 (le travail accéléré y devient un service au premier plan). */
        fun working(context: Context): Notification {
            ensureChannel(context)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Trackly traite ton message")
                .setContentIntent(openChat(context))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}
