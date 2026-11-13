package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CorrectTerm
import app.trackly.core.action.RemoveTermCorrection
import app.trackly.core.action.productChanges
import app.trackly.core.action.recipeChanges
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.model.TermCorrection
import app.trackly.core.model.TermCorrections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Retient ou retire un mot corrigé (« alpa » veut dire « Alpha »). Actions du moteur : [CorrectTerm] (qui corrige aussi,
 * dans la même action annulable, les noms et marques des produits et plats concernés) et [RemoveTermCorrection].
 * Le mot entendu et le vrai mot viennent toujours de l'utilisateur.
 */
object ManageTermCorrectionTool : AssistantTool {
    override val name = "manage_term_correction"

    private const val SET = "set"
    private const val REMOVE = "remove"

    override val description = """
        Retient ou retire un mot corrigé, uniquement à la demande de l'utilisateur :
        - set : « quand j'écris ou dicte alpa, c'est Alpha ». Retient la correction (dictée comprise) et corrige, en une seule action annulable, les noms et marques des produits et plats mémorisés qui contiennent ce mot entier.
        - remove : « retire cette correction ». La règle est désactivée ; les noms déjà corrigés restent.
        `heard` est le mot tel qu'il est entendu ou écrit à tort ; `correct` le vrai mot (obligatoire pour set, null pour remove). N'utilise pas cet outil pour changer le nom d'un seul produit (update_product) ni pour modifier des valeurs.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "operation" to Schema.string("Opération à faire.", enum = listOf(SET, REMOVE)),
        "heard" to Schema.string("Le mot entendu ou mal écrit (« alpa »)."),
        "correct" to Schema.string("Le vrai mot (« Alpha »), avec sa casse ; null pour remove.", nullable = true),
    )

    @Serializable
    internal data class Args(val operation: String, val heard: String, val correct: String? = null)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val (action: Action, operation) = when (args.operation) {
            SET -> {
                val correct = args.correct?.takeIf { it.isNotBlank() } ?: return ToolResult.error("Il faut le vrai mot (`correct`) pour set.")
                CorrectTerm(args.heard, correct) to ProductOperation.TermCorrected
            }
            REMOVE -> RemoveTermCorrection(args.heard) to ProductOperation.TermRemoved
            else -> return ToolResult.error("Opération inconnue : ${args.operation}")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val products = result.productChanges.size
                val dishes = result.recipeChanges.count { !it.after.isVariant }
                val heard = args.heard.trim()
                val label = if (operation == ProductOperation.TermCorrected) "$heard → ${args.correct!!.trim()}" else heard
                val detail = if (operation == ProductOperation.TermCorrected) renamedText(products, dishes) else null
                // Fiches qui contiennent encore le mot entendu (nom déjà pris par une autre fiche, par exemple).
                val remaining = if (operation == ProductOperation.TermCorrected) remaining(context, heard, args.correct!!.trim()) else emptyList()
                ToolResult(
                    output = buildJsonObject {
                        put("status", if (operation == ProductOperation.TermCorrected) "corrected" else "removed")
                        put("action_id", result.id.value)
                        put("heard", heard)
                        args.correct?.let { put("correct", it.trim()) }
                        put("products_renamed", products)
                        put("dishes_renamed", dishes)
                        putJsonArray("still_containing") { remaining.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        put("card_shown", true)
                    },
                    card = ProductCard(result.id.value, operation, productId = "", name = label, detail = detail),
                    applied = result,
                )
            }
        }
    }

    private fun renamedText(products: Int, dishes: Int): String? {
        val parts = listOfNotNull(
            products.takeIf { it > 0 }?.let { if (it == 1) "1 produit corrigé" else "$it produits corrigés" },
            dishes.takeIf { it > 0 }?.let { if (it == 1) "1 plat corrigé" else "$it plats corrigés" },
        )
        return parts.joinToString(", ").ifEmpty { null }
    }

    private suspend fun remaining(context: ToolContext, heard: String, correct: String): List<String> {
        val engine = context.engine
        val correction = TermCorrection("", heard, correct)
        val products = CatalogSearch.visibleProducts(engine.allProducts())
            .filter { TermCorrections.wouldChange(it.name, correction) || it.brand?.let { b -> TermCorrections.wouldChange(b, correction) } == true }
            .map { it.name }
        val dishes = Dishes.group(engine.allRecipes()).filter { TermCorrections.wouldChange(it.name, correction) }.map { it.name }
        return (products + dishes).distinct()
    }
}
