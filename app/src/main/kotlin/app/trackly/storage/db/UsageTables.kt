package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Compteur de coût local (version 6) : une ligne par requête à OpenAI (texte) ou par
 * transcription. Les jetons sont gardés tels que renvoyés ; les montants (micro-dollars) sont
 * calculés à l'enregistrement avec la table de prix du jour et ne sont jamais recalculés.
 * Aucune donnée de repas ni de message : seulement des compteurs.
 */
@Entity(tableName = "usage_events", indices = [Index(value = ["recorded_at"])])
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Instant d'enregistrement, en millisecondes depuis 1970 (UTC). */
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    /** `text` (Responses API), `transcription` ou `live` (appel vocal, coût dans [transcriptionMicros]). */
    val kind: String,
    val model: String,
    /** Niveau de raisonnement demandé (`low`…`max`) ; `null` pour une transcription. */
    val effort: String?,
    /** Rang de la requête dans le message : 0 pour la première, 1+ après un appel d'outil. */
    @ColumnInfo(name = "tool_round") val toolRound: Int,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Long,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Long,
    @ColumnInfo(name = "web_searches") val webSearches: Int,
    /** Durée d'audio transcrit, ou durée d'un appel, en millisecondes (0 pour du texte). */
    @ColumnInfo(name = "audio_ms") val audioMs: Long,
    @ColumnInfo(name = "text_micros") val textMicros: Long,
    @ColumnInfo(name = "search_micros") val searchMicros: Long,
    @ColumnInfo(name = "transcription_micros") val transcriptionMicros: Long,
    /** Faux si le modèle n'avait pas de prix connu (coût du texte alors compté 0). */
    val priced: Boolean,
)

/** Totaux d'une période, calculés par la base. */
data class UsageTotalsRow(
    val textMicros: Long,
    val searchMicros: Long,
    val transcriptionMicros: Long,
    /** Appels vocaux (lignes `live`, dont le coût est rangé dans la colonne de la transcription). */
    val liveMicros: Long,
    /** Messages de l'utilisateur ayant coûté une requête (premières requêtes de chaque message). */
    val exchanges: Int,
    val requests: Int,
    val dictations: Int,
    val calls: Int,
    val unpriced: Int,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(event: UsageEventEntity): Long

    /** Totaux des événements de [from] (inclus) à [to] (exclu), en millisecondes. */
    @Query(
        "SELECT COALESCE(SUM(text_micros), 0) AS textMicros, COALESCE(SUM(search_micros), 0) AS searchMicros, " +
            "COALESCE(SUM(CASE WHEN kind = 'live' THEN 0 ELSE transcription_micros END), 0) AS transcriptionMicros, " +
            "COALESCE(SUM(CASE WHEN kind = 'live' THEN transcription_micros ELSE 0 END), 0) AS liveMicros, " +
            "COALESCE(SUM(CASE WHEN kind = 'text' AND tool_round = 0 THEN 1 ELSE 0 END), 0) AS exchanges, " +
            "COALESCE(SUM(CASE WHEN kind = 'text' THEN 1 ELSE 0 END), 0) AS requests, " +
            "COALESCE(SUM(CASE WHEN kind = 'transcription' THEN 1 ELSE 0 END), 0) AS dictations, " +
            "COALESCE(SUM(CASE WHEN kind = 'live' THEN 1 ELSE 0 END), 0) AS calls, " +
            "COALESCE(SUM(CASE WHEN priced = 0 THEN 1 ELSE 0 END), 0) AS unpriced " +
            "FROM usage_events WHERE recorded_at >= :from AND recorded_at < :to",
    )
    fun observeTotals(from: Long, to: Long): Flow<UsageTotalsRow>

    @Query("SELECT * FROM usage_events ORDER BY id")
    suspend fun all(): List<UsageEventEntity>
}
