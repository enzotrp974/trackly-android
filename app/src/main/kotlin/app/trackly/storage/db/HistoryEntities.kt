package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

// Tables de la version 3 (reprise de l'historique). Versions immuables, comme les
// consommations : on en ajoute, on n'en modifie ni n'en supprime jamais. Listes en JSON texte.

/** Une version d'estimation journalière validée. */
@Entity(tableName = "daily_estimate_versions", primaryKeys = ["assessment_id", "version"], indices = [Index("day")])
data class DailyEstimateEntity(
    @ColumnInfo(name = "assessment_id") val assessmentId: String,
    val version: Int,
    val state: String,
    val day: String,
    val kind: String,
    val kcal: Double?,
    @ColumnInfo(name = "kcal_low") val kcalLow: Double,
    @ColumnInfo(name = "kcal_high") val kcalHigh: Double?,
    val description: String,
    /** Identifiants des entrées remplacées, en tableau JSON. */
    @ColumnInfo(name = "replaces_entry_ids") val replacesEntryIds: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version du suivi déclaré d'une journée. */
@Entity(tableName = "day_tracking_versions", primaryKeys = ["day", "version"])
data class DayTrackingEntity(
    val day: String,
    val version: Int,
    val state: String,
    @ColumnInfo(name = "closed_explicitly") val closedExplicitly: Boolean,
    @ColumnInfo(name = "tracking_complete") val trackingComplete: Boolean?,
    @ColumnInfo(name = "detailed_tracking_complete") val detailedTrackingComplete: Boolean?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version de fiche produit. */
@Entity(tableName = "product_versions", primaryKeys = ["product_id", "version"])
data class ProductEntity(
    @ColumnInfo(name = "product_id") val productId: String,
    val version: Int,
    val state: String,
    val name: String,
    val brand: String?,
    val variant: String?,
    val country: String?,
    val barcode: String?,
    /** Alias, en tableau JSON. */
    val aliases: String,
    @ColumnInfo(name = "sold_state") val soldState: String?,
    val kcal: Double?,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "fiber_g") val fiberG: Double?,
    @ColumnInfo(name = "basis_value") val basisValue: Double?,
    @ColumnInfo(name = "basis_unit") val basisUnit: String?,
    @ColumnInfo(name = "basis_state") val basisState: String?,
    @ColumnInfo(name = "nutrition_status") val nutritionStatus: String?,
    @ColumnInfo(name = "source_type") val sourceType: String?,
    @ColumnInfo(name = "source_description") val sourceDescription: String?,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    val confidence: String?,
    val verified: Boolean,
    val favorite: Boolean,
    val active: Boolean,
    @ColumnInfo(name = "redirect_to") val redirectTo: String?,
    @ColumnInfo(name = "default_match") val defaultMatch: Boolean,
    /** `occasional` (ponctuel) ou `recurring` (récurrent) ; les produits repris sont récurrents (version 5). */
    @ColumnInfo(defaultValue = "recurring") val kind: String,
    /** Portion usuelle (version 5) : nom, poids ou volume, et valeurs pour la portion. */
    @ColumnInfo(name = "portion_label") val portionLabel: String?,
    @ColumnInfo(name = "portion_weight_value") val portionWeightValue: Double?,
    @ColumnInfo(name = "portion_weight_unit") val portionWeightUnit: String?,
    @ColumnInfo(name = "portion_kcal") val portionKcal: Double?,
    @ColumnInfo(name = "portion_protein_g") val portionProteinG: Double?,
    @ColumnInfo(name = "portion_carbs_g") val portionCarbsG: Double?,
    @ColumnInfo(name = "portion_fat_g") val portionFatG: Double?,
    @ColumnInfo(name = "portion_fiber_g") val portionFiberG: Double?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version de plat récurrent. */
@Entity(tableName = "recipe_versions", primaryKeys = ["recipe_id", "version"])
data class RecipeEntity(
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val version: Int,
    val state: String,
    val name: String,
    val aliases: String,
    val active: Boolean,
    @ColumnInfo(name = "default_match") val defaultMatch: Boolean,
    /** Ingrédients, en tableau JSON (`ingredient_id`, `label`, `quantity_g`, `per_100g`). */
    val ingredients: String,
    @ColumnInfo(name = "source_template") val sourceTemplate: String?,
    /** Variantes (version 7) : plat dont cette fiche est une variante, nom de la version, variante par défaut du plat. */
    @ColumnInfo(name = "variant_of") val variantOf: String?,
    @ColumnInfo(name = "variant_name") val variantName: String?,
    @ColumnInfo(name = "default_variant_id") val defaultVariantId: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version de provision (version 7) : `active` = « j'ai », `deleted` = « je n'ai plus ». */
@Entity(tableName = "provision_versions", primaryKeys = ["provision_id", "version"])
data class ProvisionEntity(
    @ColumnInfo(name = "provision_id") val provisionId: String,
    val version: Int,
    val state: String,
    val name: String,
    @ColumnInfo(name = "product_id") val productId: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version de préférence explicite. */
@Entity(tableName = "preference_versions", primaryKeys = ["preference_id", "version"])
data class PreferenceEntity(
    @ColumnInfo(name = "preference_id") val preferenceId: String,
    val version: Int,
    val state: String,
    val kind: String,
    val trigger: String,
    val rule: String,
    val active: Boolean,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Document d'origine d'une donnée reprise, conservé tel quel (registre des reprises). */
@Entity(tableName = "imported_sources", primaryKeys = ["kind", "record_key", "version"])
data class ImportedSourceEntity(
    val kind: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    val version: Int,
    val sha256: String,
    val json: String,
    @ColumnInfo(name = "action_id") val actionId: Long,
)
