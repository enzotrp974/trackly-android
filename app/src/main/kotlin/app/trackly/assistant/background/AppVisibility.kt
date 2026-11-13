package app.trackly.assistant.background

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * L'app est-elle à l'écran ? Compte les activités démarrées (`onStart` / `onStop`) : écran verrouillé ou app
 * quittée, le compte retombe à zéro et la fin d'un tour est notifiée au lieu d'être seulement affichée.
 */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0

    val inForeground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
