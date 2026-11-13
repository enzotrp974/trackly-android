package app.trackly.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.core.action.RememberFact
import app.trackly.storage.db.TracklyDatabase
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedModel.Companion.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le fil du bilan est isolé : une conversation à part, pour ne pas polluer le contexte du suivi des aliments du jour
 * (un bilan suivi d'un appel vocal ne doit pas lui donner un contexte biaisé inutilement).
 *
 * Preuve par ce que le modèle reçoit réellement : une vraie base Room qui contient les deux fils, un modèle simulé qui
 * garde chaque requête. Rien de ce qui est dit ou répondu dans le bilan n'atteint le fil du jour, dont l'appel
 * (même contexte que le chat) hérite, et inversement. Aucun réseau.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewThreadIsolationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).allowMainThreadQueries().build()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun close() {
        Dispatchers.resetMain()
        db.close()
    }

    private val mineBilan = "PESEE-DU-BILAN-72-4"
    private val replyBilan = "REPONSE-DU-BILAN-MARQUEE"
    private val mineJour = "REPAS-DU-JOUR-POMME"
    private val replyJour = "REPONSE-DU-JOUR-MARQUEE"

    /** Tout ce que le modèle a reçu dans cette requête : consignes, outils, contexte, historique. */
    private fun received(model: ScriptedModel): String = model.requests.joinToString("\n") { it.toString() }

    private suspend fun review(engine: app.trackly.core.engine.TracklyEngine, model: ScriptedModel, say: String) {
        val services = ReviewServices.inMemory(engine, RecordingReviewNotifier(), model, history = RoomReviewHistory(db.review()))
        val period = services.periods.at(engine.now())
        val vm = ReviewViewModel(services, period)
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect { } }
        vm.send(say)
        // Le tour passe par Room, sur un autre fil : on attend la réponse, sinon les vérifications suivantes seraient vides.
        withTimeout(10_000) { vm.state.first { state -> !state.thinking && state.messages.any { it.role == ChatRole.Assistant } } }
        collector.cancel()
    }

    private fun dayTurns(engine: app.trackly.core.engine.TracklyEngine, model: ScriptedModel) = ChatTurns(
        engine, RoomChatHistory(db.chat()), TurnInterpreter(ResponsesClient(model, RetryPolicy.None), engine),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun `apres un bilan, le fil du jour ne recoit rien du bilan - ni ses messages, ni ses reponses, ni son contexte`() = runBlocking<Unit> {
        val engine = ReviewSample.engine()
        review(engine, ScriptedModel({ text(replyBilan) }), mineBilan)

        val day = ScriptedModel({ text(replyJour) })
        val turns = dayTurns(engine, day)
        turns.send(mineJour)
        turns.awaitIdle()

        val sent = received(day)
        assertTrue("le fil du jour a bien reçu son propre message", sent.contains(mineJour))
        assertFalse("message du bilan dans le fil du jour : $sent", sent.contains(mineBilan))
        assertFalse("réponse du bilan dans le fil du jour : $sent", sent.contains(replyBilan))
        // Ni les consignes, ni les outils, ni le contexte du bilan.
        assertFalse(sent.contains("record_week_info"))
        assertFalse(sent.contains("produce_weekly_report"))
        assertFalse(sent.contains("Période du bilan"))
        assertFalse(sent.contains("Chiffres de la période"))
        assertFalse(sent.contains("Bilan depuis le dernier"))
    }

    @Test
    fun `le fil du jour d apres reste propre aussi - les deux fils vivent dans la meme base sans se melanger`() = runBlocking<Unit> {
        val engine = ReviewSample.engine()
        val firstDay = ScriptedModel({ text(replyJour) })
        dayTurns(engine, firstDay).also { it.send(mineJour); it.awaitIdle() }
        review(engine, ScriptedModel({ text(replyBilan) }), mineBilan)

        // Un second message du jour, après le bilan : le modèle voit le fil du jour en entier, et lui seul.
        val secondDay = ScriptedModel({ text("suite") })
        dayTurns(engine, secondDay).also { it.send("encore un repas"); it.awaitIdle() }

        val sent = received(secondDay)
        assertTrue(sent.contains(mineJour) && sent.contains(replyJour) && sent.contains("encore un repas"))
        assertFalse(sent.contains(mineBilan) || sent.contains(replyBilan))
        // Côté base : chaque fil est dans sa propre table.
        val thread = DayThread(RoomChatHistory(db.chat())) { engine.now() }
        assertFalse(thread.last(50).any { it.text.contains(mineBilan) || it.text.contains(replyBilan) })
        val bilan = RoomReviewHistory(db.review()).last(ReviewPeriods.of(engine.now(), null).start, 50)
        assertEquals(listOf(mineBilan, replyBilan), bilan.map { it.text })
    }

    @Test
    fun `le bilan ne recoit rien du fil du jour non plus`() = runBlocking<Unit> {
        val engine = ReviewSample.engine()
        dayTurns(engine, ScriptedModel({ text(replyJour) })).also { it.send(mineJour); it.awaitIdle() }

        val bilan = ScriptedModel({ text(replyBilan) })
        review(engine, bilan, mineBilan)

        val sent = received(bilan)
        assertTrue("le bilan a bien reçu son propre message : $sent", sent.contains(mineBilan))
        assertFalse("fil du jour dans le bilan : $sent", sent.contains(mineJour) || sent.contains(replyJour))
        assertFalse(sent.contains("record_consumptions"))
    }

    @Test
    fun `le contexte du chat et de l appel n a aucun element du bilan`() {
        val ids = ContextBuilder.default().providers.map { it.id }
        assertFalse("un élément du bilan est dans le contexte du fil du jour : $ids", ids.any { it.startsWith("review") })
    }

    @Test
    fun `la memoire du fil du jour n entre pas non plus dans le bilan`() = runBlocking<Unit> {
        val engine = ReviewSample.engine()
        // Une note de mémoire du suivi (ici une ancienne demande de bilan du lundi au dimanche) sert au fil du jour…
        check(engine.execute(RememberFact("Bilan", "Demande de bilan hebdomadaire, semaine du lundi au dimanche")) is app.trackly.core.action.ActionOutcome.Applied)
        val day = ScriptedModel({ text(replyJour) })
        dayTurns(engine, day).also { it.send(mineJour); it.awaitIdle() }
        assertTrue(received(day).contains("Demande de bilan hebdomadaire"))

        // … mais le bilan « depuis le dernier » ne la lit jamais : elle ne peut pas le contredire.
        val bilan = ScriptedModel({ text(replyBilan) })
        review(engine, bilan, mineBilan)
        assertFalse(received(bilan).contains("Demande de bilan hebdomadaire"))
    }
}
