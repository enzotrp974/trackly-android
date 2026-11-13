package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProductRef
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.RelinkConsumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Quantity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * « Non, c'est l'autre » : relie une consommation à un autre produit connu, par une nouvelle
 * version. Action du moteur : [RelinkConsumption] ; les valeurs sont recalculées par l'app.
 */
object SwitchProductTool : AssistantTool {
    override val name = "switch_product"

    override val description = """
        Change le produit d'une consommation déjà enregistrée quand l'utilisateur dit que c'est un autre produit connu (« non, c'est l'autre barre », « celle à la vanille »).
        Identifie la consommation par son entry_id et le bon produit par son product_id (contexte, cartes précédentes ou search_products). L'app recalcule les valeurs.
        Pour une erreur de quantité ou une valeur à corriger, utilise correct_consumption.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "entry_id" to Schema.string("Identifiant de la consommation à rattacher à un autre produit."),
        "product_id" to Schema.string("Identifiant du bon produit."),
        "quantity" to Schema.number("Nouvelle quantité si elle change (ex. nombre de portions), sinon null : la quantité d'origine est gardée.", nullable = true, minimum = 0.0),
        "unit" to Schema.string("Unité de `quantity` (g, ml, pièce, portion), ou null.", nullable = true),
    )

    @Serializable
    internal data class Args(
        @SerialName("entry_id") val entryId: String,
        @SerialName("product_id") val productId: String,
        val quantity: Double? = null,
        val unit: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val action = try {
            RelinkConsumption(
                entryId = EntryId(args.entryId),
                productId = args.productId,
                amount = args.quantity?.let { Quantity(it, args.unit?.trim()?.takeIf(String::isNotEmpty) ?: return ToolResult.error("Unité manquante pour la quantité")) },
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val card = RecordCard.from(result, RecordKind.Corrected)
                val product = card.items.firstOrNull()?.productId?.let { id -> context.engine.product(id) }
                ToolResult(
                    output = buildJsonObject {
                        put("status", "product_changed")
                        put("action_id", result.id.value)
                        put("entry_id", args.entryId)
                        put("product_id", product?.productId)
                        put("name", product?.name)
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
