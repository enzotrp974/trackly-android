package app.trackly.call.android

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.trackly.call.CallPlatform

/** Service de premier plan (micro) et vibration courte, au toucher du bouton d'appel et à « je t'écoute ». */
class AndroidCallPlatform(context: Context) : CallPlatform {
    private val app = context.applicationContext

    override fun startForeground() = CallService.start(app)

    override fun stopForeground() = CallService.stop(app)

    override fun holdUntilSettled() = CallSettleWorker.hold(app)

    override fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private companion object {
        const val VIBRATION_MS = 45L
    }
}
