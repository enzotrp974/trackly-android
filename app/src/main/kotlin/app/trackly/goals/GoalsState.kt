package app.trackly.goals

import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import java.time.LocalDate

/** Place d'un objectif dans le temps, par rapport au jour de suivi en cours. */
enum class GoalStatus { Upcoming, InForce, Past }

/** Une période de la chronologie des objectifs, de [from] à [until] inclus (`null` : encore en vigueur). */
data class GoalPeriod(
    val from: LocalDate,
    val until: LocalDate?,
    /** Version courante de cet objectif ; au-delà de 1, il a été corrigé (les versions d'avant restent gardées). */
    val version: Int,
    val targets: GoalTargets,
    val status: GoalStatus,
)

/** Message d'un enregistrement ou d'une annulation. [undoActionId] : l'action qu'on peut encore annuler. */
data class GoalsNotice(val text: String, val error: Boolean = false, val undoActionId: Long? = null)

/** Ce que la section Objectifs des Réglages affiche. */
data class GoalsUiState(
    val today: LocalDate,
    /** Chronologie complète, de la plus récente à la plus ancienne. */
    val periods: List<GoalPeriod> = emptyList(),
    val notice: GoalsNotice? = null,
) {
    /** Objectif qui vaut le jour de suivi en cours. */
    val inForce: GoalPeriod? get() = periods.firstOrNull { it.status == GoalStatus.InForce }
    val upcoming: List<GoalPeriod> get() = periods.filter { it.status == GoalStatus.Upcoming }.sortedBy { it.from }

    companion object {
        /** Chronologie des objectifs [goals] (versions courantes) vue depuis [today]. */
        fun of(goals: Collection<Goal>, today: LocalDate, notice: GoalsNotice? = null): GoalsUiState {
            val effective = GoalTimeline.effective(goals)
            val inForceFrom = GoalTimeline.inForce(today, goals)?.effectiveFrom
            val periods = effective.mapIndexed { i, goal ->
                GoalPeriod(
                    from = goal.effectiveFrom,
                    until = effective.getOrNull(i + 1)?.effectiveFrom?.minusDays(1),
                    version = goal.version,
                    targets = goal.targets,
                    status = when {
                        goal.effectiveFrom == inForceFrom -> GoalStatus.InForce
                        goal.effectiveFrom.isAfter(today) -> GoalStatus.Upcoming
                        else -> GoalStatus.Past
                    },
                )
            }
            return GoalsUiState(today, periods.asReversed(), notice)
        }
    }
}
