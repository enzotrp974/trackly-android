package app.trackly.assistant

import app.trackly.assistant.cards.DishCard
import app.trackly.assistant.cards.DishOperation
import app.trackly.assistant.cards.ProvisionsCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.SetDefaultVariant
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Nutrients
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * « Tes plats et provisions » : tours de conversation avec des réponses de
 * modèle simulées. « Salade » prend la variante par défaut, « non, celle avec la sauce A » corrige par
 * une nouvelle version, les provisions se retiennent sans rien compter, une suggestion n'enregistre
 * rien et n'inclut jamais un plat en pause.
 */
class DishesTurnTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val sauceA = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
    private val sauceB = DishIngredientInput("Sauce vinaigrette", 20.0, Nutrients(450.0, 0.0, 3.0, 48.0))
    private val riz = DishIngredientInput("Riz cuit", 250.0, Nutrients(130.0, 2.7, 28.0, 0.3))
    private val poulet = DishIngredientInput("Poulet", 150.0, Nutrients(165.0, 31.0, 0.0, 3.6))

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T12:30")).also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            check(engine.execute(CreateDish("Salade", listOf(laitue, thon, sauceA), shortcuts = listOf("ma salade"), variantName = "sauce A", dishId = "rcp-salade")) is ActionOutcome.Applied)
            check(engine.execute(AddDishVariant("rcp-salade", "sauce B", listOf(laitue, thon, sauceB), variantId = "rcp-salade-b")) is ActionOutcome.Applied)
        }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun history(user: String, outcome: TurnOutcome.Completed) = listOf(
        HistoryMessage(HistoryMessage.Role.User, user),
        HistoryMessage(HistoryMessage.Role.Assistant, (listOf(outcome.reply) + outcome.cards.map { it.summaryForModel() }).joinToString("\n")),
    )

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun toolOutput(transport: ScriptedTransport, requestIndex: Int): JsonObject {
        val output = transport.requests[requestIndex]["input"]!!.jsonArray.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        return Json.parseToJsonElement(output.str("output")).jsonObject
    }

    private fun recordDish(recipeId: String, named: Boolean, quantity: String = "null", unit: String = "null") =
        """{"items":[{"product":null,"dish":{"recipe_id":"$recipeId","named_version":$named,"quantity":$quantity,"unit":$unit,"assumptions":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}"""

    // ───── « Salade » → variante par défaut ─────

    @Test
    fun `salade - la variante par defaut est retenue, les valeurs viennent des ingredients et la carte la montre`() = runBlocking {
        val engine = engine()
        engine.execute(SetDefaultVariant("rcp-salade", "rcp-salade-b"))
        val transport = ScriptedTransport(
            ModelReplies.call("record_consumptions", recordDish("rcp-salade", named = false)),
            ModelReplies.text("C'est noté pour midi : salade, sauce B."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("j'ai mangé une salade")) as TurnOutcome.Completed

        val entry = engine.daySummary(monday).consumptions.single()
        assertEquals("Salade (sauce B)", entry.food.label)
        assertEquals("rcp-salade-b", entry.food.recipeId)
        // 15 + 88 + 90 : calculé par le code, le modèle n'a donné aucune valeur.
        assertEquals(193.0, entry.food.nutrition.kcal, 0.0)
        val card = outcome.cards.single() as RecordCard
        assertEquals("Salade (sauce B)", card.items.single().label)
        assertTrue(card.assumptions.single().contains("Variante par défaut : sauce B"))
        assertTrue(card.summaryForModel().contains("plat rcp-salade-b"))
        // Le contexte, stable, liste les versions et la variante par défaut, avant les éléments variables.
        val context = transport.requests[0].toString()
        assertTrue(context.contains("rcp-salade-b · sauce B · PAR DÉFAUT"))
        assertTrue(context.indexOf("Plats récurrents") < context.indexOf("Journée de suivi en cours"))
        // Les outils des plats et provisions sont exposés après les outils plus anciens (préfixe de cache stable).
        // La recherche web est un outil intégré sans nom : seuls les outils `function` comptent.
        val tools = transport.requests[0]["tools"]!!.jsonArray.map { it.jsonObject }.filter { it.str("type") == "function" }.map { it.str("name") }
        assertEquals(listOf("manage_dish", "switch_dish", "update_provisions", "get_meal_ideas", "manage_term_correction", "manage_memory", "propose_meal"), tools.takeLast(7))
    }

    @Test
    fun `le modele ne peut pas imposer ses valeurs pour un plat calculable`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":null,"dish":null,"food":{"label":"Salade","quantity":null,"unit":null,"kcal":999,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"assumptions":[],"confidence":null},"recipe_id":"rcp-salade","moment":"midi","day":null}],"question":null}""",
            ),
            ModelReplies.text("Je corrige."),
        )
        interpreter(engine, transport).run(TurnInput("j'ai mangé une salade"))

        assertEquals("error", toolOutput(transport, 1).str("status"))
        assertTrue(toolOutput(transport, 1).str("error").contains("dish"))
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }

    @Test
    fun `non celle avec la sauce A - nouvelle version, valeurs recalculees, carte de correction annulable`() = runBlocking {
        val engine = engine()
        engine.execute(SetDefaultVariant("rcp-salade", "rcp-salade-b"))
        val first = interpreter(
            engine,
            ScriptedTransport(ModelReplies.call("record_consumptions", recordDish("rcp-salade", named = false)), ModelReplies.text("Noté, sauce B.")),
        ).run(TurnInput("une salade")) as TurnOutcome.Completed
        val entry = engine.daySummary(monday).consumptions.single()

        val transport = ScriptedTransport(
            ModelReplies.call("switch_dish", """{"entry_id":"${entry.entryId.value}","recipe_id":"rcp-salade","quantity":null,"unit":null}"""),
            ModelReplies.text("Corrigé : salade sauce A."),
        )
        val fixed = interpreter(engine, transport).run(TurnInput("non, celle avec la sauce A", history = history("une salade", first))) as TurnOutcome.Completed

        assertEquals(listOf(1, 2), engine.history(entry.entryId).map { it.version })
        val now = engine.daySummary(monday).consumptions.single()
        assertEquals("Salade (sauce A)", now.food.label)
        assertEquals(127.0, now.food.nutrition.kcal, 0.0)
        assertEquals(RecordKind.Corrected, (fixed.cards.single() as RecordCard).kind)
        assertEquals("dish_changed", toolOutput(transport, 1).str("status"))
        // L'historique envoyé au modèle contient l'entry_id à corriger et la version retenue.
        assertTrue(transport.requests[0].toString().contains(entry.entryId.value))

        engine.execute(Undo(ActionId(fixed.cards.single().actionId)))
        assertEquals("Salade (sauce B)", engine.daySummary(monday).consumptions.single().food.label)
    }

    @Test
    fun `une version nommee par l utilisateur est prise telle quelle, sans mention du defaut`() = runBlocking {
        val engine = engine()
        engine.execute(SetDefaultVariant("rcp-salade", "rcp-salade-b"))
        val transport = ScriptedTransport(
            ModelReplies.call("record_consumptions", recordDish("rcp-salade", named = true, quantity = "0.5", unit = "\"portion\"")),
            ModelReplies.text("Noté."),
        )
        val outcome = interpreter(engine, transport).run(TurnInput("une demi-salade sauce A")) as TurnOutcome.Completed

        val entry = engine.daySummary(monday).consumptions.single()
        assertEquals("Salade (sauce A)", entry.food.label)
        assertEquals(63.5, entry.food.nutrition.kcal, 0.0)
        assertTrue((outcome.cards.single() as RecordCard).assumptions.isEmpty())
    }

    // ───── Provisions ─────

    @Test
    fun `retiens que j ai des pommes, des bananes, une nouvelle whey - provisions, aucune consommation`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call(
                "update_provisions",
                """{"have":[{"name":"pommes","product_id":null},{"name":"bananes","product_id":null},{"name":"nouvelle whey","product_id":null}],"out":[]}""",
            ),
            ModelReplies.text("C'est retenu."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("retiens que j'ai des pommes, des bananes, une nouvelle whey")) as TurnOutcome.Completed

        assertEquals(listOf("bananes", "nouvelle whey", "pommes"), Provisions.held(engine.allProvisions()).map { it.name })
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
        val card = outcome.cards.single() as ProvisionsCard
        assertEquals(listOf("pommes", "bananes", "nouvelle whey"), card.have)
        assertTrue(card.summaryForModel().contains("aucune consommation"))
        assertEquals("true", toolOutput(transport, 1).str("nothing_counted"))
        // Annulable comme le reste.
        engine.execute(Undo(ActionId(card.actionId)))
        assertTrue(Provisions.held(engine.allProvisions()).isEmpty())
    }

    @Test
    fun `je n ai plus de thon - retire, signale les plats actifs concernes et ne met rien en pause tout seul`() = runBlocking {
        val engine = engine()
        interpreter(
            engine,
            ScriptedTransport(ModelReplies.call("update_provisions", """{"have":[{"name":"thon","product_id":null},{"name":"riz","product_id":null}],"out":[]}"""), ModelReplies.text("Retenu.")),
        ).run(TurnInput("j'ai du thon et du riz"))

        val transport = ScriptedTransport(
            ModelReplies.call("update_provisions", """{"have":[],"out":["thon","caviar"]}"""),
            ModelReplies.text("Noté : plus de thon. Je mets la salade en pause ?"),
        )
        val outcome = interpreter(engine, transport).run(TurnInput("je n'ai plus de thon")) as TurnOutcome.Completed

        assertEquals(listOf("riz"), Provisions.held(engine.allProvisions()).map { it.name })
        val result = toolOutput(transport, 1)
        assertEquals(listOf("Salade"), result["active_dishes_using_removed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("caviar"), result["not_in_list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Salade"), (outcome.cards.single() as ProvisionsCard).affectedDishes)
        // Le plat n'est pas en pause : il faut l'accord de l'utilisateur.
        assertFalse(Dishes.group(engine.allRecipes()).single().isPaused)
        // Le contexte du message suivant montre les provisions.
        val next = ScriptedTransport(ModelReplies.text("Oui."))
        interpreter(engine, next).run(TurnInput("oui, mets-la en pause"))
        assertTrue(next.requests[0].toString().contains("Ce que l'utilisateur dit avoir (jamais une consommation) : riz."))
    }

    // ───── Suggestions ─────

    @Test
    fun `suggestion de repas - calculee par le code, n enregistre rien et ne propose jamais un plat en pause`() = runBlocking {
        val engine = engine()
        engine.execute(CreateDish("Bol de riz poulet", listOf(riz, poulet), dishId = "rcp-bol"))
        engine.execute(CreateDish("Poulet grillé", listOf(poulet), dishId = "rcp-poulet"))
        engine.execute(SetDishPaused("rcp-poulet", paused = true))
        interpreter(
            engine,
            ScriptedTransport(ModelReplies.call("update_provisions", """{"have":[{"name":"riz","product_id":null},{"name":"poulet","product_id":null}],"out":[]}"""), ModelReplies.text("Retenu.")),
        ).run(TurnInput("j'ai du riz et du poulet"))
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())

        val transport = ScriptedTransport(
            ModelReplies.call("get_meal_ideas", "{}"),
            ModelReplies.text("Ce soir : un bol de riz poulet (570 kcal), tu as tout."),
        )
        val outcome = interpreter(engine, transport).run(TurnInput("qu'est-ce que je mange ce soir ?")) as TurnOutcome.Completed

        val ideas = toolOutput(transport, 1)
        assertEquals("ok", ideas.str("status"))
        assertEquals("2600.0", ideas["remaining"]!!.jsonObject.str("kcal"))
        val labels = ideas["dishes"]!!.jsonArray.map { it.jsonObject.str("label") }
        assertTrue(labels.contains("Bol de riz poulet"))
        assertTrue(labels.contains("Salade (sauce A)"))
        assertFalse("un plat en pause n'est jamais proposé", labels.contains("Poulet grillé"))
        val bol = ideas["dishes"]!!.jsonArray.map { it.jsonObject }.first { it.str("label") == "Bol de riz poulet" }
        assertTrue(bol["missing_ingredients"]!!.jsonArray.isEmpty())
        assertEquals(listOf("poulet", "riz"), ideas["provisions"]!!.jsonArray.map { it.jsonObject.str("name") })
        // Une suggestion ne compte jamais : ni consommation, ni action appliquée, ni carte.
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
        assertTrue(outcome.applied.isEmpty())
        assertTrue(outcome.cards.isEmpty())
        // Les consignes interdisent d'enregistrer une suggestion et de proposer un plat en pause.
        val instructions = transport.requests[0]["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("Une suggestion, une idée, une carte de proposition ou un menu envisagé ne sont JAMAIS enregistrés"))
        assertTrue(instructions.contains("Ne propose jamais un plat en pause"))
        // Plusieurs idées : une liste sans dosage ; une idée : la carte de proposition, chiffrée par l'app.
        assertTrue(instructions.contains("Propose des idées qui lui ressemblent"))
        assertTrue(instructions.contains("sans quantités, sans kcal ni macros, et sans carte"))
        assertTrue(instructions.contains("appelle propose_meal"))
        // Le conseil part de ses habitudes et de ses vrais repas.
        assertTrue(ideas.containsKey("usual_meals_for_moment") && ideas.containsKey("last_days") && ideas.containsKey("likely_at_home"))
        // Le plat en pause est signalé dans la liste du contexte, donc le modèle le voit.
        assertTrue(transport.requests[0].toString().contains("rcp-poulet · Poulet grillé · EN PAUSE"))
    }

    // ───── Gérer les plats par le chat ─────

    @Test
    fun `par le chat - creer un plat, choisir la variante par defaut, mettre en pause - versionne et annulable`() = runBlocking {
        val engine = engine()
        val create = ScriptedTransport(
            ModelReplies.call(
                "manage_dish",
                """{"operation":"create","recipe_id":null,"name":"Pâtes thon","variant_name":null,"variant_id":null,"shortcuts":["pâtes"],"make_default":null,"ingredients":[{"label":"Pâtes cuites","quantity_g":200,"product_id":null,"per_100g":{"kcal":150,"protein_g":5,"carbs_g":30,"fat_g":1}},{"label":"Thon","quantity_g":80,"product_id":null,"per_100g":{"kcal":110,"protein_g":25,"carbs_g":0,"fat_g":1}}]}""",
            ),
            ModelReplies.text("Plat créé."),
        )
        val created = interpreter(engine, create).run(TurnInput("enregistre mon plat pâtes thon : 200 g de pâtes cuites et 80 g de thon")) as TurnOutcome.Completed
        val dishCard = created.cards.single() as DishCard
        assertEquals(DishOperation.Created, dishCard.operation)
        val pates = Dishes.group(engine.allRecipes()).first { it.name == "Pâtes thon" }
        assertEquals(388.0, app.trackly.core.catalog.RecipeServing.perPortion(pates.base)!!.kcal, 0.0) // 300 + 88

        val setDefault = ScriptedTransport(
            ModelReplies.call("manage_dish", """{"operation":"set_default_variant","recipe_id":"rcp-salade","name":null,"variant_name":null,"variant_id":"rcp-salade-b","shortcuts":null,"make_default":null,"ingredients":null}"""),
            ModelReplies.text("Cette semaine, la salade c'est la sauce B."),
        )
        val changed = interpreter(engine, setDefault).run(TurnInput("cette semaine la salade c'est la sauce B")) as TurnOutcome.Completed
        assertEquals("rcp-salade-b", Dishes.group(engine.allRecipes()).first { it.name == "Salade" }.defaultVersion.recipeId)
        assertEquals(DishOperation.DefaultChanged, (changed.cards.single() as DishCard).operation)

        val pause = ScriptedTransport(
            ModelReplies.call("manage_dish", """{"operation":"pause","recipe_id":"${pates.base.recipeId}","name":null,"variant_name":null,"variant_id":null,"shortcuts":null,"make_default":null,"ingredients":null}"""),
            ModelReplies.text("En pause."),
        )
        val paused = interpreter(engine, pause).run(TurnInput("mets les pâtes thon en pause")) as TurnOutcome.Completed
        assertTrue(Dishes.group(engine.allRecipes()).first { it.name == "Pâtes thon" }.isPaused)
        engine.execute(Undo(ActionId(paused.cards.single().actionId)))
        assertFalse(Dishes.group(engine.allRecipes()).first { it.name == "Pâtes thon" }.isPaused)
        // Rien de tout cela n'est une consommation.
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }
}
