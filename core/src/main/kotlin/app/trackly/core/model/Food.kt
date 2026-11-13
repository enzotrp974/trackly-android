package app.trackly.core.model

/**
 * Valeurs nutritionnelles d'une consommation, pour la quantité mangée.
 * Les kcal sont toujours connues ; une macro inconnue vaut `null` (un zéro est une valeur connue).
 */
data class Nutrition(
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fiberG: Double? = null,
) {
    init {
        requireAmount(kcal, "kcal")
        proteinG?.let { requireAmount(it, "protéines") }
        carbsG?.let { requireAmount(it, "glucides") }
        fatG?.let { requireAmount(it, "lipides") }
        fiberG?.let { requireAmount(it, "fibres") }
    }
}

/** Quantité mangée, dans l'unité déclarée. Aucune conversion g/ml/pièce n'est implicite. */
data class Quantity(val value: Double, val unit: String) {
    init {
        require(value.isFinite() && value > 0) { "Quantité invalide : $value" }
        require(unit.isNotBlank()) { "Unité manquante" }
    }
}

/** Fourchette d'une estimation (hypothèse d'estimation chiffrée), sans centre inventé. */
data class KcalRange(val low: Double, val high: Double) {
    init {
        requireAmount(low, "kcal basse")
        requireAmount(high, "kcal haute")
        require(low <= high) { "Fourchette inversée : $low > $high" }
    }
}

/** Origine des valeurs (étiquette, fabricant, estimation générique, catalogue…). */
data class FoodSource(val type: String, val description: String, val url: String? = null)

enum class Confidence(val code: String) {
    High("high"),
    Medium("medium"),
    Low("low"),
    ;

    companion object {
        fun fromCode(code: String): Confidence =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Confiance inconnue : $code")
    }
}

/**
 * Ce qui a été mangé : un aliment, un produit ou un plat, avec ses valeurs.
 * Les champs optionnels reprennent ceux du système historique (`meal-entry.schema.json`)
 * pour que la reprise n'ait rien à inventer.
 */
data class Food(
    val label: String,
    val nutrition: Nutrition,
    val quantity: Quantity? = null,
    /** Hypothèses d'estimation affichées à l'utilisateur (« poulet supposé cuit »). */
    val assumptions: List<String> = emptyList(),
    val kcalRange: KcalRange? = null,
    val productId: String? = null,
    val recipeId: String? = null,
    val source: FoodSource? = null,
    val confidence: Confidence? = null,
) {
    init {
        require(label.isNotBlank()) { "Nom d'aliment vide" }
        require(label.length <= MAX_LABEL) { "Nom d'aliment trop long" }
        require(assumptions.none { it.isBlank() }) { "Hypothèse vide" }
    }

    companion object {
        const val MAX_LABEL = 300
    }
}

private fun requireAmount(value: Double, name: String) {
    require(value.isFinite() && value >= 0) { "Valeur invalide pour $name : $value" }
}

/**
 * Ajustement d'un aliment déjà enregistré (« c'était 200 g », « avec du beurre »). Tout champ `null`
 * est conservé tel quel, ainsi que ce que le correctif ne peut pas toucher : produit lié, plat lié, source.
 */
data class FoodPatch(
    val label: String? = null,
    /** Nouvelle quantité ; l'unité reste celle de l'aliment si [unit] est `null` (« g » s'il n'avait pas de quantité). */
    val quantity: Double? = null,
    val unit: String? = null,
    /** Valeurs complètes recalculées pour la nouvelle quantité (une macro `null` y est inconnue). */
    val nutrition: Nutrition? = null,
    /** Remplace la liste des hypothèses (liste vide : aucune) ; `null` la conserve. */
    val assumptions: List<String>? = null,
    val confidence: Confidence? = null,
) {
    /** Lève [IllegalArgumentException] (message en français) si le correctif est incohérent. */
    fun applyTo(food: Food): Food {
        val newQuantity = quantityFor(food)
        val before = food.quantity
        require(nutrition != null || before == null || newQuantity == before) {
            "Quantité modifiée : donne aussi les kcal et macros recalculés pour la nouvelle quantité"
        }
        val newNutrition = nutrition ?: food.nutrition
        return food.copy(
            label = label ?: food.label,
            quantity = newQuantity,
            nutrition = newNutrition,
            assumptions = assumptions ?: food.assumptions,
            // Une fourchette qui ne correspond plus aux kcal n'a plus de sens.
            kcalRange = if (newNutrition.kcal == food.nutrition.kcal) food.kcalRange else null,
            confidence = confidence ?: food.confidence,
        )
    }

    /** Quantité de [food] après le correctif (inchangée si ni [quantity] ni [unit] ne sont donnés). */
    fun quantityFor(food: Food): Quantity? = when {
        quantity != null -> Quantity(quantity, unit ?: food.quantity?.unit ?: "g")
        unit != null -> Quantity(
            (food.quantity ?: throw IllegalArgumentException("Unité donnée sans quantité")).value,
            unit,
        )
        else -> food.quantity
    }
}
