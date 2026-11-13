package app.trackly.storage

import androidx.room.withTransaction
import app.trackly.core.model.ActionId
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EntryId
import app.trackly.core.model.Goal
import app.trackly.core.store.ActionLog
import app.trackly.core.store.ActionRecord
import app.trackly.core.store.ConsumptionVersions
import app.trackly.core.store.Effect
import app.trackly.core.store.GoalVersions
import app.trackly.core.store.StoreTransaction
import app.trackly.core.store.TracklyStore
import app.trackly.storage.Mappers.toEntity
import app.trackly.storage.Mappers.toModel
import app.trackly.storage.db.ActionEntity
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.HistoryMappers.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/** Implémentation Room du port de stockage du moteur. */
class RoomTracklyStore(private val db: TracklyDatabase) : TracklyStore {
    private val tx = RoomTransaction(db)

    override suspend fun <T> transaction(block: suspend StoreTransaction.() -> T): T =
        db.withTransaction { tx.block() }

    override fun observeActiveConsumptions(from: LocalDate, to: LocalDate): Flow<List<Consumption>> =
        db.consumptions().observeActiveBetween(from.toString(), to.toString()).map { rows -> rows.map { it.toModel() } }

    override fun observeGoals(): Flow<List<Goal>> =
        db.goals().observeAllCurrent().map { rows -> rows.map { it.toModel() } }

    override fun observeDailyEstimates(from: LocalDate, to: LocalDate): Flow<List<DailyEstimate>> =
        db.dailyEstimates().observeAllVersionsBetween(from.toString(), to.toString()).map { rows -> rows.map { it.toModel() } }

    override fun observeDayTracking(from: LocalDate, to: LocalDate): Flow<List<DayTracking>> =
        db.dayTracking().observeCurrentBetween(from.toString(), to.toString()).map { rows -> rows.map { it.toModel() } }

    override fun observeFirstDay(): Flow<LocalDate?> =
        combine(db.consumptions().observeFirstActiveDay(), db.dailyEstimates().observeFirstDay()) { consumption, estimate ->
            listOfNotNull(consumption, estimate).minOrNull()?.let(LocalDate::parse)
        }

    override fun observeRecentActions(limit: Int): Flow<List<ActionRecord>> =
        db.actions().observeRecent(limit).map { rows -> rows.map { it.toModel() } }
}

private class RoomTransaction(db: TracklyDatabase) : StoreTransaction {
    override val consumptions = RoomConsumptions(db)
    override val goals = RoomGoals(db)
    override val actions = RoomActionLog(db)
    override val dailyEstimates = RoomDailyEstimates(db)
    override val dayTracking = RoomDayTracking(db)
    override val products = RoomProducts(db)
    override val recipes = RoomRecipes(db)
    override val preferences = RoomPreferences(db)
    override val provisions = RoomProvisions(db)
    override val imports = RoomImportLedger(db)
    override val weekInfos = RoomWeekInfos(db)
    override val weightGoals = RoomWeightGoals(db)
    override val referenceWeights = RoomReferenceWeights(db)
}

private class RoomConsumptions(db: TracklyDatabase) : ConsumptionVersions {
    private val dao = db.consumptions()

    override suspend fun current(entryId: EntryId) = dao.current(entryId.value)?.toModel()
    override suspend fun version(entryId: EntryId, version: Int) = dao.version(entryId.value, version)?.toModel()
    override suspend fun history(entryId: EntryId) = dao.history(entryId.value).map { it.toModel() }
    override suspend fun activeBetween(from: LocalDate, to: LocalDate) =
        dao.activeBetween(from.toString(), to.toString()).map { it.toModel() }

    override suspend fun activeForProduct(productId: String) = dao.activeForProduct(productId).map { it.toModel() }

    override suspend fun insert(consumption: Consumption) = dao.insert(consumption.toEntity())
}

private class RoomGoals(db: TracklyDatabase) : GoalVersions {
    private val dao = db.goals()

    override suspend fun current(effectiveFrom: LocalDate) = dao.current(effectiveFrom.toString())?.toModel()
    override suspend fun version(effectiveFrom: LocalDate, version: Int) = dao.version(effectiveFrom.toString(), version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(goal: Goal) = dao.insert(goal.toEntity())
}

private class RoomActionLog(db: TracklyDatabase) : ActionLog {
    private val dao = db.actions()

    override suspend fun begin(type: String, appliedAt: Instant): ActionId =
        ActionId(dao.insert(ActionEntity(type = type, title = "", appliedAt = appliedAt.toEpochMilli(), undoesActionId = null, undoneByActionId = null)))

    override suspend fun complete(id: ActionId, title: String, effects: List<Effect>, undoes: ActionId?) {
        dao.complete(id.value, title, undoes?.value)
        dao.insertEffects(effects.mapIndexed { i, effect -> effect.toEntity(id.value, i) })
    }

    override suspend fun get(id: ActionId) = dao.get(id.value)?.toModel()

    override suspend fun notUndone(before: ActionId?, limit: Int) = dao.notUndone(before?.value, limit).map { it.toModel() }

    override suspend fun markUndone(id: ActionId, by: ActionId) = dao.markUndone(id.value, by.value)

    override suspend fun existsOfType(type: String, before: ActionId) = dao.existsOfType(type, before.value)
}
