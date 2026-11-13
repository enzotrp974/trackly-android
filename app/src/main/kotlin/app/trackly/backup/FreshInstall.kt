package app.trackly.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File

/** Réponse à la proposition « Connecte Google Drive pour retrouver ta sauvegarde », faite à une app neuve. */
enum class RestoreOffer(val code: String) {
    /** Pas encore de réponse : le préchargement attend. */
    Pending("attente"),

    /** « Plus tard », « Terminer » ou retour : on continue sans Google, préchargement normal. */
    Declined("refusee"),

    /** Drive connecté et regardé : copie restaurée, ou rien à restaurer. */
    Answered("faite"),
    ;

    companion object {
        fun fromCode(code: String?): RestoreOffer = entries.firstOrNull { it.code == code } ?: Pending
    }
}

/** Ce qu'on sait de l'installation. */
data class InstallState(
    /** App neuve : aucune action depuis l'installation (la base est restée celle du premier lancement). */
    val fresh: Boolean,
    val offer: RestoreOffer,
) {
    /** La proposition de connexion Google attend une réponse (seulement sur une app neuve). */
    val offerPending: Boolean get() = fresh && offer == RestoreOffer.Pending
}

/**
 * **App neuve** : aucune action n'y a été faite depuis son installation. Le marqueur vit dans les préférences de
 * l'installation, que ni Auto Backup ni la copie Drive ne sauvegardent : une mise à jour par-dessus n'est donc jamais
 * une app neuve, une app vidée ou réinstallée l'est toujours.
 *
 * - Décidé une seule fois, au premier lancement de cette version : neuve sauf trace d'une installation précédente
 *   ([previousInstall] : préférences de Trackly déjà là, ou mise à jour d'une app qui avait une base).
 * - L'empreinte de la base au premier lancement est notée ([onBaseOpened]) : une base remise par Auto Backup laisse
 *   l'app neuve, toute écriture ensuite la rend utilisée ([markUsed], ou empreinte différente). Jamais l'inverse.
 * - Seule une app neuve peut être remplacée automatiquement par une copie Drive ([AutoRestore]).
 */
class FreshInstall(private val prefs: SharedPreferences, previousInstall: () -> Boolean) {
    private val flow: MutableStateFlow<InstallState>

    /** Une restauration automatique est préparée : l'app va redémarrer, le préchargement ne doit pas passer. */
    @Volatile var restoring: Boolean = false

    /**
     * La sauvegarde Drive est en train d'être cherchée ou préparée : un refus arrivé pendant ce temps est ignoré, et le
     * préchargement attend la fin (il ne doit pas remplir la base qu'une copie va remplacer).
     */
    private val searching = MutableStateFlow(false)

    /** Début de la recherche ; [endSearch] à la fin, quel qu'en soit le résultat. */
    fun startSearch() {
        searching.value = true
    }

    fun endSearch() {
        searching.value = false
    }

    init {
        if (!prefs.contains(FRESH)) prefs.edit(commit = true) { putBoolean(FRESH, !previousInstall()) }
        flow = MutableStateFlow(read())
    }

    val state: StateFlow<InstallState> = flow.asStateFlow()

    /** Empreinte de la base au premier lancement ; `null` tant que la base n'a pas été ouverte. */
    val baseAtInstall: String? get() = prefs.getString(FINGERPRINT, null)

    /** App neuve, et sa base est toujours celle du premier lancement ([fingerprint] : [BackupContents.fingerprint]). */
    fun isNew(fingerprint: String): Boolean {
        if (!state.value.fresh) return false
        val atInstall = baseAtInstall ?: return false
        if (atInstall == fingerprint) return true
        markUsed()
        return false
    }

    /** Rappel d'ouverture de la base de l'app : rien n'est compté si l'app n'est déjà plus neuve. */
    fun onBaseOpened(db: SupportSQLiteDatabase) {
        if (state.value.fresh) onBaseOpened(BackupContents.of(db).fingerprint)
    }

    /** La base vient d'être ouverte (migrée) : note son empreinte au premier lancement, sinon la compare. */
    @Synchronized
    fun onBaseOpened(fingerprint: String) {
        if (!state.value.fresh) return
        val atInstall = baseAtInstall
        if (atInstall == null) prefs.edit(commit = true) { putString(FINGERPRINT, fingerprint) } else if (atInstall != fingerprint) markUsed()
    }

    /**
     * Une écriture dans la base, ou une restauration appliquée : l'app n'est plus neuve, pour toujours. Faux si
     * l'écriture sur le disque a échoué.
     */
    @Synchronized
    fun markUsed(): Boolean {
        if (!state.value.fresh) return true
        val written = prefs.edit().putBoolean(FRESH, false).commit()
        flow.value = read()
        return written
    }

    @Synchronized
    fun answer(offer: RestoreOffer) {
        if (prefs.getString(OFFER, null) == offer.code) return
        prefs.edit(commit = true) { putString(OFFER, offer.code) }
        flow.value = read()
    }

    /** L'utilisateur quitte la proposition sans connecter Drive : on continue sans Google (sauf recherche en cours). */
    fun decline() {
        if (state.value.offerPending && !searching.value) answer(RestoreOffer.Declined)
    }

    /**
     * Le préchargement peut-il remplir la base vide ? Attend la réponse à la proposition (ou que l'app ne soit plus
     * neuve) ; non si une copie Drive est en train d'être restaurée.
     */
    suspend fun awaitPreloadChoice(): Boolean {
        combine(state, searching) { s, busy -> !s.offerPending && !busy }.first { it }
        return !restoring
    }

    private fun read() = InstallState(
        fresh = prefs.getBoolean(FRESH, false),
        offer = RestoreOffer.fromCode(prefs.getString(OFFER, null)),
    )

    companion object {
        const val FILE = "trackly-installation"
        private const val FRESH = "neuve"
        private const val FINGERPRINT = "empreinte_base"
        private const val OFFER = "proposition_drive"

        /** Préférences des versions précédentes : leur présence trahit une installation déjà utilisée. */
        val PREVIOUS_PREFS = listOf(
            BackupStatusStore.FILE, "trackly-mise-en-route", "trackly-assistant", "trackly-dictee", "trackly-bilan",
            "trackly-notifications", "trackly-cle", "trackly-tour-en-cours",
        )

        fun create(context: Context): FreshInstall {
            val app = context.applicationContext
            return FreshInstall(app.getSharedPreferences(FILE, Context.MODE_PRIVATE)) { previousInstall(app) }
        }

        /**
         * Trace d'une installation précédente : des préférences de Trackly (jamais rendues par Auto Backup), ou une
         * mise à jour par-dessus une app qui avait déjà une base. Une app vidée n'a ni l'un ni l'autre.
         */
        fun previousInstall(context: Context): Boolean {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (PREVIOUS_PREFS.any { File(prefsDir, "$it.xml").exists() }) return true
            val updated = runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.lastUpdateTime > info.firstInstallTime
            }.getOrDefault(true)
            return updated && context.getDatabasePath(TracklyDatabase.NAME).exists()
        }
    }
}
