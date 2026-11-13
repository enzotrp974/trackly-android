package app.trackly.core.store

import app.trackly.core.model.ActionId
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EntryId
import app.trackly.core.model.Goal
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Port de stockage du moteur. L'app l'implémente avec Room ; [InMemoryStore] sert aux tests.
 *
 * Les versions sont immuables : on n'en modifie ni n'en supprime jamais une, on en ajoute.
 * Un nouveau type de données (produits, plats, provisions…) ajoute son interface ici
 * et une propriété dans [StoreTransaction].
 */
interface TracklyStore {
    /** Exécute [block] de façon atomique : une exception annule toutes ses écritures. */
    suspend fun <T> transaction(block: suspend StoreTransaction.() -> T): T

    /** Versions courantes actives des consommations rattachées à un jour de [from] à [to] inclus. */
    fun observeActiveConsumptions(from: LocalDate, to: LocalDate): Flow<List<Consumption>>

    /** Version courante de chaque objectif daté (actifs et supprimés). */
    fun observeGoals(): Flow<List<Goal>>

    /** Toutes les versions des estimations journalières des jours de [from] à [to] inclus. */
    fun observeDailyEstimates(from: LocalDate, to: LocalDate): Flow<List<DailyEstimate>>

    /** Version courante du suivi déclaré des jours de [from] à [to] inclus. */
    fun observeDayTracking(from: LocalDate, to: LocalDate): Flow<List<DayTracking>>

    /** Premier jour ayant au moins une consommation active ou une estimation journalière, ou `null`. */
    fun observeFirstDay(): Flow<LocalDate?>

    /** Dernières actions appliquées, la plus récente d'abord. */
    fun observeRecentActions(limit: Int): Flow<List<ActionRecord>>
}

interface StoreTransaction {
    val consumptions: ConsumptionVersions
    val goals: GoalVersions
    val actions: ActionLog

    // Reprise de l'historique : voir RecordStores.kt.
    val dailyEstimates: DailyEstimateVersions
    val dayTracking: DayTrackingVersions
    val products: ProductVersions
    val recipes: RecipeVersions
    val preferences: PreferenceVersions
    val provisions: ProvisionVersions
    val imports: ImportLedger

    // Bilan du lundi : informations déclarées de la semaine.
    val weekInfos: WeekInfoVersions

    // Prévision d'arrivée : objectif de poids.
    val weightGoals: WeightGoalVersions

    // Poids de référence mis à jour après chaque bilan.
    val referenceWeights: ReferenceWeightVersions
}

interface ConsumptionVersions {
    suspend fun current(entryId: EntryId): Consumption?
    suspend fun version(entryId: EntryId, version: Int): Consumption?

    /** Toutes les versions, de la plus ancienne à la plus récente. */
    suspend fun history(entryId: EntryId): List<Consumption>

    /** Versions courantes actives rattachées à un jour de [from] à [to] inclus. */
    suspend fun activeBetween(from: LocalDate, to: LocalDate): List<Consumption>

    /** Versions courantes actives des consommations liées au produit [productId] (`Food.productId`). */
    suspend fun activeForProduct(productId: String): List<Consumption>

    /** Ajoute une version. Échoue si `(entryId, version)` existe déjà. */
    suspend fun insert(consumption: Consumption)
}

interface GoalVersions {
    suspend fun current(effectiveFrom: LocalDate): Goal?
    suspend fun version(effectiveFrom: LocalDate, version: Int): Goal?

    /** Version courante de chaque objectif daté. */
    suspend fun allCurrent(): List<Goal>

    /** Ajoute une version. Échoue si `(effectiveFrom, version)` existe déjà. */
    suspend fun insert(goal: Goal)
}

/** Journal des actions appliquées, base de l'annulation. */
interface ActionLog {
    /** Réserve un identifiant croissant pour une action en cours d'application. */
    suspend fun begin(type: String, appliedAt: Instant): ActionId

    /** Termine l'action : titre lisible, effets, et action annulée le cas échéant. */
    suspend fun complete(id: ActionId, title: String, effects: List<Effect>, undoes: ActionId?)

    suspend fun get(id: ActionId): ActionRecord?

    /**
     * Actions terminées (avec effets) qui n'ont pas été annulées, de la plus récente à la plus
     * ancienne, strictement antérieures à [before] si précisé.
     */
    suspend fun notUndone(before: ActionId?, limit: Int): List<ActionRecord>

    suspend fun markUndone(id: ActionId, by: ActionId)

    /** Une action de type [type], annulée ou non, a-t-elle été appliquée avant [before] ? (étapes uniques) */
    suspend fun existsOfType(type: String, before: ActionId): Boolean
}

/**
 * Effet d'une action sur une donnée versionnée : la version courante avant ([beforeVersion],
 * `null` si la donnée n'existait pas) et celle écrite par l'action ([afterVersion]).
 */
data class Effect(val kind: String, val key: String, val beforeVersion: Int?, val afterVersion: Int)

data class ActionRecord(
    val id: ActionId,
    /** Type stable de l'action (ex. `consumption.add`). */
    val type: String,
    /** Description courte en français, pour l'historique et le bouton « Annuler ». */
    val title: String,
    val appliedAt: Instant,
    val effects: List<Effect>,
    /** Action annulée par celle-ci, le cas échéant. */
    val undoes: ActionId?,
    /** Action qui a annulé celle-ci, le cas échéant. */
    val undoneBy: ActionId?,
)
