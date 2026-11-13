package app.trackly.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Version courante = version la plus haute de chaque `entry_id`. */
private const val CURRENT_CONSUMPTION =
    "c.version = (SELECT MAX(v.version) FROM consumption_versions v WHERE v.entry_id = c.entry_id)"

@Dao
interface ConsumptionDao {
    @Query("SELECT * FROM consumption_versions WHERE entry_id = :entryId ORDER BY version DESC LIMIT 1")
    suspend fun current(entryId: String): ConsumptionEntity?

    @Query("SELECT * FROM consumption_versions WHERE entry_id = :entryId AND version = :version")
    suspend fun version(entryId: String, version: Int): ConsumptionEntity?

    @Query("SELECT * FROM consumption_versions WHERE entry_id = :entryId ORDER BY version")
    suspend fun history(entryId: String): List<ConsumptionEntity>

    @Query("SELECT * FROM consumption_versions c WHERE c.state = 'active' AND c.day BETWEEN :from AND :to AND $CURRENT_CONSUMPTION")
    suspend fun activeBetween(from: String, to: String): List<ConsumptionEntity>

    @Query("SELECT * FROM consumption_versions c WHERE c.state = 'active' AND c.day BETWEEN :from AND :to AND $CURRENT_CONSUMPTION")
    fun observeActiveBetween(from: String, to: String): Flow<List<ConsumptionEntity>>

    @Query("SELECT * FROM consumption_versions c WHERE c.state = 'active' AND c.product_id = :productId AND $CURRENT_CONSUMPTION")
    suspend fun activeForProduct(productId: String): List<ConsumptionEntity>

    @Query("SELECT MIN(c.day) FROM consumption_versions c WHERE c.state = 'active' AND $CURRENT_CONSUMPTION")
    fun observeFirstActiveDay(): Flow<String?>

    /** Une version existante n'est jamais remplacée : le doublon fait échouer la transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConsumptionEntity)
}

private const val CURRENT_GOAL =
    "g.version = (SELECT MAX(v.version) FROM goal_versions v WHERE v.effective_from = g.effective_from)"

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal_versions WHERE effective_from = :from ORDER BY version DESC LIMIT 1")
    suspend fun current(from: String): GoalEntity?

    @Query("SELECT * FROM goal_versions WHERE effective_from = :from AND version = :version")
    suspend fun version(from: String, version: Int): GoalEntity?

    @Query("SELECT * FROM goal_versions g WHERE $CURRENT_GOAL ORDER BY g.effective_from")
    suspend fun allCurrent(): List<GoalEntity>

    @Query("SELECT * FROM goal_versions g WHERE $CURRENT_GOAL ORDER BY g.effective_from")
    fun observeAllCurrent(): Flow<List<GoalEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GoalEntity)
}

@Dao
interface ActionDao {
    @Insert
    suspend fun insert(action: ActionEntity): Long

    @Insert
    suspend fun insertEffects(effects: List<ActionEffectEntity>)

    @Query("UPDATE actions SET title = :title, undoes_action_id = :undoes WHERE id = :id")
    suspend fun complete(id: Long, title: String, undoes: Long?)

    @Query("UPDATE actions SET undone_by_action_id = :by WHERE id = :id")
    suspend fun markUndone(id: Long, by: Long)

    @Transaction
    @Query("SELECT * FROM actions WHERE id = :id")
    suspend fun get(id: Long): ActionWithEffects?

    @Transaction
    @Query(
        """SELECT * FROM actions a
        WHERE a.undone_by_action_id IS NULL
          AND EXISTS (SELECT 1 FROM action_effects e WHERE e.action_id = a.id)
          AND (:before IS NULL OR a.id < :before)
        ORDER BY a.id DESC LIMIT :limit""",
    )
    suspend fun notUndone(before: Long?, limit: Int): List<ActionWithEffects>

    @Query("SELECT EXISTS(SELECT 1 FROM actions WHERE type = :type AND id < :before)")
    suspend fun existsOfType(type: String, before: Long): Boolean

    @Transaction
    @Query("SELECT * FROM actions ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionWithEffects>>
}
