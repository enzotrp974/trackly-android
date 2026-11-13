package app.trackly.assistant

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.MainActivity
import app.trackly.assistant.background.AndroidTurnNotifier
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.TurnNotifier
import app.trackly.assistant.chat.TurnSummary
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.turn.TurnStatus
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * « J'envoie et je pars » : le tour vit hors de l'écran. Écran recréé pendant un tour (aucun doublon, état
 * retrouvé, carte déjà visible), tour terminé app fermée (notification discrète, permission respectée).
 */
@RunWith(AndroidJUnit4::class)
class ChatTurnsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val monday = LocalDate.parse("2026-11-02")
    private val snack = "comme snack j'ai pris une barre protéinée et une pomme, 150 g"
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    /** Laisse tourner le fil principal (Robolectric) jusqu'à [condition]. */
    private fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    /** Transport qui retient la requête n° [gateAt] (à partir de 0) jusqu'à l'ouverture de [gate]. */
    private class Gated(private val inner: ScriptedTransport, private val gateAt: Int) : ResponsesTransport {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun send(body: JsonObject): JsonObject {
            if (calls++ == gateAt) gate.await()
            return inner.send(body)
        }
    }

    private fun viewModel(store: ViewModelStore, services: AssistantServices) =
        ViewModelProvider.create(store, ChatViewModel.factory(services))[ChatViewModel::class]

    @Test
    fun `ecran recree pendant un tour - aucun doublon, etat et carte retrouves, puis la reponse`() {
        val engine = engine()
        val scripted = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        // L'enregistrement passe, la phrase de conclusion se fait attendre.
        val transport = Gated(scripted, gateAt = 1)
        val history = InMemoryChatHistory()
        val services = AssistantServices.inMemory(engine, transport, key, history)

        val first = ViewModelStore()
        val before = viewModel(first, services)
        val watchBefore = CoroutineScope(Dispatchers.Main.immediate).launch { before.state.collect { } }
        before.send(snack)
        idleUntil { transport.calls == 2 }
        watchBefore.cancel()
        // L'écran disparaît (rotation, app quittée, processus gardé) : son ViewModel est détruit.
        first.clear()

        val second = ViewModelStore()
        val after = viewModel(second, services)
        val watchAfter = CoroutineScope(Dispatchers.Main.immediate).launch { after.state.collect { } }
        idleUntil { after.state.value.messages.size == 2 }
        val during = after.state.value
        assertTrue("le tour continue, retrouvé par le nouvel écran", during.thinking)
        val reply = during.messages.last()
        assertEquals(ChatRole.Assistant, reply.role)
        assertTrue("la carte est déjà là, avant le texte", reply.cards.single() is RecordCard)
        assertEquals("", reply.text)
        assertEquals(reply.id, during.live?.messageId)
        assertEquals(TurnStatus.Thinking, during.live?.status)
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)

        // Un second envoi pendant le tour est refusé : un message = un tour.
        after.send("et un café")
        transport.gate.complete(Unit)
        idleUntil { !after.state.value.thinking && after.state.value.messages.last().text.isNotBlank() }
        watchAfter.cancel()

        val messages = after.state.value.messages
        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), messages.map { it.role })
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", messages.last().text)
        assertEquals(1, messages.last().cards.size)
        assertEquals(2, scripted.requests.size)
        assertNull("la réponse est relue de l'historique", after.state.value.live)
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)
        second.clear()
    }

    @Test
    fun `tour termine app fermee - notification discrete qui ouvre le chat`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val services = AssistantServices.inMemory(
            engine(), ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"), key,
            notifier = AndroidTurnNotifier(app), inForeground = { false },
        )

        assertTrue(services.turns.send(snack))
        idleUntil { !services.turns.state.value.running }

        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("Enregistré : Barre protéinée Nuvo, Pomme · 300 kcal", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(AndroidTurnNotifier.CHANNEL, notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(AndroidTurnNotifier.CHANNEL).importance)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.OPEN_CHAT, intent.getStringExtra(MainActivity.EXTRA_OPEN))
    }

    @Test
    fun `sans permission de notification - rien n est poste, le tour aboutit quand meme`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val engine = engine()
        val services = AssistantServices.inMemory(
            engine, ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"), key,
            notifier = AndroidTurnNotifier(app), inForeground = { false },
        )

        services.turns.send(snack)
        idleUntil { !services.turns.state.value.running }

        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)
    }

    @Test
    fun `app fermee avec question - la question est notifiee, app a l ecran - aucune notification`() {
        val received = mutableListOf<TurnSummary>()
        var foreground = true
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":null,"food":{"label":"Plat d'assiette","quantity":null,"unit":null,"kcal":600,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"assumptions":[],"confidence":"low"},"recipe_id":null,"moment":"midi","day":null}],"question":"Il y avait quoi dedans ?"}""",
            ),
            ModelReplies.text("J'ai compté une assiette moyenne."),
            ModelReplies.text("Bonne question."),
        )
        val services = AssistantServices.inMemory(engine(), transport, key, notifier = TurnNotifier { received += it }, inForeground = { foreground })

        foreground = false
        services.turns.send("j'ai mangé une assiette")
        idleUntil { !services.turns.state.value.running }
        assertEquals(listOf(TurnSummary("Enregistré : Plat d'assiette · 600 kcal", "Question en attente : Il y avait quoi dedans ?")), received)

        foreground = true
        services.turns.send("c'est bon le riz ?")
        idleUntil { !services.turns.state.value.running }
        assertEquals(1, received.size)
        assertFalse(services.turns.state.value.running)
    }
}
