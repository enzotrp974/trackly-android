package app.trackly.call

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * Ce que la réflexion note pendant l'appel apparaît dans le chat écrit du jour, et « Annuler » sur sa carte l'annule comme
 * n'importe quel enregistrement du chat (nouvelle version, rien de supprimé physiquement).
 */
@RunWith(AndroidJUnit4::class)
class CallUndoFromChatTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    @Test
    fun `repas note pendant l appel - visible dans le chat du jour et annulable depuis sa carte`() {
        val engine = SampleData.engine()
        val history = InMemoryChatHistory()
        val model = ScriptedModel({ CallReplies.recordFood("Pomme", 80, grams = 150) }, { CallReplies.answer(questions = listOf("Grosse ou petite ?")) })
        val thinker = AgentCallThinker(ResponsesClient(model, RetryPolicy.None), engine, history)
        val request = ThinkRequest(listOf("item_1"), listOf(CallLine(Speaker.User, "Ce midi une pomme.")), CallReflection.Medium)
        runBlocking { thinker.think(request) }
        assertTrue(runBlocking { engine.daySummary(monday).consumptions.any { it.food.label == "Pomme" } })

        val chat = ChatViewModel(
            AssistantServices.inMemory(engine, ScriptedTransport(), InMemoryApiKeyStore("sk-test-cle-factice-0000000000"), history),
            SavedStateHandle(),
        )
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { chat.state.collect { } }
        idleUntil { chat.state.value.messages.size == 1 }
        val message = chat.state.value.messages.single()
        assertTrue(message.text.startsWith(AgentCallThinker.HEADER))
        val card = message.cards.single() as RecordCard

        chat.undo(card)
        idleUntil { card.actionId in chat.state.value.undone }
        assertFalse(runBlocking { engine.daySummary(monday).consumptions.any { it.food.label == "Pomme" } })
        // Annulé par une nouvelle version : l'historique de l'entrée garde la pomme.
        val entryId = card.items.single().entryId
        assertEquals(2, runBlocking { engine.history(app.trackly.core.model.EntryId(entryId)).size })
        watch.cancel()
    }
}
