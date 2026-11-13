package app.trackly.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

/** État de la sauvegarde Drive, affiché dans Réglages › Sauvegarde. */
data class BackupStatus(
    /** L'utilisateur a connecté Google Drive depuis les réglages. */
    val connected: Boolean = false,
    val accountEmail: String? = null,
    val lastSuccessAt: Instant? = null,
    val lastSuccessName: String? = null,
    val lastFailureAt: Instant? = null,
    val lastFailureMessage: String? = null,
    /** La dernière tentative a demandé une reconnexion à Google. */
    val needsReconnect: Boolean = false,
    val lastRestoreAt: Instant? = null,
    val lastRestoreLabel: String? = null,
    /** Des changements de la base ne sont pas encore dans une copie Drive (ou rien n'a encore été copié). */
    val pending: Boolean = false,
    /** Premier changement pas encore copié sur Drive (null : tout est copié, ou inconnu). */
    val unsavedSince: Instant? = null,
    /** Dernier changement de la base. */
    val lastChangeAt: Instant? = null,
    /** Copie refusée par le garde-fou (base vide ou moins remplie que la dernière copie). */
    val lastRefusalAt: Instant? = null,
    val lastRefusalMessage: String? = null,
    /** Premier échec depuis la dernière copie réussie (null : la dernière tentative a réussi, ou aucune). */
    val failingSince: Instant? = null,
    /** Restauration préparée mais pas appliquée au redémarrage (base préparée abîmée, ou app utilisée entre-temps). */
    val lastRestoreDiscardedAt: Instant? = null,
    val lastRestoreDiscardedMessage: String? = null,
    /**
     * Connexion où Trackly n'a vu aucun dossier alors que des copies pouvaient exister (app neuve sans dossier visible,
     * ou dossier recréé après des copies réussies). Guide vers « Depuis un fichier », gardé jusqu'à une restauration ou
     * « Fermer ce message ».
     */
    val hiddenCopiesAt: Instant? = null,
) {
    /** Échec plus récent que la dernière réussite. */
    val failing: Boolean
        get() = lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt))

    /** Refus du garde-fou plus récent que la dernière réussite. */
    val refused: Boolean
        get() = lastRefusalAt != null && (lastSuccessAt == null || lastRefusalAt.isAfter(lastSuccessAt))

    /** Une copie est à faire : changements pas encore copiés, ou aucune copie réussie. */
    val needsBackup: Boolean get() = pending || lastSuccessAt == null

    /**
     * Drive a du retard sur le téléphone depuis plus de [limit] : des changements attendent depuis trop longtemps,
     * ou aucune copie n'a jamais réussi alors qu'un essai a échoué. Rien à copier : jamais en retard.
     */
    fun behindFor(now: Instant, limit: Duration): Boolean {
        if (lastSuccessAt == null) return lastFailureAt != null
        val since = unsavedSince ?: return false
        return Duration.between(since, now) > limit
    }
}

/**
 * État persistant de la sauvegarde (préférences privées). Ces préférences ne partent pas dans la
 * sauvegarde automatique Android (seule la base y est incluse) : après une réinstallation,
 * l'app redemande la connexion et retrouve le dossier Trackly par la recherche Drive.
 * Aucun jeton n'est stocké ici.
 */
class BackupStatusStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val flow = MutableStateFlow(read())
    val status: StateFlow<BackupStatus> = flow.asStateFlow()

    /** Identifiant Drive du dossier Trackly, vérifié avant chaque écriture. */
    var folderId: String?
        get() = prefs.getString(FOLDER_ID, null)
        set(value) = prefs.edit { if (value == null) remove(FOLDER_ID) else putString(FOLDER_ID, value) }

    /**
     * Copie Drive qui a refusé l'envoi parce que l'app avait moins de versions qu'elle. Tant qu'elle reste la plus
     * récente, l'envoi reste refusé, même si l'app la dépasse ensuite en nombre : une base ancienne (remise par Auto
     * Backup) ne la remplace jamais à force de saisies. Levée par une restauration.
     */
    var blockingCopyId: String?
        get() = prefs.getString(BLOCKING_COPY, null)
        set(value) = prefs.edit(commit = true) { if (value == null) remove(BLOCKING_COPY) else putString(BLOCKING_COPY, value) }

    /** Libellé de la restauration préparée, appliquée au prochain démarrage. */
    var pendingRestoreLabel: String?
        get() = prefs.getString(PENDING_RESTORE, null)
        set(value) = prefs.edit { if (value == null) remove(PENDING_RESTORE) else putString(PENDING_RESTORE, value) }

    fun markConnected(email: String?) = update {
        putBoolean(CONNECTED, true)
        if (email != null) putString(EMAIL, email)
        putBoolean(NEEDS_RECONNECT, false)
    }

    /**
     * Connexion oubliée sur ce téléphone (l'accès chez Google reste). Restent aussi la copie qui bloque l'envoi
     * ([blockingCopyId]) et la dernière restauration : à la reconnexion, le garde-fou compare comme avant.
     */
    fun markDisconnected() = update {
        putBoolean(CONNECTED, false)
        // Une prochaine connexion commence par une copie (dossier peut-être vide, s'il a été supprimé entre-temps).
        // Le dossier est retrouvé par sa marque (même compte : le même dossier, l'accès drive.file n'a pas été retiré).
        remove(SAVED_SEQ)
        remove(SAVED_FINGERPRINT)
        remove(EMAIL)
        remove(FOLDER_ID)
        putBoolean(NEEDS_RECONNECT, false)
    }

    /** Trackly ne voit plus d'anciennes copies : guide affiché dans Réglages › Sauvegarde jusqu'à une restauration. */
    fun markHiddenCopies(at: Instant) = update { putLong(HIDDEN_COPIES_AT, at.toEpochMilli()) }

    /** « Fermer ce message » : l'utilisateur a lu le guide. */
    fun clearHiddenCopies() = update { remove(HIDDEN_COPIES_AT) }

    /** Empreinte ([BackupContents.fingerprint]) de la dernière copie réussie. */
    val savedFingerprint: String? get() = prefs.getString(SAVED_FINGERPRINT, null)

    /** Numéro du dernier changement de la base (croissant ; 0 : aucun vu sur cette installation). */
    val changeSeq: Long @Synchronized get() = prefs.getLong(CHANGE_SEQ, 0)

    /** Un changement de la base vient d'être écrit. */
    @Synchronized
    fun recordChange(at: Instant) = update {
        putLong(CHANGE_SEQ, prefs.getLong(CHANGE_SEQ, 0) + 1)
        putLong(LAST_CHANGE_AT, at.toEpochMilli())
        if (!prefs.contains(UNSAVED_SINCE)) putLong(UNSAVED_SINCE, at.toEpochMilli())
    }

    /**
     * Copie réussie de la base telle qu'elle était au changement [seq], copie commencée à [startedAt]. Un changement
     * arrivé depuis reste à copier, compté au plus tôt depuis [startedAt].
     */
    @Synchronized
    fun recordSuccess(at: Instant, name: String, seq: Long = changeSeq, startedAt: Instant = at, fingerprint: String? = null) = update {
        if (fingerprint != null) putString(SAVED_FINGERPRINT, fingerprint) else remove(SAVED_FINGERPRINT)
        putLong(SUCCESS_AT, at.toEpochMilli())
        remove(FAILING_SINCE)
        putString(SUCCESS_NAME, name)
        putBoolean(NEEDS_RECONNECT, false)
        putLong(SAVED_SEQ, seq)
        // Une copie est partie : le garde-fou l'a laissée passer, la copie qui bloquait n'est plus la référence (
        // sinon, mise plus tard à la corbeille par la rotation, elle bloquerait tout pour rien).
        remove(BLOCKING_COPY)
        if (prefs.getLong(CHANGE_SEQ, 0) == seq) remove(UNSAVED_SINCE) else putLong(UNSAVED_SINCE, startedAt.toEpochMilli())
    }

    /** Le garde-fou a refusé la copie : rien n'a été envoyé, la dernière copie Drive reste la bonne. */
    fun recordRefusal(at: Instant, message: String) = update {
        putLong(REFUSAL_AT, at.toEpochMilli())
        putString(REFUSAL_MESSAGE, message)
    }

    fun recordFailure(at: Instant, message: String, needsReconnect: Boolean) = update {
        if (!prefs.contains(FAILING_SINCE)) putLong(FAILING_SINCE, at.toEpochMilli())
        putLong(FAILURE_AT, at.toEpochMilli())
        putString(FAILURE_MESSAGE, message)
        putBoolean(NEEDS_RECONNECT, needsReconnect)
    }

    /** Restauration appliquée ; faux si l'écriture sur le disque a échoué (la restauration la refera). */
    @Synchronized
    fun recordRestore(at: Instant, label: String): Boolean = update {
        // La base vient d'être remplacée : elle est à copier (une copie locale d'avant migration n'est pas sur Drive).
        putLong(CHANGE_SEQ, prefs.getLong(CHANGE_SEQ, 0) + 1)
        putLong(LAST_CHANGE_AT, at.toEpochMilli())
        if (!prefs.contains(UNSAVED_SINCE)) putLong(UNSAVED_SINCE, at.toEpochMilli())
        putLong(RESTORE_AT, at.toEpochMilli())
        putString(RESTORE_LABEL, label)
        remove(PENDING_RESTORE)
        // La base restaurée est la nouvelle référence : la copie qui bloquait l'envoi ne compte plus.
        remove(BLOCKING_COPY)
        // Une restauration est le geste que le guide des copies invisibles demandait.
        remove(HIDDEN_COPIES_AT)
    }

    /** La restauration préparée n'a pas été appliquée au redémarrage ; la base de l'app est restée. */
    fun recordRestoreDiscarded(at: Instant, message: String) = update {
        putLong(RESTORE_DISCARDED_AT, at.toEpochMilli())
        putString(RESTORE_DISCARDED_MESSAGE, message)
        remove(PENDING_RESTORE)
    }

    @Synchronized
    /** Vrai si l'écriture est arrivée sur le disque. */
    private fun update(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val written = prefs.edit().apply(block).commit()
        flow.value = read()
        return written
    }

    private fun read() = BackupStatus(
        connected = prefs.getBoolean(CONNECTED, false),
        accountEmail = prefs.getString(EMAIL, null),
        lastSuccessAt = instant(SUCCESS_AT),
        lastSuccessName = prefs.getString(SUCCESS_NAME, null),
        lastFailureAt = instant(FAILURE_AT),
        lastFailureMessage = prefs.getString(FAILURE_MESSAGE, null),
        needsReconnect = prefs.getBoolean(NEEDS_RECONNECT, false),
        lastRestoreAt = instant(RESTORE_AT),
        lastRestoreLabel = prefs.getString(RESTORE_LABEL, null),
        // Aucune copie notée sur cette installation (première version qui suit les changements comprise) : à copier.
        pending = prefs.getLong(CHANGE_SEQ, 0) != prefs.getLong(SAVED_SEQ, NEVER_SAVED),
        unsavedSince = instant(UNSAVED_SINCE),
        lastChangeAt = instant(LAST_CHANGE_AT),
        lastRefusalAt = instant(REFUSAL_AT),
        lastRefusalMessage = prefs.getString(REFUSAL_MESSAGE, null),
        failingSince = instant(FAILING_SINCE),
        lastRestoreDiscardedAt = instant(RESTORE_DISCARDED_AT),
        lastRestoreDiscardedMessage = prefs.getString(RESTORE_DISCARDED_MESSAGE, null),
        hiddenCopiesAt = instant(HIDDEN_COPIES_AT),
    )

    private fun instant(key: String): Instant? =
        if (prefs.contains(key)) Instant.ofEpochMilli(prefs.getLong(key, 0)) else null

    companion object {
        const val FILE = "trackly_backup"
        private const val CONNECTED = "connected"
        private const val EMAIL = "account_email"
        private const val FOLDER_ID = "folder_id"
        private const val SUCCESS_AT = "last_success_at"
        private const val SUCCESS_NAME = "last_success_name"
        private const val FAILURE_AT = "last_failure_at"
        private const val FAILURE_MESSAGE = "last_failure_message"
        private const val NEEDS_RECONNECT = "needs_reconnect"
        private const val RESTORE_AT = "last_restore_at"
        private const val RESTORE_LABEL = "last_restore_label"
        private const val PENDING_RESTORE = "pending_restore_label"
        private const val CHANGE_SEQ = "change_seq"
        private const val SAVED_SEQ = "saved_seq"
        private const val SAVED_FINGERPRINT = "saved_fingerprint"
        private const val FAILING_SINCE = "failing_since"
        private const val NEVER_SAVED = -1L
        private const val UNSAVED_SINCE = "unsaved_since"
        private const val LAST_CHANGE_AT = "last_change_at"
        private const val REFUSAL_AT = "last_refusal_at"
        private const val REFUSAL_MESSAGE = "last_refusal_message"
        private const val BLOCKING_COPY = "blocking_copy_id"
        private const val RESTORE_DISCARDED_AT = "last_restore_discarded_at"
        private const val RESTORE_DISCARDED_MESSAGE = "last_restore_discarded_message"
        private const val HIDDEN_COPIES_AT = "hidden_copies_at"
    }
}
