package app.trackly.core.store

import app.trackly.core.model.ActionId
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EntryId
import app.trackly.core.model.Goal
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import app.trackly.core.model.VersionedRecord
import app.trackly.core.goal.ReferenceWeight
import app.trackly.core.goal.WeightGoal
import app.trackly.core.review.WeekInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

/**
 * Stockage en mémoire, pour les tests du moteur et les aperçus. Même contrat que Room :
 * transactions atomiques (tout ou rien), versions immuables, clés uniques.
 */
class InMemoryStore : TracklyStore {
    private data class State(
        val consumptions: Map<Pair<EntryId, Int>, Consumption> = emptyMap(),
        val goals: Map<Pair<LocalDate, Int>, Goal> = emptyMap(),
        val actions: Map<ActionId, ActionRecord> = emptyMap(),
        val nextActionId: Long = 1,
        /** Données versionnées génériques, par type puis par `(key, version)`. */
        val records: Map<String, Map<Pair<String, Int>, VersionedRecord<*>>> = emptyMap(),
        val imports: Map<Triple<String, String, Int>, ImportedSource> = emptyMap(),
    ) {
        @Suppress("UNCHECKED_CAST")
        fun <T : VersionedRecord<T>> all(kind: String): Collection<T> =
            records[kind].orEmpty().values as Collection<T>

        fun <T : VersionedRecord<T>> currentOf(kind: String): List<T> =
            all<T>(kind).groupBy { it.key }.values.map { v -> v.maxBy { it.version } }
    }

    private val state = MutableStateFlow(State())
    private val mutex = Mutex()

    override suspend fun <T> transaction(block: suspend StoreTransaction.() -> T): T = mutex.withLock {
        val tx = Tx(state.value)
        val result = tx.block()
        state.value = tx.working
        result
    }

    override fun observeActiveConsumptions(from: LocalDate, to: LocalDate): Flow<List<Consumption>> =
        state.map { s -> s.currentConsumptions().filter { it.isActive && !it.day.isBefore(from) && !it.day.isAfter(to) } }

    override fun observeGoals(): Flow<List<Goal>> = state.map { it.currentGoals() }

    override fun observeDailyEstimates(from: LocalDate, to: LocalDate): Flow<List<DailyEstimate>> =
        state.map { s -> s.all<DailyEstimate>(ESTIMATES).filter { !it.day.isBefore(from) && !it.day.isAfter(to) } }

    override fun observeDayTracking(from: LocalDate, to: LocalDate): Flow<List<DayTracking>> =
        state.map { s -> s.currentOf<DayTracking>(TRACKING).filter { !it.day.isBefore(from) && !it.day.isAfter(to) } }

    override fun observeFirstDay(): Flow<LocalDate?> =
        state.map { s ->
            val consumption = s.currentConsumptions().filter { it.isActive }.minOfOrNull { it.day }
            val estimate = s.all<DailyEstimate>(ESTIMATES).minOfOrNull { it.day }
            listOfNotNull(consumption, estimate).minOrNull()
        }

    override fun observeRecentActions(limit: Int): Flow<List<ActionRecord>> =
        state.map { s -> s.actions.values.sortedByDescending { it.id.value }.take(limit) }

    private fun State.currentConsumptions(): List<Consumption> =
        consumptions.values.groupBy { it.entryId }.values.map { v -> v.maxBy { it.version } }

    private fun State.currentGoals(): List<Goal> =
        goals.values.groupBy { it.effectiveFrom }.values.map { v -> v.maxBy { it.version } }

    private class Tx(var working: State) : StoreTransaction, ConsumptionVersions, GoalVersions, ActionLog {
        override val consumptions: ConsumptionVersions get() = this
        override val goals: GoalVersions get() = this
        override val actions: ActionLog get() = this
        override val dailyEstimates: DailyEstimateVersions = object : MemoryRecords<DailyEstimate>(this, ESTIMATES), DailyEstimateVersions {
            override suspend fun allVersionsBetween(from: LocalDate, to: LocalDate) =
                tx.working.all<DailyEstimate>(ESTIMATES).filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
        }
        override val dayTracking: DayTrackingVersions = object : MemoryRecords<DayTracking>(this, TRACKING), DayTrackingVersions {
            override suspend fun currentBetween(from: LocalDate, to: LocalDate) =
                allCurrent().filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
        }
        override val products: ProductVersions = object : MemoryRecords<Product>(this, "product"), ProductVersions {}
        override val recipes: RecipeVersions = object : MemoryRecords<Recipe>(this, "recipe"), RecipeVersions {}
        override val preferences: PreferenceVersions = object : MemoryRecords<Preference>(this, "preference"), PreferenceVersions {}
        override val provisions: ProvisionVersions = object : MemoryRecords<Provision>(this, "provision"), ProvisionVersions {}
        override val weekInfos: WeekInfoVersions = object : MemoryRecords<WeekInfo>(this, "week_info"), WeekInfoVersions {}
        override val weightGoals: WeightGoalVersions = object : MemoryRecords<WeightGoal>(this, "weight_goal"), WeightGoalVersions {}
        override val referenceWeights: ReferenceWeightVersions = object : MemoryRecords<ReferenceWeight>(this, ReferenceWeight.KEY), ReferenceWeightVersions {}
        override val imports: ImportLedger = object : ImportLedger {
            override suspend fun get(kind: String, key: String, version: Int) = working.imports[Triple(kind, key, version)]

            override suspend fun latest(kind: String, key: String) =
                working.imports.values.filter { it.kind == kind && it.key == key }.maxByOrNull { it.version }

            override suspend fun insert(source: ImportedSource) {
                val id = Triple(source.kind, source.key, source.version)
                check(id !in working.imports) { "Document déjà repris : $id" }
                working = working.copy(imports = working.imports + (id to source))
            }

            override suspend fun count() = working.imports.size
        }

        // Consommations

        override suspend fun current(entryId: EntryId): Consumption? =
            working.consumptions.values.filter { it.entryId == entryId }.maxByOrNull { it.version }

        override suspend fun version(entryId: EntryId, version: Int): Consumption? =
            working.consumptions[entryId to version]

        override suspend fun history(entryId: EntryId): List<Consumption> =
            working.consumptions.values.filter { it.entryId == entryId }.sortedBy { it.version }

        override suspend fun activeBetween(from: LocalDate, to: LocalDate): List<Consumption> =
            working.consumptions.values.groupBy { it.entryId }.values
                .map { v -> v.maxBy { it.version } }
                .filter { it.isActive && !it.day.isBefore(from) && !it.day.isAfter(to) }

        override suspend fun activeForProduct(productId: String): List<Consumption> =
            working.consumptions.values.groupBy { it.entryId }.values
                .map { v -> v.maxBy { it.version } }
                .filter { it.isActive && it.food.productId == productId }

        override suspend fun insert(consumption: Consumption) {
            val key = consumption.entryId to consumption.version
            check(key !in working.consumptions) { "Version déjà enregistrée : $key" }
            working = working.copy(consumptions = working.consumptions + (key to consumption))
        }

        // Objectifs

        override suspend fun current(effectiveFrom: LocalDate): Goal? =
            working.goals.values.filter { it.effectiveFrom == effectiveFrom }.maxByOrNull { it.version }

        override suspend fun version(effectiveFrom: LocalDate, version: Int): Goal? =
            working.goals[effectiveFrom to version]

        override suspend fun allCurrent(): List<Goal> =
            working.goals.values.groupBy { it.effectiveFrom }.values.map { v -> v.maxBy { it.version } }

        override suspend fun insert(goal: Goal) {
            val key = goal.effectiveFrom to goal.version
            check(key !in working.goals) { "Version déjà enregistrée : $key" }
            working = working.copy(goals = working.goals + (key to goal))
        }

        // Journal des actions

        override suspend fun begin(type: String, appliedAt: Instant): ActionId {
            val id = ActionId(working.nextActionId)
            working = working.copy(
                nextActionId = working.nextActionId + 1,
                actions = working.actions + (id to ActionRecord(id, type, "", appliedAt, emptyList(), null, null)),
            )
            return id
        }

        override suspend fun complete(id: ActionId, title: String, effects: List<Effect>, undoes: ActionId?) {
            update(id) { it.copy(title = title, effects = effects, undoes = undoes) }
        }

        override suspend fun get(id: ActionId): ActionRecord? = working.actions[id]

        override suspend fun notUndone(before: ActionId?, limit: Int): List<ActionRecord> =
            working.actions.values
                .filter { it.undoneBy == null && it.effects.isNotEmpty() && (before == null || it.id.value < before.value) }
                .sortedByDescending { it.id.value }
                .take(limit)

        override suspend fun markUndone(id: ActionId, by: ActionId) {
            update(id) { it.copy(undoneBy = by) }
        }

        override suspend fun existsOfType(type: String, before: ActionId): Boolean =
            working.actions.values.any { it.type == type && it.id.value < before.value }

        private fun update(id: ActionId, change: (ActionRecord) -> ActionRecord) {
            val record = checkNotNull(working.actions[id]) { "Action inconnue : $id" }
            working = working.copy(actions = working.actions + (id to change(record)))
        }
    }

    /** Versions d'un type de donnée générique, dans l'état de travail de la transaction. */
    private open class MemoryRecords<T : VersionedRecord<T>>(val tx: Tx, private val kind: String) : RecordVersions<T> {
        override suspend fun current(key: String): T? =
            tx.working.all<T>(kind).filter { it.key == key }.maxByOrNull { it.version }

        override suspend fun version(key: String, version: Int): T? =
            tx.working.all<T>(kind).firstOrNull { it.key == key && it.version == version }

        override suspend fun allCurrent(): List<T> = tx.working.currentOf(kind)

        override suspend fun insert(record: T) {
            val id = record.key to record.version
            val existing = tx.working.records[kind].orEmpty()
            check(id !in existing) { "Version déjà enregistrée : $kind $id" }
            tx.working = tx.working.copy(records = tx.working.records + (kind to existing + (id to record)))
        }
    }

    private companion object {
        const val ESTIMATES = "daily_estimate"
        const val TRACKING = "day_tracking"
    }
}
