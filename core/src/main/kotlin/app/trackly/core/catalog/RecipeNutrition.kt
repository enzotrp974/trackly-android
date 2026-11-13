package app.trackly.core.catalog

import app.trackly.core.model.Nutrients
import app.trackly.core.model.Recipe

/** Valeurs d'un plat pour l'ensemble de ses ingrédients, calculées par le code. */
data class RecipeTotal(val totalG: Double, val nutrients: Nutrients)

object RecipeNutrition {
    /**
     * Total du plat (tous les ingrédients), ou `null` si les kcal ne sont pas calculables : plat
     * sans poids complets (modèle seulement) ou ingrédient aux kcal inconnues. Une macro
     * inconnue pour un seul ingrédient reste inconnue pour le plat (jamais comptée comme zéro).
     */
    fun total(recipe: Recipe): RecipeTotal? {
        if (recipe.ingredients.isEmpty()) return null
        fun sum(of: (Nutrients) -> Double?): Double? {
            var total = 0.0
            for (i in recipe.ingredients) total += (of(i.per100g) ?: return null) * i.quantityG / 100.0
            return total
        }
        val kcal = sum { it.kcal } ?: return null
        return RecipeTotal(
            totalG = recipe.ingredients.sumOf { it.quantityG },
            nutrients = Nutrients(kcal, sum { it.proteinG }, sum { it.carbsG }, sum { it.fatG }, sum { it.fiberG }),
        )
    }
}
