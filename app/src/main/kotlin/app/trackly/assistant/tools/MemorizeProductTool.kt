package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CorrectProduct
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.productChanges
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mémorise la fiche d'un produit **sans** le consommer (« retiens que ma barre protéinée fait… »), ou corrige
 * les valeurs d'un produit connu (« la barre c'est 12 g de protéines »). Rien n'est compté. Actions du
 * moteur : [MemorizeProduct], [CorrectProduct]. Pour un produit mangé, `record_consumptions` mémorise déjà la fiche.
 */
object MemorizeProductTool : AssistantTool {
    override val name = "memorize_product"

    override val description = """
        Mémorise la fiche d'un produit que l'utilisateur te demande de retenir SANS dire qu'il l'a mangé (« retiens ma nouvelle barre protéinée : … »). Ne compte rien dans le journal.
        Si l'utilisateur a mangé le produit, utilise record_consumptions (il mémorise la fiche lui-même).
        `recurring` : true seulement si l'utilisateur donne un nom à garder pour ce produit ; sinon false (produit ponctuel).
        CORRIGER un produit connu (« la barre c'est 12 g de protéines ») : `product_id` = ce produit, et `product` = sa fiche COMPLÈTE corrigée (les autres valeurs reprises telles quelles). Jamais un nouveau produit pour une correction.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "product" to newProductSchema("Fiche du produit à mémoriser."),
        "recurring" to buildJsonObject {
            put("type", "boolean")
            put("description", "true si le nom est celui choisi par l'utilisateur pour un produit qu'il veut garder.")
        },
        "product_id" to Schema.string("Produit connu dont on corrige les valeurs ; null pour mémoriser un nouveau produit.", nullable = true),
    )

    @Serializable
    internal data class Args(
        val product: NewProductArgs,
        @SerialName("recurring") val recurring: Boolean = false,
        @SerialName("product_id") val productId: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val before = args.productId?.let { context.engine.product(it) }
        val action: Action = try {
            val values = args.product.toNewProduct(if (args.recurring) ProductKind.Recurring else ProductKind.Occasional)
            if (args.productId != null) CorrectProduct(args.productId, values) else MemorizeProduct(values)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val product = result.productChanges.single().after
                val card = if (before != null) {
                    ProductCard(result.id.value, ProductOperation.Corrected, product.productId, product.name, changeLine(before, product))
                } else {
                    ProductCard(result.id.value, ProductOperation.Memorized, product.productId, product.name, if (product.kind == ProductKind.Recurring) "récurrent" else "ponctuel")
                }
                ToolResult(
                    output = buildJsonObject {
                        put("status", if (before != null) "corrected" else "memorized")
                        put("action_id", result.id.value)
                        put("product", product.toJson())
                        put("counted_in_journal", false)
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }

    /**
     * Ce qui a changé, en une ligne (« protéines 36 → 12 g pour 100 g »), quand c'est simple : au plus deux
     * valeurs, lues sur la portion si elle a changé, sinon sur la base. `null` sinon.
     */
    internal fun changeLine(before: Product, after: Product): String? {
        val names = listOf("kcal" to Nutrients::kcal, "protéines" to Nutrients::proteinG, "glucides" to Nutrients::carbsG, "lipides" to Nutrients::fatG, "fibres" to Nutrients::fiberG)
        val portionSame = before.portion?.label == after.portion?.label && before.portion?.weight == after.portion?.weight
        val baseSame = before.basis == after.basis
        val changes = names.mapNotNull { (name, of) ->
            val portion = if (portionSame) before.portion?.nutrients?.let(of) to after.portion?.nutrients?.let(of) else null
            val base = if (baseSame) of(before.nutrients) to of(after.nutrients) else null
            val onPortion = portion?.takeIf { it.first != it.second }?.let { Triple(name, it, after.portion!!.label) }
            val onBase = base?.takeIf { it.first != it.second }?.let { Triple(name, it, after.basis?.let { b -> "pour ${number(b.value)} ${b.unit}" }) }
            // Les deux ont changé : celle que l'utilisateur a dite, au chiffre rond (« 12 g »), l'autre en découle.
            if (onPortion != null && onBase != null && isRound(onBase.second.second) && !isRound(onPortion.second.second)) onBase else onPortion ?: onBase
        }
        if (changes.isEmpty() || changes.size > 2 || changes.any { it.second.first == null || it.second.second == null }) return null
        return changes.groupBy { it.third }.entries.joinToString(" · ") { (where, items) ->
            items.joinToString(" · ") { (name, values) ->
                val (old, new) = values
                if (name == "kcal") "${number(old!!)} → ${number(new!!)} kcal" else "$name ${number(old!!)} → ${number(new!!)} g"
            } + (where?.let { if (it.startsWith("pour ")) " $it" else " ($it)" } ?: "")
        }
    }

    private fun isRound(value: Double?) = value != null && value == value.roundToInt().toDouble()

    private fun number(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.1f".format(java.util.Locale.FRANCE, value)
}
