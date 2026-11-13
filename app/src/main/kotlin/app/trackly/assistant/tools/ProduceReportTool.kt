package app.trackly.assistant.tools

import app.trackly.assistant.cards.ReportReadyCard
import app.trackly.review.FiguresText
import app.trackly.review.ReportBasis
import app.trackly.review.ReviewNotifier
import app.trackly.review.ReviewPeriods
import app.trackly.review.page.ReportContent
import app.trackly.review.page.ReportPageBuilder
import app.trackly.review.ReportInfo
import app.trackly.review.page.ReferenceText
import app.trackly.review.page.ReferenceWeight
import app.trackly.core.review.WeeklyFigures
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bilan : produit le rapport de la période. Les chiffres viennent du moteur au moment
 * de la production ; le modèle ne fournit que l'**analyse** (texte brut). La page du bilan est
 * construite localement ([ReportPageBuilder]), enregistrée dans le stockage privé, puis la notification « Ton bilan
 * est prêt » est envoyée.
 */
class ProduceReportTool(
    private val periods: ReviewPeriods,
    private val notifier: ReviewNotifier,
) : AssistantTool {
    private val reports get() = periods.reports

    override val name = "produce_weekly_report"

    override val description = """
        Produit le rapport du bilan de la période (depuis le dernier bilan) et notifie l'utilisateur. À appeler quand il dit avoir tout donné ou demande son bilan (« génère », « c'est tout », « avec ce que tu as »), même s'il manque des informations : le rapport les signale lui-même.
        Les chiffres, la date, les points et les conseils sont ceux de l'application, déjà sur la page : tu n'envoies que `analysis`, le « mot de Trackly », écrit à partir du contexte ou du dernier résultat de record_week_info, sans rien recalculer ni inventer. Texte brut en français, 2 à 5 phrases courtes, 500 caractères au plus, sans markdown ni HTML.
        Ne l'appelle pas pour une simple question.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "analysis" to Schema.string(
            "Mot de Trackly, affiché sous l'en-tête et la courbe : le verdict de la page en mots simples, ce qui va bien, le seul point à regarder s'il y en a un (ou le conseil affiché, en option), et qu'un ralentissement est normal s'il y a lieu. Ton d'ami, tutoiement, sans reproche ni chiffre recalculé ; une suggestion de méthode possible à la fin. 2 à 5 phrases, 500 caractères au plus, texte brut.",
        ),
    )

    @Serializable
    internal data class Args(val analysis: String)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val analysis = args.analysis.trim()
        if (analysis.isEmpty()) return ToolResult.error("L'analyse est vide : rédige-la à partir des chiffres calculés.")
        if (analysis.length > MAX_ANALYSIS) {
            return ToolResult.error("Le mot est trop long (${analysis.length} caractères) : réécris-le en 2 à 5 phrases courtes, $NOTE_CHARS caractères au plus.")
        }
        val week = try {
            periods.forWriting(context.messageAt, context.period)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: ReviewPeriods.PERIOD_MOVED)
        }
        val today = ReviewPeriods.today(context.messageAt)
        val engine = context.engine

        // Le rapport, sur les chiffres lus après la mise à jour du poids de référence [reference].
        suspend fun saved(reference: ReferenceWeight?): Pair<WeeklyFigures, ReportInfo> {
            // Chiffres lus après la mise à jour : le jour du bilan est jugé sur les objectifs qu'il vient d'écrire,
            // comme sur l'accueil. Ce sont ceux que le modèle a lus avant d'écrire son mot (ReportBasis.figures, même plan,
            // sans écriture), et un second rapport de la période dit la même chose que le premier.
            val figures = engine.weeklyFigures(week)
            // Conseils chiffrés : calculés par l'application au moment du rapport, figés avec la page.
            val page = ReportPageBuilder.build(figures, analysis, today, engine.catchUp(figures, today)).copy(referenceWeight = reference)
            return figures to reports.save(week.start, context.messageAt, ReportContent.encode(page), week.end)
        }

        // Mise à jour du poids de référence et rapport dans une seule transaction : un arrêt de l'app à
        // n'importe quel instant ne laisse ni mise à jour sans page ni Annuler, ni rapport sans sa mise à jour.
        val (reference, done) = withContext(NonCancellable) {
            try {
                reports.atomically {
                    // Poids de référence : mis à jour une seule fois par période. Un rapport de plus pour la même période (le
                    // même jour) reprend le bloc et l'action du premier, même annulée : jamais une seconde mise à jour.
                    val reference = ReportBasis.reusedReference(reports, week) ?: run {
                        val outcome = engine.updateReferenceWeight(engine.today())
                        // Une action refusée dans la transaction la ferait défaire en entier, rapport compris : on la défait
                        // nous-mêmes, puis le rapport s'enregistre seul, avec le refus.
                        if (outcome.action == null && outcome.refusal != null) throw UpdateRefused(ReferenceText.of(outcome))
                        ReferenceText.of(outcome)
                    }
                    reference to saved(reference)
                }
            } catch (refused: UpdateRefused) {
                reports.atomically { refused.reference to saved(refused.reference) }
            }
        }
        val (figures, info) = done
        val notified = notifier.reportReady(info)
        return ToolResult(
            output = buildJsonObject {
                put("status", "report_ready")
                put("report_id", info.id)
                put("period_start", week.start.toString())
                put("period_end", week.end.toString())
                put("card_shown", true)
                put("figures_used", FiguresText.render(figures))
                reference?.let { put("reference_weight", ReferenceText.summary(it)) }
            },
            card = ReportReadyCard(info.id, week.start.toString(), periodEnd = week.end.toString(), notified = notified),
        )
    }

    /** La mise à jour du poids de référence est refusée : la transaction est défaite, le rapport s'enregistre sans elle. */
    private class UpdateRefused(val reference: ReferenceWeight?) : Exception()

    companion object {
        /** Longueur visée du mot de Trackly, dite au modèle. */
        const val NOTE_CHARS = 500

        /** Refus au-delà : la cible, plus une marge pour ne pas faire recommencer un mot à peine trop long. */
        const val MAX_ANALYSIS = 650
    }
}
