package app.trackly.backup

import app.trackly.backup.drive.DriveApi
import app.trackly.backup.drive.DriveAuthException
import app.trackly.backup.drive.DriveAuthorization
import app.trackly.backup.drive.DriveHttpException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId

/** Résultat d'une sauvegarde Drive. */
sealed interface BackupResult {
    data class Success(
        val name: String,
        val sizeBytes: Long,
        val trashedOld: Int,
        val rotationWarning: String?,
        /** Ce que contient la copie envoyée. */
        val contents: BackupContents? = null,
    ) : BackupResult

    data class Failure(
        val kind: FailureKind,
        val message: String,
        /** Écran Google à afficher (connexion, consentement) : seulement possible au premier plan. */
        val userAction: DriveAuthorization.NeedsUserAction? = null,
    ) : BackupResult {
        val retryable: Boolean get() = kind == FailureKind.Network
    }
}

/** Où écrire un export : un document choisi dans le sélecteur de fichiers Android (un fichier, en test). */
interface ExportTarget {
    /** Nom du fichier tel que le téléphone l'affiche, s'il est connu. */
    val displayName: String?

    /** Flux d'écriture (le contenu précédent est remplacé), ou `null` si l'emplacement ne s'ouvre pas. */
    fun openOutput(): OutputStream?

    /** Flux de relecture du fichier écrit. */
    fun openInput(): InputStream?

    /**
     * Retire le fichier quand l'export a commencé à l'écrire et n'aboutit pas : son contenu est perdu de toute façon,
     * et un fichier vide ou abîmé ne doit pas rester pour une sauvegarde. Si le support refuse la suppression, le
     * fichier est au moins vidé.
     */
    fun discard()

    /**
     * Retire le fichier seulement s'il est vide, donc créé à l'instant par le sélecteur. Avant l'écriture : le
     * sélecteur peut aussi renvoyer un fichier existant que l'utilisateur a choisi de remplacer, à ne jamais détruire
     * pour une panne qui n'a encore rien écrit.
     */
    fun discardIfEmpty()
}

sealed interface ExportResult {
    data class Success(val sizeBytes: Long, val contents: BackupContents) : ExportResult

    /** Aucune ligne dans aucune table : un fichier ne protégerait rien. */
    data object NothingToExport : ExportResult

    data class Failure(val message: String) : ExportResult
}

enum class FailureKind {
    /** Google Drive n'a jamais été connecté depuis les réglages. */
    NotConnected,

    /** Google demande une action de l'utilisateur (reconnexion, consentement expiré ou retiré). */
    NeedsReconnect,

    /** Projet Google Cloud absent ou mal configuré. */
    Misconfigured,

    /** Dossier Trackly introuvable (supprimé, ou plus visible après réinstallation). */
    FolderMissing,

    /** Réseau ou Drive momentanément indisponible : réessayer plus tard. */
    Network,

    /** La relecture du fichier envoyé ne correspond pas à la copie locale. */
    Verification,

    /** Copie locale de la base impossible. */
    Snapshot,

    /** Autre refus de Drive. */
    Drive,

    /**
     * Garde-fou : base sans consommation active, ou moins de versions que la dernière copie. Rien n'est envoyé,
     * la dernière copie Drive reste la bonne. Pas une panne : ni nouvel essai ni notification.
     */
    Refused,
}

/**
 * Une sauvegarde complète : copie cohérente de la base, envoi d'un **nouveau** fichier daté dans
 * le dossier Trackly vérifié, relecture (taille, MD5, dossier parent), puis rotation à 30 jours
 * limitée aux sauvegardes Trackly reconnues (mises à la corbeille de Drive, donc récupérables).
 * L'état est enregistré pour l'écran Réglages › Sauvegarde.
 *
 * **Garde-fou** (toute copie, automatique ou demandée) : une base sans consommation active, ou dont le nombre de
 * versions recule par rapport à la dernière copie Drive, n'est jamais envoyée. Une app vide ou remise à un état
 * ancien ne devient donc jamais la copie la plus récente. Après une restauration, la comparaison ne porte que sur les
 * copies faites depuis : la base restaurée, plus ancienne, est alors la référence voulue.
 *
 * Une seule sauvegarde à la fois (nuit, automatique ou bouton) : les suivantes attendent leur tour.
 */
class BackupRunner(
    private val snapshotter: DatabaseSnapshotter,
    /** Dossier de travail local, hors sauvegardes (cache). */
    private val workDir: File,
    /** Nouveau client Drive (nouveau jeton demandé à chaque sauvegarde). */
    private val drive: () -> DriveApi,
    private val folder: BackupFolder,
    private val status: BackupStatusStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    private val oneAtATime = Mutex()

    /** Copie demandée (bouton) : elle part, sous réserve du garde-fou. */
    suspend fun backUp(): BackupResult = oneAtATime.withLock { backUpAlone(skipIfUnchanged = false)!! }

    /**
     * Copie en arrière-plan, seulement si elle est utile : `null` quand il n'y a rien à copier. Décidé une fois la
     * copie précédente finie (bouton, nuit et travail automatique ne doublent pas un envoi).
     *
     * [checkContents] (la nuit) : même sans changement signalé, la base est copiée sur le téléphone et comparée à la
     * dernière copie réussie ; si elle diffère (écriture faite avant que l'écoute ne commence), elle part.
     */
    suspend fun backUpIfNeeded(checkContents: Boolean): BackupResult? = oneAtATime.withLock {
        val current = status.status.value
        when {
            !current.connected || current.needsBackup -> backUpAlone(skipIfUnchanged = false)
            checkContents -> backUpAlone(skipIfUnchanged = true)
            else -> null
        }
    }

    private suspend fun backUpAlone(skipIfUnchanged: Boolean): BackupResult? {
        if (!status.status.value.connected) {
            return BackupResult.Failure(FailureKind.NotConnected, "Google Drive n'est pas connecté")
        }
        // Changements compris dans la copie : lus avant elle, un changement pendant l'envoi reste donc à copier.
        val seq = status.changeSeq
        val startedAt = clock.instant()
        val result = try {
            attempt(skipIfUnchanged) ?: return null
        } catch (e: DriveAuthException) {
            failureOf(e)
        } catch (e: SnapshotException) {
            BackupResult.Failure(FailureKind.Snapshot, "Copie de la base impossible : ${e.message}")
        } catch (e: DriveHttpException) {
            if (e.transient) {
                BackupResult.Failure(FailureKind.Network, "Drive indisponible pour le moment (${e.code})")
            } else {
                BackupResult.Failure(FailureKind.Drive, "Drive a refusé l'envoi : ${e.message}")
            }
        } catch (e: IOException) {
            BackupResult.Failure(FailureKind.Network, "Pas de connexion à Drive")
        }
        val now = clock.instant()
        when {
            result is BackupResult.Success -> status.recordSuccess(now, result.name, seq, startedAt, result.contents?.fingerprint)
            result is BackupResult.Failure && result.kind == FailureKind.Refused -> status.recordRefusal(now, result.message)
            result is BackupResult.Failure -> status.recordFailure(
                now,
                result.message,
                // Jusqu'à la reconnexion, la copie automatique s'arrête (pas une notification par changement).
                needsReconnect = result.kind == FailureKind.NeedsReconnect ||
                    result.kind == FailureKind.FolderMissing ||
                    result.kind == FailureKind.Misconfigured,
            )
        }
        return result
    }

    /** `null` : [skipIfUnchanged] et la base est identique à la dernière copie réussie ; rien n'a été envoyé. */
    private suspend fun attempt(skipIfUnchanged: Boolean): BackupResult? {
        val snapshotFile = File(workDir, SNAPSHOT_NAME)
        try {
            val (snapshot, contents) = copyAndCount(snapshotFile)
            if (skipIfUnchanged && contents.fingerprint == status.savedFingerprint) return null
            // Base vide : refusée sans même joindre Drive.
            if (contents.activeConsumptions == 0) {
                return BackupResult.Failure(FailureKind.Refused, "Rien envoyé : aucune consommation dans l'app, la copie Drive reste la bonne")
            }
            val api = drive()
            val target = folder.find(api)
                ?: return BackupResult.Failure(
                    FailureKind.FolderMissing,
                    "Dossier Trackly introuvable sur Drive : reconnecte Google Drive dans les réglages",
                )
            val existing = BackupFiles.entries(api.search(BackupFiles.backupsQuery(target.id)), target.id, zone)
            shrinkage(contents, existing)?.let { return BackupResult.Failure(FailureKind.Refused, it) }

            val name = BackupFiles.nameFor(clock.instant(), zone)
            val uploaded = api.upload(name, target.id, BackupFiles.MIME, BackupFiles.backupProperties(snapshot, contents), snapshot.file)

            // Relecture après écriture : le fichier doit exister, au bon endroit, identique à la copie.
            val reread = api.getFile(uploaded.id)
            val verified = reread != null &&
                !reread.trashed &&
                target.id in reread.parents &&
                reread.size == snapshot.sizeBytes &&
                reread.md5Checksum.equals(snapshot.md5, ignoreCase = true)
            if (!verified) {
                // Ce fichier vient d'être créé par Trackly : on le retire pour ne pas le prendre pour une sauvegarde valide.
                if (reread != null) runCatching { api.trash(uploaded.id) }
                return BackupResult.Failure(FailureKind.Verification, "La copie envoyée sur Drive ne correspond pas à la base")
            }

            var trashed = 0
            var warning: String? = null
            try {
                val entries = BackupFiles.entries(api.search(BackupFiles.backupsQuery(target.id)), target.id, zone)
                for (old in BackupFiles.expired(entries, clock.instant(), protectId = uploaded.id, zone = zone)) {
                    api.trash(old.id)
                    trashed++
                }
            } catch (e: IOException) {
                // La sauvegarde est faite et vérifiée ; la rotation reprendra à la prochaine.
                warning = "Rotation incomplète : ${e.message}"
            }
            return BackupResult.Success(name, snapshot.sizeBytes, trashed, warning, contents)
        } finally {
            DatabaseFiles.deleteWithSides(snapshotFile)
        }
    }

    /** La copie cohérente de la base et ce qu'elle contient : le même point de départ pour Drive et pour l'export. */
    private suspend fun copyAndCount(target: File): Pair<Snapshot, BackupContents> {
        val snapshot = snapshotter.snapshot(target)
        val contents = try {
            BackupContents.of(snapshot.file)
        } catch (e: Exception) {
            throw SnapshotException("contenu de la copie illisible", e)
        }
        return snapshot to contents
    }

    /** Nom daté du fichier d'un export : celui des sauvegardes, donc reconnu par « Depuis un fichier » comme par la liste Drive. */
    fun exportName(): String = BackupFiles.nameFor(clock.instant(), zone)

    /**
     * Y a-t-il quelque chose à exporter ? Faux seulement pour une base sans aucune ligne dans aucune table. Ni le
     * garde-fou « aucune consommation » ni la comparaison avec Drive ne s'appliquent : un export n'écrase rien, et la
     * reprise d'un fichier demande de toute façon une confirmation.
     */
    suspend fun hasDataToExport(): Boolean = oneAtATime.withLock {
        val snapshotFile = File(workDir, SNAPSHOT_NAME)
        try {
            copyAndCount(snapshotFile).second.rows > 0
        } finally {
            DatabaseFiles.deleteWithSides(snapshotFile)
        }
    }

    /**
     * Export dans un fichier choisi par l'utilisateur : la copie cohérente de la sauvegarde (`VACUUM INTO`,
     * `integrity_check`), écrite vers [target], puis **relue** (taille et SHA-256 identiques à la copie). Un fichier
     * qui ne passe pas la relecture est retiré. Rien n'est envoyé sur Drive et l'état de la sauvegarde ne change pas.
     * Le fichier est une base complète : « Depuis un fichier » la reprend telle quelle.
     */
    suspend fun exportTo(target: ExportTarget): ExportResult = oneAtATime.withLock {
        val snapshotFile = File(workDir, SNAPSHOT_NAME)
        try {
            val (snapshot, contents) = try {
                copyAndCount(snapshotFile)
            } catch (e: SnapshotException) {
                target.discardIfEmpty()
                return@withLock ExportResult.Failure("Copie de la base impossible : ${e.message}")
            }
            if (contents.rows == 0L) {
                target.discardIfEmpty()
                return@withLock ExportResult.NothingToExport
            }
            // Tant que l'emplacement ne s'est pas ouvert, rien n'est écrit. Le sélecteur peut avoir rendu un
            // bon fichier choisi pour être remplacé : seul un fichier vide (créé à l'instant) est alors retiré.
            val output = try {
                target.openOutput() ?: throw IOException("emplacement fermé par Android")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                target.discardIfEmpty()
                return@withLock ExportResult.Failure("Écriture du fichier impossible : ${e.message ?: e.javaClass.simpleName}")
            }
            try {
                output.use { out -> snapshot.file.inputStream().use { it.copyTo(out) } }
                val reread = target.openInput()?.use { Checksum.of(it) } ?: throw IOException("fichier introuvable à la relecture")
                if (reread.size != snapshot.sizeBytes || !reread.sha256.equals(snapshot.sha256, ignoreCase = true)) {
                    target.discard()
                    return@withLock ExportResult.Failure("Le fichier écrit ne correspond pas à la base : rien n'a été gardé, réessaie ou choisis un autre emplacement")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Après l'ouverture (contenu remplacé) : IOException, autorisation retirée… : jamais de fichier vide laissé.
                target.discard()
                return@withLock ExportResult.Failure("Écriture du fichier impossible : ${e.message ?: e.javaClass.simpleName}")
            }
            ExportResult.Success(snapshot.sizeBytes, contents)
        } finally {
            DatabaseFiles.deleteWithSides(snapshotFile)
        }
    }

    /**
     * Refus si la base a moins de versions que la copie Drive la plus récente qui en note le nombre. Seules comptent
     * les copies faites depuis la dernière restauration : restaurer une date plus ancienne est un choix voulu.
     *
     * La copie qui a refusé est mémorisée ([BackupStatusStore.blockingCopyId]) : tant qu'elle reste la plus récente,
     * l'envoi reste refusé, même quand l'app finit par avoir plus de versions qu'elle. Compter ne prouve pas qu'une
     * base contient une copie ; seule une restauration lève ce blocage. Devenue invisible (dossier recréé, accès
     * retiré), elle bloque encore : le dossier visible n'a plus rien pour comparer.
     */
    private fun shrinkage(contents: BackupContents, existing: List<BackupEntry>): String? {
        val blocking = status.blockingCopyId
        if (blocking != null && existing.none { it.id == blocking }) {
            // La copie qui a refusé n'est plus visible (accès retiré, dossier supprimé ou recréé). Sans elle, le
            // dossier n'a plus de référence : rien ne part tant qu'une restauration n'a pas tranché.
            return "Rien envoyé : la copie Drive qui a des données absentes de l'app n'est plus visible pour Trackly " +
                "(elle reste dans ton Drive, ancien dossier Trackly, ou dans la corbeille de Drive pendant 30 jours : " +
                "sors-la de là). Pour la reprendre : Restaurer › Depuis un fichier, puis Drive dans le sélecteur. Si tu ne " +
                "l'as plus : Fichier › Exporter dans un fichier, puis tout de suite, sans rien saisir entre-temps, " +
                "Restaurer › Depuis un fichier avec ce fichier ; Trackly reprend alors ses données actuelles comme " +
                "référence, rien n'est effacé"
        }
        val restoredAt = status.status.value.lastRestoreAt
        val reference = existing
            .filter { restoredAt == null || it.takenAt.isAfter(restoredAt) }
            .firstOrNull { it.versions != null }
            ?: return null
        val versions = reference.versions ?: return null
        val moment = BackupLabels.inSentence(reference.takenAt, zone)
        if (reference.id == status.blockingCopyId) {
            return "Rien envoyé : l'app n'a pas repris la copie Drive du $moment, qui a des données absentes de l'app. " +
                "Cette copie reste la bonne. Pour la reprendre : Restaurer › Choisir une sauvegarde"
        }
        if (contents.versions >= versions) return null
        status.blockingCopyId = reference.id
        return "Rien envoyé : l'app contient moins de données que la copie Drive du " +
            "$moment (${contents.versions} versions contre $versions), " +
            "cette copie reste la bonne. Pour la reprendre : Restaurer › Choisir une sauvegarde"
    }

    private fun failureOf(e: DriveAuthException): BackupResult.Failure = when (val auth = e.authorization) {
        is DriveAuthorization.NeedsUserAction -> BackupResult.Failure(
            FailureKind.NeedsReconnect,
            "Google demande de reconnecter Drive",
            userAction = auth,
        )
        is DriveAuthorization.Failed -> BackupResult.Failure(
            if (auth.misconfigured) FailureKind.Misconfigured else FailureKind.Network,
            auth.message,
        )
        is DriveAuthorization.Granted -> BackupResult.Failure(FailureKind.Drive, "Accès Drive inattendu")
    }
}

private const val SNAPSHOT_NAME = "snapshot.db"

/** Taille et SHA-256 d'un flux, lus d'un bout à l'autre. */
private class Checksum(val size: Long, val sha256: String) {
    companion object {
        fun of(input: InputStream): Checksum {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
                size += n
            }
            return Checksum(size, md.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
