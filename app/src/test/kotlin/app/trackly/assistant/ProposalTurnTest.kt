package app.trackly.assistant

import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.prompt.ProvisionsSection
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Propositions de repas, avec des réponses de modèle simulées (banc simulé) : plusieurs idées donnent une
 * liste Markdown sans carte ; une idée choisie donne une carte de proposition chiffrée depuis les fiches, qui ne
 * compte pas ; « Noter » crée une déclaration annulable ; une version plus ancienne lit le fil sans planter.
 */
class ProposalTurnTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(): TracklyEngine {
        val store = app.trackly.core.store.InMemoryStore()
        return TracklyEngine(store, SampleData.clock("2026-11-02T18:40")).also { engine -> setUp(engine, store) }
    }

    private fun setUp(engine: TracklyEngine, store: app.trackly.core.store.InMemoryStore) {
        runBlocking {
            engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))).ok()
            engine.execute(MemorizeProduct(product("prd-pates", "Pâtes complètes", Nutrients(350.0, 13.0, 65.0, 2.5)))).ok()
            engine.execute(MemorizeProduct(product("prd-thon", "Thon au naturel", Nutrients(110.0, 25.0, 0.0, 1.0)))).ok()
            // Plat repris de l'ancien suivi, sans ingrédients pesés : son chiffrage reste inconnu, jamais deviné.
            store.transaction {
                recipes.insert(
                    app.trackly.core.model.Recipe(
                        "rcp-chef", 1, app.trackly.core.model.RecordState.Active, "Salade du chef",
                        recordedAt = java.time.Instant.EPOCH, actionId = null,
                    ),
                )
            }
            engine.execute(
                CreateDish(
                    "Bowl poulet riz",
                    listOf(DishIngredientInput("Riz cuit", 200.0, Nutrients(130.0, 2.7, 28.0, 0.3)), DishIngredientInput("Poulet", 150.0, Nutrients(165.0, 31.0, 0.0, 3.6))),
                    dishId = "rcp-bowl",
                ),
            ).ok()
        }
    }

    private fun ActionOutcome.ok() = check(this is ActionOutcome.Applied) { (this as ActionOutcome.Rejected).rejection.message }

    private fun product(id: String, name: String, per100: Nutrients) = NewProduct(
        productId = id,
        name = name,
        nutrients = per100,
        basis = NutritionBasis(100.0, "g"),
        kind = ProductKind.Recurring,
    )

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) = TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun toolOutput(transport: ScriptedTransport, requestIndex: Int): JsonObject {
        val output = transport.requests[requestIndex]["input"]!!.jsonArray.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        return Json.parseToJsonElement(output.str("output")).jsonObject
    }

    /** Pâtes 100 g + thon 80 g + sauce tomate sans fiche, au soir. Le modèle glisse des kcal : elles sont ignorées. */
    private val pastaArgs =
        """{"title":"Pâtes thon sauce tomate","moment":"soir","items":[""" +
            """{"product":{"product_id":"prd-pates","quantity":100,"unit":"g","kcal":9999},"dish":null,"food":null},""" +
            """{"product":{"product_id":"prd-thon","quantity":80,"unit":"g"},"dish":null,"food":null},""" +
            """{"product":null,"dish":null,"food":{"label":"Sauce tomate","quantity":60,"unit":"g"}}]}"""

    private val knownArgs =
        """{"title":"Pâtes au thon","moment":"soir","items":[""" +
            """{"product":{"product_id":"prd-pates","quantity":100,"unit":"g"},"dish":null,"food":null},""" +
            """{"product":{"product_id":"prd-thon","quantity":80,"unit":"g"},"dish":null,"food":null}]}"""

    // ───── Plusieurs idées : une liste courte, sans carte ─────

    @Test
    fun `4 propositions - une liste Markdown courte, aucune carte et rien de compte`() = runBlocking {
        val engine = engine()
        val list = "### 4 idées pour ce soir\n\n- Pâtes thon sauce tomate\n- Bowl poulet riz\n- Omelette et salade\n- Wrap au thon\n\nMoi je prendrais les pâtes. Dis-moi laquelle et je te la détaille."
        val transport = ScriptedTransport(ModelReplies.call("get_meal_ideas", """{"moment":"soir"}"""), ModelReplies.text(list))

        val outcome = interpreter(engine, transport).run(TurnInput("propose-moi 4 repas pour ce soir")) as TurnOutcome.Completed

        assertTrue(outcome.cards.isEmpty())
        assertFalse(outcome.reply.contains("kcal"))
        assertFalse(outcome.reply.contains("|"))
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }

    // ───── Une idée choisie : la carte, chiffrée depuis les fiches ─────

    @Test
    fun `la premiere - carte de proposition chiffree depuis les fiches, valeur inconnue signalee, rien de compte`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(ModelReplies.call("propose_meal", pastaArgs), ModelReplies.text("Bonne idée, voilà le détail."))
        val past = listOf(
            HistoryMessage(HistoryMessage.Role.User, "propose-moi 4 repas pour ce soir"),
            HistoryMessage(HistoryMessage.Role.Assistant, "- Pâtes thon sauce tomate\n- Bowl poulet riz"),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("la première", history = past)) as TurnOutcome.Completed

        val card = outcome.cards.single() as ProposalCard
        assertEquals("Pâtes thon sauce tomate", card.title)
        assertEquals("Soir", card.momentLabel)
        assertEquals(listOf("Pâtes complètes", "Thon au naturel", "Sauce tomate"), card.items.map { it.label })
        // 350 kcal/100 g × 100 g et 110 × 0,8 : de la fiche, pas les 9999 du modèle.
        assertEquals(350.0, card.items[0].kcal!!, 0.0)
        assertEquals(88.0, card.items[1].kcal!!, 0.0)
        assertEquals(20.0, card.items[1].proteinG!!, 0.0)
        assertNull(card.items[2].kcal)
        assertEquals("pas de fiche", card.items[2].unknown)
        assertEquals(438.0, card.totalKcal, 0.0)
        assertFalse(card.kcalComplete)
        assertFalse(card.protein.complete)
        assertEquals(listOf("Sauce tomate"), card.unknownItems.map { it.label })
        assertFalse(card.notableLocally)
        assertNull(card.declaration(engine.now()))
        // Une proposition ne compte pas, et le modèle le sait.
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
        val output = toolOutput(transport, 1)
        assertEquals("proposed", output.str("status"))
        assertEquals("false", output.str("recorded"))
        assertTrue(card.summaryForModel().contains("PAS notée"))
        assertTrue(card.summaryForModel().contains("proposal_id ${card.id}"))
    }

    @Test
    fun `plat sans valeurs - valeur inconnue, pas devinee`() = runBlocking {
        val engine = engine()
        val args = """{"title":"Thon et salade du chef","moment":"soir","items":[{"product":{"product_id":"prd-thon","quantity":100,"unit":"g"},"dish":null,"food":null},{"product":null,"dish":{"recipe_id":"rcp-chef","named_version":false,"portions":1},"food":null}]}"""
        val transport = ScriptedTransport(ModelReplies.call("propose_meal", args), ModelReplies.text("Voilà."))
        val card = (interpreter(engine, transport).run(TurnInput("une idée ce soir")) as TurnOutcome.Completed).cards.single() as ProposalCard
        assertNull(card.items[1].kcal)
        assertEquals("plat sans ingrédients pesés", card.items[1].unknown)
        assertEquals(110.0, card.totalKcal, 0.0)
        assertFalse(card.notableLocally)
    }

    @Test
    fun `plat - valeurs calculees depuis ses ingredients, un plat en pause ou une unite fausse sont refuses`() = runBlocking {
        val engine = engine()
        val bowl = """{"title":"Bowl poulet riz","moment":"soir","items":[{"product":null,"dish":{"recipe_id":"rcp-bowl","named_version":false,"portions":1.5},"food":null}]}"""
        val first = ScriptedTransport(ModelReplies.call("propose_meal", bowl), ModelReplies.text("Voilà."))
        val card = (interpreter(engine, first).run(TurnInput("une idée")) as TurnOutcome.Completed).cards.single() as ProposalCard
        // (260 + 247,5) × 1,5
        assertEquals(761.3, card.totalKcal, 0.05)
        assertEquals("1,5 portion", card.items.single().quantity)
        assertTrue(card.notableLocally)

        val wrongUnit = """{"title":"Pâtes","moment":"soir","items":[{"product":{"product_id":"prd-pates","quantity":1,"unit":"pièce"},"dish":null,"food":null}]}"""
        val second = ScriptedTransport(ModelReplies.call("propose_meal", wrongUnit), ModelReplies.text("Je corrige."))
        assertTrue((interpreter(engine, second).run(TurnInput("une idée")) as TurnOutcome.Completed).cards.isEmpty())
        assertEquals("error", toolOutput(second, 1).str("status"))

        engine.execute(SetDishPaused("rcp-bowl", paused = true)).ok()
        val third = ScriptedTransport(ModelReplies.call("propose_meal", bowl), ModelReplies.text("Je corrige."))
        assertTrue((interpreter(engine, third).run(TurnInput("une idée")) as TurnOutcome.Completed).cards.isEmpty())
        assertTrue(toolOutput(third, 1).str("error").contains("en pause"))
    }

    // ───── « Noter » : une déclaration annulable ─────

    @Test
    fun `noter - declaration recalculee depuis les fiches, une seule annulation pour toute la carte`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(ModelReplies.call("propose_meal", knownArgs), ModelReplies.text("Voilà."))
        val card = (interpreter(engine, transport).run(TurnInput("une idée pour ce soir")) as TurnOutcome.Completed).cards.single() as ProposalCard
        assertTrue(card.notableLocally)

        val outcome = engine.execute(card.declaration(engine.now())!!) as ActionOutcome.Applied

        val eaten = engine.daySummary(monday).consumptions
        assertEquals(listOf("Pâtes complètes", "Thon au naturel"), eaten.map { it.food.label })
        assertEquals(listOf("prd-pates", "prd-thon"), eaten.map { it.food.productId })
        assertTrue(eaten.all { it.moment == Moment.Soir })
        assertEquals(438.0, engine.daySummary(monday).totals.kcal, 0.0)
        // Annulable d'un coup, comme un repas dicté.
        engine.execute(Undo(outcome.result.id)).ok()
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }

    @Test
    fun `je l ai mangee - la carte d enregistrement porte l identifiant de la proposition`() = runBlocking {
        val engine = engine()
        val record = """{"items":[{"product":{"product_id":"prd-pates","new_product":null,"quantity":100,"unit":"g","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"soir","day":null},""" +
            """{"product":null,"dish":null,"food":{"label":"Sauce tomate","quantity":60,"unit":"g","kcal":45,"protein_g":1,"carbs_g":6,"fat_g":1.5,"fiber_g":null,"assumptions":["sauce tomate nature"],"confidence":"medium"},"recipe_id":null,"moment":"soir","day":null}],"question":null,"proposal_id":"prop-123"}"""
        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"))

        val card = (interpreter(engine, transport).run(TurnInput(ProposalCard.noteRequest(ProposalCard("prop-123", "Pâtes sauce tomate", "soir", emptyList())))) as TurnOutcome.Completed)
            .cards.single() as RecordCard

        assertEquals("prop-123", card.proposalId)
        assertEquals(2, engine.daySummary(monday).consumptions.size)
    }

    // ───── Consignes et outil exposé ─────

    @Test
    fun `consignes - liste sans dosage pour plusieurs idees, carte pour une idee, outil expose en dernier`() {
        val body = ProvisionsSection.body
        assertTrue(body.contains("une liste Markdown courte"))
        assertTrue(body.contains("sans quantités, sans kcal ni macros, et sans carte"))
        assertTrue(body.contains("appelle propose_meal"))
        assertFalse(body.contains("Propose DEUX ou TROIS idées"))
        assertTrue(SystemPrompt.default().render().contains("le bouton « Noter »"))
        val tools = app.trackly.assistant.tools.ToolRegistry.default().tools.map { it.name }
        assertEquals("propose_meal", tools.last())
    }

    // ───── Versions plus anciennes de l'app ─────

    /** Cartes telles que les connaît la 1.1 : pas de « proposal », une carte d'enregistrement sans `proposalId`. */
    @Serializable
    private sealed interface OldCard

    @Serializable
    @SerialName("record")
    private data class OldRecordCard(val actionId: Long, val kind: String) : OldCard

    /** Le décodage de la 1.1, recopié (ChatCards.decode depuis 0b93eae) : jamais d'exception. */
    private fun oldDecode(text: String): List<OldCard> = runCatching {
        Json { ignoreUnknownKeys = true; classDiscriminator = "card" }.decodeFromString(ListSerializer(OldCard.serializer()), text)
    }.getOrDefault(emptyList())

    @Test
    fun `une version plus ancienne lit une carte de proposition sans planter`() {
        val proposal = ProposalCard("prop-1", "Pâtes au thon", "soir", emptyList())
        val record = RecordCard(12, app.trackly.assistant.cards.RecordKind.Added, emptyList(), proposalId = "prop-1")

        // Message avec une proposition : la 1.1 affiche le message sans ses cartes.
        assertEquals(emptyList<OldCard>(), oldDecode(ChatCards.encode(listOf(proposal))))
        // La carte d'enregistrement née de « Noter » reste lue : le champ en plus est ignoré.
        assertEquals(listOf(OldRecordCard(12, "Added")), oldDecode(ChatCards.encode(listOf(record))))
        // Et cette version-ci : une carte d'un type futur est ignorée seule, les autres restent.
        val future = ChatCards.encode(listOf(record)).replaceFirst("[", """[{"card":"futur","actionId":3},""")
        val read = ChatCards.decode(future)
        assertEquals(listOf(12L), read.map { it.actionId })
        assertNotNull(ChatCards.decode(ChatCards.encode(listOf(proposal))).single() as? ProposalCard)
        assertTrue(ChatCards.decode("pas du json").isEmpty())
    }
}
