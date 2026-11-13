package app.trackly.assistant.tools

import app.trackly.core.action.DishIngredientInput
import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * Arguments partagés des outils « plats » et « provisions » : ingrédients donnés par le
 * modèle, et présentation des plats au modèle. Les valeurs d'un plat sont toujours calculées par l'app.
 */

/** Schéma d'un ingrédient : un produit connu (valeurs calculées par l'app) ou des valeurs pour 100 g. */
internal fun ingredientSchema() = Schema.obj(
    "label" to Schema.string("Nom de l'ingrédient (« Laitue », « Thon au naturel »). null pour prendre le nom du produit `product_id`.", nullable = true),
    "quantity_g" to Schema.number("Quantité en grammes pour UNE portion du plat.", minimum = 0.0),
    "product_id" to Schema.string("Identifiant d'un produit connu. null sinon.", nullable = true),
    "per_100g" to Schema.obj(
        "kcal" to Schema.number("Kilocalories pour 100 g.", minimum = 0.0),
        "protein_g" to Schema.number("Protéines pour 100 g ; null si inconnu.", nullable = true, minimum = 0.0),
        "carbs_g" to Schema.number("Glucides pour 100 g ; null si inconnu.", nullable = true, minimum = 0.0),
        "fat_g" to Schema.number("Lipides pour 100 g ; null si inconnu.", nullable = true, minimum = 0.0),
        description = "Valeurs pour 100 g. Pour un ingrédient relu, renvoie-les telles quelles. null seulement pour prendre celles du produit `product_id`.",
        nullable = true,
    ),
    description = "Ingrédient d'un plat.",
)

@Serializable
internal data class Per100Args(
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

@Serializable
internal data class IngredientArgs(
    val label: String? = null,
    @SerialName("quantity_g") val quantityG: Double,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("per_100g") val per100g: Per100Args? = null,
) {
    /** Lève [IllegalArgumentException] (message français) si l'ingrédient est incomplet. */
    fun toInput(): DishIngredientInput {
        require(productId != null || per100g != null) { "Ingrédient « ${label ?: "?"} » : remplis `product_id` ou `per_100g`." }
        return DishIngredientInput(
            label = label,
            quantityG = quantityG,
            per100g = per100g?.let { Nutrients(it.kcal, it.proteinG, it.carbsG, it.fatG) },
            productId = productId,
        )
    }
}

private fun nutritionOf(recipe: Recipe): JsonObject {
    val portion = RecipeServing.perPortion(recipe)
    return buildJsonObject {
        put("kcal", portion?.kcal)
        put("protein_g", portion?.proteinG)
        put("carbs_g", portion?.carbsG)
        put("fat_g", portion?.fatG)
    }
}

/** Version d'un plat vue par le modèle. */
internal fun Recipe.toJson(isDefault: Boolean): JsonObject = buildJsonObject {
    put("recipe_id", recipeId)
    put("label", Dishes.label(this@toJson))
    put("variant_name", variantName)
    put("is_default", isDefault)
    put("paused", !active)
    put("per_portion", nutritionOf(this@toJson))
    put("computable", RecipeServing.perPortion(this@toJson) != null)
    put("ingredients", ingredientsJson(this@toJson))
}

/**
 * Ingrédients d'une version vus par le modèle, valeurs pour 100 g comprises : pour modifier un ingrédient
 * (« la barre c'est 12 g de protéines »), le modèle renvoie la liste COMPLÈTE, donc il doit pouvoir la relire.
 */
internal fun ingredientsJson(recipe: Recipe): JsonArray = JsonArray(
    recipe.ingredients.map { i ->
        buildJsonObject {
            put("label", i.label)
            put("quantity_g", i.quantityG)
            put("product_id", i.productId)
            // Les valeurs enregistrées dans le plat, même liées à un produit : modifier un autre ingrédient ne doit pas les changer.
            put(
                "per_100g",
                buildJsonObject {
                    put("kcal", i.per100g.kcal)
                    put("protein_g", i.per100g.proteinG)
                    put("carbs_g", i.per100g.carbsG)
                    put("fat_g", i.per100g.fatG)
                },
            )
        }
    },
)

/**
 * Plat repris de l'ancien suivi, sans ingrédients pesés : son modèle d'origine (`template` : composants et quantités
 * par défaut), les produits connus qu'il cite par `ingredient_ref` (`template_products`), et `composition_unknown`
 * quand il contient une estimation par portion (`recipe_estimate`). Le modèle en tire la liste complète d'une
 * variante. Vide pour un plat aux ingrédients pesés.
 */
internal fun templateFields(recipe: Recipe, products: Collection<Product>): Map<String, JsonElement> {
    if (recipe.ingredients.isNotEmpty()) return emptyMap()
    val template = recipe.sourceTemplateJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: return emptyMap()
    val live = products.filter { it.isActive }.associateBy { it.productId }
    val cited = buildList { collectRefs(template, this) }.distinct()
        .mapNotNull { ref -> live[ref]?.let { p -> p.redirectTo?.let(live::get) ?: p } }
        .distinctBy { it.productId }
    return mapOf(
        "template" to template,
        "template_products" to JsonArray(cited.map { it.toJson() }),
        "composition_unknown" to JsonPrimitive(mentions(template, "recipe_estimate")),
    )
}

private fun collectRefs(element: JsonElement, into: MutableList<String>) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key == "ingredient_ref" && value is JsonPrimitive && value.isString) into += value.content else collectRefs(value, into)
        }
        is JsonArray -> element.forEach { collectRefs(it, into) }
        else -> Unit
    }
}

/** [word] apparaît comme clé ou comme valeur texte quelque part dans [element]. */
private fun mentions(element: JsonElement, word: String): Boolean = when (element) {
    is JsonObject -> element.any { (key, value) -> key == word || mentions(value, word) }
    is JsonArray -> element.any { mentions(it, word) }
    is JsonPrimitive -> element.isString && element.content == word
}

/** Plat et ses variantes vus par le modèle. */
internal fun Dish.toJson(): JsonObject = buildJsonObject {
    put("dish_id", base.recipeId)
    put("name", name)
    put("paused", isPaused)
    putJsonArray("shortcuts") { base.aliases.forEach { add(JsonPrimitive(it)) } }
    put("default_recipe_id", defaultVersion.recipeId)
    put("versions", JsonArray(versions.map { it.toJson(isDefault = it.recipeId == defaultVersion.recipeId) }))
}

internal fun Provision.toJson(): JsonObject = buildJsonObject {
    put("provision_id", provisionId)
    put("name", name)
    put("product_id", productId)
}
