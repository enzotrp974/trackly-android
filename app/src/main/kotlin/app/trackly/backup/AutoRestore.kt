package app.trackly.backup

import app.trackly.backup.drive.DriveApi
import java.time.ZoneId

/** Ce qu'a trouvé la restauration automatique à la connexion de Drive. */
sealed interface AutoRestoreOutcome {
    /** L'app n'est pas neuve : jamais remplacée automatiquement. */
    data object NotNew : AutoRestoreOutcome

    /** Aucune sauvegarde Trackly visible. [folderFound] faux : pas même le dossier (accès perdu après réinstallation ?). */
    data class NoBackup(val folderFound: Boolean) : AutoRestoreOutcome

    /** La base de l'app (remise par Auto Backup) est au moins aussi complète que la copie Drive : elle reste. */
    data class KeptBase(val backupLabel: String) : AutoRestoreOutcome

    /** Copie vérifiée et préparée : elle remplace la base au redémarrage. */
    data class Prepared(val label: String) : AutoRestoreOutcome

    /**
     * Des copies existent, mais aucune n'a pu être préparée (copie d'une version plus récente de l'app, abîmée…).
     * Rien n'est remplacé ; la proposition reste ouverte.
     */
    data class Failed(val reason: String) : AutoRestoreOutcome
}

/**
 * **Restauration automatique** d'une app neuve, à la connexion de Google Drive : la copie la plus récente est préparée
 * par le chemin de restauration habituel (contrôles, migrations, copie de sécurité), puis appliquée au redémarrage.
 *
 * - App utilisée ([FreshInstall]) : rien, jamais.
 * - Base vide : la copie la plus récente qui contient des consommations (comptées sur la copie elle-même).
 * - Base remise par Auto Backup : remplacée seulement si la copie a plus de versions qu'elle ; sinon elle reste.
 * - Le remplacement n'a lieu que si la base est encore, au redémarrage, celle qui a été jugée neuve.
 */
class AutoRestore(
    private val fresh: FreshInstall,
    private val restore: RestoreManager,
    private val drive: () -> DriveApi,
    private val folder: BackupFolder,
    /** Contenu actuel de la base de l'app, lu sur le disque. */
    private val baseContents: suspend () -> BackupContents,
    private val zone: ZoneId,
    private val currentSchema: Int = app.trackly.storage.db.TracklyDatabase.VERSION,
) {
    /**
     * Pendant toute la recherche, un « Plus tard » est ignoré et le préchargement attend ([FreshInstall.startSearch]).
     * Une erreur réseau ou Google remonte telle quelle : rien n'est préparé, la proposition reste ouverte.
     */
    suspend fun check(): AutoRestoreOutcome {
        fresh.startSearch()
        try {
            return search()
        } finally {
            fresh.endSearch()
        }
    }

    private suspend fun search(): AutoRestoreOutcome {
        val base = baseContents()
        if (!fresh.isNew(base.fingerprint)) return AutoRestoreOutcome.NotNew
        val api = drive()
        val target = folder.find(api) ?: return AutoRestoreOutcome.NoBackup(folderFound = false)
        val candidates = BackupFiles.entries(api.search(BackupFiles.backupsQuery(target.id)), target.id, zone)
            // Une copie sans consommation n'est pas une bonne sauvegarde ; une ancienne copie ne le dit pas : elle compte.
            .filter { it.activeConsumptions == null || it.activeConsumptions > 0 }
        if (candidates.isEmpty()) return AutoRestoreOutcome.NoBackup(folderFound = true)
        val newest = candidates.first()
        if ((newest.schemaVersion ?: 0) > currentSchema) {
            // La bonne copie existe, l'app est trop ancienne pour elle : jamais une copie plus ancienne à la place.
            return AutoRestoreOutcome.Failed(
                "ta dernière sauvegarde vient d’une version plus récente de Trackly : installe-la, puis reconnecte Google Drive",
            )
        }
        val baseEmpty = base.rows == 0L
        var lastError: RestoreException? = null
        // La plus récente d'abord ; abîmée, illisible ou vide, la suivante (au plus trois essais).
        for (entry in candidates.take(MAX_TRIES)) {
            val label = "Sauvegarde Drive du ${BackupLabels.inSentence(entry.takenAt, zone)}"
            if (!baseEmpty && entry.versions != null && entry.versions <= base.versions) return AutoRestoreOutcome.KeptBase(label)
            // Décidé sur le contenu réel, avant que la copie ne soit posée : un arrêt pendant la décision ne
            // restaure jamais une copie vide (SAUV-04) ni moins complète que la base remise par Auto Backup (SAUV-02).
            val prepared = try {
                restore.prepareFromDrive(entry, label, onlyIfBase = base.fingerprint) { p ->
                    hasConsumptions(p) && (baseEmpty || (p.versions ?: 0) > base.versions)
                }
            } catch (e: RestoreException) {
                lastError = e
                continue
            }
            if (!prepared.published) {
                if (!hasConsumptions(prepared)) {
                    // Ancienne copie sans compte noté, vide en réalité : ce n'est pas une bonne sauvegarde.
                    lastError = RestoreException("tes dernières sauvegardes ne contiennent aucun repas")
                    continue
                }
                return AutoRestoreOutcome.KeptBase(label)
            }
            fresh.restoring = true
            return AutoRestoreOutcome.Prepared(label)
        }
        return AutoRestoreOutcome.Failed(lastError?.message ?: "sauvegarde illisible")
    }

    private fun hasConsumptions(p: PreparedRestore) = (p.activeConsumptions ?: 0) > 0

    private companion object {
        const val MAX_TRIES = 3
    }
}
