package app.trackly.storage.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Historique local du chat (version 3) : texte seulement, et cartes en JSON (données
 * structurées déjà validées). Aucune photo n'est stockée.
 *
 * Fil du jour (version 10) : [day] rattache chaque message à une date de rattachement (règle des 5 h) et
 * [hiddenAt] marque le message masqué par « Effacer la discussion ». Aucune ligne n'est jamais supprimée : le chat
 * ne lit que le fil du jour, et la sauvegarde copie toute la table.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** `user` ou `assistant`. */
    val role: String,
    val text: String,
    /** Cartes du message, en tableau JSON (voir `ChatCards`). */
    @ColumnInfo(defaultValue = "[]") val cards: String,
    /** Date de rattachement du message (`AAAA-MM-JJ`) : le fil du jour auquel il appartient. */
    val day: String? = null,
    /** Instant (ms) où « Effacer la discussion » a masqué ce message, ou `null` s'il est visible. */
    @ColumnInfo(name = "hidden_at") val hiddenAt: Long? = null,
)

@Dao
interface ChatDao {
    /** Les [limit] derniers messages visibles du fil [day], du plus ancien au plus récent. */
    @Query("SELECT * FROM (SELECT * FROM chat_messages WHERE day = :day AND hidden_at IS NULL ORDER BY id DESC LIMIT :limit) ORDER BY id")
    fun observeDay(day: String, limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM chat_messages WHERE day = :day AND hidden_at IS NULL ORDER BY id DESC LIMIT :limit) ORDER BY id")
    suspend fun lastOfDay(day: String, limit: Int): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /**
     * Réponse de l'assistant complétée en fin de tour (ses cartes sont écrites dès qu'elles sont prêtes). Elle suit le
     * fil du jour [day] au moment où elle est écrite : une réponse qui arrive après 5 h rejoint le nouveau fil. Une réponse
     * complétée après « Effacer la discussion » redevient visible : sinon la carte (et son « Annuler ») d'un repas bel et
     * bien enregistré disparaîtrait du chat.
     */
    @Query("UPDATE chat_messages SET text = :text, cards = :cards, day = :day, hidden_at = NULL WHERE id = :id")
    suspend fun update(id: Long, text: String, cards: String, day: String)

    /** Masque les messages encore visibles du fil [day] ; renvoie leur nombre. Aucune ligne n'est supprimée. */
    @Query("UPDATE chat_messages SET hidden_at = :at WHERE day = :day AND hidden_at IS NULL")
    suspend fun hide(day: String, at: Long): Int

    /** Rétablit ce que l'effacement daté de [at] avait masqué ; renvoie le nombre de messages remis. */
    @Query("UPDATE chat_messages SET hidden_at = NULL WHERE hidden_at = :at")
    suspend fun unhide(at: Long): Int
}
