package app.trackly.assistant.chat

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.core.time.TrackingDay
import app.trackly.storage.db.ChatDao
import app.trackly.storage.db.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

enum class ChatRole(val code: String) {
    User("user"),
    Assistant("assistant"),
    ;

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code } ?: Assistant
    }
}

/** Message conservé dans l'historique local du chat : texte et cartes, jamais de photo. */
data class ChatMessage(
    val id: Long,
    val at: Instant,
    val role: ChatRole,
    val text: String,
    val cards: List<ChatCard> = emptyList(),
) {
    /** Nombre de photos annoncées par le repère « 1 photo envoyée » du message de l'utilisateur. */
    val photoCount: Int get() = if (role == ChatRole.User) PhotoMarker.split(text).second else 0

    /** Texte à afficher dans la bulle : le message sans le repère des photos. */
    val shownText: String get() = if (role == ChatRole.User) PhotoMarker.split(text).first else text

    /** Forme envoyée au modèle aux tours suivants : le texte, puis le résumé des cartes. */
    fun toHistory(): HistoryMessage = HistoryMessage(
        role = if (role == ChatRole.User) HistoryMessage.Role.User else HistoryMessage.Role.Assistant,
        text = (listOf(text) + cards.map { it.summaryForModel() }).filter { it.isNotBlank() }.joinToString("\n"),
    )
}

/**
 * Historique local du chat. Chaque message appartient à un **fil du jour** : la date de rattachement de l'instant où il
 * est écrit (règle des 5 h, [TrackingDay]). Le chat n'affiche ni n'envoie au modèle que le fil du jour courant ; les
 * autres restent en base, sans interface, et « Effacer la discussion » ([clear]) masque un fil sans supprimer de ligne.
 */
interface ChatHistory {
    /** Les [limit] derniers messages visibles du fil [day], du plus ancien au plus récent. */
    fun observe(day: LocalDate, limit: Int = DISPLAY_LIMIT): Flow<List<ChatMessage>>

    suspend fun last(day: LocalDate, limit: Int): List<ChatMessage>

    /** Écrit un message dans le fil de l'instant [at] ([TrackingDay.of]). */
    suspend fun append(at: Instant, role: ChatRole, text: String, cards: List<ChatCard> = emptyList()): Long

    /**
     * Remplace le texte et les cartes du message [id] : la réponse d'un tour est écrite dès sa première carte,
     * puis complétée (cartes suivantes, texte final). Le message suit le fil de l'instant [at] : une réponse qui arrive
     * après 5 h rejoint le nouveau fil, où l'utilisateur la voit ; si elle avait été masquée entre-temps par [clear], elle
     * redevient visible (sa carte « Annuler » ne disparaît pas). Sans effet si le message n'existe pas.
     */
    suspend fun update(id: Long, text: String, cards: List<ChatCard>, at: Instant)

    /**
     * Masque les messages encore visibles du fil [day] (« Effacer la discussion »), à l'instant [at]. Aucune ligne n'est
     * supprimée. Renvoie le nombre de messages masqués.
     */
    suspend fun clear(day: LocalDate, at: Instant): Int

    /** Rétablit ce que l'effacement fait à l'instant [clearedAt] avait masqué. Renvoie le nombre de messages remis. */
    suspend fun restore(clearedAt: Instant): Int

    companion object {
        const val DISPLAY_LIMIT = 200
    }
}

class RoomChatHistory(private val dao: ChatDao) : ChatHistory {
    override fun observe(day: LocalDate, limit: Int): Flow<List<ChatMessage>> =
        dao.observeDay(day.toString(), limit).map { rows -> rows.map(::toModel) }

    override suspend fun last(day: LocalDate, limit: Int): List<ChatMessage> = dao.lastOfDay(day.toString(), limit).map(::toModel)

    override suspend fun append(at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long = dao.insert(
        ChatMessageEntity(
            createdAt = at.toEpochMilli(), role = role.code, text = text, cards = ChatCards.encode(cards),
            day = TrackingDay.of(at).toString(),
        ),
    )

    override suspend fun update(id: Long, text: String, cards: List<ChatCard>, at: Instant) =
        dao.update(id, text, ChatCards.encode(cards), TrackingDay.of(at).toString())

    override suspend fun clear(day: LocalDate, at: Instant): Int = dao.hide(day.toString(), at.toEpochMilli())

    override suspend fun restore(clearedAt: Instant): Int = dao.unhide(clearedAt.toEpochMilli())

    private fun toModel(e: ChatMessageEntity) = ChatMessage(
        id = e.id,
        at = Instant.ofEpochMilli(e.createdAt),
        role = ChatRole.fromCode(e.role),
        text = e.text,
        cards = ChatCards.decode(e.cards),
    )
}

/** Historique en mémoire : tests et aperçus. */
class InMemoryChatHistory : ChatHistory {
    private data class Row(val message: ChatMessage, val day: LocalDate, val hiddenAt: Instant? = null)

    private val rows = MutableStateFlow<List<Row>>(emptyList())

    private fun visible(day: LocalDate, limit: Int) = rows.value.filter { it.day == day && it.hiddenAt == null }.map { it.message }.takeLast(limit)

    override fun observe(day: LocalDate, limit: Int): Flow<List<ChatMessage>> = rows.map { visible(day, limit) }

    override suspend fun last(day: LocalDate, limit: Int): List<ChatMessage> = visible(day, limit)

    override suspend fun append(at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long {
        val id = (rows.value.lastOrNull()?.message?.id ?: 0) + 1
        rows.value = rows.value + Row(ChatMessage(id, at, role, text, cards), TrackingDay.of(at))
        return id
    }

    override suspend fun update(id: Long, text: String, cards: List<ChatCard>, at: Instant) {
        rows.value = rows.value.map { if (it.message.id == id) it.copy(message = it.message.copy(text = text, cards = cards), day = TrackingDay.of(at), hiddenAt = null) else it }
    }

    override suspend fun clear(day: LocalDate, at: Instant): Int {
        val target = rows.value.count { it.day == day && it.hiddenAt == null }
        rows.value = rows.value.map { if (it.day == day && it.hiddenAt == null) it.copy(hiddenAt = at) else it }
        return target
    }

    override suspend fun restore(clearedAt: Instant): Int {
        val target = rows.value.count { it.hiddenAt == clearedAt }
        rows.value = rows.value.map { if (it.hiddenAt == clearedAt) it.copy(hiddenAt = null) else it }
        return target
    }
}
