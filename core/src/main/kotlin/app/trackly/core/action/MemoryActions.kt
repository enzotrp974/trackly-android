package app.trackly.core.action

import app.trackly.core.catalog.Provisions
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.FormattingNotes
import app.trackly.core.model.Preference
import app.trackly.core.model.RecordState
import app.trackly.core.model.TermCorrections
import app.trackly.core.model.TextMatch
import java.time.Instant
import java.util.UUID

/** Écrit une note de mémoire : nouvelle, ou [memoryId] pour la mettre à jour. Une seule annulation. */
data class RememberFact(val topic: String, val text: String, val memoryId: String? = null) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val cleanTopic = topic.trim()
        val cleanText = text.trim()
        if (cleanTopic.isEmpty() || cleanText.isEmpty()) scope.reject(Rejection.Invalid("Il faut un sujet et un texte."))
        if (cleanTopic.length > AgentMemory.MAX_TOPIC) scope.reject(Rejection.Invalid("Sujet trop long"))
        if (cleanText.length > AgentMemory.MAX_TEXT) scope.reject(Rejection.Invalid("Note trop longue : garde l'essentiel en une ou deux phrases."))
        val all = scope.tx.preferences.allCurrent()
        val existing = memoryId?.let { id ->
            all.firstOrNull { it.preferenceId == id && it.kind == AgentMemory.KIND }
                ?: scope.reject(Rejection.Invalid("Note introuvable : $id"))
        }
        if (existing != null && existing.isActive && existing.active && existing.trigger == cleanTopic && existing.rule == cleanText) {
            scope.reject(Rejection.NoChange)
        }
        if (existing == null && AgentMemory.notes(all).size >= AgentMemory.MAX_NOTES) {
            scope.reject(Rejection.Invalid("Mémoire pleine : mets à jour ou retire une note."))
        }
        val next = existing?.copy(state = RecordState.Active, trigger = cleanTopic, rule = cleanText, active = true)
            ?: memoryNote("mem-" + UUID.randomUUID(), cleanTopic, cleanText)
        HistoryKinds.Preferences.writeNextVersion(scope, existing, next)
        return "Retenu : $cleanTopic"
    }

    companion object {
        const val TYPE = "memory.remember"
    }
}

/** Retire une note de mémoire. Annulable. */
data class ForgetFact(val memoryId: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val note = scope.tx.preferences.allCurrent().firstOrNull { it.preferenceId == memoryId && it.kind == AgentMemory.KIND && it.isActive }
            ?: scope.reject(Rejection.Invalid("Note introuvable : $memoryId"))
        HistoryKinds.Preferences.writeNextVersion(scope, note, note.withState(RecordState.Deleted))
        return "Oublié : ${note.trigger}"
    }

    companion object {
        const val TYPE = "memory.forget"
    }
}

/**
 * Nettoyage unique de la mémoire : retire en une seule action, annulable, les notes de mise en forme
 * ([FormattingNotes]). Chaque note retirée reçoit une version `deleted` ; rien n'est effacé.
 *
 * Une seule fois par base : l'action est inscrite au journal même sans rien à retirer, et refusée dès qu'une action
 * de ce type y figure, même annulée (l'annulation est respectée). Cette trace voyage avec la base : une copie
 * restaurée d'avant la 1.2 est nettoyée, une copie d'après ne l'est jamais une seconde fois, et une note retenue
 * ensuite n'est jamais retirée en silence.
 */
object ForgetFormattingNotes : Action {
    const val TYPE = "memory.format_cleanup"

    override val type get() = TYPE

    override val recordedWhenUnchanged get() = true

    override suspend fun apply(scope: ActionScope): String {
        if (scope.tx.actions.existsOfType(TYPE, before = scope.id)) scope.reject(Rejection.NoChange)
        val notes = scope.tx.preferences.allCurrent().filter(FormattingNotes::matches).sortedBy { it.preferenceId }
        if (notes.isEmpty()) return "Mémoire : aucune note de mise en forme à retirer"
        for (note in notes) HistoryKinds.Preferences.writeNextVersion(scope, note, note.withState(RecordState.Deleted))
        return if (notes.size == 1) {
            "Mémoire : note de mise en forme retirée (${notes.single().trigger})"
        } else {
            "Mémoire : ${notes.size} notes de mise en forme retirées"
        }
    }
}

internal fun memoryNote(id: String, topic: String, text: String) = Preference(
    preferenceId = id, version = 1, state = RecordState.Active, kind = AgentMemory.KIND,
    trigger = topic, rule = text, recordedAt = Instant.EPOCH, actionId = null,
)

/** Une note de la mémoire de départ. */
data class SeedMemory(val id: String, val topic: String, val text: String)

/** Un mot corrigé de départ. */
data class SeedTerm(val heard: String, val correct: String)

/**
 * Connaissances de départ embarquées dans l'APK (facultatif) : mémoire, mots corrigés, stock. N'écrit que ce qui manque : rien de ce
 * que l'utilisateur a changé n'est réécrit, et un aliment qu'il a sorti du stock n'y revient pas. Comme la reprise de
 * l'historique, elle ne s'annule pas.
 */
data class SeedKnowledge(
    val memories: List<SeedMemory>,
    val terms: List<SeedTerm>,
    val stock: List<String>,
) : Action {
    override val type get() = TYPE
    override val undoable get() = false

    override suspend fun apply(scope: ActionScope): String {
        val preferences = scope.tx.preferences.allCurrent()
        var added = 0
        for (m in memories.distinctBy { it.id }) {
            if (preferences.any { it.preferenceId == m.id }) continue
            HistoryKinds.Preferences.writeNextVersion(scope, null, memoryNote(m.id, m.topic, m.text))
            added++
        }
        for (t in terms.distinctBy { TextMatch.normalize(it.heard) }) {
            val plain = TextMatch.normalize(t.heard)
            if (preferences.any { it.kind == TermCorrections.KIND && TextMatch.normalize(it.trigger) == plain }) continue
            val note = memoryNote("tc-seed-$plain", t.heard, t.correct).copy(kind = TermCorrections.KIND)
            HistoryKinds.Preferences.writeNextVersion(scope, null, note)
            added++
        }
        val known = scope.tx.provisions.allCurrent()
        val fresh = stock.filter { name -> Provisions.find(known, name, null) == null }
        for (input in ProvisionPlan.of(known, fresh.map { ProvisionInput(it) }, emptyList()).created) {
            val provision = app.trackly.core.model.Provision("prv-" + UUID.randomUUID(), 1, RecordState.Active, input.name.trim(), null, Instant.EPOCH, null)
            HistoryKinds.Provisions.writeNextVersion(scope, null, provision)
            added++
        }
        if (added == 0) scope.reject(Rejection.NoChange)
        return "Connaissances de départ : $added"
    }

    companion object {
        const val TYPE = "knowledge.seed"
    }
}
