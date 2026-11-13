package app.trackly.assistant.tools

import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeNutrition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Cherche un produit ou un plat connus : nom, raccourci (« barre choco »), marque ; sans tenir
 * compte de la casse ni des accents. Lit seulement, ne modifie rien. Alimentée par les produits
 * mémorisés et par le catalogue et les plats repris de l'ancien suivi.
 */
object SearchProductsTool : AssistantTool {
    override val name = "search_products"

    override val description = """
        Cherche dans les produits et plats connus de l'utilisateur (nom, raccourci comme « barre choco », marque). Ne modifie rien.
        À utiliser AVANT d'estimer un produit nommé, une marque ou un raccourci qui n'est pas dans la liste du contexte, et pour retrouver un produit ponctuel (« le sandwich d'hier »).
        Le résultat donne les identifiants (product_id) à passer à record_consumptions, les valeurs de la fiche et les unités acceptées. Un plat repris sans ingrédients pesés donne sa composition d'origine (`template`, `template_products`). `ambiguous` vaut true quand plusieurs produits se valent : enregistre le meilleur et pose la question « est-ce celui-ci ? ».
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "query" to Schema.string("Le nom ou le raccourci cherché, sans les mots autour (« barre choco », « sandwich thon »)."),
        "limit" to Schema.integer("Nombre maximal de produits à renvoyer ; null pour la valeur par défaut (5).", nullable = true, minimum = 1),
    )

    @Serializable
    internal data class Args(val query: String, @SerialName("limit") val limit: Int? = null)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        if (args.query.isBlank()) return ToolResult.error("Recherche vide")
        val limit = (args.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val products = context.engine.allProducts()
        val hits = CatalogSearch.products(products, args.query, limit)
        val recipes = CatalogSearch.recipes(context.engine.allRecipes(), args.query, limit)
        return ToolResult(
            buildJsonObject {
                put("status", "ok")
                put("query", args.query)
                put("ambiguous", CatalogSearch.isAmbiguous(hits))
                putJsonArray("products") {
                    for (hit in hits) add(hit.product.toJson { mapOf("match" to JsonPrimitive(hit.match.code), "score" to JsonPrimitive(hit.score)) })
                }
                putJsonArray("recipes") {
                    // Un plat et ses versions trouvées : l'identifiant à déclarer est celui de la version.
                    val all = context.engine.allRecipes()
                    for (hit in recipes) add(
                        buildJsonObject {
                            val r = hit.recipe
                            val dish = Dishes.dishOf(all, r.recipeId)
                            put("recipe_id", r.recipeId)
                            put("name", Dishes.label(r))
                            put("dish_id", dish?.base?.recipeId ?: r.recipeId)
                            put("is_default", dish?.defaultVersion?.recipeId == r.recipeId)
                            put("paused", !r.active || dish?.isPaused == true)
                            put("match", hit.match.code)
                            put("score", hit.score)
                            val total = RecipeNutrition.total(r)
                            put("computable", total != null)
                            put("ingredients", ingredientsJson(r))
                            put("total_g", total?.totalG)
                            put("total_kcal", total?.nutrients?.kcal?.round1())
                            put("total_protein_g", total?.nutrients?.proteinG?.round1())
                            put("total_carbs_g", total?.nutrients?.carbsG?.round1())
                            put("total_fat_g", total?.nutrients?.fatG?.round1())
                            templateFields(r, products).forEach { (key, value) -> put(key, value) }
                        },
                    )
                }
                if (hits.isEmpty() && recipes.isEmpty()) {
                    put("hint", "Aucun produit ni plat connu pour cette recherche : estime, et mémorise la fiche avec new_product.")
                }
            },
        )
    }

    private const val DEFAULT_LIMIT = 5
    private const val MAX_LIMIT = 10
}
