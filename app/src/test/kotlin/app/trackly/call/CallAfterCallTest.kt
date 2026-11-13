package app.trackly.call

import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * L'appel, puis ce qui suit, de bout en bout avec les vrais services : le raccroché ne bloque rien, la relecture écrit
 * la question restée sans réponse dans le fil du jour, l'app reste en vie jusqu'à sa fin, et le chat reprend ensuite son
 * modèle et son raisonnement habituels ; la réponse de l'utilisateur dans le chat corrige le repas par les actions habituelles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallAfterCallTest {
    private val monday = LocalDate.parse("2026-11-02")

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class Platform : CallPlatform {
        var holds = 0
        override fun startForeground() = Unit
        override fun stopForeground() = Unit
        override fun vibrate() = Unit
        override fun holdUntilSettled() {
            holds++
        }
    }

    private inner class Setup(scope: TestScope, callSteps: List<suspend (ScriptedModel.Request) -> kotlinx.serialization.json.JsonObject>, chat: ScriptedModel = ScriptedModel()) {
        val engine = SampleData.engine()
        val unseen = UnseenQuestions()
        val chatModel = chat
        val assistant = AssistantServices.inMemory(engine, transport = chatModel, unseenQuestions = unseen)
        val callRequests = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val usage = mutableListOf<UsageEvent>()
        private var step = 0
        val thinker = AgentCallThinker(
            ResponsesClient({ body ->
                callRequests += body
                callSteps[step++](ScriptedModel.Request(body))
            }, RetryPolicy.None),
            engine, assistant.history, UsageSink { usage += it },
        )
        val transports = mutableListOf<FakeLiveTransport>()
        val platform = Platform()
        val store = InMemoryPendingReviewStore()
        val settings = InMemoryCallSettings()
        val services = CallServices(
            scope = scope.backgroundScope,
            settings = settings,
            readKey = { "sk-test-fausse" },
            dayThread = assistant.thread,
            thinker = thinker,
            newTransport = { FakeLiveTransport().also { transports += it } },
            platform = platform,
            now = { scope.testScheduler.currentTime },
            wallClock = engine::now,
            io = StandardTestDispatcher(scope.testScheduler),
            review = CallReview(scope.backgroundScope, store, thinker, assistant.history, unseen, engine::now, io = StandardTestDispatcher(scope.testScheduler)),
        )

        suspend fun thread() = assistant.history.last(monday, 50)
    }

    @Test
    fun `question restee sans reponse - ecrite apres l appel, puis le chat reprend son modele et corrige le repas`() = runTest {
        val reviewing = CompletableDeferred<Unit>()
        val s = Setup(
            this,
            listOf(
                { CallReplies.recordFood("Pomme", 80, grams = 150) },
                { CallReplies.answer(questions = listOf("Ta pomme, grosse ou petite ?"), recap = "🍎 **Pomme** notée, 80 kcal.") },
                { r ->
                    assertTrue(r.body.toString().contains("Relecture après l'appel"))
                    reviewing.await()
                    CallReplies.review(questions = listOf("Ta pomme, grosse ou petite ?"))
                },
            ),
            chat = ScriptedModel(
                { r -> CallReplies.correctFood(r.entryIdInContext("Pomme"), 200, 105) },
                { ScriptedModel.text("C'est corrigé : grosse pomme, 105 kcal. 🍎") },
            ),
        )
        s.settings.setReflection(CallReflection.Fast)
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Ce midi une pomme."))
        t.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_000)
        // L'utilisateur raccroche sans répondre à la question.
        s.services.hangUp()
        advanceTimeBy(100)
        t.emit(ServerEvents.closed("close_requested", 20.0))
        advanceTimeBy(500)

        // L'écran n'attend pas la relecture ; l'app, si (micro fermé).
        assertTrue(s.services.settling.value)
        assertTrue(s.platform.holds > 0)
        s.services.close()
        assertFalse(s.services.screenOpen.value)
        reviewing.complete(Unit)
        runCurrent()
        assertFalse(s.services.settling.value)

        // Dans le fil du jour : la carte et le récap de l'appel, puis la question, sans répéter la carte.
        val thread = s.thread()
        assertEquals(2, thread.size)
        assertTrue(thread[0].text.startsWith(AgentCallThinker.HEADER))
        assertTrue(thread[0].cards.single() is RecordCard)
        assertEquals("📞 Après l’appel\n\nIl me reste une question :\n- Ta pomme, grosse ou petite ?", thread[1].text)
        assertTrue(thread[1].cards.isEmpty())
        assertEquals(1, s.unseen.state.value!!.count)
        assertTrue(s.store.isEmpty())
        // Relecture au réglage de l'appel (Rapide : Terra), comptée dans le suivi d'usage.
        val review = s.callRequests.last()
        assertEquals("gpt-6.1-terra", review["model"]!!.jsonPrimitive.content)
        assertEquals(3, s.usage.size)

        // L'utilisateur répond dans le chat normal : modèle et raisonnement du chat, pas ceux de l'appel.
        assertTrue(s.assistant.turns.send("Une grosse.", ReasoningEffort.Low))
        s.assistant.turns.awaitIdle()
        val chat = s.chatModel.requests.first()
        assertEquals("gpt-6.1-sol", chat["model"]!!.jsonPrimitive.content)
        assertEquals("low", chat["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull("le niveau prioritaire de l'appel ne reste pas", chat["service_tier"])
        assertFalse("plus de consignes d'appel", chat.toString().contains("Appel vocal en cours") || chat.toString().contains("Relecture après l'appel"))
        // Le chat voit la question dans le fil du jour et corrige la pomme par l'action habituelle.
        assertTrue(chat.toString().contains("Ta pomme, grosse ou petite ?"))
        assertEquals(105.0, s.engine.daySummary(monday).consumptions.single { it.food.label == "Pomme" }.food.nutrition.kcal, 0.01)
    }

    @Test
    fun `tout traite pendant l appel - aucune relecture, aucun cout, l app n est pas retenue`() = runTest {
        val s = Setup(
            this,
            listOf(
                { CallReplies.recordFood("Pomme", 80, grams = 200) },
                { CallReplies.answer(recap = "🍎 **Grosse pomme** notée.") },
            ),
        )
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Ce midi une grosse pomme de 200 g."))
        t.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(3_000)
        s.services.hangUp()
        advanceTimeBy(100)
        t.emit(ServerEvents.closed("close_requested", 20.0))
        advanceTimeBy(500)
        assertFalse(s.services.settling.value)
        assertEquals("deux requêtes : la réflexion seulement", 2, s.callRequests.size)
        assertEquals(1, s.thread().size)
        assertNull(s.unseen.state.value)
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `appel lance pendant une relecture - elle cede la reflexion tout de suite, puis reprend apres l appel`() = runTest {
        val never = CompletableDeferred<Unit>()
        val s = Setup(
            this,
            listOf(
                { never.await(); error("jamais") },
                { CallReplies.recordFood("Pomme", 80, grams = 150) },
                { CallReplies.answer(recap = "🍎 Pomme notée.") },
                { r ->
                    assertTrue(r.body.toString().contains("- Une salade ce soir."))
                    CallReplies.review(questions = listOf("Quelle salade ?"))
                },
            ),
        )
        s.store.put(
            PendingReview(
                s.engine.now().minusSeconds(600), s.engine.now().minusSeconds(500), CallReflection.Medium,
                listOf(CallLine(Speaker.User, "Une salade ce soir.")), unprocessed = listOf("Une salade ce soir."),
            ),
        )
        s.services.resumeReview()
        runCurrent()
        assertEquals("la relecture réfléchit", 1, s.callRequests.size)

        // L'utilisateur rappelle : la relecture s'arrête, sans compter cet essai.
        s.services.start(micAllowed = true)
        runCurrent()
        assertEquals(0, s.store.all().single().attempts)
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Ce midi une pomme de 150 g."))
        t.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_000)
        assertTrue("la réflexion de l'appel n'attend pas", "Pomme" in s.engine.daySummary(monday).consumptions.map { it.food.label })
        // Pas de relecture pendant l'appel, même à l'ouverture.
        s.services.resumeReview()
        runCurrent()
        assertEquals(3, s.callRequests.size)

        s.services.hangUp()
        advanceTimeBy(100)
        t.emit(ServerEvents.closed("close_requested", 20.0))
        advanceTimeBy(500)
        // Après le rapport du nouvel appel, la relecture laissée en attente reprend.
        assertEquals(4, s.callRequests.size)
        assertTrue(s.thread().last().text.endsWith("- Quelle salade ?"))
        assertTrue(s.store.isEmpty())
        assertFalse(s.services.settling.value)
    }

    @Test
    fun `reprise a l ouverture - jamais pendant un appel`() = runTest {
        val s = Setup(this, listOf({ CallReplies.review(questions = listOf("Quelle salade ?")) }))
        s.store.put(
            PendingReview(
                s.engine.now().minusSeconds(60), s.engine.now(), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une salade ce soir.")),
                unprocessed = listOf("Une salade ce soir."),
            ),
        )
        s.services.start(micAllowed = true)
        runCurrent()
        s.services.resumeReview()
        runCurrent()
        assertTrue("pas de relecture pendant un appel", s.callRequests.isEmpty())
        s.services.abort(EndReason.User)
        s.services.close()
        runCurrent()
        s.services.resumeReview()
        runCurrent()
        assertEquals(1, s.callRequests.size)
        assertTrue(s.thread().single().text.endsWith("- Quelle salade ?"))
    }

    /** L'app relancée après un arrêt d'Android : une nouvelle relecture, sur le même fichier, la même base et le même fil. */
    private fun Setup.relaunched(scope: TestScope, vararg steps: (ScriptedModel.Request) -> kotlinx.serialization.json.JsonObject): Pair<CallReview, MutableList<kotlinx.serialization.json.JsonObject>> {
        val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val model = ScriptedModel(*steps)
        val thinker = AgentCallThinker(ResponsesClient({ body -> requests += body; model.send(body) }, RetryPolicy.None), engine, assistant.history, UsageSink { })
        val review = CallReview(scope.backgroundScope, store, thinker, assistant.history, unseen, engine::now, io = StandardTestDispatcher(scope.testScheduler))
        return review to requests
    }

    @Test
    fun `app tuee pendant l appel, reflexion en cours - a la reprise le repas est note sans doublon`() = runTest {
        val never = CompletableDeferred<Unit>()
        val s = Setup(
            this,
            listOf(
                { CallReplies.recordFood("Pomme", 80, grams = 150) },
                { never.await(); error("tuée avant la fin") },
            ),
        )
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Ce midi une pomme et un yaourt nature."))
        t.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(1_000)
        // La réflexion a noté la pomme, pas encore le yaourt ; l'utilisateur ajoute un café pendant qu'elle réfléchit.
        val pommeId = (s.thread().single().cards.single() as RecordCard).items.single().entryId
        t.emit(ServerEvents.input(" Ah, et un café."))
        advanceTimeBy(1_500)
        // Android tue l'app ici : ni raccroché, ni rapport. Seul ce qui est sur le téléphone reste.
        val kept = s.store.all().single()
        assertTrue(kept.provisional)
        assertTrue(kept.transcript.joinToString { it.text }.contains("un café"))

        val (review, requests) = s.relaunched(
            this,
            { r ->
                val text = r.body.toString()
                assertTrue(text.contains("yaourt nature") && text.contains("un café"))
                assertTrue(text.contains("[entry_id " + pommeId + "]"))
                // Le modèle renote la pomme : la garde refuse.
                CallReplies.recordFoods("Pomme" to 80, "Yaourt nature" to 60, "Café" to 2, id = "a")
            },
            { r ->
                assertEquals("error", r.lastToolOutput["status"]!!.jsonPrimitive.content)
                CallReplies.recordFoods("Yaourt nature" to 60, "Café" to 2, id = "b")
            },
            { CallReplies.review(recap = "Yaourt et café notés.") },
        )
        assertTrue(review.resume())
        runCurrent()
        assertEquals(3, requests.size)
        val labels = s.engine.daySummary(monday).consumptions.map { it.food.label }
        assertEquals(1, labels.count { it == "Pomme" })
        assertEquals(1, labels.count { it == "Yaourt nature" })
        assertEquals(1, labels.count { it == "Café" })
        assertTrue(s.thread().last().text.startsWith(CallReview.HEADER))
        assertTrue("fichier effacé après la relecture", s.store.isEmpty())
    }

    @Test
    fun `app tuee juste apres un repas dicte, avant toute reflexion - a la reprise il est relu, jamais perdu`() = runTest {
        val s = Setup(this, listOf())
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Ce soir des pâtes au thon."))
        // Phrase finie, mais ni la voix ni le filet de 2,5 s n'ont encore lancé de réflexion.
        advanceTimeBy(1_200)
        assertTrue(s.callRequests.isEmpty())
        val kept = s.store.all().single()
        assertTrue(kept.provisional)

        val (review, requests) = s.relaunched(this, { CallReplies.review(questions = listOf("Combien de pâtes, à peu près ?")) })
        assertTrue(review.resume())
        runCurrent()
        assertTrue(requests.single().toString().contains("Ce soir des pâtes au thon."))
        assertTrue(s.thread().last().text.endsWith("- Combien de pâtes, à peu près ?"))
    }

    @Test
    fun `bavardage pendant l appel - rien n est garde sur le telephone`() = runTest {
        val s = Setup(this, listOf())
        s.services.start(micAllowed = true)
        runCurrent()
        val t = s.transports.single()
        t.emit(ServerEvents.started())
        t.emit(ServerEvents.input("Salut, ça va ?"))
        advanceTimeBy(1_200)
        assertTrue(s.store.isEmpty())
        s.services.abort(EndReason.User)
    }
}
