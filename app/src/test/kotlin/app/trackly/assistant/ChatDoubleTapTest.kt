package app.trackly.assistant

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.store.InMemoryStore
import app.trackly.core.store.StoreTransaction
import app.trackly.core.store.TracklyStore
import app.trackly.testing.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Double toucher sur « Annuler » / « Rétablir » : une seule bascule part par carte. Avec un vrai disque, les
 * deux lectures de l'état se croisent avant la première écriture ; le magasin ralenti ci-dessous reproduit ce croisement.
 */
@RunWith(AndroidJUnit4::class)
class ChatDoubleTapTest {
    /** Chaque transaction cède d'abord la main : deux bascules lancées ensemble lisent donc le même état. */
    private class Interleaving(private val inner: TracklyStore) : TracklyStore by inner {
        override suspend fun <T> transaction(block: suspend StoreTransaction.() -> T): T {
            yield()
            return inner.transaction(block)
        }
    }

    private val engine = TracklyEngine(Interleaving(InMemoryStore()), SampleData.clock("2026-11-02T16:10"))
    private val history = InMemoryChatHistory()
    private val services = AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore("sk-test-cle-factice-0000000000"), history = history)

    private fun card(): RecordCard = runBlocking {
        val outcome = engine.execute(AddConsumptions(NewConsumption(Food("Pomme", Nutrition(78.0, 0.4, 19.0, 0.3)), Moment.Snack))) as ActionOutcome.Applied
        RecordCard.from(outcome.result, RecordKind.Added).also { history.append(engine.now(), ChatRole.Assistant, "", listOf(it)) }
    }

    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun model() = ViewModelProvider.create(ViewModelStore(), ChatViewModel.factory(services))[ChatViewModel::class]

    private fun undoActions(): Int = runBlocking {
        engine.observeRecentActions(20).first().count { it.undoes != null }
    }

    @Test
    fun `double toucher sur Annuler - une seule annulation, aucun refus affiche`() {
        val card = card()
        val model = model()
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }

        model.undo(card)
        model.undo(card)
        settle()

        assertEquals("une seule annulation écrite", 1, undoActions())
        assertTrue("aucun message « déjà annulé » : ${model.state.value.cardNotices}", model.state.value.cardNotices.isEmpty())
        assertTrue(model.state.value.undone.contains(card.actionId))
        watch.cancel()
    }

    @Test
    fun `double toucher sur Retablir - un seul retablissement, aucun refus affiche`() {
        val card = card()
        val model = model()
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        model.undo(card)
        settle()
        assertEquals(1, undoActions())

        model.restore(card)
        model.restore(card)
        settle()

        assertEquals("annulation puis un seul rétablissement", 2, undoActions())
        assertTrue("${model.state.value.cardNotices}", model.state.value.cardNotices.isEmpty())
        assertTrue(!model.state.value.undone.contains(card.actionId))
        watch.cancel()
    }

    @Test
    fun `apres une bascule, un nouveau toucher fonctionne`() {
        val card = card()
        val model = model()
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        model.undo(card)
        settle()
        model.restore(card)
        settle()
        model.undo(card)
        settle()
        assertEquals(3, undoActions())
        assertTrue(model.state.value.undone.contains(card.actionId))
        watch.cancel()
    }
}
