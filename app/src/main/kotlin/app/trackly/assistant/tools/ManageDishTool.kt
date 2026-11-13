package app.trackly.assistant.tools

import app.trackly.assistant.cards.DishCard
import app.trackly.assistant.cards.DishOperation
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DeleteDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.SetDefaultVariant
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.UpdateDish
import app.trackly.core.catalog.Dishes
import app.trackly.core.model.Recipe
import app.trackly.core.model.TextMatch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gère les plats récurrents comme l'écran « Mes plats » : créer, ajouter une variante, modifier,
 * choisir la variante par défaut, mettre en pause ou réactiver, supprimer. Actions du moteur :
 * [CreateDish], [AddDishVariant], [UpdateDish], [SetDefaultVariant], [SetDishPaused], [DeleteDish].
 * Rien de tout cela ne compte comme une consommation.
 */
object ManageDishTool : AssistantTool {
    override val name = "manage_dish"

    private const val CREATE = "create"
    private const val ADD_VARIANT = "add_variant"
    private const val UPDATE = "update"
    private const val SET_DEFAULT = "set_default_variant"
    private const val PAUSE = "pause"
    private const val RESUME = "resume"
    private const val DELETE = "delete"

    override val description = """
        Gère les plats récurrents de l'utilisateur, uniquement sur sa DEMANDE (« enregistre mon plat… », « ajoute une variante sauce B à la salade », « mets la salade sauce B par défaut cette semaine », « mets le plat au thon en pause », « réactive-le »). Ne compte rien comme mangé.
        Opérations : create (nouveau plat avec ses ingrédients pour UNE portion), add_variant (variante d'un plat : copie les ingrédients de la version `recipe_id`, le plat ou une de ses variantes), update (nom, nom de version pour renommer une variante, raccourcis, ingrédients), set_default_variant (`recipe_id` = le plat, `variant_id` = la variante, ou null pour la version de base), pause / resume (un plat en pause n'est plus proposé en suggestion), delete (`recipe_id` d'une variante : seulement cette variante).
        « Telle sauce à la place » : add_variant ou update avec `remove_ingredients` (noms des ingrédients copiés à retirer) et `add_ingredients` ; l'app garde les autres. `ingredients` remplace au contraire toute la liste.
        Plat repris sans ingrédients pesés (`template` dans search_products) : add_variant avec `ingredients`, la liste COMPLÈTE construite depuis `template`, le changement appliqué.
        Les valeurs d'un plat sont calculées par l'app : donne les ingrédients avec un `product_id` connu, sinon leurs valeurs pour 100 g.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "operation" to Schema.string(
            "Opération à faire.",
            enum = listOf(CREATE, ADD_VARIANT, UPDATE, SET_DEFAULT, PAUSE, RESUME, DELETE),
        ),
        "recipe_id" to Schema.string(
            "Identifiant du plat (dish_id) ou de la version visée (recipe_id) ; add_variant : la version dont on copie les ingrédients ; null pour create.",
            nullable = true,
        ),
        "name" to Schema.string("Nom du plat (create, update d'un plat) ; null sinon.", nullable = true),
        "variant_name" to Schema.string("Nom de la version (« sauce B ») : add_variant, update d'une version, ou create avec une version de base nommée ; null sinon.", nullable = true),
        "variant_id" to Schema.string("set_default_variant : la variante à mettre par défaut ; null pour la version de base.", nullable = true),
        "shortcuts" to Schema.array(Schema.string("Un raccourci pour le plat."), "Liste COMPLÈTE des raccourcis du plat (update), ou null pour ne pas y toucher.", nullable = true),
        "ingredients" to Schema.array(ingredientSchema(), "Liste COMPLÈTE des ingrédients (create, update), ou de la variante (add_variant) ; null pour ne pas y toucher.", nullable = true),
        "remove_ingredients" to Schema.array(Schema.string("Nom d'un ingrédient de la version, tel qu'il est écrit."), "add_variant, update : ingrédients à retirer de la copie ; null sinon.", nullable = true),
        "add_ingredients" to Schema.array(ingredientSchema(), "add_variant, update : ingrédients à ajouter à la copie ; null sinon.", nullable = true),
        "make_default" to Schema.boolean("add_variant : la nouvelle variante devient la variante par défaut. false ou null sinon.", nullable = true),
    )

    @Serializable
    internal data class Args(
        val operation: String,
        @SerialName("recipe_id") val recipeId: String? = null,
        val name: String? = null,
        @SerialName("variant_name") val variantName: String? = null,
        @SerialName("variant_id") val variantId: String? = null,
        val shortcuts: List<String>? = null,
        val ingredients: List<IngredientArgs>? = null,
        @SerialName("remove_ingredients") val removeIngredients: List<String>? = null,
        @SerialName("add_ingredients") val addIngredients: List<IngredientArgs>? = null,
        @SerialName("make_default") val makeDefault: Boolean? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        fun need(value: String?, what: String): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("`$what` est obligatoire pour ${args.operation}.")
        val (action: Action, operation) = try {
            val inputs = args.ingredients?.map { it.toInput() }
            val swaps = args.removeIngredients != null || args.addIngredients != null
            require(inputs == null || !swaps) { "Donne soit `ingredients` (liste complète), soit `remove_ingredients` / `add_ingredients`." }
            // La version visée, et le plat auquel elle appartient (une variante se rattache toujours au plat).
            val source = if (args.operation == ADD_VARIANT || (args.operation == UPDATE && swaps)) {
                val id = need(args.recipeId, "recipe_id")
                Dishes.dishOf(context.engine.allRecipes(), id)?.let { it to it.find(id)!! } ?: return ToolResult.error("Plat introuvable : $id")
            } else null
            // Plat repris sans ingrédients pesés : la variante se construit en entier depuis son modèle.
            if (source != null && inputs == null && source.second.ingredients.isEmpty()) {
                return unstructured(source.second, context)
            }
            val swapped = if (swaps) swap(source!!.second, args.removeIngredients.orEmpty(), args.addIngredients.orEmpty().map { it.toInput() }) else null
            when (args.operation) {
                CREATE -> CreateDish(need(args.name, "name"), inputs ?: throw IllegalArgumentException("`ingredients` est obligatoire pour create."), args.shortcuts.orEmpty(), args.variantName) to DishOperation.Created
                ADD_VARIANT -> {
                    val (dish, from) = source!!
                    // Variante d'une variante : rattachée au plat, avec les ingrédients de la version nommée.
                    val copied = if (from.recipeId != dish.base.recipeId) from.ingredients.map(DishIngredientInput::of) else null
                    AddDishVariant(dish.base.recipeId, need(args.variantName, "variant_name"), inputs ?: swapped ?: copied, args.makeDefault == true) to DishOperation.VariantAdded
                }
                UPDATE -> UpdateDish(need(args.recipeId, "recipe_id"), args.name, args.variantName, args.shortcuts, inputs ?: swapped) to DishOperation.Updated
                SET_DEFAULT -> SetDefaultVariant(need(args.recipeId, "recipe_id"), args.variantId) to DishOperation.DefaultChanged
                PAUSE -> SetDishPaused(need(args.recipeId, "recipe_id"), paused = true) to DishOperation.Paused
                RESUME -> SetDishPaused(need(args.recipeId, "recipe_id"), paused = false) to DishOperation.Resumed
                DELETE -> DeleteDish(need(args.recipeId, "recipe_id")) to DishOperation.Deleted
                else -> return ToolResult.error("Opération inconnue : ${args.operation}")
            }
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val recipes = context.engine.allRecipes()
                // Le plat concerné : la dernière fiche écrite, retrouvée dans le catalogue à jour.
                val touched = result.changes.last().key
                val dish = Dishes.dishOf(recipes, touched) ?: Dishes.group(recipes).firstOrNull { d -> d.versions.any { it.recipeId == touched } }
                val card = DishCard(
                    actionId = result.id.value,
                    operation = operation,
                    dishId = dish?.base?.recipeId ?: touched,
                    name = dish?.name ?: result.title,
                    detail = dish?.let { d ->
                        when (operation) {
                            DishOperation.VariantAdded, DishOperation.DefaultChanged, DishOperation.Updated ->
                                "par défaut : " + Dishes.label(d.defaultVersion)
                            else -> null
                        }
                    },
                )
                ToolResult(
                    output = buildJsonObject {
                        put("status", "updated")
                        put("action_id", result.id.value)
                        put("title", result.title)
                        dish?.let { put("dish", it.toJson()) }
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }

    /**
     * Refus guidé, pour le modèle seulement : [recipe] n'a pas d'ingrédients pesés, donc rien à copier ni à
     * remplacer. Son modèle d'origine est rendu avec, pour construire la liste complète `ingredients`.
     */
    private suspend fun unstructured(recipe: Recipe, context: ToolContext): ToolResult {
        val fields = templateFields(recipe, context.engine.allProducts())
        val unknown = (fields["composition_unknown"] as? JsonPrimitive)?.content == "true"
        val label = Dishes.label(recipe)
        val message = when {
            unknown -> "« $label » est une estimation par portion : sa composition est inconnue. Demande à l'utilisateur ses ingrédients et leurs quantités, puis donne `ingredients`."
            fields.isEmpty() -> "« $label » n'a pas d'ingrédients pesés. Donne `ingredients`, la liste COMPLÈTE de la variante (grammes et valeurs pour 100 g estimés)."
            else -> "« $label » n'a pas d'ingrédients pesés : sa composition est dans `template`. Donne `ingredients`, la liste COMPLÈTE de la variante : " +
                "`product_id` pour les produits de `template_products`, grammes par défaut du modèle, et pour un composant sans produit (base de légumes…) " +
                "tes grammes et valeurs pour 100 g estimés ; applique le changement demandé. Ne dis rien de technique à l'utilisateur."
        }
        return ToolResult(
            buildJsonObject {
                put("status", "error")
                put("error", message)
                fields.forEach { (key, value) -> put(key, value) }
            },
        )
    }

    /**
     * Ingrédients de [from] sans ceux de [remove] (nom exact, sinon seul nom qui le contient, sans casse ni accents),
     * puis [add]. Lève [IllegalArgumentException] si un nom à retirer ne désigne pas un seul ingrédient.
     */
    private fun swap(from: Recipe, remove: List<String>, add: List<DishIngredientInput>): List<DishIngredientInput> {
        val kept = from.ingredients.toMutableList()
        for (name in remove) {
            val wanted = TextMatch.normalize(name)
            require(wanted.isNotEmpty()) { "Nom d'ingrédient à retirer vide." }
            val exact = kept.filter { TextMatch.normalize(it.label) == wanted }
            val match = exact.ifEmpty { kept.filter { TextMatch.normalize(it.label).contains(wanted) || wanted.contains(TextMatch.normalize(it.label)) } }
            require(match.size == 1) {
                "Ingrédient « $name » " + (if (match.isEmpty()) "absent" else "ambigu") + " dans « ${Dishes.label(from)} ». Ingrédients : " +
                    from.ingredients.joinToString(", ") { it.label } + "."
            }
            kept.remove(match.single())
        }
        return kept.map(DishIngredientInput::of) + add
    }
}
