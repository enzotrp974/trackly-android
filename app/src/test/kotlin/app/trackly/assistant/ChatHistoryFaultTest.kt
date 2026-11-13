package app.trackly.assistant

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.InMemoryTurnMarkerStore
import app.trackly.assistant.chat.TurnMarker
import app.trackly.assistant.chat.recordedSince
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Pannes de la base de données du chat et annulation : jamais de plantage de l'app, jamais de chargement
 * qui ne finit pas, et l'annulation d'une coroutine n'est jamais avalée.
 */
class ChatHistoryFaultTest {
    private val engine: TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))

    /** Historique en mémoire dont certaines opérations peuvent être mises en panne. */
    private class FlakyHistory(private val inner: InMemoryChatHistory = InMemoryChatHistory()) : ChatHistory by inner {
        var failLast = false
        var failAppend = false

        override suspend fun last(day: LocalDate, limit: Int): List<ChatMessage> {
            if (failLast) throw IOException("disque plein : secret 600 kcal")
            return inner.last(day, limit)
        }

        override suspend fun append(at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long {
            if (failAppend) throw IOException("base verrouillée")
            return inner.append(at, role, text, cards)
        }
    }

    private val history = FlakyHistory()
    private val today = LocalDate.parse("2026-11-02")

    /** Attend la fin du tour, avec un délai : un `running` resté vrai fait échouer le test au lieu de le bloquer. */
    private suspend fun idle(turns: ChatTurns) {
        try {
            withTimeout(5_000) { turns.awaitIdle() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("le tour ne se termine pas : « running » est resté vrai")
        }
    }

    private val markers = InMemoryTurnMarkerStore()

    private fun turns(transport: ScriptedTransport = ScriptedTransport(ModelReplies.text("Noté."))) = ChatTurns(
        engine, history, TurnInterpreter(ResponsesClient(transport), engine),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), markers = markers,
    )

    @Test
    fun `envoi - lecture de l historique en panne - erreur humaine, pas de chargement sans fin, Reessayer remarche`() = runBlocking {
        val transport = ScriptedTransport(ModelReplies.text("Noté."))
        val turns = turns(transport)
        history.failLast = true

        assertTrue(turns.send("deux oeufs"))
        idle(turns)

        assertFalse(turns.state.value.running)
        val error = turns.state.value.error!!
        assertEquals("Un problème est survenu", error.notice.title)
        assertFalse("le détail technique n'est jamais montré", error.notice.body.contains("secret"))
        assertEquals("deux oeufs", error.retryText)
        assertEquals("rien n'est parti vers le modèle", 0, transport.requests.size)

        history.failLast = false
        assertTrue(turns.retry())
        idle(turns)
        assertNull(turns.state.value.error)
        assertEquals(1, transport.requests.size)
        // Le message qui n'avait pas pu être écrit l'est au renvoi, une seule fois.
        assertEquals(listOf("deux oeufs"), history.last(today, 10).filter { it.role == ChatRole.User }.map { it.text })
    }

    @Test
    fun `envoi - ecriture du message en panne - meme traitement`() = runBlocking {
        val turns = turns()
        history.failAppend = true

        turns.send("deux oeufs")
        idle(turns)

        assertFalse(turns.state.value.running)
        assertEquals("deux oeufs", turns.state.value.error!!.retryText)
        assertTrue(history.last(today, 10).isEmpty())
    }

    @Test
    fun `Reessayer - lecture de l historique en panne - erreur humaine, running remis a faux`() = runBlocking {
        val turns = turns(ScriptedTransport(ScriptedTransport.Fail(app.trackly.assistant.openai.AssistantError.NoInternet), ModelReplies.text("Noté.")))
        turns.send("deux oeufs")
        idle(turns)
        check(turns.state.value.error != null)

        history.failLast = true
        assertTrue(turns.retry())
        idle(turns)

        assertFalse(turns.state.value.running)
        assertEquals("Un problème est survenu", turns.state.value.error!!.notice.title)
        assertEquals("deux oeufs", turns.state.value.error!!.retryText)
        history.failLast = false
        assertEquals("pas de second message écrit", 1, history.last(today, 10).count { it.role == ChatRole.User })
    }

    @Test
    fun `reprise apres redemarrage - historique en panne - aucune exception, rien d affiche`() = runBlocking {
        // Un tour interrompu à reprendre : le repère existe, mais l'historique ne se lit pas.
        val id = history.append(engine.now(), ChatRole.User, "deux oeufs")
        markers.write(TurnMarker(id, engine.now()))
        val turns = turns()
        history.failLast = true
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
        assertFalse(turns.state.value.running)
    }

    // --- L'annulation n'est jamais avalée

    @Test
    fun `recordedSince - une coroutine annulee est annulee, elle ne recoit pas une reponse par defaut`() = runBlocking {
        var seen: Any? = "pas atteint"
        CoroutineScope(Dispatchers.Unconfined).launch {
            currentCoroutineContext().cancel()
            seen = try {
                engine.recordedSince(engine.now().minus(Duration.ofMinutes(1)))
            } catch (e: CancellationException) {
                "annulée"
            }
        }.join()
        assertEquals("annulée", seen)
    }
}
