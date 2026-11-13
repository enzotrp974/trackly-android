package app.trackly.review

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.storage.db.ReviewDao
import app.trackly.storage.db.ReviewMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * Historique local de la conversation de bilan : un fil **par semaine**, distinct du chat de saisie.
 * Texte et cartes seulement ; les captures d'écran envoyées ne sont jamais conservées.
 */
interface ReviewHistory {
    fun observe(week: LocalDate, limit: Int = 200): Flow<List<ChatMessage>>

    suspend fun last(week: LocalDate, limit: Int): List<ChatMessage>

    suspend fun append(week: LocalDate, at: Instant, role: ChatRole, text: String, cards: List<ChatCard> = emptyList()): Long

    /** Premier jour du fil de bilan commencé le plus récemment (au moins un message), ou `null` s'il n'y en a aucun. */
    suspend fun latestStart(): LocalDate?
}

class RoomReviewHistory(private val dao: ReviewDao) : ReviewHistory {
    override fun observe(week: LocalDate, limit: Int): Flow<List<ChatMessage>> =
        dao.observeLast(week.toString(), limit).map { rows -> rows.map(::toModel) }

    override suspend fun last(week: LocalDate, limit: Int): List<ChatMessage> = dao.last(week.toString(), limit).map(::toModel)

    override suspend fun append(week: LocalDate, at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long =
        dao.insert(
            ReviewMessageEntity(weekStart = week.toString(), createdAt = at.toEpochMilli(), role = role.code, text = text, cards = ChatCards.encode(cards)),
        )

    override suspend fun latestStart(): LocalDate? = dao.latestWeek()?.let(LocalDate::parse)

    private fun toModel(e: ReviewMessageEntity) = ChatMessage(
        id = e.id,
        at = Instant.ofEpochMilli(e.createdAt),
        role = ChatRole.fromCode(e.role),
        text = e.text,
        cards = ChatCards.decode(e.cards),
    )
}

class InMemoryReviewHistory : ReviewHistory {
    private val messages = MutableStateFlow<List<Pair<LocalDate, ChatMessage>>>(emptyList())

    override fun observe(week: LocalDate, limit: Int): Flow<List<ChatMessage>> =
        messages.map { list -> list.filter { it.first == week }.map { it.second }.takeLast(limit) }

    override suspend fun last(week: LocalDate, limit: Int) = messages.value.filter { it.first == week }.map { it.second }.takeLast(limit)

    override suspend fun append(week: LocalDate, at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long {
        val id = (messages.value.maxOfOrNull { it.second.id } ?: 0) + 1
        messages.value = messages.value + (week to ChatMessage(id, at, role, text, cards))
        return id
    }

    override suspend fun latestStart(): LocalDate? = messages.value.maxOfOrNull { it.first }
}
