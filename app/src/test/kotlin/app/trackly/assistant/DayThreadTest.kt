package app.trackly.assistant

import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.RecordState
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedTransport
import app.trackly.testing.SettableClock
import app.trackly.testing.ScriptedModel.Companion.text
import app.trackly.testing.ScriptedModel.Companion.toolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fil du jour : le chat affiche et envoie seulement les messages de la date de rattachement courante ; à 5 h
 * il repart à zéro ; « Effacer la discussion » masque le fil sans toucher à une consommation. Temps contrôlé, aucun réseau.
 */
class DayThreadTest {
    private fun zurich(local: String): Instant = LocalDateTime.parse(local).atZone(TrackingDay.ZONE).toInstant()

    private val yesterday = LocalDate.parse("2026-11-01")
    private val today = LocalDate.parse("2026-11-02")

    private val clock = SettableClock(zurich("2026-11-01T20:00"))
    private val engine = TracklyEngine(InMemoryStore(), clock).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }
    private val history = InMemoryChatHistory()
    private val thread = DayThread(history) { clock.instant() }

    private fun turns(model: ResponsesTransport) = ChatTurns(
        engine, history, TurnInterpreter(ResponsesClient(model, RetryPolicy.None), engine),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    /** Messages `user` / `assistant` (hors contexte `developer`) de la requête, dans l'ordre. */
    private fun conversation(request: JsonObject): List<Pair<String, String>> = request["input"]!!.jsonArray.map { it.jsonObject }
        .filter { it["role"]?.jsonPrimitive?.content in setOf("user", "assistant") }
        .map { item ->
            val role = item["role"]!!.jsonPrimitive.content
            val content = item["content"]!!
            role to (if (role == "user") content.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content else content.jsonPrimitive.content)
        }

    // --- Ce que le chat affiche et envoie ---------------------------------------------------------

    @Test
    fun `a 5 h le chat repart a zero - le modele ne recoit rien de la veille`() = runBlocking<Unit> {
        val model = ScriptedModel({ text("Bonsoir.") }, { text("Bonjour.") })
        val turns = turns(model)
        turns.send("salut ce soir")
        turns.awaitIdle()

        clock.current = zurich("2026-11-02T05:00")
        turns.send("salut ce matin")
        turns.awaitIdle()

        assertEquals(listOf("user" to "salut ce matin"), conversation(model.requests[1]))
        assertEquals(listOf("salut ce matin", "Bonjour."), thread.last(10).map { it.text })
    }

    @Test
    fun `avant 5 h, c est encore le fil de la veille - le modele recoit la suite`() = runBlocking<Unit> {
        val model = ScriptedModel({ text("Bonsoir.") }, { text("Encore là.") })
        val turns = turns(model)
        turns.send("salut ce soir")
        turns.awaitIdle()

        clock.current = zurich("2026-11-02T04:59")
        turns.send("encore moi")
        turns.awaitIdle()

        assertEquals(
            listOf("user" to "salut ce soir", "assistant" to "Bonsoir.", "user" to "encore moi"),
            conversation(model.requests[1]),
        )
    }

    @Test
    fun `les messages de la veille restent en base, sans interface`() = runBlocking<Unit> {
        val turns = turns(ScriptedModel({ text("Bonsoir.") }, { text("Bonjour.") }))
        turns.send("salut ce soir")
        turns.awaitIdle()

        clock.current = zurich("2026-11-02T05:00")
        turns.send("salut ce matin")
        turns.awaitIdle()

        assertEquals(listOf("salut ce matin", "Bonjour."), thread.last(10).map { it.text })
        // Toujours là pour qui demande la veille : rien n'est supprimé.
        assertEquals(listOf("salut ce soir", "Bonsoir."), history.last(yesterday, 10).map { it.text })
    }

    // --- Passage de 5 h pendant un tour ------------------------------------------------------------

    @Test
    fun `une reponse qui arrive apres 5 h ne plante pas et n est pas perdue`() = runBlocking<Unit> {
        // Le modèle répond à 5 h 01 à un message envoyé à 4 h 59.
        val model = ScriptedModel({
            clock.current = zurich("2026-11-02T05:01")
            text("Bonne nuit, ou plutôt bonjour.")
        })
        val turns = turns(model)
        clock.current = zurich("2026-11-02T04:59")

        turns.send("je ne dors pas")
        turns.awaitIdle()

        assertNull("pas d'erreur affichée", turns.state.value.error)
        assertEquals("la réponse est dans le fil que l'utilisateur regarde maintenant", listOf("Bonne nuit, ou plutôt bonjour."), thread.last(10).map { it.text })
        assertEquals(listOf("je ne dors pas"), history.last(yesterday, 10).map { it.text })
        assertEquals("Bonne nuit, ou plutôt bonjour.", turns.state.value.live?.text)
    }

    // --- Effacer la discussion ---------------------------------------------------------------------

    @Test
    fun `effacer masque le fil du jour et le modele ne recoit plus rien`() = runBlocking<Unit> {
        val model = ScriptedModel({ text("Bonjour.") }, { text("Re-bonjour.") })
        val turns = turns(model)
        clock.current = zurich("2026-11-02T08:00")
        turns.send("salut")
        turns.awaitIdle()

        val hidden = thread.clear()
        turns.send("on repart")
        turns.awaitIdle()

        assertEquals(2, hidden)
        assertEquals(listOf("user" to "on repart"), conversation(model.requests[1]))
        assertEquals(listOf("on repart", "Re-bonjour."), thread.last(10).map { it.text })
    }

    @Test
    fun `effacer ne touche a aucune consommation enregistree par le chat`() = runBlocking<Unit> {
        clock.set("2026-11-02T08:00")
        val turns = turns(ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"))
        turns.send(snack)
        turns.awaitIdle()
        val before = engine.daySummary(today)
        val entries = before.consumptions.map { it.entryId }
        val versionsBefore = entries.map { engine.history(it) }
        assertEquals(300.0, before.totals.kcal, 0.001)

        assertEquals(2, thread.clear())

        assertEquals(before, engine.daySummary(today))
        assertEquals(versionsBefore, entries.map { engine.history(it) })
        assertTrue(entries.all { engine.history(it).last().state == RecordState.Active })
    }

    @Test
    fun `effacer sans message ne fait rien et ne plante pas`() = runBlocking<Unit> {
        assertEquals(0, thread.clear())
    }

    // --- Un repas d'hier reste corrigeable ---------------------------------------------------------

    @Test
    fun `apres la remise a zero, un repas d hier se corrige en le nommant`() = runBlocking<Unit> {
        val yaourt = Food("Yaourt nature", Nutrition(75.0, 4.5, 6.0, 3.8))
        val yaourtId = (engine.execute(AddConsumptions(NewConsumption(yaourt, Moment.Soir, explicitDay = yesterday))) as ActionOutcome.Applied)
            .result.consumptionChanges.single().after.entryId
        // Une conversation d'hier soir, puis le matin d'après.
        history.append(clock.instant(), ChatRole.User, "un yaourt hier soir")
        clock.current = zurich("2026-11-02T08:00")
        val model = ScriptedModel(
            { toolCall("get_day", """{"day": "2026-11-01"}""", "a") },
            { req -> toolCall("delete_consumption", """{"entry_id": "${req.entryIdInToolOutput("Yaourt")}"}""", "b") },
            { text("C’est retiré d’hier soir.") },
        )
        val turns = turns(model)

        turns.send("enlève le yaourt d'hier")
        turns.awaitIdle()

        assertEquals(RecordState.Deleted, engine.history(yaourtId).last().state)
        // Le modèle n'a rien reçu de la discussion d'hier : il est allé lire le journal.
        assertEquals(listOf("user" to "enlève le yaourt d'hier"), conversation(model.requests[0]))
    }

    /** Transport qui exécute [action] juste avant la requête n° [atCall] (à partir de 0), puis laisse passer. */
    private class Hooked(private val inner: ResponsesTransport, private val atCall: Int, private val action: suspend () -> Unit) : ResponsesTransport {
        private var calls = 0
        override suspend fun send(body: JsonObject): JsonObject {
            if (calls++ == atCall) action()
            return inner.send(body)
        }
    }

    private val snack = "comme snack j'ai pris une barre protéinée et une pomme, 150 g"

    @Test
    fun `un tour avec carte a cheval sur 5 h garde sa carte et sa reponse dans le nouveau fil`() = runBlocking<Unit> {
        clock.set("2026-11-02T04:58")
        // L'enregistrement passe à 4 h 58 ; la phrase de conclusion arrive à 5 h 01.
        val transport = Hooked(ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"), atCall = 1) { clock.set("2026-11-02T05:01") }
        val turns = turns(transport)

        turns.send(snack)
        turns.awaitIdle()

        assertNull(turns.state.value.error)
        val reply = thread.last(10).single()
        assertEquals(ChatRole.Assistant, reply.role)
        assertTrue("la carte d'enregistrement (avec son Annuler) est visible", reply.cards.single() is RecordCard)
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", reply.text)
        assertEquals(listOf(snack), history.last(yesterday, 10).map { it.text })
        // Le repas est rattaché à hier (règle des 5 h sur l'heure du message), le chat n'y change rien.
        assertEquals(300.0, engine.daySummary(yesterday).totals.kcal, 0.001)
    }

    @Test
    fun `effacer pendant un tour ne perd ni la reponse ni la carte Annuler`() = runBlocking<Unit> {
        clock.set("2026-11-02T08:00")
        // La carte est écrite, puis l'utilisateur efface avant la fin du texte.
        val transport = Hooked(ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"), atCall = 1) { thread.clear() }
        val turns = turns(transport)

        turns.send(snack)
        turns.awaitIdle()

        assertNull(turns.state.value.error)
        val reply = thread.last(10).single()
        assertTrue(reply.cards.single() is RecordCard)
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", reply.text)
        assertEquals(300.0, engine.daySummary(today).totals.kcal, 0.001)
    }

    @Test
    fun `effacer retire la reponse terminee affichee, pas seulement les messages`() = runBlocking<Unit> {
        clock.set("2026-11-02T08:00")
        val turns = turns(ScriptedModel({ text("Bonjour.") }))
        val thread = DayThread(history, onCleared = turns::clearFinished) { clock.instant() }
        turns.send("salut")
        turns.awaitIdle()
        assertEquals("Bonjour.", turns.state.value.live?.text)

        thread.clear()

        assertNull(turns.state.value.live)
        assertTrue(thread.last(10).isEmpty())
    }

    @Test
    fun `effacer retire aussi l erreur affichee`() = runBlocking<Unit> {
        clock.set("2026-11-02T08:00")
        val turns = turns(ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet)))
        val thread = DayThread(history, onCleared = turns::clearFinished) { clock.instant() }
        turns.send("salut")
        turns.awaitIdle()
        assertTrue(turns.state.value.error != null)

        thread.clear()

        assertNull(turns.state.value.error)
    }

    // --- Le changement de jour, pendant que le chat est ouvert --------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `chat ouvert, le jour change a 5 h sans rien faire`() = runTest {
        val start = zurich("2026-11-02T04:58")
        val virtual = object : Clock() {
            override fun getZone(): ZoneId = TrackingDay.ZONE
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
        val seen = mutableListOf<LocalDate>()
        val job = backgroundScope.launch { DayThread(history) { virtual.instant() }.days().toList(seen) }

        runCurrent()
        assertEquals(listOf(yesterday), seen)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("4 h 59 : toujours la veille", listOf(yesterday), seen)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("5 h 00 : le nouveau jour", listOf(yesterday, today), seen)
        advanceTimeBy(60 * 60_000)
        runCurrent()
        assertEquals("pas de répétition", listOf(yesterday, today), seen)
        job.cancel()
    }

    @Test
    fun `le fil observe passe au nouveau jour a 5 h`() = runTest {
        val start = zurich("2026-11-02T04:59")
        val virtual = object : Clock() {
            override fun getZone(): ZoneId = TrackingDay.ZONE
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
        history.append(zurich("2026-11-02T04:00"), ChatRole.User, "cette nuit")
        history.append(zurich("2026-11-02T05:00"), ChatRole.User, "ce matin")
        val shown = mutableListOf<List<String>>()
        val job = backgroundScope.launch {
            DayThread(history) { virtual.instant() }.observe(10).collect { list -> shown += list.map { it.text } }
        }

        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(listOf("cette nuit"), listOf("ce matin")), shown)
        job.cancel()
    }
}
