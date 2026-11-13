package app.trackly.core.suggestion

import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Provision
import app.trackly.core.summary.DaySummary

/**
 * Ce qu'il reste à manger aujourd'hui, **calculé par le code** d'après l'objectif et ce qui est
 * déjà enregistré. `null` : pas d'objectif pour cette valeur. Une macro dont une consommation est
 * inconnue donne un reste « au plus » ([macrosComplete] faux). Journée estimée, bornée ou d'énergie
 * inconnue : les macros restantes sont `null` (le détail n'est qu'un minimum).
 */
data class RemainingMacros(
    val kcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val macrosComplete: Boolean,
)

/** Un plat que l'on pourrait proposer, avec ses valeurs pour une portion. Jamais une consommation. */
data class MealCandidate(
    val recipeId: String,
    val dishId: String,
    val label: String,
    val isDefault: Boolean,
    val perPortion: Nutrition,
    /** Ingrédients que les provisions ne couvrent pas (noms). Vide : tout est là. */
    val missingIngredients: List<String>,
    /** La portion tient dans les calories restantes ; `null` sans objectif. */
    val fitsKcal: Boolean?,
)

/**
 * Suggestions de repas, fondées sur les macros restantes, les provisions et les plats
 * **actifs**. Un plat en pause n'est jamais proposé. Une suggestion ne compte jamais comme
 * consommation : ce module est en lecture seule et ne connaît aucune action d'écriture.
 */
object MealSuggestions {
    fun remaining(summary: DaySummary): RemainingMacros {
        val goal = summary.goal
        val totals = summary.totals
        // Estimation journalière, borne minimale ou énergie inconnue : le détail est partiel, les macros du jour sont inconnues.
        val macrosKnown = summary.energy.macrosKnown
        fun left(target: Double?, known: Double) = target?.takeIf { macrosKnown }?.let { (it - known).coerceAtLeast(0.0) }
        return RemainingMacros(
            kcal = summary.remainingKcal?.coerceAtLeast(0.0),
            proteinG = left(goal?.proteinG, totals.protein.known),
            carbsG = left(goal?.carbsG, totals.carbs.known),
            fatG = left(goal?.fatG, totals.fat.known),
            macrosComplete = macrosKnown && totals.protein.complete && totals.carbs.complete && totals.fat.complete,
        )
    }

    /**
     * Plats actifs et calculables, une proposition par plat (sa variante par défaut), du plus
     * pratique au moins pratique : ce qui tient dans les calories restantes d'abord, puis ce dont
     * on a tous les ingrédients, puis le plus de protéines.
     */
    fun candidates(dishes: Collection<Dish>, provisions: Collection<Provision>, remaining: RemainingMacros, limit: Int = 12): List<MealCandidate> {
        val held = Provisions.held(provisions)
        return dishes.asSequence()
            .filter { !it.isPaused }
            .mapNotNull { dish ->
                val recipe = dish.defaultVersion.takeIf { it.active } ?: dish.suggestible.firstOrNull() ?: return@mapNotNull null
                val perPortion = RecipeServing.perPortion(recipe) ?: return@mapNotNull null
                MealCandidate(
                    recipeId = recipe.recipeId,
                    dishId = dish.base.recipeId,
                    label = Dishes.label(recipe),
                    isDefault = recipe.recipeId == dish.defaultVersion.recipeId,
                    perPortion = perPortion,
                    missingIngredients = Provisions.missing(recipe, held),
                    fitsKcal = remaining.kcal?.let { perPortion.kcal <= it },
                )
            }
            .sortedWith(
                compareByDescending<MealCandidate> { it.fitsKcal == true }
                    .thenBy { it.missingIngredients.size }
                    .thenByDescending { it.perPortion.proteinG ?: 0.0 }
                    .thenBy { it.label },
            )
            .take(limit)
            .toList()
    }
}
