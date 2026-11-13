package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Table de la version 11 (prévision d'arrivée), qui porte aussi le poids de référence, ajouté plus tard sans migration.

/**
 * Une version d'un poids daté, immuable comme les autres données versionnées. La table porte **deux données distinctes**,
 * chacune sous sa clé, que rien ne mélange (lecture toujours par clé, test `ReferenceWeightRestoreTest`) :
 *
 * - `weight_goal` : l'objectif de poids. `target_kg` = poids visé, `target_date` = date visée, `losing` = sens.
 *   Sans version active, l'objectif par défaut (`WeightTarget.Default`) s'applique.
 * - `reference_weight` : le poids de référence. `target_kg` = le poids de référence, `target_date` = le jour où il a pris
 *   effet, `losing` sans objet (toujours 1). Sans version active, celui du profil repris s'applique.
 */
@Entity(tableName = "weight_goal_versions", primaryKeys = ["goal_key", "version"])
data class WeightGoalEntity(
    @ColumnInfo(name = "goal_key") val goalKey: String,
    val version: Int,
    val state: String,
    @ColumnInfo(name = "target_kg") val targetKg: Double,
    /** `AAAA-MM-JJ`. */
    @ColumnInfo(name = "target_date") val targetDate: String,
    /** 1 : perte visée, 0 : prise visée. */
    val losing: Boolean,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

@Dao
interface WeightGoalDao {
    @Query("SELECT * FROM weight_goal_versions WHERE goal_key = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): WeightGoalEntity?

    @Query("SELECT * FROM weight_goal_versions WHERE goal_key = :key AND version = :version")
    suspend fun version(key: String, version: Int): WeightGoalEntity?

    /** Version courante de la donnée [key] seulement : jamais celle de l'autre clé de la table. */
    @Query(
        "SELECT * FROM weight_goal_versions w WHERE w.goal_key = :key AND w.version = " +
            "(SELECT MAX(v.version) FROM weight_goal_versions v WHERE v.goal_key = w.goal_key)",
    )
    suspend fun allCurrentOf(key: String): List<WeightGoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WeightGoalEntity)
}
