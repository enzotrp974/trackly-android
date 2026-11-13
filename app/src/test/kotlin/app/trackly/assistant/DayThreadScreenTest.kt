package app.trackly.assistant

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.AssistantError
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.core.store.InMemoryStore
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedModel.Companion.text
import app.trackly.testing.ScriptedTransport
import app.trackly.testing.SettableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * Fil du jour vu de l'écran du chat : après « Effacer la discussion », ou une fois 5 h passées, le chat est vide.
 * Ni une réponse terminée la veille, ni une ancienne erreur ne reste affichée dans le fil vide.
 */
@RunWith(AndroidJUnit4::class)
class DayThreadScreenTest {
    private val clock = SettableClock("2026-11-01T23:00")
    private val engine = TracklyEngine(InMemoryStore(), clock).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    private fun viewModel(store: ViewModelStore, services: AssistantServices) =
        ViewModelProvider.create(store, ChatViewModel.factory(services))[ChatViewModel::class]

    /** Ouvre le chat et le suit, comme le ferait l'écran. */
    private fun watch(model: ChatViewModel) = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }

    @Test
    fun `apres Effacer la discussion, le chat est vide et la derniere reponse ne reste pas affichee`() {
        val services = AssistantServices.inMemory(engine, ScriptedModel({ text("Bonsoir.") }), key)
        val store = ViewModelStore()
        val model = viewModel(store, services)
        val watching = watch(model)
        model.send("salut")
        idleUntil { model.state.value.messages.size == 2 && !model.state.value.thinking }

        val hidden = runBlocking { services.thread.clear() }
        idleUntil { model.state.value.messages.isEmpty() }

        assertEquals(2, hidden)
        assertNull("« Bonsoir. » ne doit pas rester seul dans le fil vide", model.state.value.live)
        watching.cancel()
        store.clear()
    }

    @Test
    fun `apres Effacer la discussion, une ancienne erreur ne reste pas affichee`() {
        val services = AssistantServices.inMemory(engine, ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet)), key)
        val store = ViewModelStore()
        val model = viewModel(store, services)
        val watching = watch(model)
        model.send("salut")
        idleUntil { model.state.value.error != null }

        runBlocking { services.thread.clear() }
        idleUntil { model.state.value.error == null }

        assertNull(model.state.value.error)
        watching.cancel()
        store.clear()
    }

    @Test
    fun `le lendemain a 5 h, le chat rouvert est vide - ni reponse ni erreur de la veille`() {
        val services = AssistantServices.inMemory(engine, ScriptedModel({ text("Bonsoir.") }), key)
        val first = ViewModelStore()
        val before = viewModel(first, services)
        val watchBefore = watch(before)
        before.send("salut")
        idleUntil { before.state.value.messages.size == 2 && !before.state.value.thinking }
        watchBefore.cancel()
        first.clear()

        clock.set("2026-11-02T05:00")
        val second = ViewModelStore()
        val after = viewModel(second, services)
        val watchAfter = watch(after)
        idleUntil { after.state.value.historyLoaded }

        assertTrue(after.state.value.messages.isEmpty())
        assertNull("la réponse de la veille ne revient pas dans le fil du matin", after.state.value.live)
        watchAfter.cancel()
        second.clear()
    }

    @Test
    fun `avant 5 h, le chat rouvert montre encore le fil de la veille`() {
        val services = AssistantServices.inMemory(engine, ScriptedModel({ text("Bonsoir.") }), key)
        val first = ViewModelStore()
        val before = viewModel(first, services)
        val watchBefore = watch(before)
        before.send("salut")
        idleUntil { before.state.value.messages.size == 2 && !before.state.value.thinking }
        watchBefore.cancel()
        first.clear()

        clock.set("2026-11-02T04:59")
        val second = ViewModelStore()
        val after = viewModel(second, services)
        val watchAfter = watch(after)
        idleUntil { after.state.value.messages.size == 2 }

        assertNotNull(after.state.value.messages)
        assertEquals(listOf("salut", "Bonsoir."), after.state.value.messages.map { it.text })
        watchAfter.cancel()
        second.clear()
    }
}
