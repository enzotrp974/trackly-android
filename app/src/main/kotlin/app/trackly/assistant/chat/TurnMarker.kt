package app.trackly.assistant.chat

import android.content.Context
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.openai.ReasoningEffort
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Repère « un tour est en cours » : écrit au début d'un tour (envoi ou « Réessayer »), effacé à sa fin quelle
 * qu'elle soit (réponse, erreur, annulation). S'il reste après le redémarrage de l'app, c'est qu'Android a tué le
 * processus pendant le tour : seul cas où le message de l'utilisateur reste sans réponse **sans** que l'app l'ait dit.
 *
 * Il ne porte ni texte de message ni photo : l'identifiant du message, l'heure de début, et la cible de « Corriger »
 * (identifiants de consommations et leur libellé) et le niveau de raisonnement, pour que « Réessayer » corrige encore le bon repas.
 */
data class TurnMarker(
    val messageId: Long,
    val startedAt: Instant,
    val focus: CorrectionFocus? = null,
    /** Libellé de la cible de correction (« Riz basmati, Poulet rôti »), ou `null`. */
    val focusLabel: String? = null,
    /** Niveau de raisonnement du message, pour que « Réessayer » le garde après la mort du processus. */
    val effort: ReasoningEffort = ReasoningEffort.Default,
)

/** Où est gardé le repère [TurnMarker] entre deux lancements de l'app. */
interface TurnMarkerStore {
    fun read(): TurnMarker?

    fun write(marker: TurnMarker)

    fun clear()
}

/** En mémoire : tests et aperçus (partager la même instance entre deux [ChatTurns] simule un redémarrage). */
class InMemoryTurnMarkerStore : TurnMarkerStore {
    private var marker: TurnMarker? = null

    override fun read(): TurnMarker? = marker

    override fun write(marker: TurnMarker) {
        this.marker = marker
    }

    override fun clear() {
        marker = null
    }
}

/** Sur le téléphone : un petit fichier de préférences, hors sauvegarde automatique (voir `backup_rules.xml`). */
class SharedTurnMarkerStore(context: Context) : TurnMarkerStore {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-tour-en-cours", Context.MODE_PRIVATE)

    override fun read(): TurnMarker? {
        val id = prefs.getLong(ID, NONE).takeIf { it != NONE } ?: return null
        val at = prefs.getLong(AT, NONE).takeIf { it != NONE } ?: return null
        val entries = prefs.getString(ENTRIES, null)?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
        val action = prefs.getLong(ACTION, NONE).takeIf { it != NONE }
        return TurnMarker(
            messageId = id,
            startedAt = Instant.ofEpochMilli(at),
            focus = entries?.takeIf { it.isNotEmpty() }?.let { CorrectionFocus(action, it) },
            focusLabel = prefs.getString(LABEL, null),
            effort = ReasoningEffort.entries.firstOrNull { it.code == prefs.getString(EFFORT, null) } ?: ReasoningEffort.Default,
        )
    }

    override fun write(marker: TurnMarker) {
        prefs.edit()
            .putLong(ID, marker.messageId)
            .putLong(AT, marker.startedAt.toEpochMilli())
            .putLong(ACTION, marker.focus?.actionId ?: NONE)
            .putString(EFFORT, marker.effort.code)
            .apply { marker.focus?.let { putString(ENTRIES, Json.encodeToString(ListSerializer(String.serializer()), it.entryIds)) } ?: remove(ENTRIES) }
            .apply { marker.focusLabel?.let { putString(LABEL, it) } ?: remove(LABEL) }
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val NONE = -1L
        const val ID = "message"
        const val AT = "debut"
        const val ACTION = "action"
        const val ENTRIES = "consommations"
        const val LABEL = "libelle"
        const val EFFORT = "effort"
    }
}
