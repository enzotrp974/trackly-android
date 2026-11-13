package app.trackly.core.catalog

import app.trackly.core.model.FoodSource
import app.trackly.core.model.Food
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.TextMatch
import app.trackly.core.model.Units
import kotlin.math.roundToLong

/**
 * Un plat récurrent et ses variantes. [base] est le plat lui-même (sa version de base) ;
 * [variants] sont ses variantes non supprimées. Rien ici n'écrit : c'est une lecture du catalogue.
 */
data class Dish(val base: Recipe, val variants: List<Recipe>) {
    val name: String get() = base.name

    /** Plat en pause (rupture) : jamais proposé en suggestion. Ses variantes le sont avec lui. */
    val isPaused: Boolean get() = !base.active

    /** Le plat et ses variantes, version de base d'abord. */
    val versions: List<Recipe> get() = listOf(base) + variants

    /** Variante par défaut si elle existe et n'est pas en pause ; sinon la version de base. */
    val defaultVersion: Recipe
        get() = variants.firstOrNull { it.recipeId == base.defaultVariantId && it.active } ?: base

    /** Versions utilisables en suggestion : aucune si le plat est en pause. */
    val suggestible: List<Recipe> get() = if (isPaused) emptyList() else versions.filter { it.active }

    fun find(recipeId: String): Recipe? = versions.firstOrNull { it.recipeId == recipeId }

    /** Nom d'une version telle qu'on la montre : « Salade (sauce A) », ou « Salade » pour la base sans nom. */
    fun labelOf(recipe: Recipe): String = Dishes.label(recipe)

    /** Plusieurs versions : le choix par défaut a un sens. */
    val hasVariants: Boolean get() = variants.isNotEmpty()
}

object Dishes {
    /** Libellé d'une version de plat : « Salade (sauce B) ». */
    fun label(recipe: Recipe): String = recipe.variantName?.let { "${recipe.name} ($it)" } ?: recipe.name

    /**
     * Plats et variantes à partir des versions courantes des fiches. Les fiches supprimées sont
     * écartées, ainsi que les variantes dont le plat n'existe plus. Triés par nom, variantes par nom.
     */
    fun group(all: Collection<Recipe>): List<Dish> {
        val live = all.filter { it.isActive }
        val variantsByParent = live.filter { it.isVariant }.groupBy { it.variantOf }
        return live.filter { !it.isVariant }
            .map { base -> Dish(base, variantsByParent[base.recipeId].orEmpty().sortedBy { TextMatch.normalize(it.variantName.orEmpty()) }) }
            .sortedBy { TextMatch.normalize(it.name) }
    }

    /** Plat qui contient la fiche [recipeId] (le plat lui-même ou une de ses variantes). */
    fun dishOf(all: Collection<Recipe>, recipeId: String): Dish? = group(all).firstOrNull { it.find(recipeId) != null }

    /**
     * Version à enregistrer quand l'utilisateur désigne [recipeId]. Une variante précise est prise
     * telle quelle. Le plat lui-même, nommé sans préciser (« salade »), donne sa variante par défaut
     * quand [useDefault] ; avec `useDefault = false`, c'est sa version de base qui est visée.
     * [usedDefault] : le choix vient du défaut et non de l'utilisateur (le plat a des variantes).
     */
    data class Choice(val dish: Dish, val recipe: Recipe, val usedDefault: Boolean)

    fun choose(all: Collection<Recipe>, recipeId: String, useDefault: Boolean = true): Choice? {
        val dish = dishOf(all, recipeId) ?: return null
        if (recipeId != dish.base.recipeId || !useDefault) return Choice(dish, dish.find(recipeId)!!, usedDefault = false)
        val chosen = dish.defaultVersion
        return Choice(dish, chosen, usedDefault = dish.hasVariants)
    }
}

/**
 * Valeurs d'un plat mangé, **calculées par le code** depuis ses ingrédients et la quantité : le
 * modèle ne les fournit jamais. Les ingrédients d'un plat sont ceux d'une portion.
 */
object RecipeServing {
    private fun round(value: Double) = (value * 10).roundToLong() / 10.0

    /** Unités acceptées pour un plat, en texte. */
    const val UNITS = "portion, g"

    /** Valeurs d'une portion, ou `null` si le plat n'est pas calculable (voir [RecipeNutrition.total]). */
    fun perPortion(recipe: Recipe): Nutrition? = RecipeNutrition.total(recipe)?.let { scaled(it.nutrients, 1.0) }

    /**
     * Valeurs pour [amount] : un nombre de portions, ou des grammes de plat préparé (proportion du
     * poids total des ingrédients). Lève [IllegalArgumentException] (message en français, destiné
     * au modèle) si le plat n'est pas calculable ou si l'unité est inconnue.
     */
    fun nutrition(recipe: Recipe, amount: Quantity): Nutrition {
        val total = RecipeNutrition.total(recipe)
            ?: throw IllegalArgumentException(
                "« ${Dishes.label(recipe)} » n'a pas d'ingrédients pesés avec leurs kcal : ajoute-les dans Mes plats, ou déclare ce plat par une estimation (food).",
            )
        val factor = when (Units.canonical(amount.unit)) {
            Units.PORTION -> amount.value
            Units.GRAM -> amount.value / total.totalG
            else -> throw IllegalArgumentException("« ${Dishes.label(recipe)} » se compte en $UNITS, pas en ${amount.unit}.")
        }
        return scaled(total.nutrients, factor)
    }

    /** Aliment d'une consommation de ce plat : valeurs calculées, plat relié par `recipeId`. */
    fun food(recipe: Recipe, amount: Quantity, assumptions: List<String> = emptyList()): Food = Food(
        label = Dishes.label(recipe),
        nutrition = nutrition(recipe, amount),
        quantity = amount,
        assumptions = assumptions,
        recipeId = recipe.recipeId,
        source = FoodSource("recipe", "Plat récurrent, calculé depuis ses ingrédients"),
    )

    private fun scaled(n: app.trackly.core.model.Nutrients, factor: Double) = Nutrition(
        kcal = round(checkNotNull(n.kcal) * factor),
        proteinG = n.proteinG?.let { round(it * factor) },
        carbsG = n.carbsG?.let { round(it * factor) },
        fatG = n.fatG?.let { round(it * factor) },
        fiberG = n.fiberG?.let { round(it * factor) },
    )
}
