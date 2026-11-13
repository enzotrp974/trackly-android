package app.trackly.photos

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Cible de l'appareil photo : un fichier temporaire du cache, exposé à l'appli photo par [uri]. */
class CameraTarget(val file: File, val uri: Uri)

/**
 * Fichiers temporaires de l'appareil photo. Le contrat `TakePicture` écrit dans ce fichier du cache privé
 * de l'app (jamais dans la galerie) ; [PhotoPreparer.prepare] le supprime aussitôt après lecture. [purge] balaie ce
 * qu'un arrêt brutal aurait pu laisser.
 */
class CameraTempFiles(private val context: Context) {
    private val dir: File get() = File(context.cacheDir, DIR_NAME)

    /** Fichier vide du cache privé, où l'appareil photo écrira. */
    fun createFile(): File = File.createTempFile("capture", ".jpg", dir.also { it.mkdirs() })

    fun create(): CameraTarget {
        val file = createFile()
        return CameraTarget(file, FileProvider.getUriForFile(context, authority(context), file))
    }

    /**
     * Supprime tout fichier resté dans le dossier temporaire, sauf la capture en cours [keep] (chemin du fichier
     * confié à l'appareil photo, restauré après une recréation de l'activité).
     */
    fun purge(keep: String? = null) {
        dir.listFiles()?.filter { keep == null || it.path != keep }?.forEach { it.delete() }
    }

    companion object {
        const val DIR_NAME = "photos"

        /** Doit correspondre au `<provider>` du manifeste. */
        fun authority(context: Context) = "${context.packageName}.photos"
    }
}
