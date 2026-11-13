package app.trackly.dictation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Permission micro d'Android. Le système ne dit « refusé pour de bon » que par l'absence de
 * justification possible ([shouldShowRationale] faux après un premier refus) : il faut pour cela
 * l'activité en cours, que `MainActivity` fournit via [attach].
 */
class AndroidMicPermission(private val context: Context) : MicPermission {
    private var activity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun shouldShowRationale(): Boolean {
        val current = activity?.get() ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(current, Manifest.permission.RECORD_AUDIO)
    }
}
