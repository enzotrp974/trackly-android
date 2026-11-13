package app.trackly.assistant.context

import app.trackly.review.FiguresText
import app.trackly.review.ReportBasis
import app.trackly.review.ReportFormat
import app.trackly.review.ReviewPeriods
import app.trackly.review.page.PageText
import app.trackly.review.page.ReportPageBuilder

/** La période du bilan, ses dates, et ce que l'utilisateur a déjà donné. */
class ReviewPeriodContext(private val periods: ReviewPeriods) : ContextProvider {
    override val id = "review-period"
    override val title = "Période du bilan"

    override suspend fun render(env: ContextEnv): String {
        val period = periods.at(env.now, env.period)
        val info = env.engine.weekInfo(period.start)
        val done = periods.reports.latestFor(period.start)
        return buildString {
            appendLine(
                "Bilan depuis le dernier : du ${ReportFormat.weekLabel(period)} (${period.start} au ${period.end}), " +
                    "${period.days.size} jour(s), aujourd'hui compris.",
            )
            appendLine(
                "Dates de la période : " + period.days.takeLast(MAX_LISTED_DAYS).joinToString("; ") { "${ReportFormat.weekdayName(it)} = $it" } +
                    (if (period.days.size > MAX_LISTED_DAYS) " (les $MAX_LISTED_DAYS derniers jours)" else "") + ".",
            )
            appendLine("Déjà donné par l'utilisateur (la seule source des pesées, pas et séances) :")
            appendLine(FiguresText.declared(info))
            append(
                if (done != null) {
                    "Un rapport a déjà été produit pour cette période (un nouveau s'ajoute, rien n'est écrasé)."
                } else {
                    "Aucun rapport produit pour cette période."
                },
            )
        }
    }

    private companion object {
        const val MAX_LISTED_DAYS = 14
    }
}

/** Chiffres de la période calculés par le moteur : le modèle les cite, il ne les calcule pas. */
class ReviewFiguresContext(private val periods: ReviewPeriods) : ContextProvider {
    private val reports get() = periods.reports

    override val id = "review-figures"
    override val title = "Chiffres de la période (calculés par l'application)"

    override suspend fun render(env: ContextEnv): String {
        val period = periods.at(env.now, env.period)
        // Les chiffres que verra le rapport, mise à jour du poids de référence comprise, sans l'écrire.
        val basis = ReportBasis.figures(env.engine, reports, period)
        val figures = basis.figures
        val today = ReviewPeriods.today(env.now)
        val advice = env.engine.catchUp(figures, today)
        // La page telle que l'app la construirait maintenant : le mot de Trackly s'y accorde sans rien recalculer.
        val page = ReportPageBuilder.build(figures, "", today, advice)
        val done = reports.latestFor(period.start) != null
        val reference = ReportBasis.forModel(basis)?.let { "\n$it" }.orEmpty()
        return FiguresText.render(figures).trimEnd() + reference + "\n" + FiguresText.advice(advice) + "\n\n" + PageText.forModel(page, reportDone = done)
    }
}

/** Éléments de contexte de la conversation de bilan, du plus stable au plus variable. */
fun ContextBuilder.Companion.review(periods: ReviewPeriods) =
    ContextBuilder(listOf(ProfileContext, ReferenceWeightContext, ReviewPeriodContext(periods), ReviewFiguresContext(periods)))
