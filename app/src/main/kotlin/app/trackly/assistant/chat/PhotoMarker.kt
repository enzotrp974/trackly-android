package app.trackly.assistant.chat

/**
 * Repère texte laissé dans l'historique du chat à la place des photos envoyées : « 1 photo envoyée ».
 * Les photos ne sont jamais conservées ; seul ce texte reste, dans le message de l'utilisateur (aucune colonne
 * de plus, donc aucune migration). Le modèle le relit aussi dans l'historique, ce qui lui rappelle qu'une photo
 * a été envoyée.
 */
object PhotoMarker {
    private val trailing = Regex("""(?s)^(.*?)\n?\[(\d+) photos? envoyées?]$""")

    fun label(count: Int): String = if (count == 1) "1 photo envoyée" else "$count photos envoyées"

    /** Texte à conserver dans l'historique : le message de l'utilisateur, suivi du repère s'il y avait des photos. */
    fun stored(text: String, photoCount: Int): String = when {
        photoCount <= 0 -> text
        text.isBlank() -> "[${label(photoCount)}]"
        else -> "${text.trimEnd()}\n[${label(photoCount)}]"
    }

    /** Message sans le repère, et nombre de photos qu'il annonçait (0 s'il n'y en avait pas). */
    fun split(stored: String): Pair<String, Int> {
        val match = trailing.find(stored) ?: return stored to 0
        return match.groupValues[1] to (match.groupValues[2].toIntOrNull() ?: 0)
    }
}
