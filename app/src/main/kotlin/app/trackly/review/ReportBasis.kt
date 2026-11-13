package app.trackly.review

import app.trackly.core.engine.ReportFigures
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.ReferenceWeights
import app.trackly.core.review.WeekRange
import app.trackly.review.page.ReferenceWeight
import app.trackly.review.page.ReportContent

/**
 * Ce sur quoi se bâtit le bilan d'une période : le modèle écrit son mot sur les chiffres que verra le rapport, mise à
 * jour du poids de référence comprise, avant qu'elle soit écrite.
 */
object ReportBasis {
    /**
     * Bloc « Poids de référence » d'un rapport déjà produit pour la période [week], s'il porte une mise à jour (même annulée) :
     * un rapport de plus le reprend, sans seconde mise à jour.
     */
    suspend fun reusedReference(reports: ReportRepository, week: WeekRange): ReferenceWeight? =
        reports.latestFor(week.start)?.let { reports.get(it.id) }?.let { ReportContent.decode(it.html) }?.referenceWeight?.takeIf { it.actionId != null }

    /** Chiffres de [week] tels que les verra le prochain rapport, sans rien écrire. */
    suspend fun figures(engine: TracklyEngine, reports: ReportRepository, week: WeekRange): ReportFigures =
        if (reusedReference(reports, week) != null) ReportFigures(engine.weeklyFigures(week), null) else engine.reportFigures(week, engine.today())

    /** Phrase pour le modèle quand le rapport mettra à jour le poids de référence ; `null` sinon. */
    fun forModel(basis: ReportFigures): String? = basis.referenceChange?.let { c ->
        "Le rapport mettra le poids de référence à jour : ${ReferenceWeights.label(c.beforeKg)} → ${ReferenceWeights.label(c.afterKg)} kg, calories inchangées. " +
            "Les objectifs de protéines et de lipides ci-dessous, et les points de la page, en tiennent déjà compte."
    }
}
