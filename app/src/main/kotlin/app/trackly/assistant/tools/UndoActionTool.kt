package app.trackly.assistant.tools

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.core.action.ActionResult
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.model.ActionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Annule une action (« annule ça ») par de nouvelles versions. Actions : [Undo] / [UndoLast]. */
object UndoActionTool : AssistantTool {
    /**
     * « Remets-le » : annuler une annulation rétablit des consommations, qui ont alors une vraie carte
     * « Rétabli » (aliments, kcal, macros, Annuler / Corriger). Sinon, la ligne d'annulation.
     */
    internal fun cardOf(result: ActionResult, undone: Long): ChatCard {
        val changes = result.consumptionChanges
        val restored = result.title.startsWith(RESTORE_PREFIX)
        if (restored && changes.isNotEmpty() && changes.size == result.changes.size) {
            if (changes.all { it.after.isActive }) return RecordCard.from(result, RecordKind.Restored)
        }
        return UndoCard(actionId = result.id.value, undoneActionId = undone, title = result.title)
    }

    private const val RESTORE_PREFIX = "Rétablissement : "

    override val name = "undo_action"

    override val description = """
        Annule une action précédente quand l'utilisateur le demande (« annule », « en fait non, retire ça »).
        action_id : l'identifiant donné par le résultat d'un outil ou une carte ; null pour annuler la dernière modification encore en vigueur.
        « Remets-le », « finalement si » juste après une annulation : annule l'annulation (son action_id) ; cela rétablit l'enregistrement.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "action_id" to Schema.integer("Action à annuler, ou null pour la dernière.", nullable = true, minimum = 1),
    )

    @Serializable
    internal data class Args(@SerialName("action_id") val actionId: Long? = null)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val action = args.actionId?.let { Undo(ActionId(it)) } ?: UndoLast
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val undone = result.undoes?.value ?: 0L
                val card = cardOf(result, undone)
                ToolResult(
                    output = buildJsonObject {
                        put("status", if (card is RecordCard && card.kind == RecordKind.Restored) "restored" else "undone")
                        put("action_id", result.id.value)
                        put("undone_action_id", undone)
                        put("title", result.title)
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
