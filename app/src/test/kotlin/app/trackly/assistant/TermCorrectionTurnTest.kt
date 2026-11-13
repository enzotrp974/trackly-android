package app.trackly.assistant

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.prompt.ProductsSection
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.prompt.WebSearchSection
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mots corrigés, côté assistant : « quand j'écris ou dicte alpa, c'est Alpha » retient la correction et corrige
 * les fiches en une action annulable ; le contexte la donne au modèle ; modifier un ingrédient en parlant marche avec
 * les outils existants.
 */
class TermCorrectionTurnTest {
    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T12:30")).also { engine ->
        runBlocking {
            check(
                engine.execute(
                    MemorizeProduct(
                        NewProduct(
                            productId = "prd-alpa", name = "Barre protéinée ALPA chocolat", brand = "ALPA",
                            nutrients = Nutrients(400.0, 36.0, 22.0, 14.0), basis = NutritionBasis(100.0, "g"),
                            shortcuts = listOf("barre alpa"), kind = ProductKind.Recurring,
                        ),
                    ),
                ) is ActionOutcome.Applied,
            )
            check(
                engine.execute(
                    CreateDish(
                        "Bol ALPA", listOf(DishIngredientInput("Barre", 55.0, Nutrients(400.0, 36.0, 22.0, 14.0)), DishIngredientInput("Skyr", 150.0, Nutrients(60.0, 10.0, 4.0, 0.2))),
                        dishId = "rcp-bol",
                    ),
                ) is ActionOutcome.Applied,
            )
        }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun toolOutput(transport: ScriptedTransport, requestIndex: Int): JsonObject {
        val output = transport.requests[requestIndex]["input"]!!.jsonArray.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        return Json.parseToJsonElement(output["output"]!!.jsonPrimitive.content).jsonObject
    }

    @Test
    fun `alpa veut dire Alpha - correction retenue, fiches corrigees, carte, annulation d un geste`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call("manage_term_correction", """{"operation":"set","heard":"alpa","correct":"Alpha"}"""),
            ModelReplies.text("C'est noté : alpa veut dire Alpha."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("quand j'écris ou dicte alpa, c'est Alpha")) as TurnOutcome.Completed

        val card = outcome.cards.single() as ProductCard
        assertEquals(ProductOperation.TermCorrected, card.operation)
        assertEquals("alpa → Alpha", card.name)
        assertEquals("1 produit corrigé, 1 plat corrigé", card.detail)
        val result = toolOutput(transport, 1)
        assertEquals("corrected", result["status"]!!.jsonPrimitive.content)
        assertEquals(0, result["still_containing"]!!.jsonArray.size)
        assertEquals(listOf("alpa" to "Alpha"), engine.activeTermCorrections().map { it.heard to it.correct })
        val product = engine.product("prd-alpa")!!
        assertEquals("Barre protéinée Alpha chocolat", product.name)
        assertEquals("Alpha", product.brand)
        assertTrue("Barre protéinée ALPA chocolat" in product.aliases && "barre alpa" in product.aliases)
        assertEquals("Bol Alpha", engine.allRecipes().single().name)

        // Un geste (la carte) défait la correction ET les noms.
        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(emptyList<Any>(), engine.activeTermCorrections())
        assertEquals("Barre protéinée ALPA chocolat", engine.product("prd-alpa")!!.name)
        assertEquals("ALPA", engine.product("prd-alpa")!!.brand)
        assertEquals("Bol ALPA", engine.allRecipes().single().name)
    }

    @Test
    fun `le contexte donne les mots corriges au modele, avant les elements variables`() = runBlocking {
        val engine = engine()
        val before = ContextBuilder.default().build(ContextEnv(engine, engine.now()))
        assertTrue(!before.contains("Mots corrigés"))

        interpreter(engine, ScriptedTransport(ModelReplies.call("manage_term_correction", """{"operation":"set","heard":"alpa","correct":"Alpha"}"""), ModelReplies.text("Noté.")))
            .run(TurnInput("alpa c'est Alpha"))
        val transport = ScriptedTransport(ModelReplies.text("D'accord."))
        interpreter(engine, transport).run(TurnInput("c'est combien une barre alpa ?"))

        val context = transport.requests[0].toString()
        assertTrue(context, context.contains("Mots corrigés"))
        assertTrue(context, context.contains("alpa → Alpha"))
        assertTrue(context.indexOf("alpa → Alpha") < context.indexOf("Journée de suivi en cours"))
        // Les consignes : recherche web et mémorisation utilisent le vrai mot.
        val instructions = SystemPrompt.default().render()
        assertTrue(instructions.contains("manage_term_correction"))
        assertTrue(WebSearchSection.body.contains("vrai mot"))
        assertTrue(ProductsSection.body.contains("manage_term_correction"))
    }

    @Test
    fun `retirer une correction la desactive et l annule revient en arriere`() = runBlocking {
        val engine = engine()
        interpreter(engine, ScriptedTransport(ModelReplies.call("manage_term_correction", """{"operation":"set","heard":"alpa","correct":"Alpha"}"""), ModelReplies.text("Noté.")))
            .run(TurnInput("alpa c'est Alpha"))

        val transport = ScriptedTransport(
            ModelReplies.call("manage_term_correction", """{"operation":"remove","heard":"alpa","correct":null}"""),
            ModelReplies.text("Retiré."),
        )
        val outcome = interpreter(engine, transport).run(TurnInput("retire la correction d'alpa")) as TurnOutcome.Completed

        assertEquals(emptyList<Any>(), engine.activeTermCorrections())
        val card = outcome.cards.single() as ProductCard
        assertEquals(ProductOperation.TermRemoved, card.operation)
        // Les noms déjà corrigés restent.
        assertEquals("Bol Alpha", engine.allRecipes().single().name)
        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(1, engine.activeTermCorrections().size)
    }

    @Test
    fun `une fiche dont le nom est deja pris est signalee au modele, pas fusionnee`() = runBlocking {
        val engine = engine()
        engine.execute(
            MemorizeProduct(
                NewProduct(
                    productId = "prd-alpha", name = "Barre protéinée Alpha chocolat",
                    nutrients = Nutrients(380.0), basis = NutritionBasis(100.0, "g"),
                ),
            ),
        )
        val transport = ScriptedTransport(
            ModelReplies.call("manage_term_correction", """{"operation":"set","heard":"alpa","correct":"Alpha"}"""),
            ModelReplies.text("Une fiche n'a pas pu être corrigée."),
        )

        interpreter(engine, transport).run(TurnInput("alpa c'est Alpha"))

        assertEquals("Barre protéinée ALPA chocolat", engine.product("prd-alpa")!!.name)
        assertEquals(listOf("Barre protéinée ALPA chocolat"), toolOutput(transport, 1)["still_containing"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `modifier un ingredient en parlant - le modele relit les ingredients puis renvoie la liste complete`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"bol","limit":null}"""),
            ModelReplies.call(
                "manage_dish",
                """{"operation":"update","recipe_id":"rcp-bol","name":null,"variant_name":null,"variant_id":null,"shortcuts":null,"make_default":null,"ingredients":[{"label":"Barre","quantity_g":55,"product_id":null,"per_100g":{"kcal":400,"protein_g":12,"carbs_g":22,"fat_g":14}},{"label":"Skyr","quantity_g":150,"product_id":null,"per_100g":{"kcal":60,"protein_g":10,"carbs_g":4,"fat_g":0.2}}]}""",
            ),
            ModelReplies.text("Barre corrigée : 12 g de protéines pour 100 g."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("dans mon bol, la barre c'est 12 g de protéines")) as TurnOutcome.Completed

        // Les ingrédients (avec leurs valeurs pour 100 g) sont lisibles par le modèle avant de renvoyer la liste complète.
        val ingredients = toolOutput(transport, 1)["recipes"]!!.jsonArray.single().jsonObject["ingredients"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Barre", "Skyr"), ingredients.map { it["label"]!!.jsonPrimitive.content })
        assertEquals("36.0", ingredients[0]["per_100g"]!!.jsonObject["protein_g"]!!.jsonPrimitive.content)
        val bol = engine.allRecipes().single()
        assertEquals(12.0, bol.ingredients[0].per100g.proteinG!!, 0.0)
        assertEquals(10.0, bol.ingredients[1].per100g.proteinG!!, 0.0)
        assertEquals(2, bol.ingredients.size)
        assertEquals(1, outcome.cards.size)
        assertEquals(emptyList<Any>(), engine.activeTermCorrections())
    }
}
