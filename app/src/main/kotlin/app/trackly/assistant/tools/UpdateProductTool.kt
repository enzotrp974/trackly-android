package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddProductShortcut
import app.trackly.core.action.MakeProductRecurring
import app.trackly.core.action.RenameProduct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nomme, renomme un produit ou lui ajoute un raccourci. Actions du moteur : [MakeProductRecurring],
 * [RenameProduct], [AddProductShortcut]. Le nom vient toujours de l'utilisateur.
 */
object UpdateProductTool : AssistantTool {
    override val name = "update_product"

    private const val MAKE_RECURRING = "make_recurring"
    private const val RENAME = "rename"
    private const val ADD_SHORTCUT = "add_shortcut"

    override val description = """
        Gère la fiche d'un produit connu, uniquement à la demande de l'utilisateur :
        - make_recurring : rend récurrent un produit ponctuel sous le nom que l'utilisateur a DONNÉ ou ACCEPTÉ (réponse à ta proposition). N'invente jamais le nom et ne l'appelle pas sans cet accord.
        - rename : change le nom d'un produit (l'ancien nom reste un raccourci).
        - add_shortcut : ajoute un raccourci qui désigne ce produit (« appelle-la barre choco »).
        `value` est le nom ou le raccourci, mot pour mot comme l'utilisateur le veut.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "operation" to Schema.string("Opération à faire.", enum = listOf(MAKE_RECURRING, RENAME, ADD_SHORTCUT)),
        "product_id" to Schema.string("Identifiant du produit."),
        "value" to Schema.string("Le nom (make_recurring, rename) ou le raccourci (add_shortcut)."),
    )

    @Serializable
    internal data class Args(val operation: String, @SerialName("product_id") val productId: String, val value: String)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val before = context.engine.product(args.productId)
        val (action: Action, operation) = when (args.operation) {
            MAKE_RECURRING -> MakeProductRecurring(args.productId, args.value) to ProductOperation.MadeRecurring
            RENAME -> RenameProduct(args.productId, args.value) to ProductOperation.Renamed
            ADD_SHORTCUT -> AddProductShortcut(args.productId, args.value) to ProductOperation.ShortcutAdded
            else -> return ToolResult.error("Opération inconnue : ${args.operation}")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val product = checkNotNull(context.engine.product(args.productId))
                val card = ProductCard(
                    actionId = result.id.value,
                    operation = operation,
                    productId = product.productId,
                    name = product.name,
                    detail = when (operation) {
                        ProductOperation.ShortcutAdded -> "raccourci « ${args.value.trim()} »"
                        ProductOperation.Renamed, ProductOperation.MadeRecurring -> before?.name?.takeIf { it != product.name }?.let { "avant : $it" }
                        else -> null
                    },
                )
                ToolResult(
                    output = buildJsonObject {
                        put("status", "updated")
                        put("action_id", result.id.value)
                        put("product", product.toJson())
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }
}
