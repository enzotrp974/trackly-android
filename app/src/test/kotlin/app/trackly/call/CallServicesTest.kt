package app.trackly.call

import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.call.live.LaunchStep
import app.trackly.review.InMemoryReviewHistory
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import app.trackly.call.live.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.time.Instant
import java.time.LocalDate

/**
 * L'appel dans l'app : contexte tiré du fil du jour (jamais du fil du bilan), rien d'écrit dans le fil du jour sans repas noté, clé
 * absente ou micro refusé sans plantage, préparation gratuite réutilisée au toucher, coût et transcription gardés.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallServicesTest {
    private val engine = SampleData.engine()

    // Les services de l'assistant créent leur portée sur le fil principal.
    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class Sink : CallSink {
        val costs = mutableListOf<Pair<String, Long>>()
        var transcript: List<CallLine>? = null
        val metrics = mutableListOf<CallMetrics>()
        override suspend fun recordCost(model: String, sessionMs: Long, at: Instant) {
            costs += model to sessionMs
        }
        override fun saveTranscript(lines: List<CallLine>, at: Instant) {
            transcript = lines
        }
        override fun saveMetrics(metrics: CallMetrics) {
            this.metrics += metrics
        }
    }

    private class Platform : CallPlatform {
        var foreground = 0
        var vibrations = 0
        override fun startForeground() { foreground++ }
        override fun stopForeground() { foreground-- }
        override fun vibrate() { vibrations++ }
    }

    private inner class Setup(
        scope: TestScope,
        key: String? = "sk-test-fausse",
        keyDelayMs: Long = 0,
        historyDelayMs: Long = 0,
        val net: MutableStateFlow<String> = MutableStateFlow("wifi"),
        val keys: MutableStateFlow<Int> = MutableStateFlow(0),
        failing: TransportException? = null,
        audio: CallAudio = CallAudio.None,
    ) {
        val assistant = AssistantServices.inMemory(engine)
        val review = InMemoryReviewHistory()
        val model = ScriptedModel({ CallReplies.answer(questions = listOf("Ta pomme, quelle taille ?")) })
        val usage = mutableListOf<UsageEvent>()
        val transports = mutableListOf<FakeLiveTransport>()
        val sink = Sink()
        val platform = Platform()
        val settings = InMemoryCallSettings()
        val services = CallServices(
            scope = scope.backgroundScope,
            settings = settings,
            // Comme le Keystore : une lecture qui ne s'interrompt pas, même si l'écran se referme pendant ce temps.
            readKey = {
                withContext(NonCancellable) { delay(keyDelayMs) }
                key
            },
            // Fil du jour lu comme dans Room : une lecture qui suspend (et que l'annulation interrompt).
            dayThread = DayThread(SlowHistory(assistant.history, historyDelayMs)) { engine.now() },
            thinker = AgentCallThinker(
                ResponsesClient(model, RetryPolicy.None),
                engine,
                assistant.history,
                usage = UsageSink { usage += it },
            ),
            newTransport = { FakeLiveTransport(failWith = failing).also { transports += it } },
            audio = audio,
            platform = platform,
            sink = sink,
            now = { scope.testScheduler.currentTime },
            io = StandardTestDispatcher(scope.testScheduler),
            network = { net.value },
            keyChanges = keys,
        )
    }

    @Test
    fun `le contexte vient du fil du jour, jamais du fil du bilan, et rien n est ecrit dans le fil sans repas note`() = runTest {
        val s = Setup(this)
        val now = engine.now()
        s.assistant.history.append(now, ChatRole.User, "JOUR-POMME : ce matin une pomme")
        s.assistant.history.append(now, ChatRole.Assistant, "Noté, 80 kcal.")
        s.review.append(LocalDate.parse("2026-10-26"), now, ChatRole.User, "BILAN-SECRET : j'ai pesé 70 kg")
        val before = s.assistant.thread.last(50)

        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        val session = t.openedWith.toString()
        assertTrue(session.contains("JOUR-POMME"))
        assertFalse(session.contains("BILAN-SECRET"))

        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Et ce midi une salade."))
        t.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_000)
        val request = s.model.requests.single().toString()
        assertTrue(request.contains("JOUR-POMME"))
        assertTrue(request.contains("${Speaker.User.label} : Et ce midi une salade."))
        assertFalse(request.contains("BILAN-SECRET"))
        assertTrue(t.sentText().contains("Ta pomme, quelle taille ?"))

        s.services.hangUp()
        advanceTimeBy(100)
        t.emit(ServerEvents.closed("close_requested", 20.0))
        advanceTimeBy(200)
        assertEquals(before, s.assistant.thread.last(50))
        assertEquals(1, s.review.last(LocalDate.parse("2026-10-26"), 50).size)
    }

    @Test
    fun `cle absente - message clair, aucun transport ouvert`() = runTest {
        val s = Setup(this, key = null)
        s.services.start(micAllowed = true)
        runCurrent()
        assertTrue(s.services.screenOpen.value)
        assertEquals(CallPhase.Ended, s.services.controller.state.value.phase)
        assertEquals(EndReason.MissingKey, s.services.controller.state.value.endReason)
        assertTrue(s.transports.all { it.openedWith == null })
        assertEquals(0, s.platform.foreground)
        assertTrue(s.sink.costs.isEmpty())
    }

    @Test
    fun `micro refuse - message clair, rien ne part`() = runTest {
        val s = Setup(this)
        s.services.start(micAllowed = false)
        runCurrent()
        assertEquals(EndReason.NoPermission, s.services.controller.state.value.endReason)
        assertTrue(s.transports.isEmpty())
        s.services.close()
        assertFalse(s.services.screenOpen.value)
    }

    @Test
    fun `preparation gratuite reutilisee au toucher, puis cout, transcription et mesures gardes`() = runTest {
        val s = Setup(this)
        s.services.prepare()
        runCurrent()
        val prepared = s.transports.single()
        assertEquals(1, prepared.prepared)
        assertNull("rien d'ouvert avant le toucher", prepared.openedWith)

        s.services.start(micAllowed = true)
        runCurrent()
        assertEquals(1, s.transports.size)
        assertEquals("sk-test-fausse", prepared.keyUsed)
        assertEquals(1, s.platform.foreground)
        prepared.emit(ServerEvents.started())
        prepared.emit(ServerEvents.input("Bon, au revoir."))
        advanceTimeBy(1_000)
        prepared.emit(ServerEvents.output("À plus !"))
        advanceTimeBy(1_000)
        prepared.emit(ServerEvents.closed("close_requested", 30.0))
        advanceTimeBy(200)
        assertEquals(EndReason.Goodbye, s.services.controller.state.value.endReason)
        assertEquals(listOf("gpt-live-1" to 30_000L), s.sink.costs)
        assertEquals(CallLine(Speaker.User, "Bon, au revoir."), s.sink.transcript!!.first())
        val m = s.sink.metrics.single()
        assertTrue(m.prepared)
        assertTrue(LaunchStep.Offer in m.beforeTap)
        assertFalse(LaunchStep.Http in m.beforeTap)
        assertEquals(0, s.platform.foreground)

        // Écran refermé : nouvelle préparation pour le prochain appel.
        s.services.close()
        runCurrent()
        assertEquals(2, s.transports.size)
        assertEquals(1, s.transports.last().prepared)
    }

    @Test
    fun `liberation en arriere-plan, et preparation refaite si l echo change`() = runTest {
        val s = Setup(this)
        s.services.prepare()
        runCurrent()
        s.services.release()
        assertTrue(s.transports.single().closed)
        s.services.prepare()
        runCurrent()
        s.settings.setEcho(CallEcho.Phone)
        s.services.prepare()
        runCurrent()
        assertEquals(3, s.transports.size)
        assertTrue(s.transports[1].closed)
    }

    @Test
    fun `le reglage de reflexion de l appel choisit le modele, distinct du chat`() = runTest {
        val s = Setup(this)
        s.settings.setReflection(CallReflection.Fast)
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.delegationCreated("item_2"))
        advanceTimeBy(2_000)
        val body = s.model.requests.single()
        assertEquals("gpt-6.1-terra", body["model"]!!.jsonPrimitive.content)
        assertTrue(body.toString().contains("\"effort\":\"medium\""))
        // Niveau Standard : `priority` doublait chaque facture sans gain mesuré, et la voix n'attend pas.
        assertTrue(body["service_tier"] == null)
        // Le réglage du chat n'a pas bougé.
        assertEquals(app.trackly.assistant.openai.ReasoningEffort.Default, s.assistant.reasoning.effort.value)
        s.services.abort(EndReason.User)
    }

    /** Historique qui met [delayMs] à répondre, comme une lecture en base. */
    private class SlowHistory(private val inner: ChatHistory, private val delayMs: Long) : ChatHistory by inner {
        override suspend fun last(day: LocalDate, limit: Int): List<ChatMessage> {
            delay(delayMs)
            return inner.last(day, limit)
        }
    }

    @Test
    fun `ecran referme pendant la lecture du fil du jour - aucun appel ne part`() = runTest {
        val s = Setup(this, historyDelayMs = 1_000)
        s.services.start(micAllowed = true)
        runCurrent()
        s.services.hangUp()
        advanceTimeBy(2_000)
        assertFalse(s.services.screenOpen.value)
        assertTrue(s.transports.all { it.openedWith == null })
        assertEquals(0, s.platform.foreground)
        assertFalse(s.services.controller.isActive)
    }

    @Test
    fun `ecran referme pendant la lecture de la cle - aucun appel ne part`() = runTest {
        val s = Setup(this, keyDelayMs = 1_000)
        s.services.start(micAllowed = true)
        runCurrent()
        assertTrue(s.services.screenOpen.value)
        // Raccrocher (ou retour) avant que l'appel soit parti : l'écran se referme.
        s.services.hangUp()
        advanceTimeBy(2_000)
        assertFalse(s.services.screenOpen.value)
        assertTrue(s.transports.all { it.openedWith == null })
        assertEquals(0, s.platform.foreground)
        assertFalse(s.services.controller.isActive)
    }

    @Test
    fun `transport prepare ferme si l ecran se referme avant le depart`() = runTest {
        val s = Setup(this)
        s.services.prepare()
        runCurrent()
        val prepared = s.transports.single()
        // Clé changée entre-temps : relecture lente au toucher.
        s.keys.value = 1
        runCurrent()
        assertTrue("préparation refaite après le changement de clé", prepared.closed)
        assertEquals(2, s.transports.size)
        assertEquals(1, s.transports.last().prepared)
    }

    @Test
    fun `reseau change - la preparation n est pas reutilisee`() = runTest {
        val s = Setup(this)
        s.services.prepare()
        runCurrent()
        val first = s.transports.single()
        s.net.value = "4g"
        s.services.start(micAllowed = true)
        runCurrent()
        assertTrue(first.closed)
        assertNull(first.openedWith)
        assertEquals(2, s.transports.size)
        assertEquals("sk-test-fausse", s.transports.last().keyUsed)
        s.services.abort(EndReason.User)
    }

    @Test
    fun `appel refuse par OpenAI - aucun cout compte`() = runTest {
        val s = Setup(this, failing = TransportException("Clé refusée par OpenAI (401)", 401))
        s.services.start(micAllowed = true)
        runCurrent()
        advanceTimeBy(500)
        assertEquals(EndReason.Failed, s.services.controller.state.value.endReason)
        assertTrue(s.sink.costs.isEmpty())
        assertEquals(0, s.platform.foreground)
        assertEquals(1, s.sink.metrics.size)
    }

    @Test
    fun `muet touche pendant la connexion - le choix est garde, le micro ne s ouvre pas`() = runTest {
        val s = Setup(this, keyDelayMs = 1_000)
        s.services.start(micAllowed = true)
        runCurrent()
        // L'écran est ouvert, l'appel n'est pas encore parti (clé en cours de lecture) : l'utilisateur touche Muet.
        s.services.toggleMute()
        assertTrue(s.services.controller.state.value.micMuted)
        advanceTimeBy(2_000)
        val t = s.transports.single()
        assertTrue("l'appel est parti", t.openedWith != null)
        assertTrue("micro du transport coupé", t.micMuted)
        assertTrue("l'écran montre toujours Muet", s.services.controller.state.value.micMuted)
        s.services.abort(EndReason.User)
    }

    @Test
    fun `focus audio refuse - message clair, aucune session payante ne part`() = runTest {
        val busy = object : CallAudio {
            override fun begin(): AudioRoute? = null
            override fun setSpeaker(on: Boolean) = AudioRoute.Speaker
            override fun end() = Unit
        }
        val s = Setup(this, audio = busy)
        s.services.start(micAllowed = true)
        runCurrent()
        assertTrue(s.transports.all { it.openedWith == null })
        assertEquals(EndReason.AudioBusy, s.services.controller.state.value.endReason)
        assertEquals(EndReason.AudioBusy.label, s.services.controller.state.value.status)
        assertEquals(0, s.platform.foreground)
        assertTrue(s.sink.costs.isEmpty())
        s.services.close()
        assertFalse(s.services.screenOpen.value)
    }
}
