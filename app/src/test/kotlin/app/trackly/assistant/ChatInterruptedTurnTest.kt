package app.trackly.assistant

import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.InMemoryTurnMarkerStore
import app.trackly.assistant.chat.TurnMarker
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.PhotoMarker
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.net.LatencyLine
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

/**
 * Tour interrompu par Android (processus tué) et exception inattendue dans un tour : l'utilisateur est
 * toujours prévenu, et « Réessayer » ne compte jamais deux fois.
 */
class ChatInterruptedTurnTest {
    private val engine: TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
    private val today = java.time.LocalDate.parse("2026-11-02")
    private val history = InMemoryChatHistory()
    private val lines = mutableListOf<LatencyLine>()

    /** Repère de tour en cours, gardé entre deux [ChatTurns] : en partager un simule le redémarrage de l'app. */
    private val markers = InMemoryTurnMarkerStore()

    private fun turns(transport: ResponsesTransport = ResponsesTransport { error("pas de réseau dans ce test") }) = ChatTurns(
        engine, history, TurnInterpreter(ResponsesClient(transport), engine),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), latency = { lines += it }, markers = markers,
    )

    /**
     * Un tour que la mort du processus a interrompu : le message est dans l'historique et le repère du tour est
     * resté (il n'est effacé que par la fin du tour).
     */
    private suspend fun interrupted(
        text: String,
        minutesAgo: Long = 2,
        focus: CorrectionFocus? = null,
        label: String? = null,
    ): Long {
        val at = engine.now().minus(Duration.ofMinutes(minutesAgo))
        val id = history.append(at, ChatRole.User, text)
        markers.write(TurnMarker(id, at, focus, label))
        return id
    }

    /** Message sans repère : un échec ordinaire (rien n'a été écrit) ou un tour ancien. */
    private suspend fun userMessage(text: String, minutesAgo: Long = 2) =
        history.append(engine.now().minus(Duration.ofMinutes(minutesAgo)), ChatRole.User, text)

    @Test
    fun `dernier message de l utilisateur sans reponse et recent - erreur humaine avec Reessayer`() = runBlocking {
        val id = interrupted("deux oeufs et une tartine")
        val turns = turns()
        turns.recoverInterrupted()
        val error = turns.state.value.error!!
        assertEquals("Ton message n’a pas reçu de réponse.", error.notice.title)
        assertTrue(error.notice.retryable)
        assertEquals("deux oeufs et une tartine", error.retryText)
        assertEquals(id, error.retryMessageId)
        assertFalse(turns.state.value.running)
    }

    @Test
    fun `Noter coupe par Android - pas de Reessayer sans la garde de la carte`() = runBlocking {
        val card = app.trackly.assistant.cards.ProposalCard("prop-1", "Pâtes thon sauce tomate", "soir", emptyList())
        interrupted(app.trackly.assistant.cards.ProposalCard.noteRequest(card))
        val turns = turns()
        turns.recoverInterrupted()
        // La phrase seule partirait sans la carte ni sa vérification : on retouche « Noter » à la place.
        val error = turns.state.value.error!!
        assertNull(error.retryText)
        // Le texte donne ce geste-là, pas « renvoie ton message ».
        assertEquals("Rien n’a été noté pour « Pâtes thon sauce tomate » : retouche « Noter » sur sa carte.", error.notice.body)
        assertFalse(error.notice.body.contains("renvoyer"))
    }

    @Test
    fun `Noter coupe apres une autre action - le texte dit encore de retoucher Noter, pas de renvoyer`() = runBlocking {
        // Une autre action enregistrée dans la fenêtre : retoucher « Noter » reste sûr (garde « déjà notée »).
        check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        val card = app.trackly.assistant.cards.ProposalCard("prop-1", "Pâtes thon sauce tomate", "soir", emptyList())
        interrupted(app.trackly.assistant.cards.ProposalCard.noteRequest(card), minutesAgo = 1)
        val turns = turns()
        turns.recoverInterrupted()
        val error = turns.state.value.error!!
        assertNull(error.retryText)
        assertEquals("Rien n’a été noté pour « Pâtes thon sauce tomate » : retouche « Noter » sur sa carte.", error.notice.body)
    }

    @Test
    fun `reessayer renvoie le meme message sans le recrire dans l historique`() = runBlocking {
        interrupted("deux oeufs et une tartine")
        val turns = turns()
        turns.recoverInterrupted()
        assertTrue(turns.retry())
        turns.awaitIdle()
        assertEquals("un seul message de l'utilisateur", 1, history.last(today, 10).count { it.role == ChatRole.User })
    }

    @Test
    fun `le dernier message est celui de l assistant (carte enregistree) - rien n est propose`() = runBlocking {
        interrupted("deux oeufs")
        history.append(engine.now(), ChatRole.Assistant, "", emptyList())
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `message de plus de 15 minutes - rien n est affiche`() = runBlocking {
        interrupted("deux oeufs", minutesAgo = 16)
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `historique vide - rien n est affiche`() = runBlocking {
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `une action enregistree depuis le message - pas de Reessayer, le texte renvoie au journal`() = runBlocking {
        check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        interrupted("deux oeufs", minutesAgo = 1)
        val turns = turns()
        // L'action date de « maintenant » (horloge figée), donc après le message d'il y a une minute.
        turns.recoverInterrupted()
        val error = turns.state.value.error!!
        assertNull(error.retryText)
        assertTrue(error.notice.body, error.notice.body.contains("journal"))
    }

    @Test
    fun `message avec photos - les photos ne sont pas gardees, pas de Reessayer trompeur`() = runBlocking {
        interrupted(PhotoMarker.stored("mon déjeuner", 2))
        val turns = turns()
        turns.recoverInterrupted()
        val error = turns.state.value.error!!
        assertNull(error.retryText)
        assertTrue(error.notice.body, error.notice.body.contains("photos"))
    }

    @Test
    fun `la verification n a lieu qu une fois par processus - une erreur ecartee ne revient pas`() = runBlocking {
        interrupted("deux oeufs")
        val turns = turns()
        turns.recoverInterrupted()
        turns.dismissError()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `un tour en cours n est pas confondu avec un tour interrompu`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val turns = turns { gate.await(); error("fin") }
        turns.send("un café")
        assertTrue(turns.state.value.running)
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
        gate.complete(Unit)
        turns.awaitIdle()
    }

    // --- Le repère « tour en cours » distingue la mort du processus d'un échec ordinaire

    @Test
    fun `un echec ordinaire n ecrit rien - apres redemarrage, aucune reprise annoncee`() = runBlocking {
        for (error in listOf(AssistantError.NoInternet, AssistantError.Timeout, AssistantError.KeyRejected, AssistantError.CreditExhausted)) {
            val before = turns(ScriptedTransport(ScriptedTransport.Fail(error)))
            before.send("deux oeufs")
            before.awaitIdle()
            assertNull("le repère est effacé à la fin du tour ($error)", markers.read())
            // Android relance l'app : l'erreur d'origine a déjà été montrée, rien de plus ne doit apparaître.
            val after = turns()
            after.recoverInterrupted()
            assertNull("pas de fausse reprise ($error)", after.state.value.error)
        }
    }

    @Test
    fun `texte vide ou exception - le repere est efface aussi`() = runBlocking {
        val failing = turns { throw IllegalStateException("panne") }
        failing.send("deux oeufs")
        failing.awaitIdle()
        assertNull(markers.read())
        assertFalse(turns().apply { send("   ") }.state.value.running)
        assertNull(markers.read())
    }

    @Test
    fun `le repere est pose pendant le tour, avec le message et sans contenu`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val turns = turns { gate.await(); error("fin") }
        val focus = CorrectionFocus(actionId = 7, entryIds = listOf("e-1"))
        turns.send("deux oeufs", focus = focus, focusLabel = "Oeufs")
        val marker = markers.read()!!
        assertEquals(history.last(today, 1).single().id, marker.messageId)
        assertEquals(focus, marker.focus)
        assertEquals("Oeufs", marker.focusLabel)
        assertEquals(engine.now(), marker.startedAt)
        gate.complete(Unit)
        turns.awaitIdle()
        assertNull(markers.read())
    }

    @Test
    fun `processus tue pendant le tour - la reprise le dit sans inventer de cause`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dying = turns { gate.await(); error("fin") }
        dying.send("deux oeufs")
        // Le processus meurt ici : ni la fin du tour ni son nettoyage n'ont lieu. Un nouveau processus démarre.
        val reborn = turns()
        reborn.recoverInterrupted()
        val error = reborn.state.value.error!!
        assertEquals("Ton message n’a pas reçu de réponse.", error.notice.title)
        assertEquals("Rien n’a été enregistré : tu peux renvoyer ton message.", error.notice.body)
        assertFalse(error.notice.body.contains("Android"))
        assertEquals("deux oeufs", error.retryText)
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `message sans repere - jamais de reprise, meme recent`() = runBlocking {
        userMessage("deux oeufs")
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `repere d un autre message que le dernier de l utilisateur - pas de reprise`() = runBlocking {
        val first = interrupted("deux oeufs")
        history.append(engine.now(), ChatRole.Assistant, "Noté.")
        userMessage("et une pomme")
        assertTrue(markers.read()!!.messageId == first)
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `repere de plus de 15 minutes - pas de reprise, meme si le message est recent`() = runBlocking {
        val id = userMessage("deux oeufs", minutesAgo = 1)
        markers.write(TurnMarker(id, engine.now().minus(Duration.ofMinutes(16))))
        val turns = turns()
        turns.recoverInterrupted()
        assertNull(turns.state.value.error)
    }

    @Test
    fun `erreur ecartee - le repere est efface, elle ne revient pas apres un autre redemarrage`() = runBlocking {
        interrupted("deux oeufs")
        val first = turns()
        first.recoverInterrupted()
        first.dismissError()
        assertNull(markers.read())
        val second = turns()
        second.recoverInterrupted()
        assertNull(second.state.value.error)
    }

    // --- « Réessayer » après la mort du processus garde la cible de « Corriger »

    @Test
    fun `reessayer apres la mort du processus - la cible de correction est restauree et envoyee au modele`() = runBlocking {
        val focus = CorrectionFocus(actionId = null, entryIds = listOf("entree-riz"))
        interrupted("non, 200 g", focus = focus, label = "Riz basmati")
        val transport = ScriptedTransport(ModelReplies.text("Corrigé."))
        val turns = turns(transport)
        turns.recoverInterrupted()
        val error = turns.state.value.error!!
        assertEquals(focus, error.retryFocus)
        assertEquals("Riz basmati", error.retryFocusLabel)

        assertTrue(turns.retry())
        turns.awaitIdle()

        val request = transport.requests.single().toString()
        assertTrue("le contexte porte la correction demandée", request.contains("Correction demandée"))
        assertTrue(request.contains("entree-riz"))
        assertNull(markers.read())
    }

    @Test
    fun `reessayer apres la mort du processus - le nouveau repere garde la cible, si ca meurt encore`() = runBlocking {
        val focus = CorrectionFocus(actionId = 3, entryIds = listOf("entree-riz"))
        interrupted("non, 200 g", focus = focus, label = "Riz basmati")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val turns = turns { gate.await(); error("fin") }
        turns.recoverInterrupted()
        assertTrue(turns.retry())
        val marker = markers.read()!!
        assertEquals(focus, marker.focus)
        assertEquals("Riz basmati", marker.focusLabel)
        gate.complete(Unit)
        turns.awaitIdle()
    }

    // --- Exception inattendue dans un tour

    @Test
    fun `exception inattendue dans un tour - erreur humaine, Reessayer, type journalise sans contenu`() = runBlocking {
        val turns = turns { throw IllegalStateException("secret: 2 oeufs, 600 kcal") }
        turns.send("deux oeufs")
        turns.awaitIdle()
        val error = turns.state.value.error!!
        assertEquals("Un problème est survenu", error.notice.title)
        assertTrue(error.notice.retryable)
        assertEquals("deux oeufs", error.retryText)
        assertFalse(error.notice.body.contains("secret"))
        // Le journal du tour (debug seulement) ne porte que le type de l'exception.
        assertEquals("exception java.lang.IllegalStateException", lines.single().note)
        // L'historique garde le message de l'utilisateur ; le tour est terminé (l'écran ne reste pas bloqué).
        assertFalse(turns.state.value.running)
        assertEquals(1, history.last(today, 10).size)
    }

    @Test
    fun `la cancellation n est jamais avalee`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val turns = ChatTurns(
            engine, history, TurnInterpreter(ResponsesClient(ResponsesTransport { gate.await(); error("fin") }), engine),
            scope, latency = { lines += it },
        )
        turns.send("un café")
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(CancellationException("fin de l'app"))
        assertTrue("aucune erreur affichée pour une annulation", lines.none { it.note?.startsWith("exception") == true })
        assertNull(turns.state.value.error)
        assertNotNull(history)
    }
}
