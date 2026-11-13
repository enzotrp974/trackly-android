package app.trackly.assistant

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Origin
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Le tour de conversation. Un message, des réponses de modèle
 * simulées (JSON écrits d'après la doc), aucune requête réseau ; on vérifie les actions
 * appliquées par le moteur, les cartes, le bilan et les requêtes envoyées.
 */
class TurnInterpreterTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(local: String = "2026-11-02T16:10"): TracklyEngine = SampleData.emptyEngine(SampleData.clock(local)).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport, usage: UsageSink = UsageSink.None) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine, usageSink = usage)

    private val snack = "comme snack j'ai pris une barre protéinée et une pomme, 150 g"

    @Test
    fun `scenario de reference - le snack est enregistre aussitot, la carte suit, le bilan se met a jour, Annuler le retire`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")

        val outcome = interpreter(engine, transport).run(TurnInput(snack))

        assertTrue(outcome is TurnOutcome.Completed)
        outcome as TurnOutcome.Completed
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.", outcome.reply)
        assertEquals(listOf("record_consumptions"), outcome.toolCalls.map { it.name })

        // Moteur : deux consommations actives, au snack du jour, d'origine assistant.
        val day = engine.daySummary(monday)
        assertEquals(listOf("Barre protéinée Nuvo", "Pomme"), day.consumptions.map { it.food.label })
        assertTrue(day.consumptions.all { it.moment == Moment.Snack && it.origin == Origin.Assistant })
        assertEquals(300.0, day.totals.kcal, 0.001)
        assertEquals(2300.0, day.remainingKcal!!, 0.001)
        assertEquals(listOf("Nuvo chocolat-cacahuète, ta saveur habituelle"), day.consumptions[0].food.assumptions)

        // Carte : construite par le code depuis le résultat de l'action.
        val card = outcome.cards.single() as RecordCard
        assertEquals(RecordKind.Added, card.kind)
        assertEquals(listOf("Barre protéinée Nuvo" to "55 g", "Pomme" to "150 g"), card.items.map { it.label to it.quantity })
        assertEquals(300.0, card.totalKcal, 0.001)
        assertEquals(20.0, card.protein.grams, 0.001)
        assertEquals(31.0, card.carbs.grams, 0.001)
        assertEquals(8.0, card.fat.grams, 0.001)
        assertTrue(card.protein.complete)
        assertEquals("Snack", card.momentsLabel)
        assertEquals(listOf("Nuvo chocolat-cacahuète, ta saveur habituelle"), card.assumptions)

        // Annuler (bouton de la carte) : action d'annulation du moteur, le bilan revient à zéro.
        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(2, engine.history(day.consumptions[0].entryId).size) // rien n'est effacé
    }

    @Test
    fun `la requete suit le contrat - modele Sol, raisonnement moyen, outils stricts, sans stockage chez OpenAI`() = runBlocking {
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")

        interpreter(engine(), transport).run(TurnInput(snack))

        val first = transport.requests[0]
        assertEquals("gpt-6.1-sol", first.string("model"))
        assertEquals("false", first["store"]!!.jsonPrimitive.content)
        assertEquals("medium", first["reasoning"]!!.jsonObject.string("effort"))
        // Cache d'entrée gardé 12 h : le préfixe stable sert à tous les messages de la journée.
        assertEquals("12h", first["prompt_cache_options"]!!.jsonObject.string("ttl"))
        val allTools = first["tools"]!!.jsonArray.map { it.jsonObject }
        // La recherche web est un outil intégré d'OpenAI, sans nom ni mode strict : à part.
        assertEquals("web_search", allTools.last().string("type"))
        val tools = allTools.filter { it.string("type") == "function" }
        assertEquals(
            listOf(
                "record_consumptions", "correct_consumption", "delete_consumption", "undo_action", "get_day",
                "search_products", "switch_product", "update_product", "memorize_product", "set_goal", "manage_dish", "switch_dish", "update_provisions", "get_meal_ideas", "manage_term_correction", "manage_memory", "propose_meal",
            ),
            tools.map { it.string("name") },
        )
        assertTrue(tools.all { it["strict"]!!.jsonPrimitive.content == "true" })
        assertTrue(first.string("instructions")!!.contains("Ne sont JAMAIS enregistrés"))

        // Entrée : contexte du jour (developer), puis le message de l'utilisateur.
        val input = first["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("developer", "user"), input.map { it.string("role") })
        assertTrue(input[0].string("content")!!.contains("Jour de suivi en cours : lundi 2 novembre (2026-11-02)"))
        assertEquals(snack, input[1]["content"]!!.jsonArray[0].jsonObject.string("text"))

        // Second appel : la sortie du premier renvoyée telle quelle (raisonnement chiffré compris),
        // puis le résultat de l'outil, avec le bilan recalculé par l'app.
        val second = transport.requests[1]["input"]!!.jsonArray.map { it.jsonObject }
        val sent = OpenAiFixtures.json("snack-1-appel-outil.json")["output"]!!.jsonArray
        assertEquals(sent.toList(), second.subList(2, 4))
        val output = second[4]
        assertEquals("function_call_output", output.string("type"))
        assertEquals("call_snack_1", output.string("call_id"))
        val result = Json.parseToJsonElement(output.string("output")!!).jsonObject
        assertEquals("recorded", result.string("status"))
        val facts = result["days"]!!.jsonArray.single().jsonObject
        assertEquals(300.0, facts["consumed_kcal"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(2300.0, facts["remaining_kcal"]!!.jsonPrimitive.content.toDouble(), 0.0)

        // Préfixe stable pour le cache : mêmes consignes et mêmes outils d'un appel à l'autre.
        assertEquals(first["instructions"], transport.requests[1]["instructions"])
        assertEquals(first["tools"], transport.requests[1]["tools"])
    }

    @Test
    fun `une question n enregistre rien`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("question-reponse.json")

        val outcome = interpreter(engine, transport).run(TurnInput("combien de calories si je mange une pizza ce soir ?"))

        outcome as TurnOutcome.Completed
        assertTrue(outcome.reply.contains("Je n’ai rien enregistré"))
        assertTrue(outcome.cards.isEmpty())
        assertTrue(outcome.applied.isEmpty())
        assertEquals(1, transport.requests.size)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertTrue(engine.observeRecentActions(5).first().none { it.type == "consumption.add" })
    }

    @Test
    fun `une intention n enregistre rien`() = runBlocking {
        val engine = engine()
        val outcome = interpreter(engine, ScriptedTransport.replies("intention-reponse.json")).run(TurnInput("je vais manger des pâtes ce soir"))

        assertTrue(outcome is TurnOutcome.Completed)
        assertTrue(outcome.cards.isEmpty())
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    @Test
    fun `pour hier - la date exprimee prime sur la regle des 5 h`() = runBlocking {
        val engine = engine("2026-11-02T12:00")
        val outcome = interpreter(engine, ScriptedTransport.replies("pour-hier-appel-outil.json", "pour-hier-reponse.json"))
            .run(TurnInput("pour hier soir, j'ai mangé un yaourt nature"))

        assertTrue(outcome is TurnOutcome.Completed)
        val sunday = engine.daySummary(LocalDate.parse("2026-11-01"))
        assertEquals(listOf("Yaourt nature"), sunday.consumptions.map { it.food.label })
        assertEquals(Moment.Soir, sunday.consumptions.single().moment)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    @Test
    fun `a 0 h 30 sans date, la consommation compte pour la veille`() = runBlocking {
        val engine = engine("2026-11-03T00:30")
        interpreter(engine, ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")).run(TurnInput(snack))

        assertEquals(300.0, engine.daySummary(monday).totals.kcal, 0.001)
        assertEquals(0.0, engine.daySummary(LocalDate.parse("2026-11-03")).totals.kcal, 0.0)
        // L'heure réelle est conservée.
        assertEquals(SampleData.clock("2026-11-03T00:30").instant(), engine.daySummary(monday).consumptions[0].consumedAt)
    }

    @Test
    fun `des arguments invalides ne touchent a rien et l erreur revient au modele`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("invalide-appel-outil.json", "invalide-reponse.json")

        val outcome = interpreter(engine, transport).run(TurnInput("j'ai mangé une pomme"))

        assertTrue(outcome is TurnOutcome.Completed)
        assertTrue(outcome.cards.isEmpty())
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        val output = transport.requests[1]["input"]!!.jsonArray.last().jsonObject
        val result = Json.parseToJsonElement(output.string("output")!!).jsonObject
        assertEquals("error", result.string("status"))
    }

    @Test
    fun `annule ca - l outil d annulation retire le dernier enregistrement`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json", "annule-appel-outil.json", "annule-reponse.json")
        val assistant = interpreter(engine, transport)
        val first = assistant.run(TurnInput(snack)) as TurnOutcome.Completed

        val second = assistant.run(
            TurnInput(
                "annule ça",
                history = listOf(HistoryMessage(HistoryMessage.Role.User, snack), HistoryMessage(HistoryMessage.Role.Assistant, first.reply)),
            ),
        )

        val undo = second.cards.single() as UndoCard
        assertEquals((first.cards.single() as RecordCard).actionId, undo.undoneActionId)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        // L'historique précède le contexte et le message : préfixe stable pour le cache.
        val input = transport.requests[2]["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "developer", "user"), input.map { it.string("role") })
    }

    @Test
    fun `erreur avant tout enregistrement - le message peut etre renvoye`() = runBlocking {
        val engine = engine()
        val outcome = interpreter(engine, ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet))).run(TurnInput(snack))

        outcome as TurnOutcome.Failed
        assertEquals(AssistantError.NoInternet, outcome.error)
        assertTrue(outcome.canRetry)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    @Test
    fun `erreur apres l enregistrement - la consommation reste, la carte aussi, pas de nouvel envoi`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ScriptedTransport.Reply(OpenAiFixtures.json("snack-1-appel-outil.json")),
            ScriptedTransport.Fail(AssistantError.NoInternet),
        )

        val outcome = interpreter(engine, transport).run(TurnInput(snack))

        outcome as TurnOutcome.Failed
        assertFalse("renvoyer créerait un doublon", outcome.canRetry)
        assertEquals(1, outcome.cards.size)
        assertEquals(300.0, engine.daySummary(monday).totals.kcal, 0.001)
    }

    @Test
    fun `l usage de chaque reponse est remonte, avec le niveau de raisonnement choisi`() = runBlocking {
        val events = mutableListOf<UsageEvent>()
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")

        val outcome = interpreter(engine(), transport, usage = { events += it }).run(TurnInput(snack, effort = ReasoningEffort.High))

        assertEquals("high", transport.requests[0]["reasoning"]!!.jsonObject.string("effort"))
        assertEquals(2, events.size)
        assertTrue(events.all { it.effort == ReasoningEffort.High && it.model == "gpt-6.1-sol" })
        assertEquals(2850, events[0].usage.inputTokens)
        assertEquals(2400, events[0].usage.cacheWriteTokens)
        assertEquals(320, events[0].usage.reasoningTokens)
        assertEquals(2400, events[1].usage.cachedTokens)
        assertEquals(0, events[0].webSearchRequests)
        assertEquals(2, outcome.usage.size)
    }

    @Test
    fun `garde-fou - une boucle d appels d outils s arrete`() = runBlocking {
        val engine = engine()
        val loop = OpenAiFixtures.json("invalide-appel-outil.json")
        val transport = ScriptedTransport(*Array(4) { ScriptedTransport.Reply(loop) })

        val outcome = TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine, config = AssistantConfig(maxToolRounds = 3))
            .run(TurnInput("j'ai mangé une pomme"))

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(4, transport.requests.size)
        assertNull((outcome as TurnOutcome.Failed).cards.firstOrNull())
    }

    @Test
    fun `une image est envoyee au modele en entree, en detail eleve`() = runBlocking {
        val transport = ScriptedTransport.replies("question-reponse.json")
        interpreter(engine(), transport).run(
            TurnInput("c'est combien ?", images = listOf(app.trackly.assistant.openai.ImageInput("data:image/jpeg;base64,AAAA"))),
        )
        val content = transport.requests[0]["input"]!!.jsonArray.last().jsonObject["content"] as JsonArray
        assertEquals("input_image", content[1].jsonObject.string("type"))
        assertEquals("high", content[1].jsonObject.string("detail"))
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
