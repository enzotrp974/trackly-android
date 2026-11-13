package app.trackly.assistant

import app.trackly.assistant.tools.IngredientArgs
import app.trackly.assistant.tools.Per100Args
import app.trackly.assistant.cards.DishCard
import app.trackly.assistant.cards.DishOperation
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.action.SetGoal
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.engine.TracklyEngine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Variantes de plats par la parole, parcours d'un tour simulé. « J'ai mangé la salade avec la sauce César »
 * enregistre la bonne variante sans question ; créer une variante « avec telle sauce à la place », la renommer, la
 * mettre par défaut, la supprimer.
 */
class DishVariantsTurnTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val cesar = DishIngredientInput("Sauce César", 30.0, Nutrients(450.0, 2.0, 3.0, 48.0))
    private val yaourt = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
    private val riz = DishIngredientInput("Riz cuit", 200.0, Nutrients(130.0, 2.7, 28.0, 0.3))

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T12:30")).also { engine ->
        runBlocking {
            fun ok(outcome: ActionOutcome) = check(outcome is ActionOutcome.Applied) { "$outcome" }
            ok(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))))
            ok(engine.execute(CreateDish("Salade composée", listOf(laitue, thon), shortcuts = listOf("ma salade"), dishId = "salade")))
            ok(engine.execute(AddDishVariant("salade", "sauce César", listOf(laitue, thon, cesar), variantId = "salade-cesar")))
            ok(engine.execute(AddDishVariant("salade", "sauce yaourt", listOf(laitue, thon, yaourt), variantId = "salade-yaourt")))
            ok(engine.execute(CreateDish("Bowl thon", listOf(riz, thon), variantName = "sauce soja", dishId = "bowl")))
            ok(engine.execute(AddDishVariant("bowl", "sauce yaourt", listOf(riz, thon, yaourt), variantId = "bowl-yaourt")))
        }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) = TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun toolOutput(transport: ScriptedTransport, requestIndex: Int): JsonObject {
        val output = transport.requests[requestIndex]["input"]!!.jsonArray.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        return Json.parseToJsonElement(output.str("output")).jsonObject
    }

    private fun recordDish(recipeId: String, named: Boolean) =
        """{"items":[{"product":null,"dish":{"recipe_id":"$recipeId","named_version":$named,"quantity":null,"unit":null,"assumptions":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}"""

    private fun manage(operation: String, recipeId: String?, variantName: String? = null, variantId: String? = null, remove: String = "null", add: String = "null", makeDefault: Boolean? = null) =
        """{"operation":"$operation","recipe_id":${recipeId?.let { "\"$it\"" }},"name":null,"variant_name":${variantName?.let { "\"$it\"" }},"variant_id":${variantId?.let { "\"$it\"" }},"shortcuts":null,"ingredients":null,"remove_ingredients":$remove,"add_ingredients":$add,"make_default":$makeDefault}"""

    // ───── Manger une variante nommée ─────

    @Test
    fun `j ai mange la salade avec la sauce Cesar - la recherche donne la variante, enregistree sans question`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"la salade avec la sauce César","limit":null}"""),
            ModelReplies.call("record_consumptions", recordDish("salade-cesar", named = true)),
            ModelReplies.text("Noté : salade, sauce César."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("j'ai mangé la salade avec la sauce César")) as TurnOutcome.Completed

        // La recherche, avec les mots de liaison, met la variante César en tête.
        val recipes = toolOutput(transport, 1)["recipes"]!!.jsonArray.map { it.jsonObject }
        assertEquals("salade-cesar", recipes.first().str("recipe_id"))
        assertEquals("salade", recipes.first().str("dish_id"))
        val entry = engine.daySummary(monday).consumptions.single()
        assertEquals("Salade composée (sauce César)", entry.food.label)
        // 15 + 88 + 135 = 238 kcal, calculées par le code.
        assertEquals(238.0, entry.food.nutrition.kcal, 0.0)
        val card = outcome.cards.single() as RecordCard
        assertNull(card.question)
        assertTrue("aucune mention du défaut : ${card.assumptions}", card.assumptions.none { it.contains("défaut") })
    }

    @Test
    fun `le contexte et la consigne disent comment lire salade sauce X`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", recordDish("salade-cesar", named = true)), ModelReplies.text("Noté."))
        interpreter(engine, transport).run(TurnInput("salade sauce César"))
        val request = transport.requests[0].toString()
        assertTrue(request.contains("salade-cesar · sauce César"))
        // La consigne cite les formes réelles et interdit de redemander le plat.
        assertTrue(request.contains("la salade avec la sauce X"))
        assertTrue(request.contains("sans demander"))
    }

    // ───── Gérer les variantes par la parole ─────

    @Test
    fun `ma salade avec la sauce yaourt c est la sauce blanche a la place - variante copiee, un ingredient change`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport(
            ModelReplies.call(
                "manage_dish",
                manage(
                    "add_variant", "salade-cesar", variantName = "sauce blanche",
                    remove = """["Sauce César"]""",
                    add = """[{"label":"Sauce blanche","quantity_g":30,"product_id":null,"per_100g":{"kcal":200,"protein_g":2,"carbs_g":5,"fat_g":19}}]""",
                ),
            ),
            ModelReplies.text("Variante sauce blanche ajoutée."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("ma salade avec la sauce blanche c'est de la sauce blanche à la place de la César")) as TurnOutcome.Completed

        val dish = Dishes.group(engine.allRecipes()).first { it.base.recipeId == "salade" }
        val blanche = dish.variants.single { it.variantName == "sauce blanche" }
        // Rattachée au plat (pas à la variante César), avec les ingrédients de la César sauf la sauce.
        assertEquals("salade", blanche.variantOf)
        assertEquals(listOf("Laitue", "Thon au naturel", "Sauce blanche"), blanche.ingredients.map { it.label })
        assertEquals(163.0, RecipeServing.perPortion(blanche)!!.kcal, 0.0) // 15 + 88 + 60
        assertEquals(DishOperation.VariantAdded, (outcome.cards.single() as DishCard).operation)
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }

    @Test
    fun `un ingredient a retirer qui n existe pas - refuse, la liste des ingredients est rendue, rien n est ecrit`() = runBlocking {
        val engine = engine()
        val before = engine.allRecipes()
        val transport = ScriptedTransport(
            ModelReplies.call("manage_dish", manage("add_variant", "salade", variantName = "sauce blanche", remove = """["Mayonnaise"]""", add = "[]")),
            ModelReplies.text("Je vérifie."),
        )
        interpreter(engine, transport).run(TurnInput("ma salade sauce blanche c'est de la sauce blanche à la place de la mayo"))
        val output = toolOutput(transport, 1)
        assertEquals("error", output.str("status"))
        assertTrue(output.str("error"), output.str("error").contains("Laitue, Thon au naturel"))
        assertEquals(before, engine.allRecipes())
    }

    @Test
    fun `renommer, mettre par defaut, supprimer une variante - puis salade seul prend le defaut`() = runBlocking {
        val engine = engine()
        suspend fun say(text: String, call: String): TurnOutcome.Completed {
            val transport = ScriptedTransport(ModelReplies.call("manage_dish", call), ModelReplies.text("C'est fait."))
            return interpreter(engine, transport).run(TurnInput(text)) as TurnOutcome.Completed
        }
        fun salade() = runBlocking { Dishes.group(engine.allRecipes()).first { it.base.recipeId == "salade" } }

        say("renomme la salade sauce yaourt en sauce blanche", manage("update", "salade-yaourt", variantName = "sauce blanche"))
        assertEquals(listOf("sauce blanche", "sauce César"), salade().variants.map { it.variantName })

        say("la salade c'est la sauce César par défaut", manage("set_default_variant", "salade", variantId = "salade-cesar"))
        assertEquals("salade-cesar", salade().defaultVersion.recipeId)

        say("supprime la variante sauce blanche de ma salade", manage("delete", "salade-yaourt"))
        assertEquals(listOf("sauce César"), salade().variants.map { it.variantName })
        assertEquals("salade-cesar", salade().defaultVersion.recipeId)

        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", recordDish("salade", named = false)), ModelReplies.text("Noté, sauce César."))
        interpreter(engine, transport).run(TurnInput("à midi ma salade"))
        assertEquals("Salade composée (sauce César)", engine.daySummary(monday).consumptions.single().food.label)
    }

    // ───── Plat repris de l'ancien suivi, sans ingrédients pesés ─────

    private val habituelleTemplate = """{"components":[{"name":"base salade/légumes","default":"5 grandes feuilles iceberg + 1 tomate + 1/2 oignon"},{"ingredient_ref":"sauce-balsamique","default_quantity":{"unit":"g","value":70}},{"name":"Crème balsamique","default_quantity":{"unit":"g","value":30},"nutrition":null},{"ingredient_ref":"ketchup","default_quantity":{"unit":"g","value":50}}]}"""

    /** Moteur avec la salade habituelle reprise (modèle seulement) et ses produits, plus une sauce César. */
    private fun engineWithImportedSalad(template: String = habituelleTemplate): TracklyEngine {
        val store = app.trackly.core.store.InMemoryStore()
        return TracklyEngine(store, SampleData.clock("2026-11-02T12:30")).also { engine ->
            runBlocking {
                fun product(id: String, name: String, kcal: Double, p: Double, c: Double, f: Double) = MemorizeProduct(
                    NewProduct(name, nutrients = Nutrients(kcal, p, c, f), basis = NutritionBasis(100.0, "g"), productId = id, kind = ProductKind.Recurring),
                )
                check(engine.execute(product("sauce-balsamique", "Sauce balsamique", 250.0, 0.5, 20.0, 18.0)) is ActionOutcome.Applied)
                check(engine.execute(product("ketchup", "Ketchup", 100.0, 1.0, 24.0, 0.1)) is ActionOutcome.Applied)
                check(engine.execute(product("cesar-classique", "Sauce César classique", 400.0, 1.5, 6.0, 41.0)) is ActionOutcome.Applied)
                store.transaction {
                    recipes.insert(
                        app.trackly.core.model.Recipe(
                            "habituelle", 1, app.trackly.core.model.RecordState.Active, "Salade habituelle", aliases = listOf("ma salade habituelle"),
                            sourceTemplateJson = template, recordedAt = java.time.Instant.EPOCH, actionId = null,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `variante d un plat repris - le modele lit la composition, construit la liste complete, aucun refus technique`() = runBlocking {
        val engine = engineWithImportedSalad()
        val before = engine.allRecipes().single()
        val full = """[
            {"label":"Base salade (iceberg, tomate, oignon)","quantity_g":250,"product_id":null,"per_100g":{"kcal":20,"protein_g":1,"carbs_g":3.5,"fat_g":0.2}},
            {"label":null,"quantity_g":70,"product_id":"cesar-classique","per_100g":null},
            {"label":"Crème balsamique","quantity_g":30,"product_id":null,"per_100g":{"kcal":200,"protein_g":0.5,"carbs_g":45,"fat_g":0}},
            {"label":null,"quantity_g":50,"product_id":"ketchup","per_100g":null}
        ]""".replace("\n", "")
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"salade habituelle","limit":null}"""),
            // Premier essai à l'ancienne : remplacer un ingrédient copié. Refus guidé, pour le modèle seulement.
            ModelReplies.call("manage_dish", manage("add_variant", "habituelle", variantName = "sauce César", remove = """["Sauce balsamique"]""", add = """[{"label":null,"quantity_g":70,"product_id":"cesar-classique","per_100g":null}]""")),
            ModelReplies.call("manage_dish", """{"operation":"add_variant","recipe_id":"habituelle","name":null,"variant_name":"sauce César","variant_id":null,"shortcuts":null,"ingredients":$full,"remove_ingredients":null,"add_ingredients":null,"make_default":null}"""),
            ModelReplies.text("Variante sauce César créée."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("crée une variante de ma salade habituelle avec 70 g de sauce César classique à la place de la sauce balsamique")) as TurnOutcome.Completed

        // La recherche donne le modèle et les produits qu'il cite.
        val found = toolOutput(transport, 1)["recipes"]!!.jsonArray.map { it.jsonObject }.single { it.str("recipe_id") == "habituelle" }
        assertEquals("false", found.str("computable"))
        assertEquals(4, found["template"]!!.jsonObject["components"]!!.jsonArray.size)
        assertEquals(listOf("sauce-balsamique", "ketchup"), found["template_products"]!!.jsonArray.map { it.jsonObject.str("product_id") })
        assertEquals("false", found.str("composition_unknown"))
        // Le refus guide vers la liste complète, sans parler de structure.
        val refused = toolOutput(transport, 2)
        assertEquals("error", refused.str("status"))
        assertTrue(refused.str("error"), refused.str("error").contains("liste COMPLÈTE"))
        assertTrue(refused.containsKey("template"))
        assertTrue(!refused.str("error").contains("structur"))

        val dish = Dishes.group(engine.allRecipes()).single()
        val variant = dish.variants.single()
        assertEquals("sauce César", variant.variantName)
        assertEquals("habituelle", variant.variantOf)
        assertEquals("Sauce César classique", variant.ingredients[1].label)
        // 50 + 280 + 60 + 50 = 440 kcal, calculées par l'app.
        assertEquals(440.0, RecipeServing.perPortion(variant)!!.kcal, 0.0)
        // Le plat de base n'est pas réécrit.
        assertEquals(before, dish.base)
        assertEquals(DishOperation.VariantAdded, (outcome.cards.single() as DishCard).operation)
        assertTrue(engine.daySummary(monday).consumptions.isEmpty())
    }

    @Test
    fun `variante d un plat estime par portion - refus qui demande la composition`() = runBlocking {
        val engine = engineWithImportedSalad("""{"recipe_estimate":{"kcal_per_portion":650},"batch":{"components":[{"name":"lasagnes maison"}]}}""")
        val before = engine.allRecipes()
        val transport = ScriptedTransport(
            ModelReplies.call("manage_dish", manage("add_variant", "habituelle", variantName = "sauce César", remove = """["Sauce"]""", add = "[]")),
            ModelReplies.text("Quels ingrédients, et combien ?"),
        )
        interpreter(engine, transport).run(TurnInput("une variante de ma salade habituelle avec la sauce César"))
        val output = toolOutput(transport, 1)
        assertEquals("true", output.str("composition_unknown"))
        assertTrue(output.str("error"), output.str("error").contains("composition est inconnue"))
        assertEquals(before, engine.allRecipes())
    }

    @Test
    fun `un ingredient lie a un produit garde ses valeurs quand on le renvoie tel quel`() {
        val stored = app.trackly.core.model.RecipeIngredient("i1", "Sauce", 30.0, app.trackly.core.model.Nutrients(100.0), productId = "p1")
        val input = IngredientArgs("Sauce", 30.0, "p1", Per100Args(100.0)).toInput()
        org.junit.Assert.assertEquals(stored.per100g.kcal, input.per100g?.kcal)
        org.junit.Assert.assertEquals("p1", input.productId)
        // Sans valeurs ni produit : refusé.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { IngredientArgs("Sauce", 30.0).toInput() }
    }
}
