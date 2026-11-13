package app.trackly.core.action

import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.goal.TemporaryGoals
import app.trackly.core.goal.TemporaryPhase
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * « Objectif à partir d'une date » : l'action unique des Réglages, du chat et du panneau de
 * développement. Elle fixe un objectif nutritionnel applicable à partir de [from] (par défaut
 * le jour de suivi en cours, journée entière comprise, règle des 5 h incluse). Les jours
 * précédents gardent leur objectif : une nouvelle version est écrite pour cette date, rien n'est écrasé.
 *
 * Minimum de protéines : sans minimum dans [targets], celui de l'objectif en vigueur à la date de départ est
 * repris, si bien qu'un changement de calories ne le fait jamais baisser. Des protéines sous ce minimum sont refusées.
 *
 * [allowPast] faux : refuse une date passée (Réglages et assistant), pour ne jamais réécrire
 * l'objectif d'un jour déjà vécu. Le panneau de développement et les tests peuvent l'autoriser.
 */
data class SetGoal(val targets: GoalTargets, val from: LocalDate? = null, val allowPast: Boolean = true) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val today = scope.today
        val start = from ?: today
        if (!allowPast && start.isBefore(today)) {
            scope.reject(Rejection.Invalid("Les jours passés gardent leur objectif : choisis le jour de suivi en cours ($today) ou un jour suivant."))
        }
        if (start.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            scope.reject(Rejection.Invalid("Date de départ trop lointaine : $start (au plus ${MAX_DAYS_AHEAD} jours après le jour de suivi en cours)."))
        }
        val all = scope.tx.goals.allCurrent()
        val inForce = GoalTimeline.inForce(start, all)?.targets
        // Le minimum de protéines ne dépend pas des calories : sans minimum donné, celui en vigueur ce jour-là suit.
        val wanted = if (targets.proteinMinG == null) targets.copy(proteinMinG = inForce?.proteinMinG) else targets
        if (GoalCoherence.belowMinimum(wanted)) {
            scope.reject(
                Rejection.Invalid(
                    "Les protéines (${wanted.proteinG!!.roundToInt()} g) ne descendent pas sous le minimum de " +
                        "${GoalCoherence.floorG(wanted.proteinMinG!!).roundToInt()} g, un plancher qui n'est jamais l'objectif.",
                ),
            )
        }
        // Objectif temporaire prévu ou en cours : son retour écraserait un nouvel objectif aux calories différentes.
        // Une réécriture à calories constantes (macros du poids de référence) reste permise.
        TemporaryGoals.inEffect(scope)?.let { temporary ->
            if (temporary.returnOn.isAfter(start) && wanted.kcal != inForce?.kcal) {
                scope.reject(
                    Rejection.Invalid(
                        "Un objectif temporaire est ${if (temporary.phase == TemporaryPhase.Running) "en cours" else "prévu"} jusqu'au ${temporary.end} (retour à " +
                            "${temporary.baseKcal.roundToInt()} kcal le ${temporary.returnOn}) : annule-le d'abord depuis le bilan, puis change ton objectif.",
                    ),
                )
            }
        }
        val current = scope.tx.goals.current(start)
        if (current != null && current.isActive && current.targets == wanted) scope.reject(Rejection.NoChange)
        // Même objectif que celui déjà en vigueur ce jour-là, sans rien de prévu après : rien ne change.
        if (current?.isActive != true && inForce == wanted && GoalTimeline.upcoming(start, all).isEmpty()) scope.reject(Rejection.NoChange)
        GoalKind.writeNextVersion(
            scope,
            current,
            Goal(
                effectiveFrom = start,
                version = 1,
                state = RecordState.Active,
                targets = wanted,
                recordedAt = scope.now,
                actionId = scope.id,
            ),
            inForceBefore = inForce,
        )
        return "Objectif : ${wanted.kcal.roundToInt()} kcal dès le $start"
    }

    companion object {
        const val TYPE = "goal.set"

        /** Au-delà, la date est presque sûrement une faute de frappe. */
        const val MAX_DAYS_AHEAD = 366L
    }
}
