package app.trackly.core.action

import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeNutrition
import app.trackly.core.model.Nutrients
import app.trackly.core.model.ProductServing
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecipeIngredient
import app.trackly.core.model.RecordState
import app.trackly.core.model.TextMatch
import java.time.Instant
import java.util.UUID

/*
 * Plats récurrents, variantes, variante par défaut et pause. Chaque action écrit des
 * versions complètes de fiches de plat (`HistoryKinds.Recipes`) : l'historique et l'annulation
 * fonctionnent sans code propre. Les écrans « Mes plats » et l'assistant appellent ces actions.
 */

/**
 * Ingrédient tel que donné par l'écran ou l'assistant. Soit un produit de la mémoire ([productId],
 * sans [per100g] : les valeurs sont calculées par le code depuis la fiche, pour 100 g), soit des
 * valeurs pour 100 g données ([per100g], éventuellement avec [productId] pour garder le lien).
 */
data class DishIngredientInput(
    val label: String? = null,
    val quantityG: Double,
    val per100g: Nutrients? = null,
    val productId: String? = null,
    /** Identifiant à conserver en modifiant un plat ; sinon un identifiant neuf. */
    val ingredientId: String? = null,
) {
    init {
        require(quantityG.isFinite() && quantityG > 0) { "Quantité d'ingrédient invalide : $quantityG" }
        require(per100g != null || productId != null) { "Un ingrédient a un produit ou des valeurs pour 100 g" }
    }

    companion object {
        fun of(ingredient: RecipeIngredient) = DishIngredientInput(
            ingredient.label, ingredient.quantityG, ingredient.per100g, ingredient.productId, ingredient.ingredientId,
        )
    }
}

/** Crée un plat récurrent. Les ingrédients sont ceux d'une portion. */
data class CreateDish(
    val name: String,
    val ingredients: List<DishIngredientInput>,
    val shortcuts: List<String> = emptyList(),
    /** Nom de cette version de base (« sauce A »), facultatif. */
    val variantName: String? = null,
    /** Identifiant imposé (tests) ; sinon un identifiant neuf. */
    val dishId: String? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val clean = DishWrites.cleanName(scope, name)
        DishWrites.requireFreeDishName(scope, all, clean, ignore = null)
        val aliases = DishWrites.cleanAliases(scope, all, shortcuts, clean, ignore = null)
        val id = dishId ?: DishWrites.newId()
        if (HistoryKinds.Recipes.versionsIn(scope).current(id) != null) scope.reject(Rejection.Invalid("Le plat $id existe déjà"))
        val recipe = Recipe(
            recipeId = id, version = 1, state = RecordState.Active, name = clean, aliases = aliases,
            ingredients = DishWrites.ingredients(scope, ingredients),
            variantName = variantName?.trim()?.ifEmpty { null },
            recordedAt = Instant.EPOCH, actionId = null,
        )
        DishWrites.requireComputable(scope, recipe)
        HistoryKinds.Recipes.writeNextVersion(scope, null, recipe)
        return "Plat créé : $clean"
    }

    companion object {
        const val TYPE = "dish.create"
    }
}

/**
 * Ajoute une variante à un plat (« salade avec la sauce B »). Sans [ingredients], elle part d'une
 * copie de ceux de la version de base. [makeDefault] : elle devient la variante par défaut.
 */
data class AddDishVariant(
    val dishId: String,
    val variantName: String,
    val ingredients: List<DishIngredientInput>? = null,
    val makeDefault: Boolean = false,
    val variantId: String? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val dish = DishWrites.dish(scope, all, dishId)
        if (dish.base.recipeId != dishId) scope.reject(Rejection.Invalid("« ${Dishes.label(dish.find(dishId)!!)} » est déjà une variante : ajoute la variante au plat « ${dish.name} »."))
        val clean = variantName.trim()
        if (clean.isEmpty()) scope.reject(Rejection.Invalid("Nom de variante vide"))
        if (clean.length > NewProduct.MAX_NAME) scope.reject(Rejection.Invalid("Nom de variante trop long"))
        DishWrites.requireFreeVariantName(scope, dish, clean, ignore = null)
        val id = variantId ?: DishWrites.newId()
        if (HistoryKinds.Recipes.versionsIn(scope).current(id) != null) scope.reject(Rejection.Invalid("Le plat $id existe déjà"))
        val variant = Recipe(
            recipeId = id, version = 1, state = RecordState.Active, name = dish.name,
            ingredients = ingredients?.let { DishWrites.ingredients(scope, it) } ?: dish.base.ingredients,
            sourceTemplateJson = null,
            variantOf = dish.base.recipeId, variantName = clean,
            recordedAt = Instant.EPOCH, actionId = null,
        )
        DishWrites.requireComputable(scope, variant)
        HistoryKinds.Recipes.writeNextVersion(scope, null, variant)
        if (makeDefault) HistoryKinds.Recipes.writeNextVersion(scope, dish.base, dish.base.copy(defaultVariantId = id))
        return "Variante ajoutée : ${dish.name} ($clean)"
    }

    companion object {
        const val TYPE = "dish.add_variant"
    }
}

/**
 * Modifie un plat ou une variante, par une nouvelle version complète : [name] (plat seulement ;
 * ses variantes suivent), [variantName] (le nom de cette version), [shortcuts] (plat seulement),
 * [ingredients] (liste complète, qui remplace l'ancienne). Un champ `null` est conservé.
 */
data class UpdateDish(
    val recipeId: String,
    val name: String? = null,
    val variantName: String? = null,
    val shortcuts: List<String>? = null,
    val ingredients: List<DishIngredientInput>? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val dish = DishWrites.dish(scope, all, recipeId)
        val current = dish.find(recipeId)!!
        var next = current

        if (name != null) {
            if (current.isVariant) scope.reject(Rejection.Invalid("Le nom du plat se change sur le plat « ${dish.name} », pas sur une variante."))
            val clean = DishWrites.cleanName(scope, name)
            if (clean != current.name) {
                DishWrites.requireFreeDishName(scope, all, clean, ignore = dish.base.recipeId)
                next = next.copy(name = clean)
            }
        }
        if (variantName != null) {
            val clean = variantName.trim()
            if (clean.isEmpty()) {
                if (current.isVariant) scope.reject(Rejection.Invalid("Une variante garde un nom."))
                next = next.copy(variantName = null)
            } else if (clean != current.variantName) {
                DishWrites.requireFreeVariantName(scope, dish, clean, ignore = current.recipeId)
                next = next.copy(variantName = clean)
            }
        }
        if (shortcuts != null) {
            if (current.isVariant) scope.reject(Rejection.Invalid("Les raccourcis sont ceux du plat « ${dish.name} »."))
            next = next.copy(aliases = DishWrites.cleanAliases(scope, all, shortcuts, next.name, ignore = dish.base.recipeId))
        }
        if (ingredients != null) next = next.copy(ingredients = DishWrites.ingredients(scope, ingredients))
        if (next == current) scope.reject(Rejection.NoChange)
        if (next.ingredients != current.ingredients) DishWrites.requireComputable(scope, next)

        HistoryKinds.Recipes.writeNextVersion(scope, current, next)
        // Les variantes portent le nom du plat : elles le suivent, dans la même action.
        if (next.name != current.name) {
            for (variant in dish.variants) HistoryKinds.Recipes.writeNextVersion(scope, variant, variant.copy(name = next.name))
        }
        return "Plat modifié : ${Dishes.label(next)}"
    }

    companion object {
        const val TYPE = "dish.update"
    }
}

/**
 * Met un plat (ou une variante) en pause, ou le réactive. Un plat en pause n'est plus proposé en
 * suggestion, mais reste reconnu si l'utilisateur le mange. La variante par défaut ne se met pas
 * en pause : on choisit d'abord une autre variante par défaut.
 */
data class SetDishPaused(val recipeId: String, val paused: Boolean) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val dish = DishWrites.dish(scope, all, recipeId)
        val current = dish.find(recipeId)!!
        if (current.active == !paused) scope.reject(Rejection.NoChange)
        if (paused && current.isVariant && dish.base.defaultVariantId == current.recipeId) {
            scope.reject(Rejection.Invalid("« ${Dishes.label(current)} » est la variante par défaut : choisis d'abord une autre variante par défaut, ou mets le plat entier en pause."))
        }
        HistoryKinds.Recipes.writeNextVersion(scope, current, current.copy(active = !paused))
        return (if (paused) "Plat en pause : " else "Plat réactivé : ") + Dishes.label(current)
    }

    companion object {
        const val TYPE = "dish.set_paused"
    }
}

/**
 * Choisit la variante utilisée quand l'utilisateur nomme le plat sans préciser. [variantId] `null` :
 * la version de base du plat.
 */
data class SetDefaultVariant(val dishId: String, val variantId: String?) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val dish = DishWrites.dish(scope, all, dishId)
        if (dish.base.recipeId != dishId) scope.reject(Rejection.Invalid("Le choix par défaut se fait sur le plat « ${dish.name} »."))
        val chosen = variantId?.let { id ->
            dish.variants.firstOrNull { it.recipeId == id }
                ?: scope.reject(Rejection.NotFound("variante $id du plat ${dish.name}"))
        }
        if (chosen != null && !chosen.active) scope.reject(Rejection.Invalid("« ${Dishes.label(chosen)} » est en pause : réactive-la d'abord."))
        if (dish.base.defaultVariantId == variantId) scope.reject(Rejection.NoChange)
        HistoryKinds.Recipes.writeNextVersion(scope, dish.base, dish.base.copy(defaultVariantId = variantId))
        return "Par défaut : " + (chosen?.let(Dishes::label) ?: dish.base.let(Dishes::label))
    }

    companion object {
        const val TYPE = "dish.set_default"
    }
}

/** Supprime un plat (avec ses variantes) ou une variante : nouvelle version `deleted`, annulable. */
data class DeleteDish(val recipeId: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val all = DishWrites.all(scope)
        val dish = DishWrites.dish(scope, all, recipeId)
        val current = dish.find(recipeId)!!
        if (current.isVariant) {
            HistoryKinds.Recipes.writeNextVersion(scope, current, current.withState(RecordState.Deleted))
            if (dish.base.defaultVariantId == current.recipeId) {
                HistoryKinds.Recipes.writeNextVersion(scope, dish.base, dish.base.copy(defaultVariantId = null))
            }
        } else {
            for (version in dish.versions) HistoryKinds.Recipes.writeNextVersion(scope, version, version.withState(RecordState.Deleted))
        }
        return "Plat supprimé : " + Dishes.label(current)
    }

    companion object {
        const val TYPE = "dish.delete"
    }
}

/** Plats (fiches de recettes) écrits par l'action. */
val ActionResult.recipeChanges: List<RecordChange<Recipe>>
    get() = changes.filterIsInstance<RecordChange<*>>().filter { it.kind == "recipe" }.map {
        @Suppress("UNCHECKED_CAST")
        it as RecordChange<Recipe>
    }

internal object DishWrites {
    fun newId() = "rcp-" + UUID.randomUUID()

    suspend fun all(scope: ActionScope): List<Recipe> = scope.tx.recipes.allCurrent()

    /** Plat qui contient la fiche [recipeId] (le plat ou une de ses variantes), sinon refus. */
    fun dish(scope: ActionScope, all: List<Recipe>, recipeId: String): Dish =
        Dishes.dishOf(all, recipeId) ?: scope.reject(Rejection.NotFound("plat $recipeId"))

    fun cleanName(scope: ActionScope, name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) scope.reject(Rejection.Invalid("Nom de plat vide"))
        if (clean.length > NewProduct.MAX_NAME) scope.reject(Rejection.Invalid("Nom de plat trop long"))
        return clean
    }

    /** Refuse un nom déjà pris par un autre plat (nom ou raccourci) : un mot désigne un seul plat. */
    fun requireFreeDishName(scope: ActionScope, all: List<Recipe>, name: String, ignore: String?) {
        val plain = TextMatch.normalize(name)
        Dishes.group(all).firstOrNull { d ->
            d.base.recipeId != ignore && (TextMatch.normalize(d.name) == plain || d.base.aliases.any { TextMatch.normalize(it) == plain })
        }?.let { scope.reject(Rejection.Invalid("Le nom « $name » désigne déjà le plat « ${it.name} » (${it.base.recipeId}).")) }
    }

    fun cleanAliases(scope: ActionScope, all: List<Recipe>, aliases: List<String>, name: String, ignore: String?): List<String> {
        val clean = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(TextMatch::normalize)
            .filter { TextMatch.normalize(it) != TextMatch.normalize(name) }
        for (alias in clean) requireFreeDishName(scope, all, alias, ignore)
        return clean
    }

    fun requireFreeVariantName(scope: ActionScope, dish: Dish, name: String, ignore: String?) {
        val plain = TextMatch.normalize(name)
        dish.versions.firstOrNull { it.recipeId != ignore && it.variantName?.let(TextMatch::normalize) == plain }
            ?.let { scope.reject(Rejection.Invalid("« ${dish.name} » a déjà une version « $name » (${it.recipeId}).")) }
    }

    /** Ingrédients prêts à écrire : valeurs d'un produit calculées depuis sa fiche (pour 100 g). */
    suspend fun ingredients(scope: ActionScope, inputs: List<DishIngredientInput>): List<RecipeIngredient> {
        if (inputs.isEmpty()) scope.reject(Rejection.Invalid("Un plat a au moins un ingrédient"))
        if (inputs.size > MAX_INGREDIENTS) scope.reject(Rejection.Invalid("Trop d'ingrédients (au plus $MAX_INGREDIENTS)"))
        return inputs.map { input ->
            val product = input.productId?.takeIf { input.per100g == null }?.let { ProductWrites.known(scope, it) }
            val per100g = input.per100g ?: run {
                val p = checkNotNull(product)
                val n = try {
                    ProductServing.nutrition(p, Quantity(100.0, "g"))
                } catch (e: IllegalArgumentException) {
                    scope.reject(Rejection.Invalid(e.message ?: "Produit sans valeurs pour 100 g"))
                }
                Nutrients(n.kcal, n.proteinG, n.carbsG, n.fatG, n.fiberG)
            }
            val label = input.label?.trim()?.takeIf { it.isNotEmpty() } ?: product?.name
                ?: scope.reject(Rejection.Invalid("Un ingrédient a un nom"))
            RecipeIngredient(input.ingredientId ?: "ing-" + UUID.randomUUID(), label, input.quantityG, per100g, input.productId)
        }
    }

    /** Un plat se mange en portions : il faut pouvoir le calculer (kcal de chaque ingrédient connues). */
    fun requireComputable(scope: ActionScope, recipe: Recipe) {
        if (recipe.ingredients.isEmpty()) {
            scope.reject(Rejection.Invalid("« ${Dishes.label(recipe)} » n'a pas d'ingrédients pesés : donne la liste complète des ingrédients."))
        }
        if (RecipeNutrition.total(recipe) == null) {
            val unknown = recipe.ingredients.firstOrNull { it.per100g.kcal == null }
            scope.reject(Rejection.Invalid("Les kcal de l'ingrédient « ${unknown?.label ?: "?"} » sont inconnues : donne-les pour 100 g, ou choisis un produit connu."))
        }
    }

    const val MAX_INGREDIENTS = 40
}
