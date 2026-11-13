package app.trackly.dishes

import app.trackly.core.action.Action
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.UpdateDish
import app.trackly.core.catalog.Dish
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Product
import app.trackly.core.model.ProductServing
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecipeIngredient

/** Ce que le formulaire de « Mes plats » est en train de faire. */
sealed interface DraftTarget {
    data object NewDish : DraftTarget

    /** Variante à ajouter au plat [dishId]. */
    data class NewVariant(val dishId: String) : DraftTarget

    /** Modification du plat ou de la variante [recipeId]. */
    data class Edit(val recipeId: String, val isVariant: Boolean) : DraftTarget
}

/** Un ingrédient en texte tel que saisi (valeurs pour 100 g). */
data class IngredientDraft(
    /** Numéro stable dans le formulaire (étiquettes de test, clé d'affichage). */
    val key: Int,
    val label: String = "",
    val grams: String = "",
    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    /** Produit de la mémoire d'où viennent les valeurs, le cas échéant. */
    val productId: String? = null,
    /** Identifiant d'ingrédient conservé en modifiant. */
    val ingredientId: String? = null,
)

/** Résultat de la lecture du formulaire. */
sealed interface DishDraftResult {
    data class Ready(val action: Action) : DishDraftResult
    data class Invalid(val message: String) : DishDraftResult
}

/**
 * Formulaire d'un plat, en texte tel que saisi. [read] le transforme en action du moteur
 * ([CreateDish], [AddDishVariant] ou [UpdateDish], les mêmes que pour l'assistant) ou en message à corriger.
 */
data class DishDraft(
    val target: DraftTarget,
    val name: String = "",
    /** Nom de cette version (« sauce A »). Obligatoire pour une variante. */
    val versionName: String = "",
    /** Raccourcis séparés par des virgules (plat seulement). */
    val shortcuts: String = "",
    val ingredients: List<IngredientDraft> = emptyList(),
) {
    val isVariant: Boolean
        get() = target is DraftTarget.NewVariant || (target as? DraftTarget.Edit)?.isVariant == true

    val nextKey: Int get() = (ingredients.maxOfOrNull { it.key } ?: -1) + 1

    fun withIngredient(index: Int, change: (IngredientDraft) -> IngredientDraft) =
        copy(ingredients = ingredients.map { if (it.key == index) change(it) else it })

    fun withoutIngredient(key: Int) = copy(ingredients = ingredients.filterNot { it.key == key })

    fun withNewIngredient(ingredient: IngredientDraft? = null) =
        copy(ingredients = ingredients + (ingredient ?: IngredientDraft(nextKey)).copy(key = nextKey))

    fun read(): DishDraftResult {
        val inputs = ingredients.filterNot { it.isBlank() }.map { i ->
            readIngredient(i) ?: return DishDraftResult.Invalid(
                if (i.label.isBlank()) "Donne un nom à chaque ingrédient."
                else "Ingrédient « ${i.label.trim()} » : indique les grammes et au moins les kcal pour 100 g (des nombres).",
            )
        }
        if (inputs.isEmpty()) return DishDraftResult.Invalid("Ajoute au moins un ingrédient.")
        val shortcutList = shortcuts.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return when (target) {
            DraftTarget.NewDish -> {
                if (name.isBlank()) return DishDraftResult.Invalid("Donne un nom au plat.")
                DishDraftResult.Ready(CreateDish(name.trim(), inputs, shortcutList, versionName.trim().ifEmpty { null }))
            }
            is DraftTarget.NewVariant -> {
                if (versionName.isBlank()) return DishDraftResult.Invalid("Donne un nom à la variante (par exemple « sauce B »).")
                DishDraftResult.Ready(AddDishVariant(target.dishId, versionName.trim(), inputs))
            }
            is DraftTarget.Edit -> if (target.isVariant) {
                if (versionName.isBlank()) return DishDraftResult.Invalid("Une variante garde un nom.")
                DishDraftResult.Ready(UpdateDish(target.recipeId, variantName = versionName.trim(), ingredients = inputs))
            } else {
                if (name.isBlank()) return DishDraftResult.Invalid("Donne un nom au plat.")
                DishDraftResult.Ready(UpdateDish(target.recipeId, name = name.trim(), variantName = versionName.trim(), shortcuts = shortcutList, ingredients = inputs))
            }
        }
    }

    /** Valeurs d'une portion d'après ce qui est saisi, ou `null` tant que le formulaire est incomplet. */
    fun preview(): Nutrition? {
        val inputs = ingredients.filterNot { it.isBlank() }.mapNotNull(::readIngredient)
        if (inputs.isEmpty() || inputs.size != ingredients.count { !it.isBlank() }) return null
        fun sum(of: (Nutrients) -> Double?): Double? {
            var total = 0.0
            for (i in inputs) total += (of(i.per100g!!) ?: return null) * i.quantityG / 100.0
            return total
        }
        val kcal = sum { it.kcal } ?: return null
        return Nutrition(kcal, sum { it.proteinG }, sum { it.carbsG }, sum { it.fatG })
    }

    private fun readIngredient(i: IngredientDraft): DishIngredientInput? {
        val grams = parseAmount(i.grams)?.takeIf { it > 0 } ?: return null
        val kcal = parseAmount(i.kcal) ?: return null
        if (i.label.isBlank()) return null
        // Une macro mal écrite invalide la ligne plutôt que de devenir « inconnue » sans prévenir.
        val protein = if (i.protein.isBlank()) null else parseAmount(i.protein) ?: return null
        val carbs = if (i.carbs.isBlank()) null else parseAmount(i.carbs) ?: return null
        val fat = if (i.fat.isBlank()) null else parseAmount(i.fat) ?: return null
        return DishIngredientInput(i.label.trim(), grams, Nutrients(kcal, protein, carbs, fat), i.productId, i.ingredientId)
    }

    companion object {
        fun newDish() = DishDraft(DraftTarget.NewDish, ingredients = listOf(IngredientDraft(0)))

        /** Variante : part d'une copie des ingrédients du plat. */
        fun newVariant(dish: Dish) = DishDraft(
            DraftTarget.NewVariant(dish.base.recipeId),
            name = dish.name,
            ingredients = dish.defaultVersion.ingredients.mapIndexed { index, i -> from(index, i).copy(ingredientId = null) },
        )

        fun edit(dish: Dish, recipe: Recipe) = DishDraft(
            DraftTarget.Edit(recipe.recipeId, recipe.isVariant),
            name = dish.name,
            versionName = recipe.variantName.orEmpty(),
            shortcuts = recipe.aliases.joinToString(", "),
            ingredients = recipe.ingredients.mapIndexed { index, i -> from(index, i) },
        )

        private fun from(key: Int, i: RecipeIngredient) = IngredientDraft(
            key, i.label, number(i.quantityG), i.per100g.kcal?.let(::number).orEmpty(), i.per100g.proteinG?.let(::number).orEmpty(),
            i.per100g.carbsG?.let(::number).orEmpty(), i.per100g.fatG?.let(::number).orEmpty(), i.productId, i.ingredientId,
        )

        /** Ingrédient prérempli depuis un produit connu (valeurs pour 100 g calculées par le code), ou `null` s'il n'a pas de valeurs au gramme. */
        fun fromProduct(product: Product): IngredientDraft? {
            val n = runCatching { ProductServing.nutrition(product, Quantity(100.0, "g")) }.getOrNull() ?: return null
            return IngredientDraft(
                key = 0, label = product.name, grams = "100", kcal = number(n.kcal), protein = n.proteinG?.let(::number).orEmpty(),
                carbs = n.carbsG?.let(::number).orEmpty(), fat = n.fatG?.let(::number).orEmpty(), productId = product.productId,
            )
        }

        private fun number(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else value.toString().replace('.', ',')

        /** « 12,5 » ou « 12.5 » → 12.5 ; vide ou illisible → `null`. */
        fun parseAmount(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
}

private fun IngredientDraft.isBlank() = label.isBlank() && grams.isBlank() && kcal.isBlank() && protein.isBlank() && carbs.isBlank() && fat.isBlank()
private fun parseAmount(text: String) = DishDraft.parseAmount(text)
