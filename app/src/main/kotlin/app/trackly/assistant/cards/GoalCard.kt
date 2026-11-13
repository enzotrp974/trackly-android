package app.trackly.assistant.cards

import app.trackly.core.action.ActionResult
import app.trackly.core.model.GoalTargets
import app.trackly.day.DayLabels
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

/** Cibles d'un objectif telles qu'affichées sur la carte. Une macro sans cible vaut `null`. */
@Serializable
data class GoalValues(
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
) {
    companion object {
        fun of(targets: GoalTargets) = GoalValues(targets.kcal, targets.proteinG, targets.carbsG, targets.fatG)
    }
}

/**
 * Carte de récapitulatif d'un changement d'objectif (Réglages ou chat) : l'objectif d'avant,
 * celui d'après et la date d'effet, avec « Annuler ». Construite par le code depuis le résultat
 * de l'action [app.trackly.core.action.SetGoal], jamais depuis le texte du modèle.
 */
@Serializable
@SerialName("goal")
data class GoalCard(
    override val actionId: Long,
    /** Date d'effet, `AAAA-MM-JJ`. */
    val effectiveFrom: String,
    /** Objectif qui valait ce jour-là avant le changement ; `null` : aucun n'était fixé. */
    val before: GoalValues? = null,
    val after: GoalValues,
) : ChatCard {
    val date: LocalDate get() = LocalDate.parse(effectiveFrom)

    /** Les macros ont changé en même temps que les calories : l'assistant les a ajustées pour rester cohérent. */
    val macrosChanged: Boolean
        get() = before != null && (before.proteinG != after.proteinG || before.carbsG != after.carbsG || before.fatG != after.fatG)

    override fun summaryForModel(): String {
        val from = before?.let { "${it.kcal.roundToInt()} kcal${macros(it)} → " }.orEmpty()
        return "[Objectif changé par l'action $actionId : $from${after.kcal.roundToInt()} kcal${macros(after)}, dès le $effectiveFrom (${DayLabels.inSentence(date)})]"
    }

    private fun macros(v: GoalValues): String {
        val parts = listOfNotNull(
            v.proteinG?.let { "P ${formatNumber(it)} g" },
            v.carbsG?.let { "G ${formatNumber(it)} g" },
            v.fatG?.let { "L ${formatNumber(it)} g" },
        )
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    companion object {
        /** Carte d'une action [app.trackly.core.action.SetGoal] appliquée, ou `null` si elle n'a pas touché d'objectif. */
        fun from(result: ActionResult): GoalCard? {
            val change = result.goalChanges.lastOrNull() ?: return null
            return GoalCard(
                actionId = result.id.value,
                effectiveFrom = change.after.effectiveFrom.toString(),
                before = change.inForceBefore?.let(GoalValues::of),
                after = GoalValues.of(change.after.targets),
            )
        }
    }
}
