package app.trackly.assistant.tools

import app.trackly.assistant.cards.GoalCard
import app.trackly.assistant.cards.GoalValues
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.GoalTargets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Fixe l'objectif nutritionnel à partir d'une date. Même action que les Réglages :
 * [SetGoal]. Avant d'appliquer, le code vérifie que les macros collent aux calories
 * ([GoalCoherence]) ; sinon l'outil refuse, sans rien écrire, et le modèle se corrige.
 */
object SetGoalTool : AssistantTool {
    override val name = "set_goal"

    override val description = """
        Fixe l'objectif nutritionnel quand l'utilisateur le demande explicitement (« finalement je veux être à 2 300 kcal », « mets mes protéines à 160 g », « à partir de demain, 2 200 kcal »).
        À appeler seulement sur une demande de changement, jamais pour une question, un souhait vague ou une suggestion de ta part.
        L'objectif change dès le jour de suivi en cours, journée entière comprise ; les jours passés gardent le leur. `from_date` : null sauf si l'utilisateur donne une autre date de départ (aujourd'hui ou plus tard).
        Donne toujours les trois macros. Si seules les calories changent, ajuste toi-même les macros à partir de l'objectif en vigueur et du profil du contexte : garde les protéines, ajuste lipides et glucides pour que 4×protéines + 4×glucides + 9×lipides colle aux calories.
        `protein_g` est l'idéal de protéines. Le minimum de protéines du contexte est un plancher que l'application garde toute seule : ne le donne jamais comme `protein_g`, et ne descends jamais dessous.
        Si l'utilisateur donne ses macros, respecte-les. L'application contrôle la cohérence et refuse sinon : corrige alors tes macros.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "kcal" to Schema.number("Objectif en kilocalories par jour.", minimum = 0.0),
        "protein_g" to Schema.number("Objectif de protéines en grammes par jour. null seulement si l'objectif actuel n'a pas de macros.", nullable = true, minimum = 0.0),
        "carbs_g" to Schema.number("Objectif de glucides en grammes par jour. null seulement si l'objectif actuel n'a pas de macros.", nullable = true, minimum = 0.0),
        "fat_g" to Schema.number("Objectif de lipides en grammes par jour. null seulement si l'objectif actuel n'a pas de macros.", nullable = true, minimum = 0.0),
        "from_date" to Schema.date(
            "Date de départ AAAA-MM-JJ UNIQUEMENT si l'utilisateur en donne une (« à partir de demain », « dès lundi »), aujourd'hui ou plus tard. Sinon null : jour de suivi en cours.",
        ),
    )

    @Serializable
    internal data class Args(
        val kcal: Double,
        @SerialName("protein_g") val proteinG: Double? = null,
        @SerialName("carbs_g") val carbsG: Double? = null,
        @SerialName("fat_g") val fatG: Double? = null,
        @SerialName("from_date") val fromDate: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val engine = context.engine
        val today = engine.today()
        val from = try {
            args.fromDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        } catch (e: DateTimeParseException) {
            return ToolResult.error("Date invalide : ${args.fromDate} (format AAAA-MM-JJ)")
        }
        val targets = try {
            GoalTargets(args.kcal, args.proteinG, args.carbsG, args.fatG)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        if (from != null && from.isBefore(today)) {
            return ToolResult.error("Les jours passés gardent leur objectif : `from_date` doit être le jour de suivi en cours ($today) ou plus tard.")
        }

        // Contrôle par le code, avant toute écriture : le modèle propose, l'application dispose.
        val inForce = GoalTimeline.inForce(from ?: today, engine.goals())?.targets
        GoalCoherence.problem(targets, inForce)?.let { return refused(it) }

        return when (val outcome = engine.execute(SetGoal(targets, from, allowPast = false))) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val card = GoalCard.from(result)
                    ?: return ToolResult.error("Aucun objectif n'a été modifié.")
                ToolResult(
                    output = buildJsonObject {
                        put("status", "goal_set")
                        put("action_id", result.id.value)
                        put("effective_from", card.effectiveFrom)
                        put("before", card.before?.let(::values) ?: JsonNull)
                        put("after", values(card.after))
                        // Plancher gardé par l'application, jamais la cible.
                        put("protein_minimum_g", result.goalChanges.lastOrNull()?.after?.targets?.proteinMinG?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("days") { add(dayFacts(engine, card.date)) }
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }

    private fun refused(message: String) = ToolResult(buildJsonObject {
        put("status", "refused")
        put("reason", "incoherent_goal")
        put("message", message)
        put("nothing_saved", true)
    })

    private fun values(v: GoalValues): JsonObject = buildJsonObject {
        put("kcal", v.kcal)
        put("protein_g", v.proteinG?.let(::JsonPrimitive) ?: JsonNull)
        put("carbs_g", v.carbsG?.let(::JsonPrimitive) ?: JsonNull)
        put("fat_g", v.fatG?.let(::JsonPrimitive) ?: JsonNull)
    }
}
