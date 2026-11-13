package app.trackly.backup

import app.trackly.backup.drive.DriveApi
import app.trackly.backup.drive.DriveFile
import app.trackly.backup.drive.DriveHttpException
import java.time.Instant

/** Dossier Trackly trouvé ou créé sur Drive. */
data class FolderResult(val folder: DriveFile, val created: Boolean)

/**
 * Le dossier « Trackly » de Drive : **créé par l'app** (avec `drive.file`, un dossier créé à la
 * main serait invisible), marqué par une propriété privée, et vérifié avant toute écriture.
 *
 * Recherche : identifiant mémorisé (relu et vérifié), sinon recherche Drive par la marque
 * (utile après une réinstallation ; si Drive ne montre plus l'ancien dossier, on ne trouve rien).
 */
class BackupFolder(private val status: BackupStatusStore) {

    /** Le dossier Trackly vérifié, ou `null` s'il est introuvable (jamais de création ici). */
    suspend fun find(api: DriveApi): DriveFile? {
        status.folderId?.let { id ->
            val known = api.getFile(id)
            if (known != null && BackupFiles.isTracklyFolder(known)) return known
            status.folderId = null
        }
        // Plusieurs dossiers marqués (rare) : le plus ancien, pour garder une suite stable.
        val found = api.search(BackupFiles.FOLDER_QUERY)
            .filter(BackupFiles::isTracklyFolder)
            .minByOrNull { it.createdTime ?: Instant.MAX }
        if (found != null) status.folderId = found.id
        return found
    }

    /** Trouve le dossier, ou le crée à la racine de « Mon Drive » puis le relit pour le vérifier. */
    suspend fun findOrCreate(api: DriveApi): FolderResult {
        find(api)?.let { return FolderResult(it, created = false) }
        val created = api.createFolder(BackupFiles.FOLDER_NAME, BackupFiles.folderProperties())
        var verified = api.getFile(created.id)
        // Si Drive n'a pas retenu la marque à la création, on la pose sur CE dossier (créé à l'instant par Trackly).
        if (verified != null && verified.isFolder && !verified.trashed && !BackupFiles.isTracklyFolder(verified)) {
            api.setAppProperties(verified.id, BackupFiles.folderProperties())
            verified = api.getFile(created.id)
        }
        if (verified == null || !BackupFiles.isTracklyFolder(verified)) {
            throw DriveHttpException(200, "dossier Trackly créé mais non vérifié à la relecture")
        }
        status.folderId = verified.id
        return FolderResult(verified, created = true)
    }
}
