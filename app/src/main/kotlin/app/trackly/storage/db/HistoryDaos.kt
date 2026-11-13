package app.trackly.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Version courante = version la plus haute de chaque clé. Une version existante n'est jamais
// remplacée : un doublon fait échouer la transaction (ABORT).

@Dao
interface DailyEstimateDao {
    @Query("SELECT * FROM daily_estimate_versions WHERE assessment_id = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): DailyEstimateEntity?

    @Query("SELECT * FROM daily_estimate_versions WHERE assessment_id = :key AND version = :version")
    suspend fun version(key: String, version: Int): DailyEstimateEntity?

    @Query(
        "SELECT * FROM daily_estimate_versions e WHERE e.version = " +
            "(SELECT MAX(v.version) FROM daily_estimate_versions v WHERE v.assessment_id = e.assessment_id)",
    )
    suspend fun allCurrent(): List<DailyEstimateEntity>

    @Query("SELECT * FROM daily_estimate_versions WHERE day BETWEEN :from AND :to")
    suspend fun allVersionsBetween(from: String, to: String): List<DailyEstimateEntity>

    @Query("SELECT * FROM daily_estimate_versions WHERE day BETWEEN :from AND :to")
    fun observeAllVersionsBetween(from: String, to: String): Flow<List<DailyEstimateEntity>>

    @Query("SELECT MIN(day) FROM daily_estimate_versions")
    fun observeFirstDay(): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DailyEstimateEntity)
}

private const val CURRENT_TRACKING =
    "t.version = (SELECT MAX(v.version) FROM day_tracking_versions v WHERE v.day = t.day)"

@Dao
interface DayTrackingDao {
    @Query("SELECT * FROM day_tracking_versions WHERE day = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): DayTrackingEntity?

    @Query("SELECT * FROM day_tracking_versions WHERE day = :key AND version = :version")
    suspend fun version(key: String, version: Int): DayTrackingEntity?

    @Query("SELECT * FROM day_tracking_versions t WHERE $CURRENT_TRACKING")
    suspend fun allCurrent(): List<DayTrackingEntity>

    @Query("SELECT * FROM day_tracking_versions t WHERE t.day BETWEEN :from AND :to AND $CURRENT_TRACKING")
    suspend fun currentBetween(from: String, to: String): List<DayTrackingEntity>

    @Query("SELECT * FROM day_tracking_versions t WHERE t.day BETWEEN :from AND :to AND $CURRENT_TRACKING")
    fun observeCurrentBetween(from: String, to: String): Flow<List<DayTrackingEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DayTrackingEntity)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM product_versions WHERE product_id = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): ProductEntity?

    @Query("SELECT * FROM product_versions WHERE product_id = :key AND version = :version")
    suspend fun version(key: String, version: Int): ProductEntity?

    @Query(
        "SELECT * FROM product_versions p WHERE p.version = " +
            "(SELECT MAX(v.version) FROM product_versions v WHERE v.product_id = p.product_id) ORDER BY p.name",
    )
    suspend fun allCurrent(): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProductEntity)
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipe_versions WHERE recipe_id = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): RecipeEntity?

    @Query("SELECT * FROM recipe_versions WHERE recipe_id = :key AND version = :version")
    suspend fun version(key: String, version: Int): RecipeEntity?

    @Query(
        "SELECT * FROM recipe_versions r WHERE r.version = " +
            "(SELECT MAX(v.version) FROM recipe_versions v WHERE v.recipe_id = r.recipe_id) ORDER BY r.name",
    )
    suspend fun allCurrent(): List<RecipeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RecipeEntity)
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preference_versions WHERE preference_id = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): PreferenceEntity?

    @Query("SELECT * FROM preference_versions WHERE preference_id = :key AND version = :version")
    suspend fun version(key: String, version: Int): PreferenceEntity?

    @Query(
        "SELECT * FROM preference_versions p WHERE p.version = " +
            "(SELECT MAX(v.version) FROM preference_versions v WHERE v.preference_id = p.preference_id)",
    )
    suspend fun allCurrent(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PreferenceEntity)
}

@Dao
interface ProvisionDao {
    @Query("SELECT * FROM provision_versions WHERE provision_id = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): ProvisionEntity?

    @Query("SELECT * FROM provision_versions WHERE provision_id = :key AND version = :version")
    suspend fun version(key: String, version: Int): ProvisionEntity?

    @Query(
        "SELECT * FROM provision_versions p WHERE p.version = " +
            "(SELECT MAX(v.version) FROM provision_versions v WHERE v.provision_id = p.provision_id) ORDER BY p.name",
    )
    suspend fun allCurrent(): List<ProvisionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProvisionEntity)
}

@Dao
interface ImportedSourceDao {
    @Query("SELECT * FROM imported_sources WHERE kind = :kind AND record_key = :key AND version = :version")
    suspend fun get(kind: String, key: String, version: Int): ImportedSourceEntity?

    @Query("SELECT * FROM imported_sources WHERE kind = :kind AND record_key = :key ORDER BY version DESC LIMIT 1")
    suspend fun latest(kind: String, key: String): ImportedSourceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ImportedSourceEntity)

    @Query("SELECT COUNT(*) FROM imported_sources")
    suspend fun count(): Int
}
