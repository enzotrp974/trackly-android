package app.trackly.assistant.model

import android.content.Context
import app.trackly.assistant.openai.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Niveau de raisonnement choisi dans le sélecteur du chat. Il reste celui qu'on a choisi d'un message à l'autre
 * et après un redémarrage de l'app, jusqu'à ce qu'on en choisisse un autre.
 */
interface ReasoningSettings {
    val effort: StateFlow<ReasoningEffort>

    fun setEffort(effort: ReasoningEffort)
}

/**
 * Niveau gardé dans les préférences de l'app, avec le modèle de l'assistant. Il voyage dans la copie Drive
 * (voir `PortableSettings`). Un code inconnu (version future) donne le niveau par défaut.
 */
class SharedReasoningSettings(context: Context) : ReasoningSettings {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _effort = MutableStateFlow(read())

    override val effort: StateFlow<ReasoningEffort> = _effort.asStateFlow()

    override fun setEffort(effort: ReasoningEffort) {
        prefs.edit().putString(KEY, effort.code).apply()
        _effort.value = effort
    }

    private fun read(): ReasoningEffort =
        ReasoningEffort.entries.firstOrNull { it.code == prefs.getString(KEY, null) } ?: ReasoningEffort.Default

    companion object {
        /** Fichier et clé de préférences, repris par la liste des réglages de la copie Drive. */
        const val FILE = "trackly-assistant"
        const val KEY = "raisonnement"
    }
}

/** Niveau en mémoire (tests, aperçus). */
class InMemoryReasoningSettings(effort: ReasoningEffort = ReasoningEffort.Default) : ReasoningSettings {
    private val _effort = MutableStateFlow(effort)
    override val effort: StateFlow<ReasoningEffort> = _effort.asStateFlow()

    override fun setEffort(effort: ReasoningEffort) {
        _effort.value = effort
    }
}
