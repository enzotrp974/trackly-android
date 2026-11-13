package app.trackly.storage

import app.trackly.core.model.ActionId
import app.trackly.core.model.Confidence
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EntryId
import app.trackly.core.model.EstimateKind
import app.trackly.core.model.FoodSource
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecipeIngredient
import app.trackly.core.model.RecordState
import app.trackly.core.store.ImportedSource
import app.trackly.storage.db.DailyEstimateEntity
import app.trackly.storage.db.DayTrackingEntity
import app.trackly.storage.db.ImportedSourceEntity
import app.trackly.storage.db.PreferenceEntity
import app.trackly.storage.db.ProductEntity
import app.trackly.storage.db.ProvisionEntity
import app.trackly.core.model.Provision
import app.trackly.storage.db.RecipeEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Conversions des données de la reprise (version 3) entre le moteur et les lignes Room. */
internal object HistoryMappers {
    private fun strings(json: String): List<String> = JSONArray(json).let { a -> List(a.length()) { a.getString(it) } }

    private fun JSONObject.doubleOrNull(name: String): Double? = if (isNull(name)) null else getDouble(name)

    fun DailyEstimate.toEntity() = DailyEstimateEntity(
        assessmentId = assessmentId,
        version = version,
        state = state.code,
        day = day.toString(),
        kind = kind.code,
        kcal = kcal,
        kcalLow = kcalLow,
        kcalHigh = kcalHigh,
        description = description,
        replacesEntryIds = JSONArray(replacesEntryIds.map { it.value }).toString(),
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun DailyEstimateEntity.toModel() = DailyEstimate(
        assessmentId = assessmentId,
        version = version,
        state = RecordState.fromCode(state),
        day = LocalDate.parse(day),
        kind = EstimateKind.fromCode(kind),
        kcal = kcal,
        kcalLow = kcalLow,
        kcalHigh = kcalHigh,
        description = description,
        replacesEntryIds = strings(replacesEntryIds).map(::EntryId),
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun DayTracking.toEntity() = DayTrackingEntity(
        day = day.toString(),
        version = version,
        state = state.code,
        closedExplicitly = closedExplicitly,
        trackingComplete = trackingComplete,
        detailedTrackingComplete = detailedTrackingComplete,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun DayTrackingEntity.toModel() = DayTracking(
        day = LocalDate.parse(day),
        version = version,
        state = RecordState.fromCode(state),
        closedExplicitly = closedExplicitly,
        trackingComplete = trackingComplete,
        detailedTrackingComplete = detailedTrackingComplete,
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun Product.toEntity() = ProductEntity(
        productId = productId,
        version = version,
        state = state.code,
        name = name,
        brand = brand,
        variant = variant,
        country = country,
        barcode = barcode,
        aliases = JSONArray(aliases).toString(),
        soldState = soldState,
        kcal = nutrients.kcal,
        proteinG = nutrients.proteinG,
        carbsG = nutrients.carbsG,
        fatG = nutrients.fatG,
        fiberG = nutrients.fiberG,
        basisValue = basis?.value,
        basisUnit = basis?.unit,
        basisState = basis?.state,
        nutritionStatus = nutritionStatus,
        sourceType = source?.type,
        sourceDescription = source?.description,
        sourceUrl = source?.url,
        confidence = confidence?.code,
        verified = verified,
        favorite = favorite,
        active = active,
        redirectTo = redirectTo,
        defaultMatch = defaultMatch,
        kind = kind.code,
        portionLabel = portion?.label,
        portionWeightValue = portion?.weight?.value,
        portionWeightUnit = portion?.weight?.unit,
        portionKcal = portion?.nutrients?.kcal,
        portionProteinG = portion?.nutrients?.proteinG,
        portionCarbsG = portion?.nutrients?.carbsG,
        portionFatG = portion?.nutrients?.fatG,
        portionFiberG = portion?.nutrients?.fiberG,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun ProductEntity.toModel() = Product(
        productId = productId,
        version = version,
        state = RecordState.fromCode(state),
        name = name,
        brand = brand,
        variant = variant,
        country = country,
        barcode = barcode,
        aliases = strings(aliases),
        soldState = soldState,
        nutrients = Nutrients(kcal, proteinG, carbsG, fatG, fiberG),
        basis = if (basisValue != null && basisUnit != null) NutritionBasis(basisValue, basisUnit, basisState) else null,
        nutritionStatus = nutritionStatus,
        source = if (sourceType != null && sourceDescription != null) FoodSource(sourceType, sourceDescription, sourceUrl) else null,
        confidence = confidence?.let(Confidence::fromCode),
        verified = verified,
        favorite = favorite,
        active = active,
        redirectTo = redirectTo,
        defaultMatch = defaultMatch,
        kind = ProductKind.fromCode(kind),
        portion = portionLabel?.let { label ->
            ProductPortion(
                label = label,
                weight = if (portionWeightValue != null && portionWeightUnit != null) Quantity(portionWeightValue, portionWeightUnit) else null,
                nutrients = Nutrients(portionKcal, portionProteinG, portionCarbsG, portionFatG, portionFiberG),
            )
        },
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    private fun Nutrients.toJson() = JSONObject()
        .put("kcal", kcal ?: JSONObject.NULL)
        .put("protein_g", proteinG ?: JSONObject.NULL)
        .put("carbs_g", carbsG ?: JSONObject.NULL)
        .put("fat_g", fatG ?: JSONObject.NULL)
        .put("fiber_g", fiberG ?: JSONObject.NULL)

    private fun JSONObject.toNutrients() = Nutrients(
        doubleOrNull("kcal"), doubleOrNull("protein_g"), doubleOrNull("carbs_g"), doubleOrNull("fat_g"), doubleOrNull("fiber_g"),
    )

    fun Recipe.toEntity() = RecipeEntity(
        recipeId = recipeId,
        version = version,
        state = state.code,
        name = name,
        aliases = JSONArray(aliases).toString(),
        active = active,
        defaultMatch = defaultMatch,
        ingredients = JSONArray(
            ingredients.map { i ->
                JSONObject()
                    .put("ingredient_id", i.ingredientId)
                    .put("label", i.label)
                    .put("quantity_g", i.quantityG)
                    .put("per_100g", i.per100g.toJson())
                    .apply { i.productId?.let { put("product_id", it) } }
            },
        ).toString(),
        sourceTemplate = sourceTemplateJson,
        variantOf = variantOf,
        variantName = variantName,
        defaultVariantId = defaultVariantId,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun RecipeEntity.toModel() = Recipe(
        recipeId = recipeId,
        version = version,
        state = RecordState.fromCode(state),
        name = name,
        aliases = strings(aliases),
        active = active,
        defaultMatch = defaultMatch,
        ingredients = JSONArray(ingredients).let { a ->
            List(a.length()) { index ->
                val i = a.getJSONObject(index)
                RecipeIngredient(
                    i.getString("ingredient_id"), i.getString("label"), i.getDouble("quantity_g"), i.getJSONObject("per_100g").toNutrients(),
                    productId = if (i.isNull("product_id")) null else i.getString("product_id"),
                )
            }
        },
        sourceTemplateJson = sourceTemplate,
        variantOf = variantOf,
        variantName = variantName,
        defaultVariantId = defaultVariantId,
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun Preference.toEntity() = PreferenceEntity(
        preferenceId = preferenceId,
        version = version,
        state = state.code,
        kind = kind,
        trigger = trigger,
        rule = rule,
        active = active,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun PreferenceEntity.toModel() = Preference(
        preferenceId = preferenceId,
        version = version,
        state = RecordState.fromCode(state),
        kind = kind,
        trigger = trigger,
        rule = rule,
        active = active,
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun ImportedSource.toEntity() = ImportedSourceEntity(kind, key, version, sha256, json, actionId.value)

    fun ImportedSourceEntity.toModel() = ImportedSource(kind, recordKey, version, sha256, json, ActionId(actionId))
}

internal fun Provision.toEntity() = ProvisionEntity(
    provisionId = provisionId,
    version = version,
    state = state.code,
    name = name,
    productId = productId,
    recordedAt = recordedAt.toEpochMilli(),
    actionId = actionId?.value,
)

internal fun ProvisionEntity.toModel() = Provision(
    provisionId = provisionId,
    version = version,
    state = RecordState.fromCode(state),
    name = name,
    productId = productId,
    recordedAt = Instant.ofEpochMilli(recordedAt),
    actionId = actionId?.let(::ActionId),
)
