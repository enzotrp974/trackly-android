package app.trackly.assistant.tools

import app.trackly.assistant.cards.WeekInfoCard
import app.trackly.core.action.ActionOutcome
import app.trackly.core.review.DaySteps
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.SessionCount
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.review.FiguresText
import app.trackly.review.ReportBasis
import app.trackly.review.ReviewPeriods
import app.trackly.review.page.PageText
import app.trackly.review.page.ReportPageBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Bilan : enregistre les informations **déclarées** par l'utilisateur pour la période du
 * bilan (action [DeclareWeekInfo] du moteur). Jamais d'estimation : seules les valeurs dites ou lues
 * sur une capture par l'utilisateur sont enregistrées. Le résultat renvoie l'état enregistré et les
 * chiffres recalculés par l'application.
 */
class RecordWeekInfoTool(private val periods: ReviewPeriods) : AssistantTool {
    override val name = "record_week_info"

    override val description = """
        Enregistre les informations de la période du bilan que l'utilisateur te donne : pesées datées, pas, séances. À appeler dès qu'il donne une valeur, par texte ou sur une capture d'écran de sa montre. Plusieurs valeurs d'un même message : un seul appel.
        N'enregistre QUE ce que l'utilisateur dit ou ce qui est lisible sur sa capture. Jamais de poids, de pas ou de séance estimé, supposé ou déduit. Si une valeur ou sa date est illisible ou incertaine, ne l'enregistre pas et demande.
        Les dates doivent être dans la période du bilan (voir le contexte), jamais dans le futur. Une valeur du même jour (ou du même type de séance) remplace la précédente. `null` pour tout ce que l'utilisateur n'a pas donné dans ce message.
        Ne sert pas à enregistrer des repas.
    """.trimIndent()

    private val datePattern = "^\\d{4}-\\d{2}-\\d{2}$"

    override val parameters: JsonObject = Schema.obj(
        "weigh_ins" to Schema.array(
            Schema.obj(
                "date" to Schema.string("Jour de la pesée, AAAA-MM-JJ.", pattern = datePattern),
                "kg" to Schema.number("Poids en kilogrammes, tel que dit ou lu (ex. 72,4 → 72.4).", minimum = 0.0),
            ),
            "Pesées données ou lues, une par jour au plus. null si aucune dans ce message.",
            nullable = true,
        ),
        "steps_by_day" to Schema.array(
            Schema.obj(
                "date" to Schema.string("Jour, AAAA-MM-JJ.", pattern = datePattern),
                "steps" to Schema.integer("Nombre de pas de ce jour, tel que donné ou lu.", minimum = 0),
            ),
            "Pas d'un jour précis. null si aucun dans ce message.",
            nullable = true,
        ),
        "steps_average" to Schema.integer(
            "Moyenne de pas par jour de la période, UNIQUEMENT si l'utilisateur la donne lui-même (ne la calcule jamais). null sinon.",
            nullable = true,
            minimum = 0,
        ),
        "sessions" to Schema.array(
            Schema.obj(
                "type" to Schema.string("Type de séance en français, au singulier : musculation, course, vélo…"),
                "count" to Schema.integer("Nombre de séances de ce type sur toute la période du bilan.", minimum = 0),
            ),
            "Séances de la semaine par type. Liste non vide = fusion par type (un type donné remplace l'ancien, les autres sont conservés). Liste vide [] = l'utilisateur dit n'avoir fait AUCUNE séance sur la période : cela REMET À ZÉRO les séances déjà notées. null si rien de nouveau sur les séances.",
            nullable = true,
        ),
        "remove_weigh_in_dates" to Schema.array(
            Schema.string("Jour de la pesée à retirer, AAAA-MM-JJ.", pattern = datePattern),
            "Pesées à retirer (erreur de lecture signalée par l'utilisateur). null sinon.",
            nullable = true,
        ),
        "remove_steps_dates" to Schema.array(
            Schema.string("Jour dont les pas sont à retirer, AAAA-MM-JJ.", pattern = datePattern),
            "Jours de pas à retirer. null sinon.",
            nullable = true,
        ),
    )

    @Serializable
    internal data class WeighInArg(val date: String, val kg: Double)

    @Serializable
    internal data class StepsArg(val date: String, val steps: Int)

    @Serializable
    internal data class SessionArg(val type: String, val count: Int)

    @Serializable
    internal data class Args(
        @SerialName("weigh_ins") val weighIns: List<WeighInArg>? = null,
        @SerialName("steps_by_day") val stepsByDay: List<StepsArg>? = null,
        @SerialName("steps_average") val stepsAverage: Int? = null,
        val sessions: List<SessionArg>? = null,
        @SerialName("remove_weigh_in_dates") val removeWeighInDates: List<String>? = null,
        @SerialName("remove_steps_dates") val removeStepsDates: List<String>? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val engine = context.engine
        val week = periods.forWriting(context.messageAt, context.period)
        val maxSessions = SessionCount.maxFor(week.days.size)
        args.sessions?.firstOrNull { it.count > maxSessions }?.let {
            return ToolResult.error("Nombre de séances invalide : ${it.count} ${it.type} (au plus $maxSessions sur les ${week.days.size} jours de la période).")
        }
        val action = try {
            DeclareWeekInfo(
                weekStart = week.start,
                weighIns = args.weighIns.orEmpty().map { WeighIn(date(it.date), it.kg) },
                steps = args.stepsByDay.orEmpty().map { DaySteps(date(it.date), it.steps) },
                stepsAverage = args.stepsAverage,
                sessions = args.sessions?.map { SessionCount(it.type.trim(), it.count) },
                removeWeighInDays = args.removeWeighInDates.orEmpty().map(::date),
                removeStepDays = args.removeStepsDates.orEmpty().map(::date),
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val info = engine.weekInfo(week.start) ?: return ToolResult.error("Aucune information n'a été enregistrée.")
                // Les chiffres que verra le rapport, mise à jour du poids de référence comprise.
                val basis = ReportBasis.figures(engine, periods.reports, week)
                val figures = basis.figures
                val today = ReviewPeriods.today(context.messageAt)
                val advice = engine.catchUp(figures, today)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "recorded")
                        put("action_id", outcome.result.id.value)
                        put("week_start", week.start.toString())
                        put("week_info", infoJson(info))
                        put("figures_computed_by_app", FiguresText.render(figures) + ReportBasis.forModel(basis)?.let { "\n$it" }.orEmpty())
                        // Les pesées ou les pas changent la prévision, donc les conseils.
                        put("advice_computed_by_app", FiguresText.advice(advice))
                        // La page change avec eux : le mot de Trackly s'accorde à celle-ci.
                        put("page_computed_by_app", PageText.forModel(ReportPageBuilder.build(figures, "", today, advice)))
                        put("card_shown", true)
                    },
                    card = WeekInfoCard.from(outcome.result.id.value, info),
                    applied = outcome.result,
                )
            }
        }
    }

    private fun date(text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Date invalide : $text (format AAAA-MM-JJ)")
    }

    private fun infoJson(info: WeekInfo): JsonObject = buildJsonObject {
        put("weigh_ins", buildJsonArray { info.weighIns.forEach { addJsonObject { put("date", it.day.toString()); put("kg", it.kg) } } })
        put("steps_by_day", buildJsonArray { info.steps.forEach { addJsonObject { put("date", it.day.toString()); put("steps", it.steps) } } })
        put("steps_average", info.stepsAverage?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "sessions",
            info.sessions?.let { list -> buildJsonArray { list.forEach { addJsonObject { put("type", it.type); put("count", it.count) } } } } ?: JsonNull,
        )
    }
}
