package app.trackly.assistant.tools

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.RelinkConsumptionToDish
import app.trackly.core.model.EntryId
import app.trackly.core.model.Quantity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * « Non, celle avec la sauce B » : relie une consommation de plat à une autre variante, par une
 * nouvelle version. Action du moteur : [RelinkConsumptionToDish] ; les valeurs sont recalculées.
 */
object SwitchDishTool : AssistantTool {
    override val name = "switch_dish"

    override val description = """
        Change la version d'un plat déjà enregistré quand l'utilisateur corrige la variante (« non, celle avec la sauce B », « c'était la salade au thon »).
        Identifie la consommation par son entry_id et la bonne version par son recipe_id (liste des plats du contexte). L'app recalcule les valeurs depuis les ingrédients de cette version.
        Pour une erreur de quantité, utilise correct_consumption ; pour un autre produit, switch_product.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "entry_id" to Schema.string("Identifiant de la consommation à rattacher à une autre version du plat."),
        "recipe_id" to Schema.string("Identifiant (recipe_id) de la bonne version : la variante, ou le plat pour sa version de base."),
        "quantity" to Schema.number("Nouvelle quantité si elle change (portions ou grammes de plat), sinon null : la quantité d'origine est gardée.", nullable = true, minimum = 0.0),
        "unit" to Schema.string("« portion » ou « g », ou null.", nullable = true),
    )

    @Serializable
    internal data class Args(
        @SerialName("entry_id") val entryId: String,
        @SerialName("recipe_id") val recipeId: String,
        val quantity: Double? = null,
        val unit: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val action = try {
            RelinkConsumptionToDish(
                EntryId(args.entryId),
                args.recipeId,
                args.quantity?.let { Quantity(it, args.unit?.trim()?.takeIf(String::isNotEmpty) ?: "portion") },
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val card = RecordCard.from(result, RecordKind.Corrected)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "dish_changed")
                        put("action_id", result.id.value)
                        put("entry_id", args.entryId)
                        put("label", card.items.firstOrNull()?.label)
                        put("kcal", card.items.firstOrNull()?.kcal)
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
