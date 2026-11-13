package app.trackly.core.model

import java.time.Instant
import java.time.LocalDate

/** Nature d'une estimation journalière validée (contrat v2 du système historique). */
enum class EstimateKind(val code: String) {
    /** Total estimé, avec un centre et une fourchette. */
    Estimate("estimate"),

    /** « Au moins X kcal » : ni centre ni borne haute, jamais une moyenne ponctuelle. */
    LowerBound("lower_bound"),
    ;

    companion object {
        fun fromCode(code: String): EstimateKind =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Type d'estimation inconnu : $code")
    }
}

/**
 * Estimation globale d'une journée, explicitement validée par l'utilisateur (« journée non
 * suivie, environ 2 500 kcal »). Elle remplace l'énergie détaillée sans s'y additionner ;
 * les macros de la journée deviennent inconnues. Ce n'est jamais un total calculé en cache.
 */
data class DailyEstimate(
    val assessmentId: String,
    override val version: Int,
    override val state: RecordState,
    val day: LocalDate,
    val kind: EstimateKind,
    /** Centre ; `null` pour une borne minimale. */
    val kcal: Double?,
    val kcalLow: Double,
    /** Borne haute ; `null` pour une borne minimale. */
    val kcalHigh: Double?,
    val description: String,
    /** Anciennes entrées agrégées remplacées : elles ne redeviennent jamais des repas à compter. */
    val replacesEntryIds: List<EntryId> = emptyList(),
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<DailyEstimate> {
    init {
        require(assessmentId.isNotBlank()) { "Identifiant d'estimation vide" }
        require(version >= 1) { "Version invalide : $version" }
        require(kcalLow.isFinite() && kcalLow >= 0) { "Borne basse invalide : $kcalLow" }
        when (kind) {
            EstimateKind.LowerBound -> require(kcal == null && kcalHigh == null) {
                "Une borne minimale ne définit ni total exact ni borne haute"
            }
            EstimateKind.Estimate -> {
                require(kcal != null && kcalHigh != null) { "Une estimation exige un centre et une borne haute" }
                require(kcalLow <= kcal && kcal <= kcalHigh) { "Estimation journalière hors fourchette" }
            }
        }
    }

    override val key get() = assessmentId
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}

/**
 * Suivi déclaré d'une journée (système historique) : clôture explicite, journée déclarée
 * incomplète, ou détail déclaré complet après suppression d'une estimation.
 * `null` = non déclaré. Clé : le jour.
 */
data class DayTracking(
    val day: LocalDate,
    override val version: Int,
    override val state: RecordState,
    val closedExplicitly: Boolean = false,
    val trackingComplete: Boolean? = null,
    val detailedTrackingComplete: Boolean? = null,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<DayTracking> {
    init {
        require(version >= 1) { "Version invalide : $version" }
    }

    override val key get() = day.toString()
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}

/** Valeurs pour la base de référence d'une fiche ; chaque valeur peut être inconnue (`null`). */
data class Nutrients(
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fiberG: Double? = null,
) {
    init {
        for (value in listOfNotNull(kcal, proteinG, carbsG, fatG, fiberG)) {
            require(value.isFinite() && value >= 0) { "Valeur nutritionnelle invalide : $value" }
        }
    }
}

/** Base explicite des valeurs d'un produit (100 g, 100 ml, une pièce…), état cru/cuit si connu. */
data class NutritionBasis(val value: Double, val unit: String, val state: String? = null) {
    init {
        require(value.isFinite() && value > 0) { "Base nutritionnelle invalide : $value" }
        require(unit.isNotBlank()) { "Unité de base manquante" }
    }
}

/**
 * Fiche produit (catalogue). Les valeurs restent celles de la fiche : aucune conversion
 * g/ml/pièce n'est implicite et une fiche partielle reste partielle.
 *
 * Les **raccourcis** (« barre choco ») sont les [aliases] : un seul mot pour un seul concept.
 * Un produit repris du catalogue historique est récurrent (l'utilisateur l'a déjà nommé) ;
 * un produit mémorisé par l'assistant est d'abord ponctuel.
 */
data class Product(
    val productId: String,
    override val version: Int,
    override val state: RecordState,
    val name: String,
    val brand: String? = null,
    val variant: String? = null,
    val country: String? = null,
    val barcode: String? = null,
    val aliases: List<String> = emptyList(),
    val soldState: String? = null,
    /** Valeurs pour la base [basis] (historiquement « pour 100 »). */
    val nutrients: Nutrients,
    val basis: NutritionBasis? = null,
    /** `complete`, `partial` ou `unknown` si la fiche le précise. */
    val nutritionStatus: String? = null,
    val source: FoodSource? = null,
    val confidence: Confidence? = null,
    val verified: Boolean = false,
    val favorite: Boolean = false,
    /** Faux : produit en pause. */
    val active: Boolean = true,
    /** Produit vers lequel celui-ci est redirigé (doublon fusionné). */
    val redirectTo: String? = null,
    /** Choisi quand l'utilisateur nomme le produit sans préciser. */
    val defaultMatch: Boolean = false,
    /** Ponctuel (mémorisé tel que consommé) ou récurrent (nommé par l'utilisateur). Voir [ProductKind]. */
    val kind: ProductKind = ProductKind.Recurring,
    /** Valeurs pour une portion usuelle, en plus (ou à la place) de la base [basis]. */
    val portion: ProductPortion? = null,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<Product> {
    init {
        require(productId.isNotBlank()) { "Identifiant de produit vide" }
        require(name.isNotBlank()) { "Nom de produit vide" }
        require(version >= 1) { "Version invalide : $version" }
    }

    override val key get() = productId
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}

/**
 * Ingrédient d'un plat : quantité en grammes et valeurs pour 100 g. Quand l'ingrédient vient d'un
 * produit de la mémoire, [productId] le rappelle (les valeurs, elles, sont celles du moment de
 * l'écriture : un plat enregistré ne change pas tout seul si la fiche du produit change plus tard).
 */
data class RecipeIngredient(
    val ingredientId: String,
    val label: String,
    val quantityG: Double,
    val per100g: Nutrients,
    val productId: String? = null,
) {
    init {
        require(quantityG.isFinite() && quantityG > 0) { "Quantité d'ingrédient invalide : $quantityG" }
    }
}

/**
 * Plat récurrent (recette du système historique), versionné par `(recipeId, version)`.
 * Un plat sans poids complets garde ses composants dans [sourceTemplateJson] et n'est
 * jamais calculé comme zéro.
 *
 * Variantes : un plat (« Salade ») peut avoir des variantes (« sauce A », « sauce B »). Une
 * variante est une autre fiche de plat avec ses propres ingrédients, rattachée par [variantOf] ;
 * elle porte le nom du plat et son propre [variantName]. Le plat désigne celle qu'on utilise quand
 * l'utilisateur dit seulement « salade » : [defaultVariantId] (`null` : la version de base, le plat
 * lui-même). Les ingrédients sont ceux d'**une portion** (un plat entier mangé = 1 portion).
 */
data class Recipe(
    val recipeId: String,
    override val version: Int,
    override val state: RecordState,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Faux : plat en pause. */
    val active: Boolean = true,
    val defaultMatch: Boolean = false,
    val ingredients: List<RecipeIngredient> = emptyList(),
    /** Modèle d'origine (objet JSON), conservé tel quel. */
    val sourceTemplateJson: String? = null,
    /** Plat dont cette fiche est une variante, ou `null` pour un plat. */
    val variantOf: String? = null,
    /** Nom de cette version (« sauce B »), ou `null` pour la version de base d'un plat. */
    val variantName: String? = null,
    /** Pour un plat : variante utilisée par défaut, ou `null` pour sa version de base. */
    val defaultVariantId: String? = null,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<Recipe> {
    init {
        require(recipeId.isNotBlank()) { "Identifiant de plat vide" }
        require(name.isNotBlank()) { "Nom de plat vide" }
        require(version >= 1) { "Version invalide : $version" }
        require(variantOf != recipeId) { "Un plat ne peut pas être sa propre variante" }
        require(variantOf == null || defaultVariantId == null) { "Seul un plat désigne une variante par défaut" }
        require(variantOf == null || variantName != null) { "Une variante porte un nom" }
    }

    /** Variante d'un plat (et non le plat lui-même). */
    val isVariant: Boolean get() = variantOf != null

    override val key get() = recipeId
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}

/** Règle durable formulée explicitement par l'utilisateur (« le riz est pesé cuit »). */
data class Preference(
    val preferenceId: String,
    override val version: Int,
    override val state: RecordState,
    /** `cooking_default`, `portion_default`, `product_default`, `recipe_alias`, `interaction_preference`, `display_preference`. */
    val kind: String,
    val trigger: String,
    val rule: String,
    /** Faux : règle désactivée (jamais supprimée). */
    val active: Boolean = true,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<Preference> {
    init {
        require(preferenceId.isNotBlank()) { "Identifiant de préférence vide" }
        require(rule.isNotBlank()) { "Règle vide" }
        require(version >= 1) { "Version invalide : $version" }
    }

    override val key get() = preferenceId
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}

/**
 * Provision : un aliment ou produit que l'utilisateur dit avoir à disposition. Liste simple,
 * sans quantités. Elle sert aux suggestions et à la reconnaissance, et n'est **jamais**
 * une consommation. Clé : l'identifiant de la provision ; état `active` = « j'ai », `deleted` =
 * « je n'ai plus » (la fiche reste dans l'historique, et revient en « j'ai » par une nouvelle version).
 */
data class Provision(
    val provisionId: String,
    override val version: Int,
    override val state: RecordState,
    val name: String,
    /** Produit de la mémoire que cette provision désigne, s'il est connu. */
    val productId: String? = null,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<Provision> {
    init {
        require(provisionId.isNotBlank()) { "Identifiant de provision vide" }
        require(name.isNotBlank()) { "Nom de provision vide" }
        require(version >= 1) { "Version invalide : $version" }
    }

    override val key get() = provisionId
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}
