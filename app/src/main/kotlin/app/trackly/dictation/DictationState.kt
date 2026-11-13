package app.trackly.dictation

import app.trackly.assistant.chat.ErrorNotice
import app.trackly.assistant.chat.notice
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import java.time.Instant

/** Où en est la dictée. */
enum class DictationPhase {
    /** Rien en cours. */
    Idle,

    /** Le micro n'est pas encore autorisé : une explication est affichée ([DictationUiState.permission]). */
    Permission,

    /** Le micro écoute. En direct, le texte défile ([DictationUiState.text]). */
    Listening,

    /** Arrêt demandé : on attend le texte final (direct) ou la transcription (après l'arrêt). */
    Finishing,

    /** La dictée a échoué ; l'enregistrement et le texte partiel sont encore là ([DictationUiState.failure]). */
    Failed,
}

/** Pourquoi une dictée a échoué. Les erreurs d'OpenAI gardent la classification du chat. */
sealed interface DictationFailure {
    data class Api(val error: AssistantError) : DictationFailure

    /** Le micro ne s'ouvre pas (pris par une autre appli, aucun format accepté). */
    data object MicUnavailable : DictationFailure

    /** Aucune parole reconnue (silence, ou enregistrement trop court). */
    data object NothingHeard : DictationFailure
}

/** Les mêmes messages distincts que dans le chat pour les erreurs d'OpenAI ; deux messages propres à la dictée. */
fun DictationFailure.notice(): ErrorNotice = when (this) {
    is DictationFailure.Api -> error.notice()
    DictationFailure.MicUnavailable -> ErrorNotice(
        title = "Micro indisponible",
        body = "Trackly n’arrive pas à ouvrir le micro (une autre appli l’utilise peut-être). Réessaie, ou écris ton message.",
        retryable = false,
    )
    DictationFailure.NothingHeard -> ErrorNotice(
        title = "Je n’ai rien entendu",
        body = "Rapproche le téléphone de ta bouche et réessaie, ou écris ton message.",
        retryable = false,
    )
}

/** Ce que l'écran affiche. */
data class DictationUiState(
    val phase: DictationPhase = DictationPhase.Idle,
    val mode: DictationMode = DictationMode.Default,
    /** En direct : le texte dicté jusqu'ici (provisoire). Après un échec : le texte partiel, récupérable. */
    val text: String = "",
    /** Niveau sonore, de 0 à 1 (barres animées). */
    val level: Float = 0f,
    val elapsedMs: Long = 0,
    /** En direct : la connexion s'ouvre encore ; l'audio est déjà gardé, rien n'est perdu. */
    val connecting: Boolean = false,
    val permission: PermissionPrompt? = null,
    val failure: DictationFailure? = null,
    /** Faux ou vrai : « Réessayer » relance la transcription de l'enregistrement gardé. */
    val canRetry: Boolean = false,
) {
    val active: Boolean get() = phase != DictationPhase.Idle
}

/** Ce que l'utilisateur demande à l'arrêt. */
enum class StopAction {
    /** Terminer et envoyer au chat (« Envoyer »). */
    Send,

    /** Terminer et placer le texte dans la zone de saisie, pour le relire. */
    Edit,
}

/** Ce qui reçoit le texte dicté : le chat de saisie, ou la conversation de bilan. */
fun interface DictationReceiver {
    /** [sendNow] : envoyé tel quel ; sinon placé dans la zone de saisie (sans écraser ce qui y est déjà). */
    fun receiveDictation(text: String, sendNow: Boolean, notice: String?)
}

/** Texte dicté, remis au chat. [send] vrai : message envoyé tel quel ; sinon placé dans la zone de saisie. */
data class DictationResult(val text: String, val send: Boolean, val notice: String? = null)

/** Reçoit le coût de chaque transcription (le compteur de coût local). */
fun interface TranscriptionUsage {
    suspend fun record(model: String, audioMs: Long, at: Instant)

    companion object {
        val None = TranscriptionUsage { _, _, _ -> }
    }
}

/** Erreur d'OpenAI vue par la dictée ; la fin de tampon vide du direct devient « rien entendu ». */
internal fun apiFailure(error: AssistantError): DictationFailure =
    if (error is AssistantError.Unexpected && error.detail == COMMIT_EMPTY) DictationFailure.NothingHeard else DictationFailure.Api(error)

internal const val COMMIT_EMPTY = "input_audio_buffer_commit_empty"

internal fun AssistantException.toFailure(): DictationFailure = apiFailure(error)
