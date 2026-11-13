package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Tables de la version 8 (bilan du lundi) ; version 11 : fin de période des rapports.

/**
 * Une version des informations déclarées d'une semaine (pesées, pas, séances), immuable comme les
 * autres données versionnées. Listes en JSON texte ; `sessions` est `NULL` quand elles n'ont pas été fournies.
 */
@Entity(tableName = "week_info_versions", primaryKeys = ["week_start", "version"])
data class WeekInfoEntity(
    @ColumnInfo(name = "week_start") val weekStart: String,
    val version: Int,
    val state: String,
    @ColumnInfo(name = "weigh_ins") val weighIns: String,
    val steps: String,
    @ColumnInfo(name = "steps_average") val stepsAverage: Int?,
    val sessions: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "action_id") val actionId: Long?,
)

@Dao
interface WeekInfoDao {
    @Query("SELECT * FROM week_info_versions WHERE week_start = :key ORDER BY version DESC LIMIT 1")
    suspend fun current(key: String): WeekInfoEntity?

    @Query("SELECT * FROM week_info_versions WHERE week_start = :key AND version = :version")
    suspend fun version(key: String, version: Int): WeekInfoEntity?

    @Query(
        "SELECT * FROM week_info_versions w WHERE w.version = " +
            "(SELECT MAX(v.version) FROM week_info_versions v WHERE v.week_start = w.week_start)",
    )
    suspend fun allCurrent(): List<WeekInfoEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WeekInfoEntity)
}

/**
 * Conversation de bilan : un fil par semaine. Texte et cartes seulement ; les captures d'écran
 * envoyées au modèle ne sont jamais conservées.
 */
@Entity(tableName = "review_messages", indices = [Index(value = ["week_start"])])
data class ReviewMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val role: String,
    val text: String,
    @ColumnInfo(defaultValue = "[]") val cards: String,
)

/**
 * Rapport HTML d'un bilan, enregistré dans la base privée de l'app (donc sauvegardé avec elle).
 * Jamais écrasé : une nouvelle génération ajoute une ligne.
 */
@Entity(tableName = "weekly_reports", indices = [Index(value = ["week_start"])])
data class WeeklyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    val html: String,
    /** Dernier jour de la période du bilan (version 11). `NULL` : bilan d'une semaine du lundi au dimanche, qui finit 6 jours après [weekStart]. */
    @ColumnInfo(name = "period_end") val periodEnd: String? = null,
)

/** Rapport sans son HTML (liste des bilans passés). */
data class WeeklyReportRow(
    val id: Long,
    @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "period_end") val periodEnd: String? = null,
)

@Dao
interface ReviewDao {
    @Query("SELECT * FROM (SELECT * FROM review_messages WHERE week_start = :week ORDER BY id DESC LIMIT :limit) ORDER BY id")
    fun observeLast(week: String, limit: Int): Flow<List<ReviewMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM review_messages WHERE week_start = :week ORDER BY id DESC LIMIT :limit) ORDER BY id")
    suspend fun last(week: String, limit: Int): List<ReviewMessageEntity>

    @Insert
    suspend fun insert(message: ReviewMessageEntity): Long

    /** Premier jour du fil commencé le plus récemment (dates ISO : l'ordre du texte est celui des dates), ou `null`. */
    @Query("SELECT MAX(week_start) FROM review_messages")
    suspend fun latestWeek(): String?
}

@Dao
interface WeeklyReportDao {
    @Insert
    suspend fun insert(report: WeeklyReportEntity): Long

    @Query("SELECT id, week_start, generated_at, period_end FROM weekly_reports ORDER BY week_start DESC, id DESC")
    fun observeRows(): Flow<List<WeeklyReportRow>>

    @Query("SELECT * FROM weekly_reports WHERE id = :id")
    suspend fun get(id: Long): WeeklyReportEntity?

    @Query("SELECT COUNT(*) FROM weekly_reports WHERE week_start = :week")
    suspend fun countForWeek(week: String): Int

    @Query("SELECT id FROM weekly_reports WHERE week_start = :week ORDER BY id DESC LIMIT 1")
    suspend fun latestIdForWeek(week: String): Long?

    /** Le bilan dont la période finit le plus tard (à fin égale, le dernier produit), sans son HTML. */
    @Query(
        "SELECT id, week_start, generated_at, period_end FROM weekly_reports " +
            "ORDER BY COALESCE(period_end, date(week_start, '+6 days')) DESC, id DESC LIMIT 1",
    )
    suspend fun latestRow(): WeeklyReportRow?
}
