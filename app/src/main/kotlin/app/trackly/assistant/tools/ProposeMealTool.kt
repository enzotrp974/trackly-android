package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalItem
import app.trackly.assistant.cards.ProposalPricing
import app.trackly.assistant.cards.cardQuantity
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.model.Moment
import app.trackly.core.model.Quantity
import app.trackly.core.model.Units
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Carte de proposition : UNE idée de repas, chiffrée par l'app depuis les fiches connues (produits, plats),
 * avec un bouton « Noter ». Rien n'est enregistré : une proposition ne compte pas tant qu'elle n'est pas notée.
 * Le modèle ne donne que les fiches et les quantités, jamais de kcal ni de macros ; un aliment sans fiche reste
 * à valeur inconnue, signalée sur la carte.
 */
object ProposeMealTool : AssistantTool {
    override val name = "propose_meal"

    override val description = """
        Affiche une carte de proposition : UNE idée de repas détaillée, chiffrée par l'app depuis ses fiches, avec un bouton « Noter ». N'enregistre RIEN.
        À appeler quand l'utilisateur demande UNE idée, ou choisit une idée d'une liste que tu viens de donner (« la deuxième », « ok pour les pâtes »). Jamais pour plusieurs idées à la fois : une liste courte en Markdown, sans carte.
        Pour chaque aliment, remplis UN SEUL champ : `product` (fiche connue), `dish` (plat récurrent actif) ou `food` (aliment sans fiche : sa valeur restera inconnue). Ne donne jamais de kcal ni de macros : l'app les calcule.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "title" to Schema.string("Nom court de l'idée, en français (« Pâtes thon sauce tomate »)."),
        "moment" to Schema.string("Moment du repas proposé.", enum = MOMENT_CODES),
        "items" to Schema.array(
            Schema.obj(
                "product" to Schema.obj(
                    "product_id" to Schema.string("Identifiant d'un produit connu (contexte, get_meal_ideas ou search_products)."),
                    "quantity" to Schema.number("Quantité proposée, dans l'unité `unit`.", minimum = 0.0),
                    "unit" to Schema.string("Unité de la fiche (g, ml, pièce) ou « portion »."),
                    description = "Produit connu ; null sinon.",
                    nullable = true,
                ),
                "dish" to Schema.obj(
                    "recipe_id" to Schema.string("Identifiant d'un plat actif (dish_id) ou d'une de ses variantes (recipe_id)."),
                    "named_version" to Schema.boolean("true si tu proposes une version précise du plat ; false : sa variante par défaut."),
                    "portions" to Schema.number("Nombre de portions (1 = le plat entier).", minimum = 0.0),
                    description = "Plat récurrent ; null sinon.",
                    nullable = true,
                ),
                "food" to Schema.obj(
                    "label" to Schema.string("Nom court de l'aliment sans fiche (« Sauce tomate »)."),
                    "quantity" to Schema.number("Quantité proposée ; null si sans objet.", nullable = true, minimum = 0.0),
                    "unit" to Schema.string("Unité de `quantity` ; null si `quantity` est null.", nullable = true),
                    description = "Aliment sans fiche connue : sa valeur sera signalée comme inconnue. null sinon.",
                    nullable = true,
                ),
            ),
            "Aliments de l'idée, un par fiche.",
            minItems = 1,
        ),
    )

    @Serializable
    internal data class ProductArg(@SerialName("product_id") val productId: String, val quantity: Double, val unit: String)

    @Serializable
    internal data class DishArg(@SerialName("recipe_id") val recipeId: String, @SerialName("named_version") val namedVersion: Boolean = false, val portions: Double = 1.0)

    @Serializable
    internal data class FoodArg(val label: String, val quantity: Double? = null, val unit: String? = null)

    @Serializable
    internal data class Item(val product: ProductArg? = null, val dish: DishArg? = null, val food: FoodArg? = null)

    @Serializable
    internal data class Args(val title: String, val moment: String, val items: List<Item>)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val title = args.title.trim()
        if (title.isEmpty()) return ToolResult.error("Donne un nom court à l'idée (`title`).")
        if (args.items.isEmpty()) return ToolResult.error("Une proposition a au moins un aliment.")
        val moment = Moment.entries.firstOrNull { it.code == args.moment } ?: return ToolResult.error("Moment inconnu : ${args.moment}")
        val products = context.engine.allProducts()
        val recipes = context.engine.allRecipes()
        val items = try {
            args.items.mapIndexed { i, item ->
                require(listOfNotNull(item.product, item.dish, item.food).size == 1) {
                    "Aliment ${i + 1} : remplis UN SEUL champ parmi `product`, `dish` et `food`."
                }
                when {
                    item.product != null -> productItem(item.product, products)
                    item.dish != null -> dishItem(item.dish, recipes)
                    else -> foodItem(item.food!!)
                }
            }
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        val card = ProposalCard(id = "prop-" + UUID.randomUUID(), title = title, moment = moment.code, items = items)
        return ToolResult(
            output = buildJsonObject {
                put("status", "proposed")
                put("proposal_id", card.id)
                put("recorded", false)
                putJsonArray("items") {
                    for (item in items) addJsonObject {
                        put("label", item.label)
                        put("quantity", item.quantity)
                        put("kcal", item.kcal)
                        put("protein_g", item.proteinG)
                        put("carbs_g", item.carbsG)
                        put("fat_g", item.fatG)
                        item.unknown?.let { put("unknown", it) }
                    }
                }
                put("total_kcal", card.totalKcal.round1())
                put("total_complete", card.kcalComplete)
                put("card_shown", true)
                put(
                    "next_step",
                    "La carte montre les quantités, les valeurs et le bouton Noter : ne les répète pas, réponds en une phrase courte. " +
                        "Rien n'est compté tant que l'utilisateur n'a pas appuyé sur Noter ou dit l'avoir mangée.",
                )
            },
            card = card,
        )
    }

    private fun productItem(arg: ProductArg, all: List<app.trackly.core.model.Product>): ProposalItem {
        val found = all.firstOrNull { it.productId == arg.productId && it.isActive }
            ?: throw IllegalArgumentException("Produit inconnu ou supprimé : ${arg.productId}. Cherche-le avec search_products, ou propose-le par `food`.")
        // Une unité qui ne convient pas à la fiche lève IllegalArgumentException : le modèle se corrige.
        return ProposalPricing.product(CatalogSearch.resolve(found, all), Quantity(arg.quantity, arg.unit.trim()))
    }

    private fun dishItem(arg: DishArg, all: List<app.trackly.core.model.Recipe>): ProposalItem {
        val choice = Dishes.choose(all, arg.recipeId, useDefault = !arg.namedVersion)
            ?: throw IllegalArgumentException("Plat inconnu ou supprimé : ${arg.recipeId}.")
        if (choice.dish.isPaused || !choice.recipe.active) throw IllegalArgumentException("« ${Dishes.label(choice.recipe)} » est en pause : ne le propose pas.")
        require(arg.portions > 0) { "Nombre de portions invalide : ${arg.portions}." }
        return ProposalPricing.dish(choice.recipe, Quantity(arg.portions, Units.PORTION))
    }

    private fun foodItem(arg: FoodArg): ProposalItem {
        val label = arg.label.trim()
        require(label.isNotEmpty()) { "Un aliment sans fiche a besoin d'un nom (`food.label`)." }
        val unit = arg.unit?.trim()?.takeIf { it.isNotEmpty() } ?: Units.GRAM
        val quantity = arg.quantity?.let { q -> cardQuantity(q, unit) }
        // Quantité gardée aussi en nombre : « Noter » refuse une estimation d'une autre quantité (CHAT-02).
        return ProposalItem(label = label, quantity = quantity, amount = arg.quantity, unit = unit.takeIf { arg.quantity != null }, unknown = "pas de fiche")
    }
}
