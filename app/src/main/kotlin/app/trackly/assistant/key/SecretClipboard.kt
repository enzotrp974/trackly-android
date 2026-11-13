package app.trackly.assistant.key

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/** Lit la clé copiée (bouton « Coller »), et vide le presse-papiers après un enregistrement réussi : la clé n'y traîne pas. */
fun interface SecretClipboard {
    fun clear()

    /** Texte du presse-papiers, ou `null` s'il est vide ou illisible. */
    fun read(): String? = null

    companion object {
        val None = SecretClipboard { }
    }
}

/** Presse-papiers d'Android : `clearPrimaryClip` (API 28+), sinon remplacement par un texte vide. */
class AndroidSecretClipboard(context: Context) : SecretClipboard {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun read(): String? = runCatching {
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }.getOrNull()

    override fun clear() {
        val clipboard = manager ?: return
        // Jamais d'échec visible : le presse-papiers est un confort, l'enregistrement de la clé a déjà réussi.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
