package app.trackly.dictation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Les deux façons de dicter. Le mode « en direct » est celui par défaut ;
 * « après l'arrêt » coûte près de quatre fois moins par minute.
 */
enum class DictationMode(val code: String, val model: String) {
    /** `gpt-live-transcribe` : le texte s'écrit pendant la parole, un panneau dédié l'affiche. */
    Live("live", "gpt-live-transcribe"),

    /** `gpt-transcribe` : l'audio est envoyé à l'arrêt, le texte arrive dans la zone de saisie. */
    AfterStop("after_stop", "gpt-transcribe"),
    ;

    companion object {
        val Default = Live

        /** Mode d'un code enregistré ; un code inconnu (version plus récente, fichier abîmé) donne le mode par défaut. */
        fun fromCode(code: String?): DictationMode = entries.firstOrNull { it.code == code } ?: Default
    }
}

/** Réglages de la dictée : le mode choisi, et si le système a déjà été sollicité pour le micro. */
interface DictationSettings {
    val mode: StateFlow<DictationMode>

    fun setMode(mode: DictationMode)

    /**
     * Vrai dès que la fenêtre d'autorisation du micro a été montrée une fois. Sans cela, un refus
     * définitif (aucune fenêtre ne s'ouvre plus) ne se distingue pas d'une première demande.
     */
    var micRequested: Boolean
}

/** Réglages gardés dans les préférences de l'app. */
class SharedDictationSettings(context: Context) : DictationSettings {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-dictee", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(DictationMode.fromCode(prefs.getString(KEY_MODE, null)))

    override val mode: StateFlow<DictationMode> = _mode.asStateFlow()

    override fun setMode(mode: DictationMode) {
        prefs.edit().putString(KEY_MODE, mode.code).apply()
        _mode.value = mode
    }

    override var micRequested: Boolean
        get() = prefs.getBoolean(KEY_MIC_REQUESTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MIC_REQUESTED, value).apply()
        }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_MIC_REQUESTED = "micro_demande"
    }
}

/** Réglages en mémoire (tests, aperçus). */
class InMemoryDictationSettings(
    mode: DictationMode = DictationMode.Default,
    override var micRequested: Boolean = false,
) : DictationSettings {
    private val _mode = MutableStateFlow(mode)
    override val mode: StateFlow<DictationMode> = _mode.asStateFlow()

    override fun setMode(mode: DictationMode) {
        _mode.value = mode
    }
}
