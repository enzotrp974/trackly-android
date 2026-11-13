package app.trackly.assistant.tools

import app.trackly.assistant.cards.TemporaryGoalCard
import app.trackly.core.review.CatchUpResult
import app.trackly.review.FiguresText
import app.trackly.review.ReviewPeriods
import app.trackly.review.page.AdviceText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat du bilan : montre la carte d'un objectif temporaire **chiffré par l'application** (`CatchUpAdvices`), avec son
 * bouton « Appliquer ». L'outil n'applique rien : rien ne change sans le bouton de l'utilisateur. Le modèle ne choisit que la
 * durée (bornée de 1 à 7 jours, la baisse est recalculée) et le mélange avec des pas ; il n'envoie aucun chiffre de calories.
 */
class ProposeTemporaryGoalTool(private val periods: ReviewPeriods) : AssistantTool {
    override val name = "propose_temporary_goal"

    override val description = """
        Affiche la carte d'un objectif temporaire (calories un peu plus basses quelques jours, puis retour tout seul), chiffré par l'application, avec un bouton « Appliquer » pour l'utilisateur. N'applique rien.
        Seulement si les « Conseils chiffrés » du contexte proposent un objectif temporaire et que l'utilisateur est d'accord pour le voir, ou le demande. Jamais quand le contexte dit « Aucun conseil » ou « Pas de dépassement à rattraper ».
        Tu ne fixes aucune calorie : au plus la durée (`days`, si l'utilisateur en veut une autre) et `with_steps` pour le mélange moitié calories, moitié pas. Cite les chiffres du résultat, tels quels.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "days" to Schema.integer("Durée voulue par l'utilisateur, de 1 à 7 jours (l'application borne) ; null pour la durée de l'application.", nullable = true, minimum = 1),
        "with_steps" to Schema.boolean("Vrai : le mélange, moitié de la baisse en calories et le reste en pas ; null ou faux sinon.", nullable = true),
    )

    @Serializable
    internal data class Args(
        val days: Int? = null,
        @SerialName("with_steps") val withSteps: Boolean? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val engine = context.engine
        val today = ReviewPeriods.today(context.messageAt)
        val figures = engine.weeklyFigures(periods.at(context.messageAt, context.period))
        val result = engine.catchUp(figures, today, args.days)
        val advice = result as? CatchUpResult.Advice
        val plan = advice?.plan ?: return ToolResult.error("Aucun objectif temporaire à proposer. " + FiguresText.advice(result))
        val mix = if (args.withSteps == true) advice.mix ?: return ToolResult.error("Pas de mélange possible ici : propose l'objectif temporaire seul, ou la phrase des pas.") else null
        val shown = mix?.plan ?: plan
        val card = TemporaryGoalCard(AdviceText.temporary(shown, advice.overshootKcal, mix?.steps))
        return ToolResult(
            output = buildJsonObject {
                put("status", "proposed_not_applied")
                put("temporary_kcal", shown.temporaryKcal)
                put("drop_kcal_per_day", shown.dropKcal)
                put("first_day", shown.start.toString())
                put("last_day", shown.end.toString())
                put("return_on", shown.returnOn.toString())
                put("return_kcal", shown.baseKcal)
                mix?.let { put("extra_steps_per_day", it.steps.extraSteps); put("steps_kcal_per_day", it.steps.kcalPerDay) }
                put("card_shown", true)
                put("note", "Rien n'est appliqué : l'utilisateur décide avec le bouton de la carte. Ne répète pas la carte ; une phrase suffit.")
            },
            card = card,
        )
    }
}
