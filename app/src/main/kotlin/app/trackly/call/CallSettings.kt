package app.trackly.call

import android.content.Context
import app.trackly.assistant.openai.AssistantModel
import app.trackly.assistant.openai.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Réflexion pendant l'appel (Réglages › Appel) : trois positions, du plus vif au plus juste. Distincte du réglage du
 * chat, qui ne change pas après l'appel.
 */
enum class CallReflection(val code: String, val label: String, val model: AssistantModel, val effort: ReasoningEffort, val body: String) {
    Fast("rapide", "Rapide", AssistantModel.Terra, ReasoningEffort.Medium, "GPT-6.1 Terra, moyen. Le plus vif."),
    Medium("moyenne", "Moyenne", AssistantModel.Sol, ReasoningEffort.Medium, "GPT-6.1 Sol, moyen. Le meilleur équilibre."),
    High("elevee", "Élevée", AssistantModel.Sol, ReasoningEffort.High, "GPT-6.1 Sol, élevé. Le plus juste, la question peut arriver plus tard."),
    ;

    companion object {
        val Default = Medium

        fun fromCode(code: String?): CallReflection = entries.firstOrNull { it.code == code } ?: Default
    }
}

/**
 * Annulation d'écho pendant l'appel. WebRTC par défaut ; « Téléphone »
 * ajoute l'annulation matérielle du téléphone, quand il en a une, au traitement de WebRTC.
 */
enum class CallEcho(val code: String, val label: String, val body: String) {
    WebRtc("webrtc", "WebRTC", "Annulation logicielle de WebRTC."),
    Phone("telephone", "Téléphone", "Annulation du téléphone, complétée par WebRTC."),
    ;

    companion object {
        val Default = WebRtc

        fun fromCode(code: String?): CallEcho = entries.firstOrNull { it.code == code } ?: Default
    }
}

/** Réglages de l'appel, lus au lancement de chaque appel. */
interface CallSettings {
    val reflection: StateFlow<CallReflection>
    val echo: StateFlow<CallEcho>

    fun setReflection(value: CallReflection)

    fun setEcho(value: CallEcho)
}

/** Réglages gardés dans les préférences ; ils voyagent dans la copie Drive (voir `PortableSettings`). */
class SharedCallSettings(context: Context) : CallSettings {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _reflection = MutableStateFlow(CallReflection.fromCode(prefs.getString(KEY_REFLECTION, null)))
    private val _echo = MutableStateFlow(CallEcho.fromCode(prefs.getString(KEY_ECHO, null)))

    override val reflection: StateFlow<CallReflection> = _reflection.asStateFlow()
    override val echo: StateFlow<CallEcho> = _echo.asStateFlow()

    override fun setReflection(value: CallReflection) {
        prefs.edit().putString(KEY_REFLECTION, value.code).apply()
        _reflection.value = value
    }

    override fun setEcho(value: CallEcho) {
        prefs.edit().putString(KEY_ECHO, value.code).apply()
        _echo.value = value
    }

    companion object {
        const val FILE = "trackly-appel"
        const val KEY_REFLECTION = "reflexion"
        const val KEY_ECHO = "echo"
    }
}

/** Réglages en mémoire (tests, aperçus). */
class InMemoryCallSettings(
    reflection: CallReflection = CallReflection.Default,
    echo: CallEcho = CallEcho.Default,
) : CallSettings {
    private val _reflection = MutableStateFlow(reflection)
    private val _echo = MutableStateFlow(echo)
    override val reflection: StateFlow<CallReflection> = _reflection.asStateFlow()
    override val echo: StateFlow<CallEcho> = _echo.asStateFlow()

    override fun setReflection(value: CallReflection) {
        _reflection.value = value
    }

    override fun setEcho(value: CallEcho) {
        _echo.value = value
    }
}
