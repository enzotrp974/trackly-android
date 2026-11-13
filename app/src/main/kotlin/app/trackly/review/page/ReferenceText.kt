package app.trackly.review.page

import app.trackly.core.goal.ReferenceWeightOutcome
import app.trackly.core.goal.MeasuredWeight
import app.trackly.core.goal.ReferenceWeightPlan
import app.trackly.core.goal.ReferenceWeights
import app.trackly.core.model.GoalTargets
import app.trackly.review.ReportFormat
import kotlin.math.roundToLong

/**
 * Le bloc « Poids de référence » de la page du bilan, écrit par l'application d'après la mise à jour faite à la
 * production du rapport : seulement les valeurs qui changent, une valeur identique au gramme près
 * s'écrit « inchangé (69 g) », et toujours « calories inchangées ». Sans pesée, la page le dit, sans alerte.
 */
object ReferenceText {
    /** Le bloc, ou `null` sans poids de référence connu (rien à dire). */
    fun of(outcome: ReferenceWeightOutcome): ReferenceWeight? = when (val plan = outcome.plan) {
        ReferenceWeightPlan.NoReference -> null
        is ReferenceWeightPlan.NoWeighIn -> ReferenceWeight(
            plan.currentKg, plan.currentKg, listOf(CALORIES), status = ReferenceStatus.NoWeighIn,
            source = "Aucune pesée ces ${ReferenceWeights.MAX_AGE_DAYS} derniers jours : il ne change pas. Donne une pesée au prochain bilan pour le mettre à jour.",
        )
        is ReferenceWeightPlan.Unchanged -> ReferenceWeight(
            plan.currentKg, plan.currentKg, listOf(CALORIES), status = ReferenceStatus.Unchanged,
            source = "${source(plan.measured)} Même poids qu'avant : rien ne change.",
        )
        is ReferenceWeightPlan.Refused -> notUpdated(plan.currentKg, plan.measured, plan.message)
        is ReferenceWeightPlan.Change -> {
            val id = outcome.action
            if (id == null) {
                notUpdated(plan.beforeKg, plan.measured, outcome.refusal ?: "il n'a pas pu être enregistré")
            } else {
                val upcoming = plan.rewrites.count { it.from != plan.day }
                ReferenceWeight(
                    beforeKg = plan.beforeKg,
                    afterKg = plan.afterKg,
                    lines = plan.inForce?.let { lines(it.before, it.after) }.orEmpty() +
                        listOfNotNull(upcomingLine(upcoming)) + CALORIES,
                    actionId = id.value,
                    status = ReferenceStatus.Updated,
                    source = source(plan.measured),
                )
            }
        }
    }

    /** Les grammes de l'objectif en vigueur, avant et après : seulement ce qui existe, « inchangé » au gramme près. */
    fun lines(before: GoalTargets, after: GoalTargets): List<String> = listOfNotNull(
        change("protéines", "inchangées", before.proteinG, after.proteinG),
        change("minimum de protéines", "inchangé", before.proteinMinG, after.proteinMinG),
        change("lipides max", "inchangé", before.fatG, after.fatG),
        change("glucides", "inchangés", before.carbsG, after.carbsG),
    )

    const val CALORIES = "calories inchangées"

    /** Une phrase pour l'IA du bilan (résultat de l'outil), pour qu'elle ne contredise pas la page. */
    fun summary(r: ReferenceWeight): String = when (r.status) {
        ReferenceStatus.Updated -> "Mis à jour par l'application : ${ReferenceWeights.label(r.beforeKg)} → ${ReferenceWeights.label(r.afterKg)} kg (${r.lines.joinToString(", ")}). La page l'affiche avec Annuler : n'en reparle que si l'utilisateur pose la question."
        else -> "Inchangé à ${ReferenceWeights.label(r.afterKg)} kg. ${r.source}"
    }

    private fun change(name: String, same: String, before: Double?, after: Double?): String? {
        if (before == null || after == null) return null
        val a = before.roundToLong()
        val b = after.roundToLong()
        return if (a == b) "$name $same ($a g)" else "$name $a → $b g"
    }

    private fun upcomingLine(count: Int): String? = when (count) {
        0 -> null
        1 -> "l'objectif déjà prévu suit, avec ses calories"
        else -> "les $count objectifs déjà prévus suivent, chacun avec ses calories"
    }

    private fun source(m: MeasuredWeight): String = when {
        m.averaged && m.count > 1 -> "Moyenne de tes ${m.count} pesées des 7 derniers jours."
        m.averaged -> "Ta pesée du ${ReportFormat.dayMonth(m.lastDay)}, la seule des 7 derniers jours."
        else -> "Ta dernière pesée, du ${ReportFormat.dayMonth(m.lastDay)} (aucune ces 7 derniers jours)."
    }

    private fun notUpdated(currentKg: Double, measured: MeasuredWeight, reason: String) = ReferenceWeight(
        currentKg, currentKg, listOf(CALORIES), status = ReferenceStatus.NotUpdated,
        source = "${source(measured)} Pas de mise à jour : ${reason.removePrefix("Poids de référence non mis à jour : ").trimEnd('.')}.",
    )
}
