package app.trackly.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream

/**
 * Document créé par le sélecteur de fichiers Android (`ACTION_CREATE_DOCUMENT`) : l'endroit d'un export. Les accès au
 * fournisseur de fichiers (parfois en ligne) se font hors du fil principal : le contrôleur les appelle dans `io`.
 */
class DocumentExportTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ExportTarget {
    override val displayName: String? by lazy { column(OpenableColumns.DISPLAY_NAME)?.toString() }

    // « wt » : le contenu précédent est remplacé, pas complété (un fichier plus long resterait corrompu). Un
    // fournisseur qui refuse ce mode reçoit « w » : la relecture (taille, SHA-256) contrôle de toute façon le résultat.
    override fun openOutput(): OutputStream? =
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")

    override fun openInput(): InputStream? = resolver.openInputStream(uri)

    override fun discard() {
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        // Suppression refusée : au moins vider le fichier, qu'on ne le prenne pas pour une sauvegarde.
        if (!deleted) runCatching { resolver.openOutputStream(uri, "wt")?.close() }
    }

    override fun discardIfEmpty() {
        // Taille inconnue : dans le doute, le fichier reste (il a peut-être été choisi pour être remplacé).
        if (column(OpenableColumns.SIZE) == 0L) discard()
    }

    private fun column(name: String): Any? = runCatching {
        resolver.query(uri, arrayOf(name), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.isNull(0)) {
                null
            } else if (name == OpenableColumns.SIZE) {
                c.getLong(0)
            } else {
                c.getString(0)
            }
        }
    }.getOrNull()
}
