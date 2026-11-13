package app.trackly.core.model

/**
 * Mémoire de l'assistant : ce qui reste vrai sur l'utilisateur (goûts, routines, ce qu'il évite). Chaque note est
 * une [Preference] de type [KIND] : déclencheur = sujet, règle = texte. Versionnée, annulable et sauvegardée comme le reste.
 */
object AgentMemory {
    const val KIND = "agent_memory"
    const val MAX_TOPIC = 60
    const val MAX_TEXT = 600
    const val MAX_NOTES = 80

    fun notes(preferences: Collection<Preference>): List<Preference> =
        preferences.filter { it.kind == KIND && it.isActive && it.active }.sortedBy { TextMatch.normalize(it.trigger) }
}
