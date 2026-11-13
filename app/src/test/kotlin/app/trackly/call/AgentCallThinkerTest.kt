package app.trackly.call

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Nutrients
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.LocalDate

/**
 * La réflexion pendant l'appel est l'agent Trackly du chat : mêmes consignes, mêmes outils, mêmes actions versionnées
 * et annulables. Elle seule note ; ce qu'elle note va dans le chat du jour, et elle rend ses questions dans l'ordre.
 * Réponses du modèle simulées : aucun appel réseau.
 */
class AgentCallThinkerTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val pomme = listOf(CallLine(Speaker.User, "Ce midi j'ai mangé une pomme."), CallLine(Speaker.Voice, "Ok."))

    private fun request(transcript: List<CallLine> = pomme, noted: List<NotedEntry> = emptyList(), asked: List<String> = emptyList()) =
        ThinkRequest(listOf("item_1"), transcript, CallReflection.Medium, noted, asked)

    private class Setup(val engine: TracklyEngine = SampleData.engine(), vararg steps: (ScriptedModel.Request) -> JsonObject) {
        val model = ScriptedModel(*steps)
        val history = InMemoryChatHistory()
        val usage = mutableListOf<UsageEvent>()
        val thinker = AgentCallThinker(ResponsesClient(model, RetryPolicy.None), engine, history, UsageSink { usage += it })
    }

    private suspend fun TracklyEngine.labels(day: LocalDate = LocalDate.parse("2026-11-02")) = daySummary(day).consumptions.map { it.food.label }

    @Test
    fun `aliment note avec une question - la question revient, la carte et le recap vont dans le chat du jour`() = runBlocking {
        val s = Setup(
            SampleData.engine(),
            { CallReplies.recordFood("Pomme", 80, grams = 150) },
            { CallReplies.answer(questions = listOf("Ta pomme, elle était grosse comment ?"), recap = "🍎 **Pomme** notée, 80 kcal (moyenne supposée).") },
        )
        val result = s.thinker.think(request()) as ThinkResult.Done
        assertNull(result.say)
        assertEquals(listOf("Ta pomme, elle était grosse comment ?"), result.questions)
        val noted = result.noted.single()
        assertEquals("Pomme", noted.label)
        assertEquals("midi", noted.moment)
        assertTrue("Pomme" in s.engine.labels())

        // Dans le chat du jour : la carte (Annuler, Corriger) et le récap, jamais la transcription.
        val message = s.history.last(monday, 10).single()
        assertEquals(ChatRole.Assistant, message.role)
        assertTrue(message.text.startsWith(AgentCallThinker.HEADER))
        assertTrue(message.text.contains("Pomme** notée"))
        assertFalse(message.text.contains("Ce midi j'ai mangé"))
        val card = message.cards.single() as RecordCard
        assertEquals(RecordKind.Added, card.kind)
        assertEquals(noted.actionId, card.actionId)
        assertEquals(2, s.usage.size)
    }

    @Test
    fun `requete - agent du chat, modele du reglage, niveau standard, cle de cache du chat, reponse structuree, fil du jour et transcription`() = runBlocking {
        val s = Setup(SampleData.engine(), { CallReplies.answer(questions = listOf("C'est quoi l'autre truc ?")) })
        val now = s.engine.now()
        s.history.append(now, ChatRole.User, "JOUR-SKYR : ce matin un skyr")
        s.history.append(now.minusSeconds(86_400), ChatRole.User, "HIER-SECRET : hier soir une raclette")
        val result = s.thinker.think(
            ThinkRequest(listOf("item_1"), listOf(CallLine(Speaker.User, "Une pomme et un autre truc.")), CallReflection.High, asked = listOf("Quelle taille ?")),
        ) as ThinkResult.Done
        assertEquals(listOf("C'est quoi l'autre truc ?"), result.questions)
        assertTrue("rien n'est noté, rien n'est écrit", result.noted.isEmpty() && s.history.last(monday, 10).size == 1)

        val body = s.model.requests.single()
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Niveau Standard : `priority` (« fast ») doublait chaque facture, un raté du cache passait de 0,11 à 0,22 $.
        assertNull(body["service_tier"])
        // Même clé de cache que le chat (mêmes consignes, mêmes outils) : le préfixe en cache est partagé.
        val chat = app.trackly.testing.ScriptedModel({ CallReplies.answer() })
        app.trackly.assistant.turn.TurnInterpreter(ResponsesClient(chat, RetryPolicy.None), SampleData.engine()).run(app.trackly.assistant.turn.TurnInput("une pomme"))
        val key = body["prompt_cache_key"]!!.jsonPrimitive.content
        assertTrue(key.startsWith("trackly-"))
        assertEquals(key, chat.requests.single()["prompt_cache_key"]!!.jsonPrimitive.content)
        // Mêmes consignes et mêmes outils que le chat, dans le même ordre (même préfixe pour le cache d'entrée).
        assertEquals(SystemPrompt.default().render(), body["instructions"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(ToolRegistry.default().definitions()), body["tools"])
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)
        val text = body.toString()
        assertTrue(text.contains("JOUR-SKYR"))
        assertFalse("seulement le fil du jour", text.contains("HIER-SECRET"))
        assertTrue(text.contains("${Speaker.User.label} : Une pomme et un autre truc."))
        assertTrue(text.contains("Appel vocal en cours"))
        assertTrue(text.contains("- Quelle taille ?"))
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("le contexte du jour, puis les consignes de l'appel", 2, input.count { it["role"]?.jsonPrimitive?.content == "developer" })
    }

    @Test
    fun `plat connu de sa memoire - note sans question, valeurs calculees par l app`() = runBlocking {
        val engine = SampleData.engine().also { e ->
            runBlocking {
                val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
                val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
                check(e.execute(CreateDish("Salade thon", listOf(laitue, thon), shortcuts = listOf("ma salade habituelle"), dishId = "rcp-salade")) is ActionOutcome.Applied)
            }
        }
        val s = Setup(
            engine,
            { r ->
                // Le plat et son raccourci sont dans le contexte : le modèle peut le reconnaître sans demander.
                assertTrue(r.context.contains("ma salade habituelle"))
                CallReplies.recordDish("rcp-salade")
            },
            { CallReplies.answer(recap = "🥗 **Salade thon** notée.") },
        )
        val result = s.thinker.think(request(listOf(CallLine(Speaker.User, "À midi ma salade habituelle.")))) as ThinkResult.Done
        assertTrue(result.questions.isEmpty())
        assertEquals("rcp-salade", result.noted.single().recipeId)
    }

    @Test
    fun `ajout pendant l appel - un aliment deja note est refuse, la reflexion corrige et ne note que le nouveau`() = runBlocking {
        val first = Setup(SampleData.engine(), { CallReplies.recordFood("Pomme", 80, grams = 150) }, { CallReplies.answer(questions = listOf("Grosse ou petite ?")) })
        val noted = (first.thinker.think(request()) as ThinkResult.Done).noted
        val pommeId = noted.single().entryId

        val engine = first.engine
        val outputs = mutableListOf<JsonObject>()
        val s = Setup(
            engine,
            // Le modèle se trompe et renote la pomme avec le yaourt : refusé, rien n'est enregistré.
            { CallReplies.recordFoods("Pomme" to 80, "Yaourt nature" to 60, id = "a") },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.correctFood(pommeId, 200, 105, id = "b")
            },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.recordFood("Yaourt nature", 60, id = "c")
            },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.answer(recap = "Pomme corrigée (200 g), yaourt ajouté.")
            },
        )
        val transcript = pomme + CallLine(Speaker.User, "Une grosse. Ah, et un yaourt nature.")
        val result = s.thinker.think(request(transcript, noted = noted)) as ThinkResult.Done
        assertEquals("error", outputs[0]["status"]!!.jsonPrimitive.content)
        assertTrue(outputs[0]["error"]!!.jsonPrimitive.content.contains(pommeId))
        assertEquals(listOf("Pomme", "Yaourt nature"), engine.labels().filter { it == "Pomme" || it == "Yaourt nature" }.sorted())
        assertEquals(105.0, engine.daySummary(monday).consumptions.single { it.food.label == "Pomme" }.food.nutrition.kcal, 0.01)
        assertEquals(setOf(pommeId), result.noted.filter { it.label == "Pomme" }.map { it.entryId }.toSet())
        // La liste « déjà noté » est donnée au modèle avec l'entry_id à corriger.
        assertTrue(s.model.requests.first().toString().contains("Pomme (midi, 2026-11-02) [entry_id $pommeId]"))
    }

    @Test
    fun `la garde vaut aussi dans la meme reflexion`() = runBlocking {
        val outputs = mutableListOf<JsonObject>()
        val s = Setup(
            SampleData.engine(),
            { CallReplies.recordFood("Pomme", 80, id = "a") },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.recordFood("pomme", 80, id = "b")
            },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.answer()
            },
        )
        s.thinker.think(request())
        assertEquals("recorded", outputs[0]["status"]!!.jsonPrimitive.content)
        assertEquals("error", outputs[1]["status"]!!.jsonPrimitive.content)
        assertEquals(1, s.engine.labels().count { it == "Pomme" })
    }

    @Test
    fun `plat a variantes - le plat deja note n est pas renote, meme par sa variante`() = runBlocking {
        val engine = SampleData.engine().also { e ->
            runBlocking {
                val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
                val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
                val sauce = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
                check(e.execute(CreateDish("Salade", listOf(laitue, thon), dishId = "rcp-salade")) is ActionOutcome.Applied)
                check(e.execute(app.trackly.core.action.AddDishVariant("rcp-salade", "sauce", listOf(laitue, thon, sauce), variantId = "rcp-salade-b")) is ActionOutcome.Applied)
                check(e.execute(app.trackly.core.action.SetDefaultVariant("rcp-salade", "rcp-salade-b")) is ActionOutcome.Applied)
            }
        }
        val first = Setup(engine, { CallReplies.recordDish("rcp-salade") }, { CallReplies.answer() })
        val noted = (first.thinker.think(request(listOf(CallLine(Speaker.User, "Ma salade.")))) as ThinkResult.Done).noted
        assertEquals("rcp-salade-b", noted.single().recipeId)
        val outputs = mutableListOf<JsonObject>()
        val again = Setup(
            engine,
            { CallReplies.recordDish("rcp-salade") },
            { r ->
                outputs += r.lastToolOutput
                CallReplies.answer()
            },
        )
        again.thinker.think(request(listOf(CallLine(Speaker.User, "Ma salade, avec la sauce.")), noted = noted))
        assertEquals("error", outputs.single()["status"]!!.jsonPrimitive.content)
        assertEquals(1, engine.daySummary(monday).consumptions.count { it.food.recipeId?.startsWith("rcp-salade") == true })
    }

    @Test
    fun `une reflexion a la fois pour toute l app - celle d un nouvel appel attend la precedente`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val bodies = mutableListOf<JsonObject>()
        val transport = ResponsesTransport { body ->
            bodies += body
            if (bodies.size == 1) gate.await()
            CallReplies.answer()
        }
        val thinker = AgentCallThinker(ResponsesClient(transport, RetryPolicy.None), SampleData.engine(), InMemoryChatHistory())
        val first = launch { thinker.think(request()) }
        kotlinx.coroutines.yield()
        val second = launch { thinker.think(request()) }
        repeat(5) { kotlinx.coroutines.yield() }
        assertEquals(1, bodies.size)
        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, bodies.size)
    }

    /**
     * Constaté à l'essai : « une pomme » sans taille était notée tout de suite, sans question, parce que les consignes du
     * chat (« enregistre d'abord, puis demande », « portion habituelle, aucune question ») l'emportaient. Pendant l'appel, un
     * aliment flou ou inconnu se demande AVANT d'être noté ; un plat ou un produit connu, jamais. Le chat écrit ne change pas.
     */
    @Test
    fun `consignes de l appel - aliment flou demande avant de noter, plat connu sans question, priment sur le chat`() = runBlocking {
        val s = Setup(SampleData.engine(), { CallReplies.answer(questions = listOf("Ta pomme, elle était grosse comment ?")) })
        val result = s.thinker.think(request()) as ThinkResult.Done
        assertTrue("rien n'est noté avant la réponse", result.noted.isEmpty() && s.history.last(monday, 10).isEmpty())
        val input = s.model.requests.single()["input"]!!.jsonArray.map { it.jsonObject }
        val call = input.filter { it["role"]?.jsonPrimitive?.content == "developer" }.last().toString()
        assertTrue(call.contains("Appel vocal en cours"))
        assertTrue("question avant de noter", call.contains("ne le note pas encore"))
        assertTrue("plat ou produit connu : sans question", call.contains("connu de sa mémoire"))
        assertTrue("prime sur les règles du chat", call.contains("remplacent les règles du chat"))
        assertFalse("plus de « note tout de suite comme dans le chat »", call.contains("note-le tout de suite avec ta meilleure estimation, comme dans le chat"))
        // Le chat écrit garde ses consignes : la règle de l'appel n'est pas dans les consignes stables.
        assertFalse(SystemPrompt.default().render().contains("ne le note pas encore"))
    }

    @Test
    fun `consignes de la relecture - un aliment reste sans reponse et non note est note avec sa portion habituelle`() {
        val text = CallInstructions.review(
            ReviewRequest(pomme, Instant.EPOCH, emptyList(), listOf("Ta pomme, elle était grosse comment ?"), emptyList(), false, CallReflection.Medium),
        )
        assertTrue(text.contains("Un aliment dit pendant l'appel et pas encore noté"))
        assertTrue(text.contains("sinon la portion habituelle"))
    }

    @Test
    fun `chiffre de la voix contredit - la reflexion le corrige`() = runBlocking {
        val s = Setup(SampleData.engine(), { CallReplies.answer(say = "En fait non, une pomme c'est plutôt 80 kcal.") })
        val result = s.thinker.think(
            request(listOf(CallLine(Speaker.User, "Une pomme, ça fait combien ?"), CallLine(Speaker.Voice, "Environ 200 calories."))),
        ) as ThinkResult.Done
        assertEquals("En fait non, une pomme c'est plutôt 80 kcal.", result.say)
    }

    @Test
    fun `echec reseau - rien de note, rien d ecrit, aucun texte brut`() = runBlocking {
        val engine = SampleData.engine()
        val before = engine.labels()
        val history = InMemoryChatHistory()
        val thinker = AgentCallThinker(ResponsesClient(ResponsesTransport { throw AssistantException(AssistantError.NoInternet) }, RetryPolicy.None), engine, history)
        val result = thinker.think(request())
        assertEquals(ThinkResult.Failed("NoInternet"), result)
        assertEquals(before, engine.labels())
        assertTrue(history.last(monday, 10).isEmpty())
    }

    @Test
    fun `echec apres un enregistrement - la carte reste dans le chat, l entree est rendue`() = runBlocking {
        var n = 0
        val engine = SampleData.engine()
        val history = InMemoryChatHistory()
        val transport = ResponsesTransport {
            if (n++ == 0) CallReplies.recordFood("Pomme", 80) else throw AssistantException(AssistantError.NoInternet)
        }
        val result = AgentCallThinker(ResponsesClient(transport, RetryPolicy.None), engine, history).think(request()) as ThinkResult.Failed
        assertEquals("Pomme", result.noted.single().label)
        assertTrue(history.last(monday, 10).single().cards.single() is RecordCard)
    }

    @Test
    fun `reponse illisible - ce qui est note reste note, rien n est dit, la parole reste a traiter`() = runBlocking {
        val s = Setup(SampleData.engine(), { CallReplies.recordFood("Pomme", 80) }, { ScriptedModel.text("Pomme notée !") })
        val noted = mutableListOf<NotedEntry>()
        val result = s.thinker.think(request().copy(onNoted = { noted += it })) as ThinkResult.Failed
        assertEquals("Pomme", result.noted.single().label)
        assertEquals("connu de l'appel dès l'action appliquée", result.noted, noted)
        assertEquals(AgentCallThinker.HEADER, s.history.last(monday, 10).single().text)
    }

    @Test
    fun `aucun niveau prioritaire - chaque requete de la reflexion part au niveau standard`() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val transport = ResponsesTransport { body ->
            bodies += body
            if (bodies.size == 1) CallReplies.recordFood("Pomme", 80) else CallReplies.answer()
        }
        val thinker = AgentCallThinker(ResponsesClient(transport, RetryPolicy.None), SampleData.engine(), InMemoryChatHistory())
        assertTrue(thinker.think(request()) is ThinkResult.Done)
        assertEquals(2, bodies.size)
        assertTrue(bodies.none { it.containsKey("service_tier") })
        // Même clé de cache d'une requête à l'autre du tour.
        assertEquals(1, bodies.map { it["prompt_cache_key"] }.toSet().size)
        assertTrue(bodies.first()["prompt_cache_key"] != null)
    }

    @Test
    fun `lecture de CallAnswer`() {
        assertEquals(CallAnswer.Parsed(null, listOf("a ?", "b ?"), "r"), CallAnswer.parse("""{"say":null,"questions":["a ?"," b ? ",""],"recap":"r"}"""))
        assertEquals("En fait non.", CallAnswer.parse("""{"say":"En fait non.","questions":[],"recap":""}""")!!.say)
        assertNull(CallAnswer.parse("Pomme notée"))
        assertNull(CallAnswer.parse("""{"say":null}"""))
    }

    @Test
    fun `l heure de la carte est celle du moteur`() = runBlocking {
        val s = Setup(SampleData.engine(), { CallReplies.recordFood("Pomme", 80) }, { CallReplies.answer() })
        s.thinker.think(request())
        assertEquals(Instant.parse("2026-11-02T11:30:00Z"), s.history.last(monday, 10).single().at)
    }
}
