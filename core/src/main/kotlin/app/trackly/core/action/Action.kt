package app.trackly.core.action

import app.trackly.core.model.ActionId
import app.trackly.core.model.Consumption
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.store.Effect
import app.trackly.core.store.StoreTransaction
import app.trackly.core.time.TrackingDay
import java.time.Instant
import java.time.LocalDate

/**
 * Action typée : la seule façon de modifier les données. Les écrans, le panneau de
 * développement et l'assistant appellent exactement les mêmes actions.
 *
 * Ajouter une action = écrire une classe qui implémente [Action] (aucun registre central).
 * Elle écrit de nouvelles versions par [ActionScope] et déclare chaque [Change] ;
 * l'annulation est alors gratuite, à condition que le type de donnée touché ait son
 * [VersionedKind] (voir [CoreKinds]).
 */
interface Action {
    /** Type stable, enregistré dans le journal (ex. `consumption.add`). */
    val type: String

    /**
     * Applique l'action dans la transaction de [scope] et renvoie un titre court en français.
     * Lever [ActionRejected] annule tout ce que l'action a écrit.
     */
    suspend fun apply(scope: ActionScope): String

    /**
     * Faux : l'action est tracée dans le journal sans ses effets, donc hors de portée de
     * « Annuler » (une reprise de l'historique ne doit pas s'effacer d'un toucher).
     */
    val undoable: Boolean get() = true

    /**
     * Vrai : l'action est inscrite au journal même si elle ne change rien. Pour une étape unique, dont la trace
     * dans la base dit qu'elle a déjà eu lieu, y compris dans une copie restaurée. Sans effet, elle reste hors de
     * portée de « Annuler ».
     */
    val recordedWhenUnchanged: Boolean get() = false
}

/** Contexte d'application d'une action : transaction, horloge figée, identifiant. */
class ActionScope internal constructor(
    val id: ActionId,
    /** Instant d'application, identique pour toutes les versions écrites par l'action. */
    val now: Instant,
    val tx: StoreTransaction,
    val kinds: Map<String, VersionedKind>,
) {
    private val recorded = mutableListOf<Change>()
    val changes: List<Change> get() = recorded

    /** Action annulée par celle-ci (renseigné par les actions d'annulation). */
    var undoes: ActionId? = null

    /** Jour de suivi en cours au moment de l'action. */
    val today: LocalDate get() = TrackingDay.of(now)

    fun record(change: Change) {
        recorded += change
    }

    fun reject(rejection: Rejection): Nothing = throw ActionRejected(rejection)
}

/**
 * Changement produit par une action sur une donnée versionnée. Le résultat de l'action
 * les expose tels quels : la carte d'enregistrement y lit aliments, kcal et macros.
 */
interface Change {
    val kind: String
    val key: String
    val beforeVersion: Int?
    val afterVersion: Int

    /** Jours de suivi dont le bilan change. */
    val days: Set<LocalDate>

    val effect: Effect get() = Effect(kind, key, beforeVersion, afterVersion)
}

data class ConsumptionChange(val before: Consumption?, val after: Consumption) : Change {
    override val kind get() = ConsumptionKind.NAME
    override val key get() = after.entryId.value
    override val beforeVersion get() = before?.version
    override val afterVersion get() = after.version
    override val days get() = setOfNotNull(before?.day, after.day)
}

data class GoalChange(
    val before: Goal?,
    val after: Goal,
    /**
     * Objectif qui valait le jour de départ avant ce changement (peut venir d'une date plus ancienne) :
     * c'est le « avant » du récapitulatif. `null` : aucun objectif n'était fixé, ou changement d'annulation.
     */
    val inForceBefore: GoalTargets? = null,
) : Change {
    override val kind get() = GoalKind.NAME
    override val key get() = after.effectiveFrom.toString()
    override val beforeVersion get() = before?.version
    override val afterVersion get() = after.version

    /** Un objectif vaut pour les jours suivants aussi : on signale son jour de départ. */
    override val days get() = setOf(after.effectiveFrom)
}

/** Raison typée d'un refus : l'assistant peut la renvoyer au modèle, l'écran l'afficher. */
sealed class Rejection(val message: String) {
    class Invalid(message: String) : Rejection(message)
    class NotFound(what: String) : Rejection("Introuvable : $what")
    data object NoChange : Rejection("Rien à changer")
    data object NothingToUndo : Rejection("Rien à annuler")
    class AlreadyUndone(val target: ActionId) : Rejection("Cette action est déjà annulée")
    class UndoConflict(val target: ActionId) :
        Rejection("Impossible d'annuler : une action plus récente a modifié les mêmes données")

    override fun toString() = "${this::class.simpleName}($message)"
}

class ActionRejected(val rejection: Rejection) : Exception(rejection.message)

/** Résultat d'une action appliquée, réutilisable par la carte d'enregistrement et l'annulation. */
data class ActionResult(
    val id: ActionId,
    val type: String,
    val title: String,
    val appliedAt: Instant,
    val changes: List<Change>,
    val undoes: ActionId?,
) {
    val days: Set<LocalDate> get() = changes.flatMapTo(sortedSetOf()) { it.days }
    val consumptionChanges: List<ConsumptionChange> get() = changes.filterIsInstance<ConsumptionChange>()
    val goalChanges: List<GoalChange> get() = changes.filterIsInstance<GoalChange>()
}

sealed interface ActionOutcome {
    data class Applied(val result: ActionResult) : ActionOutcome
    data class Rejected(val rejection: Rejection) : ActionOutcome
}
