package app.trackly.dictation

/** Ce que la dictée sait de la permission micro (Android : `RECORD_AUDIO`). */
interface MicPermission {
    fun isGranted(): Boolean

    /** Vrai si le système accepte de reposer la question (refusée une fois, pas encore définitivement). */
    fun shouldShowRationale(): Boolean
}

/** Permission fixe (tests, aperçus). */
class FixedMicPermission(var granted: Boolean = true, var rationale: Boolean = false) : MicPermission {
    override fun isGranted() = granted
    override fun shouldShowRationale() = rationale
}

/** Message affiché à la place de la dictée tant que le micro n'est pas autorisé. */
enum class PermissionPrompt {
    /** Première demande (ou question reposable) : explique pourquoi, puis ouvre la fenêtre du système. */
    Explain,

    /** Refusé : la dictée est facultative, on peut réessayer ou écrire. */
    Denied,

    /** Refusé pour de bon : le système ne montre plus sa fenêtre, seul le chemin par les paramètres de l'app reste. */
    Blocked,
}
