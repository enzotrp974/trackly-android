package app.trackly.assistant.chat

import app.trackly.assistant.cards.RecordCard

/**
 * Questions « en attente ». L'assistant enregistre d'abord sa meilleure estimation puis pose
 * sa question dans la carte. Elle reste signalée tant que l'utilisateur n'a pas répondu ; sa réponse (le message
 * suivant) affine l'estimation par une nouvelle version, ou la confirme, et la question disparaît de la carte.
 * Sans réponse, l'estimation reste comptée.
 */
object PendingQuestions {
    /** Actions des cartes dont la question est encore sans réponse : celles qui suivent le dernier message de l'utilisateur. */
    fun of(messages: List<ChatMessage>): Set<Long> {
        val lastUser = messages.indexOfLast { it.role == ChatRole.User }
        return messages.drop(lastUser + 1)
            .flatMap { it.cards }
            .filterIsInstance<RecordCard>()
            .filter { !it.question.isNullOrBlank() }
            .map { it.actionId }
            .toSet()
    }
}
