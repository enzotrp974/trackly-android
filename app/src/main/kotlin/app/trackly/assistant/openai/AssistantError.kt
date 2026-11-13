package app.trackly.assistant.openai

/**
 * Échec d'un appel à OpenAI, classé pour afficher le bon message :
 * l'absence d'Internet n'est jamais confondue avec un problème de clé ou de crédit.
 */
sealed interface AssistantError {
    /** Aucune clé enregistrée (ou clé illisible, à ressaisir). Aucun appel n'est fait. */
    data object MissingKey : AssistantError

    /** Pas de réseau, ou serveur injoignable. */
    data object NoInternet : AssistantError

    /** OpenAI n'a pas répondu à temps. */
    data object Timeout : AssistantError

    /** 401 : clé fausse, révoquée ou d'un autre projet. Pas de nouvel essai. */
    data object KeyRejected : AssistantError

    /** 403 : pays ou région non pris en charge, ou accès interdit au modèle. */
    data object Forbidden : AssistantError

    /** 429 `credit_balance_exhausted` ou `insufficient_quota` : crédit prépayé épuisé. */
    data object CreditExhausted : AssistantError

    /** 429 `*_spend_limit_exceeded` / `*_usage_limit_exceeded` : plafond mensuel atteint. */
    data object SpendLimitReached : AssistantError

    /** 429 temporaire (débit) : les essais automatiques n'ont pas suffi. */
    data class RateLimited(val retryAfterSeconds: Long?) : AssistantError

    /** 5xx : erreur ou surcharge chez OpenAI, après les essais automatiques. */
    data class ServerError(val status: Int) : AssistantError

    /** Réponse inattendue (400, JSON illisible, réponse incomplète…). */
    data class Unexpected(val status: Int?, val detail: String?) : AssistantError
}

/** Exception portant une [AssistantError] ; levée par le transport et le client. */
class AssistantException(val error: AssistantError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/**
 * Classe une réponse d'erreur HTTP d'OpenAI. On se fie à `error.code` (et à `error.type`
 * pour `insufficient_quota`), jamais au seul statut : un 429 peut être un crédit épuisé
 * (bloquant) ou une simple limite de débit (temporaire).
 */
object OpenAiErrors {
    private val CREDIT = setOf("credit_balance_exhausted", "insufficient_quota")
    private val SPEND = setOf(
        "project_spend_limit_exceeded",
        "organization_spend_limit_exceeded",
        "organization_usage_limit_exceeded",
        "project_usage_limit_exceeded",
    )

    fun classify(status: Int, code: String?, type: String?, retryAfterSeconds: Long?, message: String?): AssistantError =
        when {
            status == 401 -> AssistantError.KeyRejected
            status == 403 -> AssistantError.Forbidden
            code in CREDIT -> AssistantError.CreditExhausted
            code in SPEND || code.orEmpty().endsWith("_spend_limit_exceeded") || code.orEmpty().endsWith("_usage_limit_exceeded") ->
                AssistantError.SpendLimitReached
            type == "insufficient_quota" -> AssistantError.CreditExhausted
            status == 429 -> AssistantError.RateLimited(retryAfterSeconds)
            status >= 500 -> AssistantError.ServerError(status)
            else -> AssistantError.Unexpected(status, message ?: code)
        }

    /**
     * Erreur reçue au milieu d'un flux (événement `error`, sans statut HTTP) : on lui prête le statut qu'aurait
     * eu la même erreur avant le flux, puis on la classe comme les autres. Inconnue : erreur serveur (temporaire,
     * réessayée ; rien n'a été exécuté, puisque les outils ne le sont que sur une réponse complète).
     */
    fun classifyStreamError(code: String?, type: String?, message: String?): AssistantError {
        val c = code.orEmpty()
        val status = when {
            c == "invalid_api_key" || type == "authentication_error" -> 401
            c in CREDIT || c in SPEND || c.endsWith("_limit_exceeded") || type == "insufficient_quota" -> 429
            "rate_limit" in c || type == "rate_limit_error" || c == "slow_down" -> 429
            type == "invalid_request_error" -> 400
            else -> 500
        }
        return classify(status, code, type, retryAfterSeconds = null, message = message)
    }

    /** Erreurs temporaires qui méritent un nouvel essai automatique. */
    fun isTransient(error: AssistantError): Boolean =
        error is AssistantError.RateLimited || error is AssistantError.ServerError
}
