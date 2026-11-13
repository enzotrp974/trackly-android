package app.trackly.core.action

import app.trackly.core.goal.ReferenceWeightUndo
import app.trackly.core.goal.UpdateReferenceWeight
import app.trackly.core.goal.SetTemporaryGoal
import app.trackly.core.goal.TemporaryGoalUndo
import app.trackly.core.model.ActionId
import app.trackly.core.store.ActionRecord

/**
 * Annule une action précise : chaque donnée touchée retrouve son état d'avant par une
 * nouvelle version, sans effacer l'historique. Refusé si une action plus récente a
 * modifié entre-temps l'une de ces données (rien n'est écrasé en silence). Un objectif, un poids de référence ou une
 * consommation réécrits depuis à l'identique (même contenu, autre version : une correction annulée, par exemple) ne
 * comptent pas comme modifiés ([VersionedKind.sameContent]).
 *
 * Annuler une annulation rétablit l'action d'origine (« rétablir »).
 */
data class Undo(val target: ActionId) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val record = scope.tx.actions.get(target) ?: scope.reject(Rejection.NotFound("action $target"))
        if (record.undoneBy != null) scope.reject(Rejection.AlreadyUndone(target))
        val chain = UndoChain.rootOf(scope, record)
        // Poids de référence : annulation au contenu, refusée avec ce qu'il faut défaire d'abord.
        if (chain.root.type == UpdateReferenceWeight.TYPE) return ReferenceWeightUndo.apply(scope, record, chain.root, chain.depth)
        // Objectif temporaire : son annulation ne réécrit jamais un jour passé (arrêt en cours de route, ou refus).
        if (chain.root.type == SetTemporaryGoal.TYPE) {
            TemporaryGoalUndo.apply(scope, record, chain.root, chain.depth)?.let { title ->
                scope.undoes = target
                scope.tx.actions.markUndone(target, scope.id)
                return title
            }
        }
        // Une donnée touchée plusieurs fois par l'action : de son état d'avant à son dernier état.
        val effects = record.effects
            .groupBy { it.kind to it.key }
            .values
            .map { touches -> touches.first().copy(afterVersion = touches.last().afterVersion) }
        if (effects.isEmpty()) scope.reject(Rejection.NothingToUndo)
        for (effect in effects) {
            val kind = scope.kinds[effect.kind] ?: scope.reject(Rejection.Invalid("Type de donnée inconnu : ${effect.kind}"))
            if (!kind.unchangedSince(scope, effect.key, effect.afterVersion)) scope.reject(Rejection.UndoConflict(target))
        }
        for (effect in effects) {
            scope.kinds.getValue(effect.kind).restore(scope, effect.key, effect.beforeVersion)
        }
        scope.undoes = target
        scope.tx.actions.markUndone(target, scope.id)
        // Titre lisible même au bout d'une chaîne « annulation ← rétablissement… » : on nomme l'action d'origine.
        return (if (chain.depth % 2 == 1) "Rétablissement : " else "Annulation : ") + chain.root.title
    }

    companion object {
        const val TYPE = "action.undo"
    }
}

/**
 * Annule la dernière modification encore en vigueur, comme un « Annuler » d'éditeur :
 * des annulations successives remontent l'historique ; après un rétablissement, c'est
 * le rétablissement qui s'annule.
 */
data object UndoLast : Action {
    override val type get() = Undo.TYPE

    override suspend fun apply(scope: ActionScope): String {
        val target = lastInEffect(scope) ?: scope.reject(Rejection.NothingToUndo)
        return Undo(target.id).apply(scope)
    }

    /**
     * Une chaîne « action ← annulation ← rétablissement… » est en vigueur quand elle compte
     * un nombre pair d'annulations. On annule le maillon le plus récent de la chaîne en
     * vigueur la plus récente.
     */
    private suspend fun lastInEffect(scope: ActionScope): ActionRecord? {
        var before: ActionId? = null
        while (true) {
            val page = scope.tx.actions.notUndone(before, PAGE)
            for (head in page) {
                // Le nettoyage de la mémoire au lancement n'est pas un geste de l'utilisateur : « annule » vise
                // son dernier geste. Le nettoyage reste annulable par son identifiant.
                if (UndoChain.rootOf(scope, head).root.type in NOT_A_GESTURE) continue
                if (undoDepth(scope, head) % 2 == 0) return head
            }
            if (page.size < PAGE) return null
            before = page.last().id
        }
    }

    private suspend fun undoDepth(scope: ActionScope, record: ActionRecord): Int = UndoChain.rootOf(scope, record).depth

    private const val PAGE = 50

    // La mise à jour du poids de référence suit la production d'un bilan : elle s'annule sur la page du bilan.
    private val NOT_A_GESTURE = setOf(ForgetFormattingNotes.TYPE, UpdateReferenceWeight.TYPE)
}

/** Remonte une chaîne « action ← annulation ← rétablissement… » jusqu'à l'action d'origine. */
internal object UndoChain {
    /** [root] : action d'origine ; [depth] : nombre d'annulations entre elle et [record] (0 si [record] est l'origine). */
    class Root(val root: ActionRecord, val depth: Int)

    suspend fun rootOf(scope: ActionScope, record: ActionRecord): Root {
        var depth = 0
        var current = record
        while (true) {
            val target = current.undoes ?: return Root(current, depth)
            depth++
            current = scope.tx.actions.get(target) ?: return Root(current, depth)
        }
    }
}

/**
 * Où en est une action vis-à-vis de l'annulation : ce qu'une carte du chat doit montrer
 * (« Annuler » ou « Rétablir ») et si le bouton peut encore servir.
 */
data class UndoStatus(
    /** Action demandée. */
    val action: ActionId,
    /**
     * L'action est en vigueur : jamais annulée, ou rétablie (nombre pair d'annulations dans sa chaîne
     * « action ← annulation ← rétablissement… »).
     */
    val inEffect: Boolean,
    /**
     * Dernier maillon de la chaîne, celui qu'on annule pour basculer : `Undo(head)` annule une action en
     * vigueur, ou rétablit une action annulée. Vaut [action] tant qu'elle n'a jamais été annulée.
     */
    val head: ActionId,
    /**
     * Une action plus récente a modifié les mêmes données depuis [head] : basculer serait refusé
     * ([Rejection.UndoConflict]). La carte le dit au lieu de proposer un bouton qui échouerait.
     */
    val superseded: Boolean,
) {
    /** L'action a été annulée (et pas rétablie). */
    val undone: Boolean get() = !inEffect
}

internal object UndoStatuses {
    /** Statut de [id] lu dans la transaction de [scope] (aucune écriture), ou `null` si l'action est inconnue. */
    suspend fun of(scope: ActionScope, id: ActionId): UndoStatus? {
        var current = scope.tx.actions.get(id) ?: return null
        var links = 0
        while (true) {
            val next = current.undoneBy ?: break
            current = scope.tx.actions.get(next) ?: break
            links++
        }
        val effects = current.effects.groupBy { it.kind to it.key }.values.map { touches -> touches.last() }
        val superseded = effects.any { effect ->
            val kind = scope.kinds[effect.kind] ?: return@any false
            !kind.unchangedSince(scope, effect.key, effect.afterVersion)
        }
        return UndoStatus(action = id, inEffect = links % 2 == 0, head = current.id, superseded = superseded)
    }
}
