package app.trackly.backup

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

/** Notifications de la sauvegarde ; un toucher ouvre Réglages › Sauvegarde. */
class AndroidBackupNotifier(context: Context) : BackupNotifier {
    private val context = context.applicationContext

    override fun askToReconnect(message: String) =
        post(ID_RECONNECT, "Reconnecte Google Drive", "$message. Touche pour ouvrir Réglages › Sauvegarde.")

    override fun reportFailing(message: String) =
        post(ID_FAILING, "Sauvegarde Drive en échec", "Aucune sauvegarde réussie depuis 2 jours : $message.")

    private fun post(id: Int, title: String, text: String) {
        ensureChannel(context)
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BACKUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            // Une notification déjà affichée est mise à jour sans sonner ni vibrer de nouveau.
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : l'état reste visible dans les réglages.
        }
    }

    private fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL = "sauvegarde"
        private const val ID_RECONNECT = 1301
        private const val ID_FAILING = 1302

        /** Crée le canal « Sauvegarde » (sans effet s'il existe déjà). */
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Sauvegarde", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Problèmes de sauvegarde sur Google Drive"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

/** Redémarre l'app après la préparation d'une restauration. */
fun interface AppRestarter {
    fun restart()
}

/** Relance l'activité principale dans une nouvelle tâche, puis arrête le processus. */
class ProcessRestarter(private val context: Context) : AppRestarter {
    override fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val component = launch.component ?: return
        context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}
