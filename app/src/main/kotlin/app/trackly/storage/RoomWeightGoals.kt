package app.trackly.storage

import app.trackly.core.goal.ReferenceWeight
import app.trackly.core.goal.WeightGoal
import app.trackly.core.goal.WeightTarget
import app.trackly.core.model.ActionId
import app.trackly.core.model.RecordState
import app.trackly.core.store.ReferenceWeightVersions
import app.trackly.core.store.WeightGoalVersions
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.db.WeightGoalEntity
import java.time.Instant
import java.time.LocalDate

// Objectif de poids (version 11) et poids de référence, utilisés par RoomTracklyStore. Les deux vivent dans la table
// `weight_goal_versions`, chacun sous sa clé (voir WeightGoalTables.kt) : chaque côté ne lit et n'écrit que la sienne.

internal class RoomWeightGoals(db: TracklyDatabase) : WeightGoalVersions {
    private val dao = db.weightGoals()

    override suspend fun current(key: String) = mine(key) { dao.current(it)?.toModel() }
    override suspend fun version(key: String, version: Int) = mine(key) { dao.version(it, version)?.toModel() }
    override suspend fun allCurrent() = dao.allCurrentOf(WeightGoal.KEY).map { it.toModel() }
    override suspend fun insert(record: WeightGoal) = dao.insert(record.toEntity())

    private inline fun <T> mine(key: String, read: (String) -> T?): T? = if (key == WeightGoal.KEY) read(key) else null
}

/** Poids de référence : clé `reference_weight` ; `target_kg` = le poids, `target_date` = le jour où il a pris effet. */
internal class RoomReferenceWeights(db: TracklyDatabase) : ReferenceWeightVersions {
    private val dao = db.weightGoals()

    override suspend fun current(key: String) = mine(key) { dao.current(it)?.toReference() }
    override suspend fun version(key: String, version: Int) = mine(key) { dao.version(it, version)?.toReference() }
    override suspend fun allCurrent() = dao.allCurrentOf(ReferenceWeight.KEY).map { it.toReference() }
    override suspend fun insert(record: ReferenceWeight) = dao.insert(record.toEntity())

    private inline fun <T> mine(key: String, read: (String) -> T?): T? = if (key == ReferenceWeight.KEY) read(key) else null
}

private fun ReferenceWeight.toEntity() = WeightGoalEntity(
    goalKey = key,
    version = version,
    state = state.code,
    targetKg = kg,
    targetDate = from.toString(),
    losing = true,
    recordedAt = recordedAt.toEpochMilli(),
    actionId = actionId?.value,
)

private fun WeightGoalEntity.toReference() = ReferenceWeight(
    kg = targetKg,
    from = LocalDate.parse(targetDate),
    version = version,
    state = RecordState.fromCode(state),
    recordedAt = Instant.ofEpochMilli(recordedAt),
    actionId = actionId?.let(::ActionId),
)

private fun WeightGoal.toEntity() = WeightGoalEntity(
    goalKey = key,
    version = version,
    state = state.code,
    targetKg = target.kg,
    targetDate = target.date.toString(),
    losing = target.losing,
    recordedAt = recordedAt.toEpochMilli(),
    actionId = actionId?.value,
)

private fun WeightGoalEntity.toModel() = WeightGoal(
    target = WeightTarget(targetKg, LocalDate.parse(targetDate), losing),
    version = version,
    state = RecordState.fromCode(state),
    recordedAt = Instant.ofEpochMilli(recordedAt),
    actionId = actionId?.let(::ActionId),
)
