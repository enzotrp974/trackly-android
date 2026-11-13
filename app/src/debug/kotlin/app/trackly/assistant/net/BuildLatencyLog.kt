package app.trackly.assistant.net

import android.util.Log

/**
 * Debug seulement : les mesures de latence vont au journal Android, étiquette `TracklyLatence`
 * (`adb logcat -s TracklyLatence`). Une ligne ne contient que des noms d'étapes et des durées
 * ([LatencyLine]) : ni texte de message, ni adresse, ni en-tête, ni clé. L'APK release n'a pas ce fichier.
 */
internal object BuildLatencyLog {
    val log = LatencyLog { line -> Log.d(TAG, line.format()) }

    private const val TAG = "TracklyLatence"
}
