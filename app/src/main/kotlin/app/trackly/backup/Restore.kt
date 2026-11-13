package app.trackly.backup

import app.trackly.backup.drive.DriveApi
import app.trackly.storage.db.TracklyDatabase
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Refus de restaurer, avec un message clair pour l'utilisateur. Rien n'a été modifié. */
class RestoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Sauvegardes listées sur Drive ; [folderFound] faux si le dossier Trackly est introuvable. */
data class BackupListing(val entries: List<BackupEntry>, val folderFound: Boolean)

/** Restauration vérifiée et prête : elle sera appliquée au redémarrage de l'app. */
data class PreparedRestore(
    val label: String,
    /** Version du schéma de la sauvegarde (avant migration). */
    val fromSchema: Int,
    val activeConsumptions: Int?,
    val lastDay: String?,
    /** Lignes des tables versionnées de la base préparée (après migration), pour la comparer à celle de l'app. */
    val versions: Long? = null,
    /** La sauvegarde portait des réglages non secrets ([PortableSettings]) : ils seront remis au redémarrage. */
    val carriesSettings: Boolean = false,
    /** Faux : écartée par `accept` avant d'être posée, rien n'attend le redémarrage. */
    val published: Boolean = true,
)

/** Étapes d'une restauration où le processus peut s'arrêter ; les tests y simulent un arrêt. */
enum class RestoreStep {
    /** La base préparée est posée : une restauration attend le redémarrage. */
    Published,
    CopyJournaled,
    SafetyCopied,
    ReplaceJournaled,
    SidesDeleted,
    Replaced,
    SettingsApplied,
    RestoreRecorded,
    PreloadNotified,
    MarkedUsed,

    /** Remplacement en échec : la base d'origine est remise (jamais atteinte sans échec). */
    RollingBack,
}

/** Copie de la base gardée sur le téléphone (avant une restauration ou une migration). */
data class LocalCopy(val file: File, val label: String)

/**
 * Restauration à une date choisie, en deux temps :
 *
 * 1. **Préparer** (app en marche, rien n'est remplacé) : télécharger, contrôler taille, MD5 et
 *    SHA-256, `integrity_check`, version du schéma (plus récente que l'app : refusée), puis ouvrir
 *    une copie avec Room et **toutes les migrations** (une sauvegarde plus ancienne est migrée et
 *    validée par Room). Le résultat, un fichier unique vérifié, attend à côté de la base.
 * 2. **Appliquer** au redémarrage, avant toute ouverture de la base ([PendingRestore]) : copie de
 *    sécurité de la base actuelle, puis remplacement.
 *
 * Une restauration automatique ([AutoRestore]) passe par le même chemin, avec une condition de plus : la base de l'app
 * doit être, au moment du remplacement, exactement celle qui a été jugée neuve (`onlyIfBase`).
 */
class RestoreManager(
    private val databaseFile: File,
    /** Dossier de travail, hors sauvegardes (`no_backup`). */
    private val workDir: File,
    /** Copies locales (avant restauration, avant migration). */
    private val localCopiesDir: File,
    private val drive: () -> DriveApi,
    private val folder: BackupFolder,
    private val status: BackupStatusStore,
    /** Ouvre une base Room (avec toutes les migrations) sur un fichier donné. */
    private val openWithRoom: (File) -> TracklyDatabase,
    private val zone: ZoneId,
    private val currentSchema: Int = TracklyDatabase.VERSION,
    /** Étapes franchies (tests : arrêt simulé du processus). */
    private val onStep: (RestoreStep) -> Unit = {},
) {
    val stagedFile: File get() = PendingRestore.stagedFile(databaseFile)

    suspend fun listBackups(): BackupListing {
        val api = drive()
        val target = folder.find(api) ?: return BackupListing(emptyList(), folderFound = false)
        return BackupListing(BackupFiles.entries(api.search(BackupFiles.backupsQuery(target.id)), target.id, zone), true)
    }

    /** Copies de la base gardées sur le téléphone, de la plus récente à la plus ancienne. */
    fun localCopies(): List<LocalCopy> = localCopiesDir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { LocalCopy(it, localLabel(it)) }

    suspend fun prepareFromDrive(
        entry: BackupEntry,
        label: String,
        onlyIfBase: String? = null,
        accept: (PreparedRestore) -> Boolean = { true },
    ): PreparedRestore {
        val download = File(workDir, "download.db")
        DatabaseFiles.deleteWithSides(download)
        try {
            val api = drive()
            // Le fichier doit toujours être une sauvegarde reconnue du dossier Trackly.
            val target = folder.find(api) ?: throw RestoreException("Dossier Trackly introuvable sur Drive")
            val meta = api.getFile(entry.id)
            if (meta == null || !BackupFiles.isTracklyBackup(meta, target.id)) {
                throw RestoreException("Cette sauvegarde n'est plus disponible sur Drive")
            }
            api.download(entry.id, download)
            if (meta.size != null && download.length() != meta.size) {
                throw RestoreException("Téléchargement incomplet : taille différente")
            }
            if (meta.md5Checksum != null && !DatabaseFiles.md5(download).equals(meta.md5Checksum, ignoreCase = true)) {
                throw RestoreException("Téléchargement abîmé : somme de contrôle différente")
            }
            val sha = meta.appProperties[BackupFiles.SHA256_KEY]
            if (sha != null && !DatabaseFiles.sha256(download).equals(sha, ignoreCase = true)) {
                throw RestoreException("Sauvegarde abîmée : empreinte SHA-256 différente")
            }
            return prepareFromFile(download, label, onlyIfBase, accept)
        } finally {
            DatabaseFiles.deleteWithSides(download)
        }
    }

    /**
     * Prépare la restauration d'un fichier de base (sauvegarde téléchargée, copie locale…). [onlyIfBase] : empreinte
     * ([BackupContents.fingerprint]) que la base de l'app doit encore avoir au remplacement, sinon il n'a pas lieu.
     * [accept] décide sur le contenu migré, **avant** que la base préparée ne soit posée (SAUV-02) : un arrêt
     * pendant la décision ne laisse jamais une copie écartée attendre le redémarrage.
     */
    suspend fun prepareFromFile(
        source: File,
        label: String,
        onlyIfBase: String? = null,
        accept: (PreparedRestore) -> Boolean = { true },
    ): PreparedRestore {
        val info = DatabaseFiles.inspect(source) ?: throw RestoreException("Ce fichier n'est pas une base lisible")
        if (!info.isRoomDatabase || info.schemaVersion < 1) throw RestoreException("Ce fichier n'est pas une sauvegarde Trackly")
        if (!info.intact) throw RestoreException("Cette sauvegarde est abîmée (contrôle d'intégrité en échec)")
        if (info.schemaVersion > currentSchema) {
            throw RestoreException(
                "Cette sauvegarde vient d'une version plus récente de Trackly (base v${info.schemaVersion}, " +
                    "app v$currentSchema) : mets l'app à jour avant de la restaurer",
            )
        }
        cancelPrepared()
        val candidate = File(workDir, "candidate.db")
        val staging = File(stagedFile.path + ".tmp")
        DatabaseFiles.deleteWithSides(staging)
        DatabaseFiles.copyWithSides(source, candidate)
        var consumptions: Int? = null
        var lastDay: String? = null
        var settings: List<PortableSettings.Entry>? = null
        try {
            val db = try {
                openWithRoom(candidate).also { it.openHelper.writableDatabase } // migrations + validation Room
            } catch (e: Exception) {
                throw RestoreException("Migration de la sauvegarde impossible : ${e.message}", e)
            }
            try {
                val sqlite = db.openHelper.writableDatabase
                // Les réglages joints à la copie n'entrent jamais dans la base de l'app : retirés ici, remis au redémarrage.
                settings = try {
                    PortableSettings.detach(sqlite)
                } catch (e: Exception) {
                    throw RestoreException("Réglages de la sauvegarde illisibles : ${e.message}", e)
                }
                runCatching {
                    sqlite.query(ACTIVE_SUMMARY).use { c ->
                        if (c.moveToFirst()) {
                            consumptions = c.getInt(0)
                            lastDay = if (c.isNull(1)) null else c.getString(1)
                        }
                    }
                }
                DatabaseSnapshotter({ db }).snapshot(staging)
            } finally {
                db.close()
            }
        } finally {
            DatabaseFiles.deleteWithSides(candidate)
        }
        val staged = DatabaseFiles.inspect(staging)
        if (staged == null || !staged.intact || staged.schemaVersion != currentSchema) {
            DatabaseFiles.deleteWithSides(staging)
            throw RestoreException("La base préparée n'a pas passé le contrôle final")
        }
        DatabaseFiles.SIDE_SUFFIXES.forEach { File(staging.path + it).delete() }
        val versions = runCatching { BackupContents.of(staging).versions }.getOrNull()
        DatabaseFiles.SIDE_SUFFIXES.forEach { File(staging.path + it).delete() }
        val prepared = PreparedRestore(label, info.schemaVersion, consumptions, lastDay, versions, carriesSettings = settings != null)
        if (!accept(prepared)) {
            DatabaseFiles.deleteWithSides(staging)
            return prepared.copy(published = false)
        }
        // Réglages et condition d'abord : la base préparée, posée en dernier, décide seule qu'une restauration attend.
        settings?.let { PendingRestore.writeDurably(PendingRestore.settingsFile(databaseFile), PortableSettings.toJson(it)) }
        onlyIfBase?.let { PendingRestore.writeDurably(PendingRestore.conditionFile(databaseFile), it) }
        if (!staging.renameTo(stagedFile)) {
            PendingRestore.discardSides(databaseFile)
            DatabaseFiles.deleteWithSides(staging)
            throw RestoreException("Impossible de préparer la restauration (écriture locale)")
        }
        onStep(RestoreStep.Published)
        status.pendingRestoreLabel = label
        return prepared
    }

    /** Abandonne une restauration préparée mais pas encore appliquée. */
    fun cancelPrepared() {
        DatabaseFiles.deleteWithSides(stagedFile)
        PendingRestore.discardSides(databaseFile)
        status.pendingRestoreLabel = null
    }

    private fun localLabel(file: File): String {
        val stamp = Regex("""(\d{8}-\d{6})""").find(file.name)?.value
        val at = stamp?.let { runCatching { java.time.LocalDateTime.parse(it, STAMP).atOffset(ZoneOffset.UTC).toInstant() }.getOrNull() }
        val kind = when {
            file.name.startsWith(PendingRestore.SAFETY_PREFIX) -> "avant la restauration"
            file.name.startsWith(PendingRestore.UNAPPLIED_PREFIX) -> "non appliquée (sauvegarde choisie)"
            else -> "avant une mise à jour"
        }
        return if (at != null) "Copie $kind du ${LABEL.format(at.atZone(zone))}" else "Copie $kind"
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val LABEL = DateTimeFormatter.ofPattern("dd.MM.yyyy 'à' HH:mm")

        /** Consommations actives (version courante de chaque entrée) et dernier jour concerné. */
        private const val ACTIVE_SUMMARY =
            "SELECT COUNT(*), MAX(c.day) FROM consumption_versions c " +
                "WHERE c.state = 'active' AND c.version = " +
                "(SELECT MAX(v.version) FROM consumption_versions v WHERE v.entry_id = c.entry_id)"
    }
}

/** Ce qu'a donné le redémarrage, pour une restauration préparée. */
sealed interface RestoreApplication {
    /**
     * Base remplacée. [safetyCopy] : copie de sécurité de l'ancienne base (absente s'il n'y en avait pas). [label] :
     * libellé de la restauration, noté au début du remplacement.
     */
    data class Applied(
        val safetyCopy: File?,
        val settings: List<PortableSettings.Entry>?,
        val label: String? = null,
    ) : RestoreApplication

    /** Rien remplacé : la base préparée était abîmée, ou l'app a servi depuis la décision d'une restauration automatique. */
    data class Discarded(val reason: String) : RestoreApplication

    /**
     * Base remplacée, mais la suite (réglages, restauration notée, garde-fou levé, préchargement prévenu) n'a pas pu
     * être écrite : le journal reste et la finit au lancement suivant, sans rien remplacer.
     */
    data class Interrupted(val reason: String) : RestoreApplication
}

/**
 * Application d'une restauration préparée, au démarrage, **avant** l'ouverture de la base :
 * aucune connexion n'est ouverte, les fichiers sont au repos.
 *
 * Ordre sûr, rejouable quel que soit l'instant où le processus est tué, même plusieurs fois :
 *
 * 1. contrôles (base préparée intacte ; restauration automatique : la base actuelle a encore l'empreinte jugée neuve,
 *    [conditionFile]) ; sinon rien n'est remplacé ;
 * 2. **journal** « copie » ([journalFile]) : nom de la copie de sécurité et libellé, fixés une fois pour toutes ;
 * 3. copie de sécurité de la base actuelle, avec son WAL, dans `no_backup/db-backups/`, forcée sur le disque ;
 * 4. journal « remplacement » : à partir d'ici, la base actuelle peut être modifiée ;
 * 5. fichiers annexes de l'ancienne base supprimés (un ancien WAL ne doit jamais être rejoué sur la nouvelle), renommage
 *    atomique ; en cas d'échec, la base d'origine est remise avant toute ouverture (sinon les données présentes restent et
 *    la sauvegarde préparée est mise à part, jamais appliquée seule) ;
 * 6. réglages de la copie remis, puis ce qui suit une restauration ([applyPending], `onApplied`), écrit sur le disque ;
 * 7. journal et fichiers annexes retirés : la restauration est finie.
 *
 * Au lancement suivant, un journal fait reprendre là où l'arrêt a eu lieu, **sans réévaluer les contrôles** (la base
 * actuelle peut déjà avoir perdu son WAL) : « copie » refait la copie sous le même nom (la base n'a pas encore été
 * touchée), « remplacement » ne refait jamais la copie de sécurité, qui reste celle de la base d'origine.
 */
object PendingRestore {
    const val SAFETY_PREFIX = "trackly-avant-restauration-"

    /** Sauvegarde préparée qui n'a pas pu être appliquée, gardée à part : jamais appliquée seule. */
    const val UNAPPLIED_PREFIX = "trackly-restauration-non-appliquee-"
    const val KEEP_SAFETY_COPIES = 3
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun stagedFile(database: File): File = File(database.path + ".restore")

    /** Réglages de la sauvegarde préparée ([PortableSettings]), remis dans les préférences au remplacement. */
    fun settingsFile(database: File): File = File(database.path + ".restore-reglages.json")

    /** Restauration automatique : empreinte que la base actuelle doit encore avoir. */
    fun conditionFile(database: File): File = File(database.path + ".restore-condition")

    /** Journal du remplacement en cours : présent du début de la copie de sécurité à la fin de la restauration. */
    fun journalFile(database: File): File = File(database.path + ".restore-journal")

    internal fun discardSides(database: File) {
        settingsFile(database).delete()
        conditionFile(database).delete()
        journalFile(database).delete()
        File(journalFile(database).path + ".tmp").delete()
    }

    /** Écrit un fichier annexe et le force sur le disque : il doit exister dès que la base préparée existe. */
    internal fun writeDurably(file: File, text: String) {
        java.io.FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** État noté dans le journal. [replacing] faux : la copie de sécurité est en cours, la base n'est pas touchée. */
    private data class Journal(val replacing: Boolean, val safety: File?, val label: String?) {
        fun write(database: File) {
            val tmp = File(journalFile(database).path + ".tmp")
            writeDurably(tmp, listOf(if (replacing) REPLACING else COPYING, safety?.path.orEmpty(), label.orEmpty()).joinToString("\n"))
            if (!tmp.renameTo(journalFile(database))) throw IOException("journal de restauration non écrit")
        }

        companion object {
            const val COPYING = "copie"
            const val REPLACING = "remplacement"

            /** Journal illisible : traité comme « remplacement », la base d'origine n'est jamais recopiée par-dessus sa copie. */
            fun read(database: File): Journal? {
                val file = journalFile(database)
                if (!file.isFile) return null
                val lines = runCatching { file.readText().split("\n") }.getOrDefault(emptyList())
                return Journal(
                    replacing = lines.getOrNull(0) != COPYING,
                    safety = lines.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(::File),
                    label = lines.getOrNull(2)?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /** Applique la restauration en attente ; renvoie la copie de sécurité, ou `null` s'il n'y avait rien à faire. */
    fun applyIfPending(database: File, safetyDir: File, clock: Clock): File? =
        (applyPending(database, safetyDir, clock) as? RestoreApplication.Applied)?.let { it.safetyCopy ?: database }

    /**
     * Applique la restauration en attente, ou reprend celle qu'un arrêt a interrompue ; `null` s'il n'y en avait pas.
     * [beforeReplace] reçoit les réglages de la copie juste après le remplacement ; [onApplied] fait ce qui suit une
     * restauration, avant que le journal ne soit retiré (rejoué si le processus s'arrête pendant, ou si ce n'est pas
     * écrit). Ne lève jamais d'exception : l'app s'ouvre quoi qu'il arrive. Un échec avant ou pendant le remplacement
     * laisse ou remet la base d'origine ; un échec après laisse le journal, qui finira la restauration au lancement
     * suivant sans rien remplacer.
     */
    fun applyPending(
        database: File,
        safetyDir: File,
        clock: Clock,
        beforeReplace: (List<PortableSettings.Entry>?) -> Unit = {},
        /** Libellé de la restauration préparée, gardé dans le journal. */
        label: String? = null,
        /** Ce qui suit une restauration ; faux si ce n'est pas écrit sur le disque (le journal reste). */
        onApplied: (RestoreApplication.Applied) -> Boolean = { true },
        onStep: (RestoreStep) -> Unit = {},
    ): RestoreApplication? {
        val staged = stagedFile(database)
        var journal = Journal.read(database)
        if (journal == null) {
            if (!staged.isFile) {
                discardSides(database)
                return null
            }
            val info = DatabaseFiles.inspect(staged)
            DatabaseFiles.SIDE_SUFFIXES.forEach { File(staged.path + it).delete() }
            if (info == null || !info.intact || !info.isRoomDatabase) {
                // Ne devrait pas arriver (contrôlée à la préparation) : on garde la base actuelle.
                return discard(database, null, "La sauvegarde préparée n'a pas passé le contrôle au redémarrage")
            }
            val condition = conditionFile(database).takeIf { it.isFile }?.readText()
            if (condition != null && condition != fingerprintOf(database)) {
                return discard(database, null, "L'app a servi entre la décision et le redémarrage : elle n'est plus remplacée automatiquement")
            }
            journal = Journal(
                replacing = false,
                safety = if (database.isFile) File(safetyDir, "$SAFETY_PREFIX${STAMP.format(clock.instant())}.db") else null,
                label = label,
            )
        } else if (!journal.replacing && !staged.isFile) {
            // Copie de sécurité commencée, base préparée disparue : rien à appliquer, la base actuelle reste.
            return discard(database, journal, "La sauvegarde préparée a disparu avant le remplacement")
        }
        if (!journal.replacing) {
            try {
                journal.write(database)
                onStep(RestoreStep.CopyJournaled)
                journal.safety?.let { safety ->
                    DatabaseFiles.copyWithSides(database, safety)
                    syncWithSides(safety)
                    prune(safetyDir)
                }
                onStep(RestoreStep.SafetyCopied)
                journal = journal.copy(replacing = true)
                journal.write(database)
                onStep(RestoreStep.ReplaceJournaled)
            } catch (e: Exception) {
                // Rien n'a encore touché la base actuelle : elle reste, la restauration est abandonnée.
                return discard(database, journal, "La restauration n'a pas pu commencer (${e.message}) : la base de l'app est restée")
            }
        }
        // Remplacement. Base préparée absente : un lancement précédent l'a déjà faite, seule la suite reste.
        var replaced = !staged.isFile
        try {
            if (!replaced) {
                DatabaseFiles.SIDE_SUFFIXES.forEach { File(database.path + it).delete() }
                onStep(RestoreStep.SidesDeleted)
                if (!staged.renameTo(database)) {
                    staged.copyTo(database, overwrite = true)
                    staged.delete()
                }
                replaced = true
            }
            onStep(RestoreStep.Replaced)
        } catch (e: Exception) {
            // Jamais d'app ouverte sur une base à moitié remplacée : retour à la base d'origine (SAUV-06).
            if (!replaced) return rollBack(database, safetyDir, clock, journal, e, onStep)
        }
        // La base est la copie : la suite (réglages, restauration notée…) se rejoue tant qu'elle n'est pas écrite.
        return try {
            val settings = settingsFile(database).takeIf { it.isFile }?.let { f ->
                runCatching { PortableSettings.fromJson(f.readText()) }.getOrNull()
            }
            beforeReplace(settings)
            onStep(RestoreStep.SettingsApplied)
            val applied = RestoreApplication.Applied(journal.safety?.takeIf { it.isFile }, settings, journal.label)
            if (!onApplied(applied)) return unfinished("son suivi n'a pas pu être enregistré")
            discardSides(database)
            applied
        } catch (e: Exception) {
            unfinished(e.message)
        }
    }

    /** Base remplacée, suite pas encore écrite : le journal reste et la refera au lancement suivant (sans rien remplacer). */
    private fun unfinished(why: String?) = RestoreApplication.Interrupted(
        "Restauration faite, mais pas finie ($why) : elle se finira au prochain lancement de l'app",
    )

    /**
     * Le remplacement a échoué : la base d'origine (copie de sécurité, journal WAL compris) est remise avant toute
     * ouverture. Si cela échoue aussi, les données présentes restent et la sauvegarde préparée est gardée à part
     * ([UNAPPLIED_PREFIX], dans les copies du téléphone) : jamais appliquée seule sur une base qui a pu changer.
     * Le journal est retiré en premier : un arrêt ensuite ne fait jamais reprendre le remplacement sur cette base.
     */
    private fun rollBack(
        database: File,
        safetyDir: File,
        clock: Clock,
        journal: Journal,
        cause: Exception,
        onStep: (RestoreStep) -> Unit,
    ): RestoreApplication.Discarded {
        val why = cause.message
        return try {
            onStep(RestoreStep.RollingBack)
            val safety = journal.safety
            if (safety != null) DatabaseFiles.copyWithSides(safety, database) else DatabaseFiles.deleteWithSides(database)
            journalFile(database).delete()
            DatabaseFiles.deleteWithSides(stagedFile(database))
            discardSides(database)
            RestoreApplication.Discarded(
                "la sauvegarde n'a pas pu être appliquée ($why) ; tes données d'avant sont remises, rien n'est perdu. " +
                    "Tu peux la refaire depuis Restaurer",
            )
        } catch (_: Exception) {
            journalFile(database).delete()
            val staged = stagedFile(database)
            val kept = File(safetyDir, "$UNAPPLIED_PREFIX${STAMP.format(clock.instant())}.db")
            if (staged.isFile && runCatching { safetyDir.mkdirs(); staged.renameTo(kept) }.getOrDefault(false)) {
                discardSides(database)
            } else if (staged.isFile) {
                // Pas pu la mettre à part : elle attend, mais seulement sur la base telle qu'elle est maintenant.
                runCatching { writeDurably(conditionFile(database), fingerprintOf(database)) }
            }
            RestoreApplication.Discarded(
                "la sauvegarde n'a pas pu être appliquée ($why), ni tes données d'avant remises : tes données actuelles " +
                    "sont gardées. Dans Restaurer › Choisir une sauvegarde : « Copie non appliquée » est la sauvegarde " +
                    "choisie, « Copie avant la restauration » tes données d'avant",
            )
        }
    }

    /** Abandon avant toute modification de la base actuelle : base préparée, annexes et copie de sécurité commencée. */
    private fun discard(database: File, journal: Journal?, reason: String): RestoreApplication.Discarded {
        DatabaseFiles.deleteWithSides(stagedFile(database))
        journal?.safety?.let(DatabaseFiles::deleteWithSides)
        discardSides(database)
        return RestoreApplication.Discarded(reason)
    }

    /** Force sur le disque la copie de sécurité et ses fichiers annexes, avant que la base d'origine ne soit touchée. */
    private fun syncWithSides(file: File) {
        (listOf(file) + DatabaseFiles.SIDE_SUFFIXES.map { File(file.path + it) }).filter { it.isFile }.forEach { f ->
            java.io.RandomAccessFile(f, "rw").use { it.fd.sync() }
        }
    }

    /**
     * Empreinte de la base actuelle, fermée : lue sur une copie (base et journal WAL, pas l'index `-shm`), pour ne
     * jamais toucher la base elle-même. Base absente : celle d'une base vide.
     */
    internal fun fingerprintOf(database: File): String {
        if (!database.isFile) return BackupContents(0, 0, 0).fingerprint
        val copy = File(database.path + ".verification")
        DatabaseFiles.deleteWithSides(copy)
        return try {
            database.copyTo(copy, overwrite = true)
            File(database.path + "-wal").takeIf { it.isFile }?.copyTo(File(copy.path + "-wal"), overwrite = true)
            // Rejoue le journal dans la copie : la lecture voit tout ce qui a été écrit.
            android.database.sqlite.SQLiteDatabase.openDatabase(copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
            BackupContents.of(copy).fingerprint
        } catch (e: Exception) {
            // Illisible : jamais égale à une empreinte attendue, donc jamais de remplacement automatique.
            "illisible"
        } finally {
            DatabaseFiles.deleteWithSides(copy)
        }
    }

    private fun prune(dir: File) {
        dir.listFiles { f -> f.name.startsWith(SAFETY_PREFIX) && f.name.endsWith(".db") }.orEmpty()
            .sortedByDescending { it.name }
            .drop(KEEP_SAFETY_COPIES)
            .forEach(DatabaseFiles::deleteWithSides)
    }
}
