package app.trackly.assistant.tools

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.model.EntryId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Supprime une consommation (nouvelle version `deleted`, annulable). Action : [DeleteConsumption]. */
object DeleteConsumptionTool : AssistantTool {
    override val name = "delete_consumption"

    override val description = """
        Supprime UNE consommation enregistrée quand l'utilisateur dit qu'il ne l'a finalement pas mangée ou demande de la retirer (« enlève la pomme »).
        Identifie-la par son entry_id (journée du contexte, correction demandée par l'utilisateur, cartes précédentes, ou get_day pour un autre jour) ; n'invente jamais un entry_id. Plusieurs aliments à retirer : un appel par aliment.
        Pour annuler l'action qui vient d'être faite en entier, préfère undo_action.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "entry_id" to Schema.string("Identifiant de la consommation à supprimer."),
    )

    @Serializable
    internal data class Args(@SerialName("entry_id") val entryId: String)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val action = try {
            DeleteConsumption(EntryId(args.entryId))
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Identifiant invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val card = RecordCard.from(result, RecordKind.Deleted)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "deleted")
                        put("action_id", result.id.value)
                        put("entry_id", args.entryId)
                        putJsonArray("entries") { for (item in card.items) add(entryFacts(item)) }
                        putJsonArray("days") { for (day in result.days) add(dayFacts(context.engine, day)) }
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }
}
