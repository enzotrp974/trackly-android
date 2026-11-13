package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.core.time.TrackingDay
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Historique local du chat : texte et cartes relus tels quels, dans l'ordre. */
@RunWith(AndroidJUnit4::class)
class ChatHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val history = RoomChatHistory(db.chat())
    private val at = Instant.parse("2026-11-02T15:10:00Z")
    private val day = LocalDate.parse("2026-11-02")

    @After
    fun close() {
        db.close()
    }

    private val card = RecordCard(
        actionId = 7,
        kind = RecordKind.Added,
        items = listOf(
            CardItem("e1", "Barre protéinée Nuvo", "55 g", "snack", "2026-11-02", 222.0, 19.6, 12.0, 7.7),
            CardItem("e2", "Pomme", "150 g", "snack", "2026-11-02", 78.0, 0.4, 19.0, null),
        ),
        assumptions = listOf("Nuvo chocolat-cacahuète"),
    )

    @Test
    fun `messages et cartes relus dans l ordre`() = runBlocking {
        history.append(at, ChatRole.User, "comme snack une barre Nuvo et une pomme, 150 g")
        history.append(at.plusSeconds(4), ChatRole.Assistant, "C’est noté.", listOf(card, UndoCard(8, 7, "Annulation : Ajout")))

        val messages = history.observe(day).first()

        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), messages.map { it.role })
        assertEquals(listOf(card, UndoCard(8, 7, "Annulation : Ajout")), messages[1].cards)
        assertEquals(at.plusSeconds(4), messages[1].at)
        // Macro inconnue : total incomplet, jamais inventé.
        assertTrue(!(messages[1].cards[0] as RecordCard).fat.complete)
    }

    @Test
    fun `le modele relit le texte et le resume des cartes, avec les identifiants`() = runBlocking {
        history.append(at, ChatRole.Assistant, "C’est noté.", listOf(card))

        val sent = history.last(day, 10).single().toHistory()

        assertEquals(HistoryMessage.Role.Assistant, sent.role)
        assertTrue(sent.text.startsWith("C’est noté.\n[Enregistré par l'action 7 : Barre protéinée Nuvo (55 g), 222 kcal, Snack du 2026-11-02, entry_id e1"))
    }

    @Test
    fun `seuls les derniers messages sont lus`() = runBlocking {
        repeat(5) { history.append(at.plusSeconds(it.toLong()), ChatRole.User, "message $it") }
        assertEquals(listOf("message 3", "message 4"), history.last(day, 2).map { it.text })
    }

    // --- Fil du jour ------------------------------------------------------------------------

    private fun zurich(local: String): Instant = LocalDateTime.parse(local).atZone(TrackingDay.ZONE).toInstant()

    private val yesterday = LocalDate.parse("2026-11-01")

    private fun rowCount(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM chat_messages").use { c ->
        c.moveToFirst()
        c.getInt(0)
    }

    @Test
    fun `le fil ne montre que les messages de la date de rattachement demandee`() = runBlocking {
        history.append(zurich("2026-11-01T22:30"), ChatRole.User, "hier soir")
        history.append(zurich("2026-11-02T04:59"), ChatRole.User, "juste avant 5 h")
        history.append(zurich("2026-11-02T05:00"), ChatRole.User, "à 5 h pile")
        history.append(zurich("2026-11-02T12:00"), ChatRole.Assistant, "midi")

        assertEquals(listOf("hier soir", "juste avant 5 h"), history.last(yesterday, 10).map { it.text })
        assertEquals(listOf("à 5 h pile", "midi"), history.last(day, 10).map { it.text })
        assertEquals(listOf("à 5 h pile", "midi"), history.observe(day).first().map { it.text })
        assertTrue(history.last(LocalDate.parse("2026-11-03"), 10).isEmpty())
    }

    @Test
    fun `les anciens messages restent en base apres le passage de 5 h`() = runBlocking {
        history.append(zurich("2026-11-01T22:30"), ChatRole.User, "hier soir")
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "ce matin")

        assertEquals(listOf("ce matin"), history.last(day, 10).map { it.text })
        assertEquals(2, rowCount())
    }

    @Test
    fun `effacer masque le fil du jour sans supprimer aucune ligne`() = runBlocking {
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "ce matin")
        history.append(zurich("2026-11-02T08:01"), ChatRole.Assistant, "noté", listOf(card))

        val hidden = history.clear(day, zurich("2026-11-02T09:00"))

        assertEquals(2, hidden)
        assertTrue(history.last(day, 10).isEmpty())
        assertTrue(history.observe(day).first().isEmpty())
        assertEquals("aucune ligne supprimée", 2, rowCount())
    }

    @Test
    fun `effacer ne touche pas aux autres jours`() = runBlocking {
        history.append(zurich("2026-11-01T22:30"), ChatRole.User, "hier soir")
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "ce matin")

        history.clear(day, zurich("2026-11-02T09:00"))

        assertEquals(listOf("hier soir"), history.last(yesterday, 10).map { it.text })
    }

    @Test
    fun `effacer se rétablit côté données, avec le même horodatage`() = runBlocking {
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "ce matin")
        history.append(zurich("2026-11-02T08:01"), ChatRole.Assistant, "noté", listOf(card))
        val clearedAt = zurich("2026-11-02T09:00")
        history.clear(day, clearedAt)

        val restored = history.restore(clearedAt)

        assertEquals(2, restored)
        assertEquals(listOf("ce matin", "noté"), history.last(day, 10).map { it.text })
        assertEquals(listOf(card), history.last(day, 10).last().cards)
    }

    @Test
    fun `un deuxieme effacement ne masque que ce qui a ete ecrit depuis le premier`() = runBlocking {
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "avant le premier effacement")
        val first = zurich("2026-11-02T09:00")
        history.clear(day, first)
        history.append(zurich("2026-11-02T10:00"), ChatRole.User, "entre les deux")
        val second = zurich("2026-11-02T11:00")

        assertEquals(1, history.clear(day, second))
        // Rétablir le dernier effacement ne ressort pas ce que le premier avait masqué.
        assertEquals(1, history.restore(second))
        assertEquals(listOf("entre les deux"), history.last(day, 10).map { it.text })
    }

    @Test
    fun `une reponse ecrite apres l effacement reste visible`() = runBlocking {
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "ce matin")
        history.clear(day, zurich("2026-11-02T08:03"))

        history.append(zurich("2026-11-02T08:05"), ChatRole.Assistant, "ma réponse en cours")

        assertEquals(listOf("ma réponse en cours"), history.last(day, 10).map { it.text })
    }

    @Test
    fun `une reponse terminee apres 5 h rejoint le fil du nouveau jour au lieu de se perdre`() = runBlocking {
        history.append(zurich("2026-11-02T04:58"), ChatRole.User, "deux oeufs")
        val replyId = history.append(zurich("2026-11-02T04:59"), ChatRole.Assistant, "", listOf(card))

        history.update(replyId, "C’est noté.", listOf(card), zurich("2026-11-02T05:01"))

        assertEquals(listOf("deux oeufs"), history.last(yesterday, 10).map { it.text })
        assertEquals(listOf("C’est noté."), history.last(day, 10).map { it.text })
        assertEquals(listOf(card), history.last(day, 10).single().cards)
    }

    @Test
    fun `une reponse completee apres l effacement redevient visible avec sa carte`() = runBlocking {
        history.append(zurich("2026-11-02T08:00"), ChatRole.User, "deux oeufs")
        val replyId = history.append(zurich("2026-11-02T08:00"), ChatRole.Assistant, "", listOf(card))
        history.clear(day, zurich("2026-11-02T08:01"))
        assertTrue(history.last(day, 10).isEmpty())

        history.update(replyId, "C’est noté.", listOf(card), zurich("2026-11-02T08:02"))

        // Sans cela, la carte (et son bouton Annuler) d'un repas bel et bien enregistré disparaîtrait du chat.
        val shown = history.last(day, 10).single()
        assertEquals("C’est noté.", shown.text)
        assertEquals(listOf(card), shown.cards)
    }
}
