package app.trackly.backup

import app.trackly.backup.drive.DriveFile
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Une sauvegarde Trackly reconnue sur Drive. */
data class BackupEntry(
    val id: String,
    val name: String,
    val takenAt: Instant,
    val sizeBytes: Long?,
    val md5: String?,
    val sha256: String?,
    val schemaVersion: Int?,
    /** Consommations actives de la copie (absent sur les copies d'avant la sauvegarde automatique). */
    val activeConsumptions: Int? = null,
    /** Lignes des tables versionnées de la copie, voir [BackupContents.versions]. */
    val versions: Long? = null,
)

/**
 * Règles de nommage et de reconnaissance des sauvegardes Drive. Un fichier n'est traité comme
 * sauvegarde Trackly (listé, restaurable, supprimable par la rotation) que s'il remplit **toutes**
 * les conditions : dans le dossier Trackly, marqué par Trackly (`appProperties`), nommé selon le
 * modèle, et pas à la corbeille. Tout le reste est ignoré, jamais modifié.
 */
object BackupFiles {
    const val FOLDER_NAME = "Trackly"
    const val MIME = "application/vnd.sqlite3"
    const val KEEP_DAYS = 30L

    /** Copies les plus récentes toujours gardées, quel que soit leur âge. */
    const val KEEP_RECENT = 10

    // Marques privées (`appProperties` : visibles seulement par Trackly). Clé + valeur ≤ 124 octets.
    const val ROLE_KEY = "trackly_role"
    const val ROLE_BACKUPS = "backups"
    const val KIND_KEY = "trackly_kind"
    const val KIND_DATABASE = "db_backup"
    const val SCHEMA_KEY = "schema"
    const val SHA256_KEY = "sha256"
    const val CONSUMPTIONS_KEY = "consumptions"
    const val VERSIONS_KEY = "versions"

    private val NAME = Regex("""^trackly-(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})\.db$""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** Nom daté, heure locale : `trackly-2026-11-02_03-12-05.db`. */
    fun nameFor(at: Instant, zone: ZoneId): String = "trackly-${STAMP.format(at.atZone(zone))}.db"

    fun folderProperties(): Map<String, String> = mapOf(ROLE_KEY to ROLE_BACKUPS)

    fun backupProperties(snapshot: Snapshot, contents: BackupContents): Map<String, String> = mapOf(
        KIND_KEY to KIND_DATABASE,
        SCHEMA_KEY to snapshot.schemaVersion.toString(),
        SHA256_KEY to snapshot.sha256,
        CONSUMPTIONS_KEY to contents.activeConsumptions.toString(),
        VERSIONS_KEY to contents.versions.toString(),
    )

    /** Requête Drive des dossiers Trackly (non à la corbeille). */
    val FOLDER_QUERY = "mimeType = '${DriveFile.FOLDER_MIME}' and trashed = false and " +
        "appProperties has { key='$ROLE_KEY' and value='$ROLE_BACKUPS' }"

    /** Requête Drive des sauvegardes d'un dossier. */
    fun backupsQuery(folderId: String): String =
        "'${folderId.replace("'", "")}' in parents and trashed = false and " +
            "appProperties has { key='$KIND_KEY' and value='$KIND_DATABASE' }"

    fun isTracklyFolder(file: DriveFile): Boolean =
        file.isFolder && !file.trashed && file.appProperties[ROLE_KEY] == ROLE_BACKUPS

    fun isTracklyBackup(file: DriveFile, folderId: String): Boolean =
        !file.trashed &&
            !file.isFolder &&
            folderId in file.parents &&
            file.appProperties[KIND_KEY] == KIND_DATABASE &&
            NAME.matches(file.name)

    /** Moment de la sauvegarde : date de création Drive, sinon celle du nom. */
    fun takenAt(file: DriveFile, zone: ZoneId): Instant? = file.createdTime ?: NAME.matchEntire(file.name)?.let { m ->
        val (date, h, min, s) = m.destructured
        runCatching { LocalDateTime.parse("${date}T$h:$min:$s").atZone(zone).toInstant() }.getOrNull()
    }

    /** Sauvegardes reconnues d'un dossier, de la plus récente à la plus ancienne. */
    fun entries(files: List<DriveFile>, folderId: String, zone: ZoneId): List<BackupEntry> =
        files.filter { isTracklyBackup(it, folderId) }
            .mapNotNull { f ->
                val at = takenAt(f, zone) ?: return@mapNotNull null
                BackupEntry(
                    id = f.id,
                    name = f.name,
                    takenAt = at,
                    sizeBytes = f.size,
                    md5 = f.md5Checksum,
                    sha256 = f.appProperties[SHA256_KEY],
                    schemaVersion = f.appProperties[SCHEMA_KEY]?.toIntOrNull(),
                    activeConsumptions = f.appProperties[CONSUMPTIONS_KEY]?.toIntOrNull(),
                    versions = f.appProperties[VERSIONS_KEY]?.toLongOrNull(),
                )
            }
            .sortedByDescending { it.takenAt }

    /**
     * Rotation : ce qui n'est pas gardé. Sont gardées les [keepRecent] copies les plus récentes, et la dernière copie
     * de chaque jour (heure de [zone]) des [keepDays] derniers jours. La sauvegarde [protectId] (celle qu'on vient
     * d'envoyer et de vérifier) n'est jamais concernée. [entries] ne contient que des sauvegardes reconnues.
     */
    fun expired(
        entries: List<BackupEntry>,
        now: Instant,
        protectId: String,
        zone: ZoneId,
        keepRecent: Int = KEEP_RECENT,
        keepDays: Long = KEEP_DAYS,
    ): List<BackupEntry> {
        val newestFirst = entries.sortedByDescending { it.takenAt }
        val limit = now.minus(Duration.ofDays(keepDays))
        val kept = buildSet {
            add(protectId)
            newestFirst.take(keepRecent).forEach { add(it.id) }
            newestFirst.filter { !it.takenAt.isBefore(limit) }
                .groupBy { it.takenAt.atZone(zone).toLocalDate() }
                .values
                .forEach { sameDay -> add(sameDay.first().id) }
        }
        return newestFirst.filter { it.id !in kept }
    }
}
