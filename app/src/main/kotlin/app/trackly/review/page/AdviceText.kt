package app.trackly.review.page

import app.trackly.core.review.CatchUpResult
import app.trackly.core.review.StepsOption
import app.trackly.core.review.TemporaryPlan
import app.trackly.review.ReportFormat
import java.time.LocalDate

/**
 * Textes des conseils chiffrés, pour la page du bilan et la carte du chat du bilan : mêmes mots des deux côtés. Tous les
 * chiffres viennent de `CatchUpAdvices` ; ce fichier ne fait que les écrire.
 */
object AdviceText {
    /** Les cartes de la page, dans l'ordre : objectif temporaire, phrase des pas, phrase de la date. Vide : aucun conseil. */
    fun of(result: CatchUpResult?): List<Advice> {
        val advice = result as? CatchUpResult.Advice ?: return emptyList()
        return listOfNotNull(
            advice.plan?.let { temporary(it, advice.overshootKcal) },
            advice.steps?.let { steps(it, alone = advice.plan == null) },
            advice.newDate?.let { date(advice.targetKg, it, alone = advice.plan == null && advice.steps == null) },
        )
    }

    /** La carte de l'objectif temporaire ; [extraSteps] : les pas du mélange, ajoutés en ligne. */
    fun temporary(plan: TemporaryPlan, overshootKcal: Double?, extraSteps: StepsOption? = null): Advice {
        val total = plan.totalKcal
        val over = overshootKcal?.let { Math.round(it / 10.0) * 10.0 }
        val title = when {
            extraSteps != null || over == null -> "Rattraper ${ReportFormat.int(total + (extraSteps?.let { it.kcalPerDay * it.days } ?: 0.0))} kcal"
            // Un écart d'arrondi (10 kcal par jour au plus) ne fait pas d'un plan complet un rattrapage partiel.
            total + 10.0 * plan.days < over -> "Rattraper ${ReportFormat.int(total)} des ${ReportFormat.int(over)} kcal"
            else -> "Rattraper les ${ReportFormat.int(over)} kcal"
        }
        val rows = buildList {
            add("${span(plan.start, plan.end)}, ${ReportFormat.signedInt(-plan.dropKcal)} par jour")
            extraSteps?.let { add("Et environ +${ReportFormat.int(it.extraSteps.toDouble())} pas par jour (à peu près ${ReportFormat.int(it.kcalPerDay)} kcal)") }
            add("${capitalized(dayName(plan.returnOn))}, retour à ${ReportFormat.int(plan.baseKcal)} tout seul")
        }
        return Advice(
            kicker = "Objectif temporaire",
            title = title,
            big = ReportFormat.int(plan.temporaryKcal),
            unit = "kcal",
            rows = rows,
            kind = AdviceKind.Temporary,
            start = plan.start.toString(),
            days = plan.days,
            dropKcal = plan.dropKcal,
            baseKcal = plan.baseKcal,
        )
    }

    /** Le conseil en pas : une phrase, avec son équivalent en kcal au poids actuel, sans bouton. */
    fun steps(s: StepsOption, alone: Boolean): Advice {
        val from = if (s.fromKnown) "" else ", en partant de tes ${ReportFormat.int(s.fromSteps)} pas habituels"
        val lead = if (alone) "Pour rattraper" else "Ou bouger plus"
        val sentence = "$lead : environ +${ReportFormat.int(s.extraSteps.toDouble())} pas par jour pendant ${days(s.days)}, " +
            "soit à peu près ${ReportFormat.int(s.kcalPerDay)} kcal de plus par jour à ton poids actuel (${ReportFormat.one(s.weightKg)} kg)$from."
        return Advice(kicker = "Pas", title = sentence, big = "", kind = AdviceKind.Steps)
    }

    /** Garder son rythme et viser la date d'arrivée prévue. */
    fun date(targetKg: Double, newDate: LocalDate, alone: Boolean): Advice = Advice(
        kicker = "Date",
        title = "${if (alone) "Tu peux" else "Ou"} garder ton rythme et viser le ${ReportFormat.dayMonthYear(newDate)}.",
        big = "",
        kind = AdviceKind.Date,
        targetKg = targetKg,
        newDate = newDate.toString(),
    )

    /** « Du samedi 7 au mardi 10 novembre » ; un seul jour : « Samedi 7 novembre ». */
    fun span(start: LocalDate, end: LocalDate): String = when {
        start == end -> capitalized("${dayName(start)} ${ReportFormat.dayMonth(start).substringAfter(' ')}")
        start.month == end.month -> "Du ${dayName(start)} au ${dayName(end)} ${ReportFormat.dayMonth(end).substringAfter(' ')}"
        else -> "Du ${dayName(start)} ${ReportFormat.dayMonth(start).substringAfter(' ')} au ${dayName(end)} ${ReportFormat.dayMonth(end).substringAfter(' ')}"
    }

    /** « mercredi 11 », « samedi 1er ». */
    fun dayName(day: LocalDate): String = "${ReportFormat.weekdayName(day)} ${ReportFormat.dayMonth(day).substringBefore(' ')}"

    private fun days(n: Int) = if (n == 1) "1 jour" else "$n jours"

    private fun capitalized(text: String) = text.replaceFirstChar { it.uppercase() }
}
