package app.trackly.importing

import app.trackly.core.importing.ImportReport
import app.trackly.day.DayLabels
import app.trackly.day.formatKcal
import kotlin.math.roundToInt

/** Ligne du résumé de la reprise. */
data class SummaryRow(val label: String, val value: String, val detail: String? = null)

/** Résumé lisible d'une reprise : présentation seulement, les chiffres viennent du moteur. */
object ImportSummary {
    fun rows(report: ImportReport): List<SummaryRow> = buildList {
        val period = if (report.firstDay != null && report.lastDay != null) {
            "du ${DayLabels.short(report.firstDay!!)} au ${DayLabels.short(report.lastDay!!)}"
        } else {
            null
        }
        add(SummaryRow("Journées", formatKcal(report.days), period))
        add(SummaryRow("Repas", formatKcal(report.consumptions)))
        add(SummaryRow("Produits", formatKcal(report.products)))
        add(SummaryRow("Plats", formatKcal(report.recipes)))
        add(SummaryRow("Préférences", formatKcal(report.preferences)))
        val goal = report.currentGoal
        add(
            if (goal == null) {
                SummaryRow("Objectif actuel", "Aucun")
            } else {
                SummaryRow("Objectif actuel", "${formatKcal(goal.targets.kcal.roundToInt())} kcal", "depuis le ${DayLabels.short(goal.since)}")
            },
        )
        if (report.verifiedDays > 0) {
            add(SummaryRow("Totaux vérifiés", formatKcal(report.verifiedDays), "journées identiques à l’ancien suivi"))
        }
    }
}
