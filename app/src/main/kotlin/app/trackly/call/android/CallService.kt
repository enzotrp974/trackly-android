package app.trackly.call.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.trackly.MainActivity
import app.trackly.R
import app.trackly.TracklyApplication
import app.trackly.call.EndReason

/**
 * Service de premier plan de type micro : garde l'appel vivant écran éteint. Lancé seulement depuis l'écran d'appel
 * visible, au toucher du bouton d'appel, et arrêté au raccroché. Aucun appel ne démarre en arrière-plan.
 *
 * L'arrêt passe par une demande ([ACTION_STOP]) que le service traite lui-même, après son `startForeground` : un
 * appel qui échoue en quelques millisecondes ne peut pas arrêter le service avant qu'il soit au premier plan (Android
 * ferait planter l'app).
 */
class CallService : Service() {
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Appel en cours", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Appel avec Trackly")
                .setContentText("Le micro est ouvert. Touche pour revenir à l’appel.")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foreground = true
        }
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** L'app est balayée des récents pendant un appel : l'appel s'arrête (jamais d'appel sans écran). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { (application as TracklyApplication).container.call.abort(EndReason.ClosedApp) }
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "appel"
        private const val ID = 55
        private const val ACTION_STOP = "app.trackly.appel.ARRETER"

        @Volatile private var running = false

        fun start(context: Context) {
            running = runCatching { context.startForegroundService(Intent(context, CallService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            // Le service est déjà au premier plan : cette demande reste permise même app en arrière-plan.
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP)) }
                .onFailure { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}
