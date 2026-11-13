package app.trackly.core.store

import app.trackly.core.model.ActionId
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import app.trackly.core.model.VersionedRecord
import app.trackly.core.goal.ReferenceWeight
import app.trackly.core.goal.WeightGoal
import app.trackly.core.review.WeekInfo
import java.time.LocalDate

/** Versions d'une donnée versionnée générique (voir [VersionedRecord]). Versions immuables. */
interface RecordVersions<T : VersionedRecord<T>> {
    suspend fun current(key: String): T?
    suspend fun version(key: String, version: Int): T?

    /** Version courante de chaque donnée (actives et supprimées). */
    suspend fun allCurrent(): List<T>

    /** Ajoute une version. Échoue si `(key, version)` existe déjà. */
    suspend fun insert(record: T)
}

interface DailyEstimateVersions : RecordVersions<DailyEstimate> {
    /**
     * Toutes les versions (anciennes et supprimées comprises) des estimations des jours de
     * [from] à [to] : les entrées remplacées le restent même si l'estimation est supprimée.
     */
    suspend fun allVersionsBetween(from: LocalDate, to: LocalDate): List<DailyEstimate>
}

interface DayTrackingVersions : RecordVersions<DayTracking> {
    /** Version courante du suivi des jours de [from] à [to]. */
    suspend fun currentBetween(from: LocalDate, to: LocalDate): List<DayTracking>
}

/** Informations déclarées de la semaine (bilan du lundi), clé : le lundi de la semaine. */
interface WeekInfoVersions : RecordVersions<WeekInfo>

/** Objectif de poids (prévision d'arrivée), une seule clé. */
interface WeightGoalVersions : RecordVersions<WeightGoal>

/** Poids de référence, une seule clé ; même table que l'objectif de poids dans Room, jamais mélangés (lecture par clé). */
interface ReferenceWeightVersions : RecordVersions<ReferenceWeight>

interface ProductVersions : RecordVersions<Product>
interface RecipeVersions : RecordVersions<Recipe>
interface PreferenceVersions : RecordVersions<Preference>
interface ProvisionVersions : RecordVersions<Provision>

/**
 * Document d'origine d'une donnée reprise (export JSON d'un suivi antérieur), conservé tel quel :
 * rien n'est perdu, même les champs que l'app n'utilise pas. Clé `(kind, key, version)`.
 * [sha256] est l'empreinte du JSON canonique (clés triées) : même empreinte = même contenu.
 */
data class ImportedSource(
    val kind: String,
    val key: String,
    val version: Int,
    val sha256: String,
    val json: String,
    val actionId: ActionId,
)

/** Registre des documents repris : base de l'idempotence de la reprise. */
interface ImportLedger {
    suspend fun get(kind: String, key: String, version: Int): ImportedSource?

    /** Version la plus haute reprise pour cette donnée. */
    suspend fun latest(kind: String, key: String): ImportedSource?

    suspend fun insert(source: ImportedSource)

    suspend fun count(): Int
}
