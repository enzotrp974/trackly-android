package app.trackly.assistant.chat

import app.trackly.assistant.openai.AssistantError

/** Message d'erreur affiché dans le chat, et ce que l'utilisateur peut faire. */
data class ErrorNotice(
    val title: String,
    val body: String,
    /** Un bouton « Réessayer » a du sens (cause passagère). */
    val retryable: Boolean,
    /** Un bouton « Saisir ma clé » ouvre les réglages. */
    val opensKeySettings: Boolean = false,
    /** Faute de réseau : la saisie à la main est proposée en plan B. Jamais pour une clé, un crédit ou OpenAI. */
    val network: Boolean = false,
) {
    companion object
}

/** Textes des erreurs qui ne viennent pas d'OpenAI : tour interrompu, exception inattendue. */
private const val CHECK_JOURNAL = "Une partie a peut-être déjà été enregistrée : regarde ton journal avant de renvoyer ton message."

/**
 * Le message est resté sans réponse (processus arrêté pendant le tour) : aucune cause n'est inventée.
 * [notingTitle] : le tour venait de « Noter » sur cette carte. Le bon geste est de la retoucher, pas de renvoyer la phrase,
 * qui partirait sans la vérification de la carte. Il passe avant « regarde ton journal » : le repas et sa
 * carte s'écrivent ensemble, donc ce repas n'est pas noté, et retoucher une carte déjà notée ne compte rien deux fois.
 */
internal fun ErrorNotice.Companion.interrupted(recorded: Boolean, lostPhotos: Int, notingTitle: String? = null): ErrorNotice = ErrorNotice(
    title = "Ton message n’a pas reçu de réponse.",
    body = when {
        notingTitle != null -> "Rien n’a été noté pour « $notingTitle » : retouche « Noter » sur sa carte."
        recorded -> CHECK_JOURNAL
        lostPhotos > 0 -> "Trackly ne garde jamais les photos : envoie ton message à nouveau avec ${if (lostPhotos == 1) "la photo" else "les photos"}."
        else -> "Rien n’a été enregistré : tu peux renvoyer ton message."
    },
    retryable = true,
)

/**
 * Erreur d'OpenAI après qu'une partie du message a été enregistrée (carte déjà écrite) : la cause reste dans le titre, mais
 * le texte ne pousse pas à renvoyer le message, ce qui compterait deux fois.
 */
internal fun ErrorNotice.afterRecording(): ErrorNotice = copy(
    // Une cause passagère disait « réessaie » : remplacée. Une cause durable (crédit, clé) garde sa consigne.
    body = if (retryable) CHECK_JOURNAL else "$body $CHECK_JOURNAL",
    // « Saisir à la main » ferait ressaisir un repas déjà noté.
    network = false,
)

internal fun ErrorNotice.Companion.unexpectedFault(recorded: Boolean): ErrorNotice = ErrorNotice(
    title = "Un problème est survenu",
    body = if (recorded) "L’assistant s’est arrêté en cours de route. $CHECK_JOURNAL" else "Ton message n’a pas pu être traité, et rien n’a été enregistré. Réessaie ; si ça recommence, ferme puis rouvre Trackly.",
    retryable = true,
)

/**
 * Textes des erreurs : l'absence d'Internet, la clé refusée, le crédit épuisé,
 * le plafond atteint, la limite de débit et l'erreur serveur ont chacun leur message.
 */
fun AssistantError.notice(): ErrorNotice = when (this) {
    AssistantError.MissingKey -> ErrorNotice(
        title = "Clé OpenAI manquante",
        body = "Ajoute ta clé OpenAI dans les réglages pour parler à l’assistant. Ton bilan reste consultable.",
        retryable = true,
        opensKeySettings = true,
    )
    AssistantError.NoInternet -> ErrorNotice(
        title = "Pas de connexion Internet",
        body = "Ton message n’est pas parti. Tout ce qui est enregistré reste consultable ; réessaie une fois connecté.",
        retryable = true,
        network = true,
    )
    AssistantError.Timeout -> ErrorNotice(
        title = "OpenAI ne répond pas",
        body = "La réponse a pris trop de temps. Réessaie dans un instant.",
        retryable = true,
    )
    AssistantError.KeyRejected -> ErrorNotice(
        title = "Clé OpenAI refusée",
        body = "OpenAI ne reconnaît pas ta clé (fausse, révoquée ou d’un autre projet). Saisis une nouvelle clé dans les réglages.",
        retryable = false,
        opensKeySettings = true,
    )
    AssistantError.Forbidden -> ErrorNotice(
        title = "Accès refusé par OpenAI",
        body = "Ta clé n’a pas accès à ce modèle depuis ici. Vérifie les droits du projet Trackly sur platform.openai.com.",
        retryable = false,
        opensKeySettings = true,
    )
    AssistantError.CreditExhausted -> ErrorNotice(
        title = "Crédit OpenAI épuisé",
        body = "Ton crédit API est à zéro. Recharge-le sur platform.openai.com (Billing), puis réessaie.",
        retryable = false,
    )
    AssistantError.SpendLimitReached -> ErrorNotice(
        title = "Plafond de dépense atteint",
        body = "Le plafond mensuel du projet OpenAI est atteint. Relève-le dans les réglages du projet (Limits) ou attends le mois prochain.",
        retryable = false,
    )
    is AssistantError.RateLimited -> ErrorNotice(
        title = "Trop de demandes d’un coup",
        body = "OpenAI limite le débit pour l’instant. Réessaie dans quelques secondes.",
        retryable = true,
    )
    is AssistantError.ServerError -> ErrorNotice(
        title = "Erreur chez OpenAI",
        body = "Le service d’OpenAI a un problème (erreur $status). Réessaie plus tard.",
        retryable = true,
    )
    is AssistantError.Unexpected -> ErrorNotice(
        title = "Réponse inattendue",
        body = "L’assistant n’a pas pu répondre" + (status?.let { " (code $it)" } ?: "") + ". Réessaie ; si ça recommence, reformule ton message.",
        retryable = true,
    )
}
