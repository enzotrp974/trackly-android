package app.trackly.assistant

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.trackly.assistant.background.TurnWorker
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Application de test : les tours du chat, branchés sur un transport retenu jusqu'à l'ouverture d'une barrière. */
class TurnWorkerTestApplication : Application(), TurnWorker.Host {
    val gate = CompletableDeferred<Unit>()
    private val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
    private val gated = ResponsesTransport { body -> gate.await(); transport.send(body) }
    val services: AssistantServices by lazy {
        AssistantServices.inMemory(SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")), gated, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
    }
    override val chatTurns: ChatTurns get() = services.turns
}

/** Le travail accéléré garde l'app en vie exactement le temps du tour. */
@RunWith(AndroidJUnit4::class)
@Config(application = TurnWorkerTestApplication::class)
class TurnWorkerTest {
    private val app: TurnWorkerTestApplication = ApplicationProvider.getApplicationContext()

    private fun worker() = TestListenableWorkerBuilder<TurnWorker>(app as Context).build()

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    @Test
    fun `aucun tour en cours - le travail se termine aussitot`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test
    fun `tour en cours - le travail attend sa fin`() {
        app.services.turns.send("comme snack j'ai pris une barre protéinée et une pomme, 150 g")
        val work = CoroutineScope(Dispatchers.Default).async { worker().doWork() }
        Thread.sleep(200)
        assertFalse("le travail attend la fin du tour", work.isCompleted)

        app.gate.complete(Unit)
        idleUntil { work.isCompleted }
        assertEquals(ListenableWorker.Result.success(), runBlocking { work.await() })
        assertFalse(app.services.turns.state.value.running)
    }
}
