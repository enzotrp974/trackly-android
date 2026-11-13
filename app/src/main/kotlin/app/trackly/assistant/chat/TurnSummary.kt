package app.trackly.assistant.chat

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.markdown.Markdown
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.day.formatKcal
import kotlin.math.roundToInt

/**
 * Résumé d'un tour terminé pendant que l'app n'est plus à l'écran, pour une notification discrète :
 * ce qui a été enregistré (« Enregistré : Barre protéinée, Pomme · 287 kcal »), la question en attente, ou la réponse.
 */
data class TurnSummary(val title: String, val text: String?) {
    companion object {
        private const val MAX_TEXT = 180

        fun of(outcome: TurnOutcome): TurnSummary? {
            val records = outcome.cards.filterIsInstance<RecordCard>()
            val reply = (outcome as? TurnOutcome.Completed)?.reply?.trim()?.takeIf { it.isNotEmpty() }?.let { short(Markdown.plain(it)) }
            val question = records.firstNotNullOfOrNull { it.question }
                ?: records.flatMap { it.doubts }.firstOrNull()?.let { "Est-ce « ${it.chosen.name} » ?" }
            val failed = (outcome as? TurnOutcome.Failed)?.error?.notice()
            if (records.isNotEmpty()) {
                val text = when {
                    failed != null -> "La réponse n’a pas pu finir : ${failed.title}"
                    question != null -> "Question en attente : $question"
                    else -> reply
                }
                return TurnSummary(recordTitle(records), text)
            }
            if (failed != null) return TurnSummary("Ton message n’a pas abouti", failed.title)
            if (reply == null && outcome.cards.isEmpty()) return null
            return TurnSummary("Trackly a répondu", reply)
        }

        /** « Enregistré : Barre protéinée, Pomme · 300 kcal » (verbe de la première carte). */
        private fun recordTitle(records: List<RecordCard>): String {
            val verb = when (records.first().kind) {
                RecordKind.Added -> "Enregistré"
                RecordKind.Corrected -> "Corrigé"
                RecordKind.Deleted -> "Supprimé"
                RecordKind.Restored -> "Rétabli"
            }
            val items = records.flatMap { it.items }
            val labels = items.map { it.label }.distinct().joinToString(", ")
            val counted = records.filter { it.kind != RecordKind.Deleted }.flatMap { it.items }
            val kcal = if (counted.isEmpty()) "" else " · ${formatKcal(counted.sumOf { it.kcal }.roundToInt())} kcal"
            return "$verb : $labels$kcal"
        }

        private fun short(text: String): String = if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
