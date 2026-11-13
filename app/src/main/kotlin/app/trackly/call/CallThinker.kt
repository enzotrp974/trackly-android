package app.trackly.call

import java.time.Instant

/** Qui parle dans la transcription d'un appel. */
enum class Speaker(val label: String) { User("Utilisateur"), Voice("Voix") }

/** Une réplique de l'appel : les morceaux de transcription d'un même locuteur, regroupés. */
data class CallLine(val speaker: Speaker, val text: String)

/** Ce qui a lancé une réflexion. */
enum class ThinkTrigger(val code: String) {
    /** La voix a jugé qu'il fallait réfléchir (`session.delegation.created`). */
    Voice("voix"),

    /** L'utilisateur a répondu à la dernière question de la réflexion : l'app relance d'elle-même. */
    Answer("reponse"),

    /** Pause après une parole de l'utilisateur que la voix n'a pas transmise (filet de l'app). */
    Pause("pause"),
}

/**
 * Consommation notée (ou corrigée) pendant l'appel, par une réflexion. La suivante la reçoit pour corriger au lieu de noter
 * une deuxième fois ; la relecture après l'appel la reçoit aussi.
 */
data class NotedEntry(
    val entryId: String,
    val label: String,
    /** Code du moment (`matin`, `midi`…). */
    val moment: String,
    /** Date de rattachement `AAAA-MM-JJ`. */
    val day: String,
    val productId: String? = null,
    val recipeId: String? = null,
    /** Action du moteur qui l'a notée ou corrigée en dernier (base de « Annuler » dans le chat). */
    val actionId: Long,
    /** Supprimée pendant l'appel : elle sort de la liste. */
    val deleted: Boolean = false,
)

/** Ce que l'appel demande à la réflexion : la transcription jusque-là, ce qui est déjà noté, et le réglage choisi. */
data class ThinkRequest(
    /** Délégations de la voix auxquelles cette réflexion répond (`delegation_id`), vide quand l'app l'a lancée seule. */
    val delegationIds: List<String>,
    /** Transcription de l'appel jusqu'à la demande, du plus ancien au plus récent. */
    val transcript: List<CallLine>,
    val reflection: CallReflection,
    /** Déjà noté pendant cet appel : à corriger, jamais à noter une deuxième fois. */
    val noted: List<NotedEntry> = emptyList(),
    /** Questions déjà posées à l'utilisateur pendant cet appel (à ne pas reposer). */
    val asked: List<String> = emptyList(),
    val trigger: ThinkTrigger = ThinkTrigger.Voice,
    /**
     * Appelé dès qu'une action est appliquée pendant la réflexion : ce qui est noté est connu de l'appel même si la réflexion
     * est ensuite coupée (délai, appel fini), pour que la suivante corrige au lieu de noter une deuxième fois.
     */
    val onNoted: (List<NotedEntry>) -> Unit = {},
)

/** Résultat d'une réflexion. */
sealed interface ThinkResult {
    /** Ce qui a été noté ou corrigé pendant cette réflexion (déjà visible et annulable dans le chat du jour). */
    val noted: List<NotedEntry>

    /**
     * Réflexion aboutie. [say] : réponse à une question directe de l'utilisateur (chiffres de l'app) ou correction d'un chiffre dit par
     * la voix (« En fait non… »), sinon `null`. [questions] : ce qui reste flou, du plus important au moins important.
     */
    data class Done(
        val say: String? = null,
        val questions: List<String> = emptyList(),
        override val noted: List<NotedEntry> = emptyList(),
    ) : ThinkResult

    /**
     * Échec (réseau, clé…) : la voix continue sans inventer, ce que l'utilisateur a dit reste à traiter. Ce qui a pu être noté
     * avant l'échec est dans [noted]. Jamais de texte d'erreur brut à la voix.
     */
    data class Failed(val reason: String, override val noted: List<NotedEntry> = emptyList()) : ThinkResult
}

/**
 * Réflexion pendant l'appel, délégation `client`. **Seule la réflexion peut noter un repas** : la voix n'a aucun outil.
 * L'implémentation de l'app ([AgentCallThinker]) est l'agent Trackly du chat, avec ses outils, sa mémoire et ses actions
 * versionnées et annulables ; le contrôleur d'appel n'en lance qu'une à la fois.
 */
fun interface CallThinker {
    suspend fun think(request: ThinkRequest): ThinkResult

    companion object {
        /** Aucune réflexion (aperçus) : la voix continue seule, rien n'est noté. */
        val None = CallThinker { ThinkResult.Failed("aucune réflexion") }
    }
}

/**
 * Ce que l'appel laisse à la relecture après l'appel, une fois la dernière réflexion terminée. Rien n'est écrit sur
 * disque ici : le rapport ne vit qu'en mémoire ([CallReview] en garde le nécessaire jusqu'à la fin de la relecture).
 */
data class CallMealReport(
    /** Noté ou corrigé pendant l'appel, déjà dans le chat du jour (annulable). */
    val noted: List<NotedEntry>,
    /** Questions de la dernière réflexion restées sans réponse, dans l'ordre d'importance. */
    val openQuestions: List<String>,
    /**
     * Paroles de l'utilisateur qu'aucune réflexion n'a traitées (appel fini avant, réflexion en échec ou abandonnée). Vide si tout a
     * été traité. Jamais conservées au-delà de la relecture.
     */
    val unprocessed: List<String>,
    /** Transcription complète, pour situer [unprocessed] et les réponses. */
    val transcript: List<CallLine>,
    /** Début de l'appel (heure murale) : ce qui a été noté depuis se relit dans le fil du jour. */
    val startedAt: Instant = Instant.EPOCH,
    /** Réglage de réflexion de l'appel : la relecture garde le même modèle et le même effort. */
    val reflection: CallReflection = CallReflection.Default,
) {
    /** Rien à reprendre après l'appel. */
    val settled: Boolean get() = openQuestions.isEmpty() && unprocessed.isEmpty()
}
