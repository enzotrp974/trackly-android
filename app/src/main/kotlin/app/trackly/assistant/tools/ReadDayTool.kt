package app.trackly.assistant.tools

import app.trackly.core.model.Consumption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lit une journée : consommations actives avec leur `entry_id`, ordre de la journée, bilan calculé
 * par l'app. Lecture seule, aucune carte. Sert à désigner de façon fiable l'entrée à corriger ou à
 * supprimer dans une autre journée que celle du contexte (« enlève le yaourt d'hier ») et à répondre
 * à « j'ai mangé quoi avant-hier ? ».
 */
object ReadDayTool : AssistantTool {
    override val name = "get_day"

    override val description = """
        Lit les consommations ACTIVES d'une journée de suivi, avec leur entry_id, et le bilan calculé par l'app.
        À utiliser avant de corriger ou supprimer une consommation d'un autre jour que celui du contexte (hier, une date), ou quand tu n'as pas son entry_id.
        Le bilan donne l'énergie de la journée : si energy_status vaut estimate, lower_bound ou unknown, consumed_kcal vient de l'estimation validée (ou du minimum connu), pas de la somme des entrées, et les macros sont null. Cite ces chiffres tels quels.
        Lecture seule : ne modifie rien.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "day" to Schema.string("Journée AAAA-MM-JJ (jamais dans le futur).", pattern = "^\\d{4}-\\d{2}-\\d{2}$"),
    )

    @Serializable
    internal data class Args(val day: String)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val day = try {
            parseDay(args.day, context.latestDay()) ?: return ToolResult.error("Date manquante")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Date invalide")
        }
        val summary = context.engine.daySummary(day)
        return ToolResult(
            buildJsonObject {
                put("status", "ok")
                put("day", day.toString())
                putJsonArray("entries") {
                    for (group in summary.moments) for (c in group.consumptions) add(entry(c))
                }
                put("empty", summary.consumptions.isEmpty())
                put("bilan", dayFacts(context.engine, day))
            },
        )
    }

    private fun entry(c: Consumption): JsonObject = buildJsonObject {
        val n = c.food.nutrition
        put("entry_id", c.entryId.value)
        put("label", c.food.label)
        put("quantity", c.food.quantity?.value?.let(::JsonPrimitive) ?: JsonNull)
        put("unit", c.food.quantity?.unit)
        put("moment", c.moment.code)
        put("kcal", n.kcal.round1())
        put("protein_g", n.proteinG?.round1()?.let(::JsonPrimitive) ?: JsonNull)
        put("carbs_g", n.carbsG?.round1()?.let(::JsonPrimitive) ?: JsonNull)
        put("fat_g", n.fatG?.round1()?.let(::JsonPrimitive) ?: JsonNull)
        put("action_id", c.actionId?.value?.let(::JsonPrimitive) ?: JsonNull)
        put("assumptions", JsonArray(c.food.assumptions.map(::JsonPrimitive)))
    }
}
