package app.trackly.assistant

import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatPrefill
import app.trackly.assistant.chat.ChatReturn
import app.trackly.assistant.chat.ChatRole
import app.trackly.core.model.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** « + » qui comprend le contexte et retour au jour de la consommation. */
class ChatOpeningTest {
    private val today = LocalDate.parse("2026-11-03")

    @Test
    fun `aujourd hui - rien pour le plus du bas, le moment pour le plus d un moment`() {
        assertNull(ChatPrefill.of(today, today, null))
        assertEquals("Ce matin : ", ChatPrefill.of(today, today, Moment.Matin))
        assertEquals("À midi : ", ChatPrefill.of(today, today, Moment.Midi))
        assertEquals("En snack : ", ChatPrefill.of(today, today, Moment.Snack))
        assertEquals("Ce soir : ", ChatPrefill.of(today, today, Moment.Soir))
    }

    @Test
    fun `jour passe - la date en toutes lettres, avec le moment touche`() {
        val sunday = LocalDate.parse("2026-11-01")
        assertEquals("Pour dimanche 1er novembre : ", ChatPrefill.of(sunday, today, null))
        assertEquals("Pour dimanche 1er novembre, au matin : ", ChatPrefill.of(sunday, today, Moment.Matin))
        assertEquals("Pour dimanche 1er novembre, le soir : ", ChatPrefill.of(sunday, today, Moment.Soir))
        assertEquals("Pour mercredi 31 décembre 2025 : ", ChatPrefill.of(LocalDate.parse("2025-12-31"), today, null))
    }

    private fun record(id: Long, kind: RecordKind, day: String) = RecordCard(
        actionId = id, kind = kind,
        items = listOf(CardItem("e$id", "Aliment $id", moment = "midi", day = day, kcal = 100.0)),
    )

    private fun reply(id: Long, vararg cards: RecordCard) = ChatMessage(id, Instant.EPOCH, ChatRole.Assistant, "", cards.toList())

    @Test
    fun `retour au jour de la derniere consommation enregistree pendant le chat, si elle compte encore`() {
        val messages = listOf(
            reply(1, record(10, RecordKind.Added, "2026-10-20")),
            reply(2, record(11, RecordKind.Added, "2026-11-01")),
            reply(3, record(12, RecordKind.Added, "2026-11-02")),
            reply(4, record(13, RecordKind.Deleted, "2026-10-30")),
        )
        // Le message 1 date d'avant l'ouverture du chat ; la suppression ne déplace pas l'écran.
        assertEquals(LocalDate.parse("2026-11-02"), ChatReturn.dayAfter(messages, sinceId = 2, undone = emptySet()))
        // La dernière a été annulée : on revient au jour de celle d'avant.
        assertEquals(LocalDate.parse("2026-11-01"), ChatReturn.dayAfter(messages, sinceId = 2, undone = setOf(12L)))
        // Rien d'enregistré pendant cette ouverture : l'écran ne bouge pas.
        assertNull(ChatReturn.dayAfter(messages, sinceId = 5, undone = emptySet()))
    }

    @Test
    fun `premier message de l ouverture - attend que l historique soit charge`() {
        val old = listOf(reply(7, record(10, RecordKind.Added, "2026-10-20")))
        // Liste encore vide parce que l'historique n'est pas lu : aucune borne, sinon tout compterait.
        assertNull(ChatReturn.firstNewId(historyLoaded = false, messages = emptyList()))
        assertNull(ChatReturn.firstNewId(historyLoaded = false, messages = old))
        // Chargé : le message suivant le dernier ; historique vraiment vide : le premier.
        assertEquals(8L, ChatReturn.firstNewId(historyLoaded = true, messages = old))
        assertEquals(1L, ChatReturn.firstNewId(historyLoaded = true, messages = emptyList()))
    }
}
