package app.trackly.backup

import android.app.PendingIntent
import app.trackly.backup.drive.DriveApi
import app.trackly.backup.drive.DriveAuthException
import app.trackly.backup.drive.DriveAuthorization
import app.trackly.backup.drive.DriveAuthorizer
import app.trackly.core.importing.ImportOutcome
import app.trackly.day.DayLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Écran Google refermé sans motif : par l'utilisateur, ou faute de projet Google Cloud prêt. */
const val CONSENT_UNFINISHED = "Google n’a pas terminé la connexion. Si tu as choisi ton compte et que ça s’arrête là, le projet Google Cloud de Trackly n’est pas prêt."

/** Opération en cours, affichée à la place des boutons. */
enum class BackupBusy(val label: String, val nearRestore: Boolean = false, val nearExport: Boolean = false) {
    Connecting("Connexion à Google…"),
    LookingForBackup("Recherche de ta sauvegarde sur Drive…"),
    BackingUp("Sauvegarde en cours…"),
    Listing("Recherche des sauvegardes…", nearRestore = true),
    Preparing("Vérification du fichier…", nearRestore = true),
    Restarting("Redémarrage de Trackly…", nearRestore = true),
    Exporting("Export en cours…", nearExport = true),
}

/** [nearRestore], [nearExport] : affiché à côté des boutons de restauration ou d'export, là où l'utilisateur vient de toucher. */
data class BackupNotice(val text: String, val error: Boolean = false, val nearRestore: Boolean = false, val nearExport: Boolean = false)

/** Une sauvegarde proposée à la restauration. */
data class RestoreChoice(val key: String, val title: String, val detail: String, val source: Source) {
    sealed interface Source {
        data class Drive(val entry: BackupEntry) : Source
        data class Local(val file: File) : Source
    }
}

data class BackupUiState(
    val status: BackupStatus = BackupStatus(),
    val busy: BackupBusy? = null,
    val notice: BackupNotice? = null,
    /** Écran Google à afficher (choix du compte, consentement). */
    val consent: PendingIntent? = null,
    /** Liste des sauvegardes ouverte (null = fermée). */
    val choices: List<RestoreChoice>? = null,
    /** Le dossier Trackly n'a pas été trouvé en listant les sauvegardes. */
    val folderMissing: Boolean = false,
    /** Sauvegarde choisie, en attente de confirmation. */
    val confirming: RestoreChoice? = null,
    /** Nom proposé au sélecteur de fichiers pour un export : l'écran l'ouvre, puis appelle [BackupController.exportPickerOpened]. */
    val exportName: String? = null,
)

/** Planification des sauvegardes en arrière-plan (WorkManager en vrai, faux en test). */
interface BackupScheduling {
    /** Filet de nuit. */
    fun schedule()

    /** Copie automatique bientôt, si des changements ne sont pas encore sur Drive. */
    fun backupSoon()

    /** Arrête le filet de nuit et la sauvegarde automatique. */
    fun cancel()
}

/**
 * Ce que fait l'écran Réglages › Sauvegarde : connexion Google (consentement unique), sauvegarde
 * immédiate, liste et restauration. Les opérations tournent dans la portée de l'application :
 * quitter l'écran ne les interrompt pas.
 */
class BackupController(
    private val runner: BackupRunner,
    private val restore: RestoreManager,
    private val folder: BackupFolder,
    private val authorizer: DriveAuthorizer,
    private val drive: () -> DriveApi,
    private val status: BackupStatusStore,
    private val scheduling: BackupScheduling,
    private val restarter: AppRestarter,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val zone: ZoneId,
    /** Reprise d'un export d'historique, s'il est choisi à la place d'une sauvegarde. */
    private val importHistory: suspend (String) -> ImportOutcome,
    /** Restauration automatique d'une app neuve, à la connexion ; `null` : jamais. */
    private val autoRestore: AutoRestore? = null,
    /** Réponse à la proposition de connexion faite à une app neuve. */
    private val fresh: FreshInstall? = null,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    private val ui = MutableStateFlow(BackupUiState(status = status.status.value))
    val state: StateFlow<BackupUiState> = ui.asStateFlow()

    /** Action à reprendre une fois l'écran Google validé. */
    private var afterConsent: (() -> Unit)? = null

    init {
        scope.launch { status.status.collect { s -> ui.update { it.copy(status = s) } } }
    }

    /**
     * Connexion à Google Drive. Sur une app neuve, la sauvegarde la plus récente est d'abord cherchée et, s'il y a lieu,
     * restaurée sans autre geste : l'app redémarre avec ses données ([AutoRestore]).
     */
    fun connect() = run(BackupBusy.Connecting) {
        when (val auth = authorizer.authorize()) {
            is DriveAuthorization.Granted -> {
                status.markConnected(auth.accountEmail)
                val auto = autoRestore?.let { restoring ->
                    ui.update { it.copy(busy = BackupBusy.LookingForBackup) }
                    io { restoring.check() }
                }
                if (auto is AutoRestoreOutcome.Prepared) {
                    fresh?.answer(RestoreOffer.Answered)
                    ui.update {
                        it.copy(busy = BackupBusy.Restarting, notice = BackupNotice("${auto.label} trouvée : Trackly redémarre avec tes données."))
                    }
                    restarter.restart()
                    return@run
                }
                // Des copies avaient déjà réussi : un dossier créé maintenant veut dire que Trackly ne voit plus l'ancien.
                val hadCopies = status.status.value.lastSuccessAt != null
                val result = io { folder.findOrCreate(drive()) }
                val hidden = (auto is AutoRestoreOutcome.NoBackup && !auto.folderFound) || (result.created && hadCopies)
                if (hidden) status.markHiddenCopies(clock.instant())
                scheduling.schedule()
                scheduling.backupSoon()
                if (auto is AutoRestoreOutcome.Failed) {
                    // Connecté, mais rien de restauré : la proposition reste ouverte (réessayer, ou « Plus tard »).
                    notice(
                        "Connecté, mais ta sauvegarde n’a pas pu être reprise : ${auto.reason}. Réessaie, ou choisis-la dans " +
                            "Réglages › Sauvegarde › Choisir une sauvegarde.",
                        error = true,
                    )
                    return@run
                }
                fresh?.answer(RestoreOffer.Answered)
                notice(connectedMessage(result.created, auto, hadCopies))
            }
            is DriveAuthorization.NeedsUserAction -> askConsent(auth, ::connect)
            is DriveAuthorization.Failed -> notice(auth.message, error = true)
        }
    }

    fun backupNow() = run(BackupBusy.BackingUp) {
        when (val result = io { runner.backUp() }) {
            is BackupResult.Success -> notice(
                "Sauvegarde envoyée et vérifiée : ${result.name}" +
                    (if (result.trashedOld > 0) " (${result.trashedOld} ancienne(s) mise(s) à la corbeille)" else ""),
            )
            is BackupResult.Failure -> {
                val action = result.userAction
                if (action != null) askConsent(action, ::backupNow) else notice(result.message, error = true)
            }
        }
    }

    /**
     * Étape 1 de « Exporter dans un fichier » : rien n'est créé tant qu'il n'y a rien à exporter. Sinon le nom daté est
     * proposé au sélecteur de fichiers ([BackupUiState.exportName]).
     */
    fun startExport() = run(BackupBusy.Exporting) {
        val hasData = try {
            io { runner.hasDataToExport() }
        } catch (e: SnapshotException) {
            // Un problème de base locale, pas de Drive : le message ne parle pas de connexion.
            notice("Copie de la base impossible : ${e.message}", error = true, nearExport = true)
            return@run
        }
        if (!hasData) {
            notice(NOTHING_TO_EXPORT, nearExport = true)
            return@run
        }
        ui.update { it.copy(exportName = runner.exportName()) }
    }

    /** Android n'a aucune application pour choisir où écrire un fichier. */
    fun exportUnavailable() = notice(
        "Ce téléphone n’a pas de sélecteur de fichiers : l’export est impossible ici.",
        error = true,
        nearExport = true,
    )

    /** Le sélecteur de fichiers est ouvert : la demande est consommée, une rotation de l'écran ne le rouvre pas. */
    fun exportPickerOpened() = ui.update { it.copy(exportName = null) }

    /** Étape 2 : l'emplacement choisi (`null` : le sélecteur a été fermé sans choix, rien ne se passe). */
    fun onExportDestination(target: ExportTarget?) {
        ui.update { it.copy(exportName = null) }
        if (target == null) return
        if (ui.value.busy != null) {
            // Rien n'a été écrit : le fichier vide créé par le sélecteur est retiré, et l'utilisateur est prévenu.
            scope.launch {
                io { target.discardIfEmpty() }
                notice("Une autre opération est en cours : réessaie dans un instant.", error = true, nearExport = true)
            }
            return
        }
        run(BackupBusy.Exporting) {
            when (val result = io { runner.exportTo(target) }) {
                is ExportResult.Success -> notice(
                    "Fichier exporté et vérifié" + (io { target.displayName }?.let { " : $it" } ?: "") +
                        " (${(result.sizeBytes + 1023) / 1024} Ko). Pour le reprendre : Restaurer › Depuis un fichier.",
                    nearExport = true,
                )
                ExportResult.NothingToExport -> notice(NOTHING_TO_EXPORT, nearExport = true)
                is ExportResult.Failure -> notice(result.message, error = true, nearExport = true)
            }
        }
    }

    /**
     * Arrête la sauvegarde automatique et oublie la connexion sur ce téléphone. L'accès accordé chez Google n'est
     * **jamais** retiré : avec `drive.file`, un retrait peut cacher à Trackly ses anciennes sauvegardes, même après
     * reconnexion. « Connecter Google Drive » retrouve ensuite le même dossier, et le garde-fou garde sa référence.
     */
    fun disconnect() = run(BackupBusy.Connecting) {
        status.markDisconnected()
        scheduling.cancel()
        notice(
            "Google Drive déconnecté sur ce téléphone : plus aucune copie ne part. Tes sauvegardes restent dans ton Drive, " +
                "et « Connecter Google Drive » les retrouve. Ne retire pas l’accès de Trackly depuis ton compte Google : " +
                "il pourrait ne plus revoir ces sauvegardes (elles resteraient dans Drive).",
        )
    }

    /** Ouvre la liste : sauvegardes Drive (si connecté) et copies gardées sur le téléphone. */
    fun openRestore() = run(BackupBusy.Listing) {
        val local = restore.localCopies().map { copy ->
            RestoreChoice("local:${copy.file.name}", copy.label, "Sur le téléphone", RestoreChoice.Source.Local(copy.file))
        }
        if (!status.status.value.connected) {
            ui.update { it.copy(choices = local, folderMissing = false) }
            return@run
        }
        val listing = io { restore.listBackups() }
        val remote = listing.entries.map { entry ->
            RestoreChoice("drive:${entry.id}", title(entry.takenAt), detail(entry), RestoreChoice.Source.Drive(entry))
        }
        ui.update { it.copy(choices = remote + local, folderMissing = !listing.folderFound) }
    }

    fun closeRestore() = ui.update { it.copy(choices = null, confirming = null, folderMissing = false) }

    fun choose(choice: RestoreChoice) = ui.update { it.copy(confirming = choice) }

    fun cancelChoice() = ui.update { it.copy(confirming = null) }

    /** Un fichier de sauvegarde choisi sur le téléphone (téléchargé depuis Drive, par exemple). */
    fun pickFile(name: String?, open: () -> InputStream?) = run(BackupBusy.Preparing) {
        val target = File(workDir, "fichier-choisi.db")
        io {
            target.parentFile?.mkdirs()
            val input = open() ?: throw RestoreException("Fichier illisible")
            input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
        }
        if (io { looksLikeExport(target) }) {
            val outcome = io {
                try {
                    if (target.length() > MAX_EXPORT_BYTES) throw RestoreException("Fichier trop volumineux")
                    importHistory(target.readText())
                } finally {
                    target.delete()
                }
            }
            notice(importMessage(outcome), error = outcome is ImportOutcome.Refused, nearRestore = true)
            return@run
        }
        val label = name ?: "Fichier du téléphone"
        ui.update { it.copy(confirming = RestoreChoice("file", label, "Fichier choisi sur le téléphone", RestoreChoice.Source.Local(target))) }
    }

    /** Vérifie et prépare la sauvegarde choisie, puis redémarre l'app pour l'appliquer. */
    fun confirmRestore() {
        val choice = ui.value.confirming ?: return
        run(BackupBusy.Preparing) {
            io {
                when (val source = choice.source) {
                    is RestoreChoice.Source.Drive -> restore.prepareFromDrive(source.entry, choice.title)
                    is RestoreChoice.Source.Local -> restore.prepareFromFile(source.file, choice.title)
                }
            }
            ui.update { it.copy(busy = BackupBusy.Restarting, confirming = null, choices = null) }
            restarter.restart()
        }
    }

    /** Retour de l'écran Google. [failure] : motif donné par Google (sinon, écran fermé ou projet Google Cloud pas prêt). */
    fun onConsentResult(granted: Boolean, failure: String? = null) {
        val next = afterConsent
        afterConsent = null
        ui.update { it.copy(consent = null) }
        if (granted && next != null) next() else if (!granted) notice(failure ?: CONSENT_UNFINISHED, error = true)
    }

    fun dismissNotice() = ui.update { it.copy(notice = null) }

    /** « Fermer ce message » sous le guide des anciennes copies invisibles. */
    fun dismissHiddenCopies() = status.clearHiddenCopies()

    private fun askConsent(auth: DriveAuthorization.NeedsUserAction, retry: () -> Unit) {
        if (auth.pendingIntent == null) {
            notice("Google demande une reconnexion, mais n'a pas fourni d'écran", error = true)
            return
        }
        afterConsent = retry
        ui.update { it.copy(consent = auth.pendingIntent) }
    }

    private fun notice(text: String, error: Boolean = false, nearRestore: Boolean = false, nearExport: Boolean = false) =
        ui.update { it.copy(notice = BackupNotice(text, error, nearRestore, nearExport)) }

    /** Échec d'une opération : message à côté des boutons touchés, et la confirmation ne reste pas ouverte pour rien. */
    private fun fail(busy: BackupBusy, text: String) {
        ui.update { it.copy(confirming = if (busy == BackupBusy.Preparing) null else it.confirming) }
        notice(text, error = true, nearRestore = busy.nearRestore, nearExport = busy.nearExport)
    }

    /** Un export d'historique est un document JSON ; une sauvegarde, une base SQLite. */
    private fun looksLikeExport(file: File): Boolean = file.inputStream().buffered().use { input ->
        generateSequence { input.read().takeIf { it >= 0 } }
            .firstOrNull { it !in UTF8_MARK && !Character.isWhitespace(it) } == '{'.code
    }

    private fun importMessage(outcome: ImportOutcome): String = when (outcome) {
        is ImportOutcome.Imported -> "Historique repris : ${outcome.report.days} journées."
        is ImportOutcome.NothingNew -> "Cet historique est déjà dans l’app : rien de nouveau."
        is ImportOutcome.Refused -> listOfNotNull(outcome.reason, outcome.details.firstOrNull(), "Rien n’a été repris.")
            .joinToString(". ") { it.trimEnd('.') } + "."
    }

    private fun run(busy: BackupBusy, block: suspend () -> Unit) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = busy, notice = null) }
        scope.launch {
            try {
                block()
            } catch (e: DriveAuthException) {
                val auth = e.authorization
                if (auth is DriveAuthorization.NeedsUserAction) {
                    askConsent(auth) { replay(busy) }
                } else {
                    fail(busy, e.message ?: "Connexion à Google impossible")
                }
            } catch (e: RestoreException) {
                fail(busy, e.message ?: "Restauration impossible")
            } catch (e: IOException) {
                fail(busy, "Pas de connexion à Drive : ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(busy, "Erreur inattendue : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ui.update { if (it.busy == BackupBusy.Restarting) it else it.copy(busy = null) }
            }
        }
    }

    private fun connectedMessage(created: Boolean, auto: AutoRestoreOutcome?, hadCopies: Boolean): String {
        val next = "Une copie y partira quelques minutes après chaque changement."
        return when {
            auto is AutoRestoreOutcome.NoBackup && !auto.folderFound ->
                "Aucune sauvegarde Trackly visible dans ton Drive. Si tu en avais une, Google ne la montre plus à cette " +
                    "installation : Réglages › Sauvegarde › Depuis un fichier ouvre le sélecteur du téléphone, où tu peux " +
                    "choisir Drive directement, puis le dernier fichier trackly-….db du dossier Trackly. " +
                    (if (created) "Nouveau dossier « Trackly » créé. " else "") + next
            auto is AutoRestoreOutcome.NoBackup -> "Dossier « Trackly » retrouvé, sans sauvegarde à reprendre. $next"
            auto is AutoRestoreOutcome.KeptBase ->
                "Les données de l’app sont déjà au moins aussi complètes que la ${auto.backupLabel.replaceFirstChar { it.lowercase() }} : elles restent. $next"
            created && hadCopies ->
                "Nouveau dossier « Trackly » créé : Trackly ne voit plus ses anciennes copies (elles restent dans ton Drive, " +
                    "ancien dossier Trackly). Pour en reprendre une : Réglages › Sauvegarde › Depuis un fichier, puis Drive " +
                    "dans le sélecteur. $TRASHED_FOLDER $next"
            created -> "Dossier « Trackly » créé dans ton Drive. $next"
            else -> "Dossier « Trackly » retrouvé dans ton Drive. $next"
        }
    }

    private fun replay(busy: BackupBusy) = when (busy) {
        BackupBusy.Connecting, BackupBusy.LookingForBackup -> connect()
        BackupBusy.BackingUp -> backupNow()
        BackupBusy.Listing -> openRestore()
        BackupBusy.Preparing -> confirmRestore()
        BackupBusy.Restarting, BackupBusy.Exporting -> Unit
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun title(at: Instant): String = BackupLabels.moment(at, zone)

    private fun detail(entry: BackupEntry): String {
        val size = entry.sizeBytes?.let { "${(it + 1023) / 1024} Ko" } ?: "taille inconnue"
        return "Drive · $size"
    }

}

/** Libellés de dates de la sauvegarde. */
object BackupLabels {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

    /** « Lundi 2 novembre, 03:12 » (heure locale). */
    fun moment(at: Instant, zone: ZoneId): String {
        val local = at.atZone(zone)
        return "${DayLabels.format(local.toLocalDate())}, ${TIME.format(local)}"
    }

    /** Même moment au milieu d'une phrase (« faite le lundi 2 novembre, 03:12 »). */
    fun inSentence(at: Instant, zone: ZoneId): String = moment(at, zone).replaceFirstChar { it.lowercase(Locale.FRENCH) }
}

/** Un dossier Trackly mis à la corbeille paraît invisible ; Drive le garde 30 jours. */
internal const val TRASHED_FOLDER =
    "Dossier Trackly mis à la corbeille de Drive : sors-le de la corbeille (30 jours), puis Déconnecter et Connecter Google Drive."

private const val NOTHING_TO_EXPORT = "Rien à exporter : l’app ne contient encore aucune donnée."

private const val MAX_EXPORT_BYTES = 64L * 1024 * 1024
private val UTF8_MARK = setOf(0xEF, 0xBB, 0xBF)
