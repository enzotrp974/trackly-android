package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Relation

/**
 * Une version de consommation. Les versions sont immuables : on en ajoute, on n'en modifie
 * ni n'en supprime jamais. Dates au format ISO (`2026-11-02`), instants en millisecondes UTC.
 */
@Entity(
    tableName = "consumption_versions",
    primaryKeys = ["entry_id", "version"],
    indices = [Index("day"), Index("product_id")],
)
data class ConsumptionEntity(
    @ColumnInfo(name = "entry_id") val entryId: String,
    val version: Int,
    val state: String,
    val day: String,
    @ColumnInfo(name = "consumed_at") val consumedAt: Long,
    val moment: String,
    val label: String,
    @ColumnInfo(name = "quantity_value") val quantityValue: Double?,
    @ColumnInfo(name = "quantity_unit") val quantityUnit: String?,
    val kcal: Double,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "fiber_g") val fiberG: Double?,
    @ColumnInfo(name = "product_id") val productId: String?,
    @ColumnInfo(name = "recipe_id") val recipeId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String?,
    @ColumnInfo(name = "source_description") val sourceDescription: String?,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    val confidence: String?,
    /** Hypothèses d'estimation, en tableau JSON de textes (version 2). */
    @ColumnInfo(defaultValue = "[]") val assumptions: String,
    @ColumnInfo(name = "kcal_low") val kcalLow: Double?,
    @ColumnInfo(name = "kcal_high") val kcalHigh: Double?,
    val origin: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

/** Une version d'objectif nutritionnel daté (version 2 ; minimum de protéines en version 9). */
@Entity(tableName = "goal_versions", primaryKeys = ["effective_from", "version"])
data class GoalEntity(
    @ColumnInfo(name = "effective_from") val effectiveFrom: String,
    val version: Int,
    val state: String,
    val kcal: Double,
    /** Idéal de protéines : la cible. */
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
    /** Minimum de protéines, un plancher (version 9). `null` : aucun minimum connu. */
    @ColumnInfo(name = "protein_min_g") val proteinMinG: Double? = null,
)

/** Journal des actions appliquées. */
@Entity(tableName = "actions")
data class ActionEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long,
    @ColumnInfo(name = "undoes_action_id") val undoesActionId: Long?,
    @ColumnInfo(name = "undone_by_action_id") val undoneByActionId: Long?,
)

/** Effet d'une action sur une donnée versionnée (base de l'annulation). */
@Entity(tableName = "action_effects", primaryKeys = ["action_id", "position"])
data class ActionEffectEntity(
    @ColumnInfo(name = "action_id") val actionId: Long,
    val position: Int,
    val kind: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    @ColumnInfo(name = "before_version") val beforeVersion: Int?,
    @ColumnInfo(name = "after_version") val afterVersion: Int,
)

data class ActionWithEffects(
    @Embedded val action: ActionEntity,
    @Relation(parentColumn = "id", entityColumn = "action_id")
    val effects: List<ActionEffectEntity>,
)
