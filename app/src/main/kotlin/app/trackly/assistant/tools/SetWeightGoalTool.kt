package app.trackly.assistant.tools

import app.trackly.assistant.cards.WeightGoalCard
import app.trackly.core.action.ActionOutcome
import app.trackly.core.goal.SetWeightGoal
import app.trackly.review.FiguresText
import app.trackly.review.page.PageText
import app.trackly.review.page.ReportPageBuilder
import app.trackly.review.ReviewPeriods
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Chat du bilan : change l'objectif de poids (poids visé et date), action [SetWeightGoal] du moteur, annulable par la
 * carte. Ne touche ni aux calories ni au poids de référence. Le résultat renvoie la prévision d'arrivée recalculée par
 * l'application.
 */
class SetWeightGoalTool(private val periods: ReviewPeriods) : AssistantTool {
    override val name = "set_weight_goal"

    override val description = """
        Change l'objectif de poids (poids visé et date) quand l'utilisateur le demande explicitement (« vise 60 kg pour le 1er avril », « repousse ma date au 15 mars »).
        Jamais pour une question, un souhait vague ou une idée de ta part. Si l'utilisateur ne change qu'une des deux valeurs, reprends l'autre telle qu'elle est dans le contexte.
        Ne change ni les calories ni le poids de référence. L'application recalcule la date d'arrivée : cite celle du résultat.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "target_kg" to Schema.number("Poids visé en kilogrammes (ex. 60,5 → 60.5).", minimum = 0.0),
        "target_date" to Schema.string("Date visée, AAAA-MM-JJ, après aujourd'hui.", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
    )

    @Serializable
    internal data class Args(
        @SerialName("target_kg") val targetKg: Double,
        @SerialName("target_date") val targetDate: String,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val date = try {
            LocalDate.parse(args.targetDate)
        } catch (e: DateTimeParseException) {
            return ToolResult.error("Date invalide : ${args.targetDate} (format AAAA-MM-JJ)")
        }
        val engine = context.engine
        return when (val outcome = engine.execute(SetWeightGoal(args.targetKg, date))) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val (before, after) = SetWeightGoal.change(result.changes) ?: return ToolResult.error("Aucun objectif de poids n'a été modifié.")
                val figures = engine.weeklyFigures(periods.at(context.messageAt, context.period))
                val today = ReviewPeriods.today(context.messageAt)
                val advice = engine.catchUp(figures, today)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "weight_goal_set")
                        put("action_id", result.id.value)
                        put("before", SetWeightGoal.label(before))
                        put("after", SetWeightGoal.label(after))
                        put("forecast_computed_by_app", FiguresText.forecast(figures.forecast))
                        // Le verdict, la marge et les conseils changent avec l'objectif : le mot s'accorde à cette page.
                        put("advice_computed_by_app", FiguresText.advice(advice))
                        put("page_computed_by_app", PageText.forModel(ReportPageBuilder.build(figures, "", today, advice)))
                        put("card_shown", true)
                    },
                    card = WeightGoalCard.from(result.id.value, before, after),
                    applied = result,
                )
            }
        }
    }
}
