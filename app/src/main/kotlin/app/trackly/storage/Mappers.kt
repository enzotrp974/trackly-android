package app.trackly.storage

import app.trackly.core.model.ActionId
import app.trackly.core.model.Confidence
import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.FoodSource
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.KcalRange
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Origin
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.core.store.ActionRecord
import app.trackly.core.store.Effect
import app.trackly.storage.db.ActionEffectEntity
import app.trackly.storage.db.ActionWithEffects
import app.trackly.storage.db.ConsumptionEntity
import app.trackly.storage.db.GoalEntity
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate

/** Conversions entre le modèle du moteur et les lignes Room. */
internal object Mappers {
    fun Consumption.toEntity() = ConsumptionEntity(
        entryId = entryId.value,
        version = version,
        state = state.code,
        day = day.toString(),
        consumedAt = consumedAt.toEpochMilli(),
        moment = moment.code,
        label = food.label,
        quantityValue = food.quantity?.value,
        quantityUnit = food.quantity?.unit,
        kcal = food.nutrition.kcal,
        proteinG = food.nutrition.proteinG,
        carbsG = food.nutrition.carbsG,
        fatG = food.nutrition.fatG,
        fiberG = food.nutrition.fiberG,
        productId = food.productId,
        recipeId = food.recipeId,
        sourceType = food.source?.type,
        sourceDescription = food.source?.description,
        sourceUrl = food.source?.url,
        confidence = food.confidence?.code,
        assumptions = JSONArray(food.assumptions).toString(),
        kcalLow = food.kcalRange?.low,
        kcalHigh = food.kcalRange?.high,
        origin = origin.code,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
    )

    fun ConsumptionEntity.toModel() = Consumption(
        entryId = EntryId(entryId),
        version = version,
        state = RecordState.fromCode(state),
        day = LocalDate.parse(day),
        consumedAt = Instant.ofEpochMilli(consumedAt),
        moment = Moment.fromCode(moment),
        food = Food(
            label = label,
            nutrition = Nutrition(kcal, proteinG, carbsG, fatG, fiberG),
            quantity = if (quantityValue != null && quantityUnit != null) Quantity(quantityValue, quantityUnit) else null,
            assumptions = JSONArray(assumptions).let { array -> List(array.length()) { array.getString(it) } },
            kcalRange = if (kcalLow != null && kcalHigh != null) KcalRange(kcalLow, kcalHigh) else null,
            productId = productId,
            recipeId = recipeId,
            source = if (sourceType != null && sourceDescription != null) FoodSource(sourceType, sourceDescription, sourceUrl) else null,
            confidence = confidence?.let(Confidence::fromCode),
        ),
        origin = Origin(origin),
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun Goal.toEntity() = GoalEntity(
        effectiveFrom = effectiveFrom.toString(),
        version = version,
        state = state.code,
        kcal = targets.kcal,
        proteinG = targets.proteinG,
        carbsG = targets.carbsG,
        fatG = targets.fatG,
        recordedAt = recordedAt.toEpochMilli(),
        actionId = actionId?.value,
        proteinMinG = targets.proteinMinG,
    )

    fun GoalEntity.toModel() = Goal(
        effectiveFrom = LocalDate.parse(effectiveFrom),
        version = version,
        state = RecordState.fromCode(state),
        targets = GoalTargets(kcal, proteinG, carbsG, fatG, proteinMinG),
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )

    fun ActionWithEffects.toModel() = ActionRecord(
        id = ActionId(action.id),
        type = action.type,
        title = action.title,
        appliedAt = Instant.ofEpochMilli(action.appliedAt),
        effects = effects.sortedBy { it.position }.map { Effect(it.kind, it.recordKey, it.beforeVersion, it.afterVersion) },
        undoes = action.undoesActionId?.let(::ActionId),
        undoneBy = action.undoneByActionId?.let(::ActionId),
    )

    fun Effect.toEntity(actionId: Long, position: Int) =
        ActionEffectEntity(actionId, position, kind, key, beforeVersion, afterVersion)
}
