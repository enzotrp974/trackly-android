package app.trackly.assistant.model

import android.content.Context
import app.trackly.assistant.openai.AssistantModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Réglage « Modèle de l'assistant » (Réglages). Lu à chaque message par l'assistant et le bilan :
 * un changement vaut dès le message suivant, sans redémarrer l'app.
 */
interface AssistantModelSettings {
    val model: StateFlow<AssistantModel>

    fun setModel(model: AssistantModel)

    /** Code du modèle à envoyer à OpenAI pour la prochaine requête. */
    fun currentCode(): String = model.value.code
}

/** Réglage gardé dans les préférences de l'app (hors sauvegarde : seule la base est sauvegardée). */
class SharedAssistantModelSettings(context: Context) : AssistantModelSettings {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-assistant", Context.MODE_PRIVATE)
    private val _model = MutableStateFlow(AssistantModel.fromCode(prefs.getString(KEY_MODEL, null)))

    override val model: StateFlow<AssistantModel> = _model.asStateFlow()

    override fun setModel(model: AssistantModel) {
        prefs.edit().putString(KEY_MODEL, model.code).apply()
        _model.value = model
    }

    private companion object {
        const val KEY_MODEL = "modele"
    }
}

/** Réglage en mémoire (tests, aperçus). */
class InMemoryAssistantModelSettings(model: AssistantModel = AssistantModel.Default) : AssistantModelSettings {
    private val _model = MutableStateFlow(model)
    override val model: StateFlow<AssistantModel> = _model.asStateFlow()

    override fun setModel(model: AssistantModel) {
        _model.value = model
    }
}
