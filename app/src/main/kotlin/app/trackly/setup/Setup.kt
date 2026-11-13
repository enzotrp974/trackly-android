package app.trackly.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.KeyVerification
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.backup.BackupStatus
import app.trackly.backup.FreshInstall
import app.trackly.backup.InstallState
import app.trackly.backup.RestoreOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Les étapes de la mise en route, dans l'ordre où on les propose. */
enum class SetupStep { Key, History, Drive, Notifications, Battery, ExportFile }

/** Une ligne de la liste : [done] la coche ; [detail] ne dit que l'utile (un problème, une consigne courte), souvent rien. */
data class SetupItem(val step: SetupStep, val title: String, val detail: String, val done: Boolean, val manual: Boolean = false)

/** La liste de mise en route telle qu'affichée. */
data class SetupState(val items: List<SetupItem>, val dismissed: Boolean = false) {
    val doneCount: Int get() = items.count { it.done }
    val total: Int get() = items.size

    /** Tout est coché, ou l'utilisateur a dit « Terminer » : la liste ne se propose plus d'elle-même. */
    val finished: Boolean get() = dismissed || items.all { it.done }

    /** « 2 étapes sur 5 » (pour l'entrée des Réglages). */
    val progress: String get() = "$doneCount sur $total"

    fun item(step: SetupStep): SetupItem? = items.firstOrNull { it.step == step }

    companion object {
        /** Ce qu'on sait de l'appareil et de l'app, ramené aux cinq lignes. */
        fun of(
            key: ApiKeyStatus,
            verification: KeyVerificationState,
            notificationsAllowed: Boolean,
            driveConnected: Boolean,
            batteryDone: Boolean,
            importDone: Boolean,
            exportFileDeleted: Boolean,
            dismissed: Boolean,
            /** L'historique venait de l'APK : aucun fichier d'export à supprimer. */
            historyEmbedded: Boolean = false,
            /** App neuve : la connexion Google, qui ramène la sauvegarde, est proposée en premier. */
            restoreOffer: Boolean = false,
        ): SetupState {
            val keyPresent = key is ApiKeyStatus.Present
            val drive = if (restoreOffer) {
                SetupItem(SetupStep.Drive, "Google Drive", RESTORE_OFFER_DETAIL, done = false)
            } else {
                SetupItem(SetupStep.Drive, "Google Drive", "", done = driveConnected)
            }
            val items = buildList {
                if (restoreOffer) add(drive)
                add(
                    SetupItem(
                        SetupStep.Key,
                        "Clé OpenAI",
                        when {
                            !keyPresent || verification == KeyVerificationState.Valid -> ""
                            verification == KeyVerificationState.Refused -> "OpenAI refuse cette clé."
                            verification is KeyVerificationState.Pending -> "Pas encore vérifiée : pas de réseau."
                            verification == KeyVerificationState.Checking -> "Vérification…"
                            else -> "Pas encore vérifiée."
                        },
                        done = keyPresent && verification == KeyVerificationState.Valid,
                    ),
                )
                add(SetupItem(SetupStep.History, "Historique", "", done = importDone))
                if (!restoreOffer) add(drive)
                add(SetupItem(SetupStep.Notifications, "Notifications", "", done = notificationsAllowed))
                add(
                    SetupItem(
                        SetupStep.Battery,
                        "Batterie (Samsung)",
                        "Sans restriction, et jamais en veille.",
                        done = batteryDone,
                        manual = true,
                    ),
                )
                if (importDone && !historyEmbedded) {
                    add(
                        SetupItem(
                            SetupStep.ExportFile,
                            "Fichier d’export",
                            "Supprime-le du téléphone et de Drive.",
                            done = exportFileDeleted,
                            manual = true,
                        ),
                    )
                }
            }
            return SetupState(items, dismissed)
        }

        const val RESTORE_OFFER_DETAIL = "Connecte-le d’abord : si Trackly y a une sauvegarde, tout revient tout seul."
    }
}

/** Ce que l'utilisateur a coché lui-même ou décidé, gardé sur le téléphone (réglage d'interface, pas une donnée). */
interface SetupPrefs {
    var batteryDone: Boolean
    var exportFileDeleted: Boolean
    var dismissed: Boolean

    /** La liste s'est déjà ouverte d'elle-même une fois (après la reprise, ou au premier lancement). */
    var autoOpened: Boolean

    /** L'historique embarqué dans l'APK a été repris : il ne le sera plus jamais. */
    var historyPreloaded: Boolean

    /**
     * Le préchargement a commencé à écrire sur cette installation, sans forcément finir : il reprend au lancement
     * suivant même si la base n'est plus vide. Jamais rendu par Auto Backup (préférences non sauvegardées).
     */
    var historyPreloadStarted: Boolean

    /** Pose [historyPreloaded] tout de suite sur le disque ; faux si l'écriture a échoué. */
    fun markHistoryPreloaded(): Boolean {
        historyPreloaded = true
        return true
    }
}

class SharedSetupPrefs(context: Context) : SetupPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-mise-en-route", Context.MODE_PRIVATE)

    private fun flag(key: String) = prefs.getBoolean(key, false)

    private fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override var batteryDone: Boolean
        get() = flag("batterie")
        set(value) = set("batterie", value)
    override var exportFileDeleted: Boolean
        get() = flag("export_supprime")
        set(value) = set("export_supprime", value)
    override var dismissed: Boolean
        get() = flag("terminee")
        set(value) = set("terminee", value)
    override var autoOpened: Boolean
        get() = flag("ouverte")
        set(value) = set("ouverte", value)
    override var historyPreloaded: Boolean
        get() = flag("historique_embarque")
        set(value) = set("historique_embarque", value)

    override fun markHistoryPreloaded(): Boolean = prefs.edit().putBoolean("historique_embarque", true).commit()
    override var historyPreloadStarted: Boolean
        get() = flag("historique_embarque_commence")
        set(value) = set("historique_embarque_commence", value)
}

class InMemorySetupPrefs : SetupPrefs {
    override var batteryDone = false
    override var exportFileDeleted = false
    override var dismissed = false
    override var autoOpened = false
    override var historyPreloaded = false
    override var historyPreloadStarted = false
}

/**
 * La liste de mise en route, recalculée à chaque changement : clé (et sa vérification), notifications, Drive, reprise.
 * Les étapes qu'Android ne laisse pas lire (batterie) ou qui sont hors de l'app (supprimer le fichier) se cochent à la main.
 */
class SetupChecklist(
    private val keyStore: ApiKeyStore,
    verification: KeyVerification,
    notificationsAllowed: StateFlow<Boolean>,
    backup: StateFlow<BackupStatus>,
    importDone: Flow<Boolean>,
    private val prefs: SetupPrefs,
    scope: CoroutineScope,
    /** Installation : sur une app neuve, la connexion Google est proposée d'abord, à chaque lancement jusqu'à une réponse. */
    private val install: FreshInstall? = null,
) {
    private val manual = MutableStateFlow(Manual(prefs.batteryDone, prefs.exportFileDeleted, prefs.dismissed))

    private data class Manual(val battery: Boolean, val exportFile: Boolean, val dismissed: Boolean)

    private val installState: StateFlow<InstallState> = install?.state ?: MutableStateFlow(InstallState(fresh = false, offer = RestoreOffer.Answered))

    val state: StateFlow<SetupState> = combine(
        keyStore.status, verification.state, notificationsAllowed, backup, importDone, manual, installState,
    ) { values ->
        val key = values[0] as ApiKeyStatus
        val check = values[1] as KeyVerificationState
        val allowed = values[2] as Boolean
        val drive = values[3] as BackupStatus
        val imported = values[4] as Boolean
        val manual = values[5] as Manual
        val installation = values[6] as InstallState
        SetupState.of(
            key, check, allowed, drive.connected, manual.battery, imported, manual.exportFile, manual.dismissed, prefs.historyPreloaded,
            restoreOffer = installation.offerPending,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        SetupState.of(
            ApiKeyStatus.Unknown, KeyVerificationState.Unchecked, false, false, prefs.batteryDone, false, prefs.exportFileDeleted, prefs.dismissed,
            restoreOffer = installState.value.offerPending,
        ),
    )

    fun markBatteryDone(done: Boolean = true) {
        prefs.batteryDone = done
        manual.value = manual.value.copy(battery = done)
    }

    fun markExportFileDeleted(done: Boolean = true) {
        prefs.exportFileDeleted = done
        manual.value = manual.value.copy(exportFile = done)
    }

    /** « Terminer » : la liste ne se rouvre plus d'elle-même, mais reste accessible dans les Réglages tant qu'il manque une étape. */
    fun dismiss() {
        install?.decline()
        prefs.dismissed = true
        manual.value = manual.value.copy(dismissed = true)
    }

    /** « Plus tard », ou retour : sur une app neuve, c'est continuer sans Google (le préchargement peut passer). */
    fun leave() {
        install?.decline()
    }

    /**
     * Faut-il ouvrir la liste d'elle-même (après la reprise, ou au premier lancement) ? Une seule fois ; sur une app neuve,
     * à chaque lancement tant que la connexion Google attend une réponse.
     */
    fun takeAutoOpen(): Boolean {
        if (installState.value.offerPending) {
            prefs.autoOpened = true
            return true
        }
        if (prefs.autoOpened || state.value.finished) return false
        prefs.autoOpened = true
        return true
    }
}

/**
 * Réglages Samsung de la batterie. Android ne permet pas à une app de les changer : on ouvre l'écran système le plus
 * proche, et on explique le chemin pour le reste.
 */
object BatteryHelp {
    /**
     * « Informations sur l'appli » de Trackly (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`, documenté) : sur One UI,
     * on y trouve « Batterie » puis « Sans restriction ». Il n'existe pas d'intent public pour « Applications jamais
     * en veille » : le chemin est expliqué.
     */
    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Liste des apps dont la batterie est optimisée (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, documenté), si l'écran précédent manque. */
    fun optimizationListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val PATH_UNRESTRICTED = "Informations sur l’appli › Batterie › Sans restriction"
    const val PATH_NEVER_SLEEPING = "Réglages › Batterie › Limites d’utilisation en arrière-plan › Applications jamais en veille › + › Trackly"

    /** Ouvre le premier écran système qui répond ; `false` si aucun (l'utilisateur suit alors le chemin écrit). */
    fun open(context: Context): Boolean {
        for (intent in listOf(appInfoIntent(context.packageName), optimizationListIntent())) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
