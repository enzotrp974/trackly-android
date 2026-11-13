package app.trackly.assistant

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Produits : tours de conversation avec des réponses de modèle
 * simulées. On vérifie la reconnaissance par raccourci, le doute enregistré avec sa question,
 * la proposition de récurrence à la deuxième consommation, le nommage accepté, « c'est l'autre »,
 * et que les valeurs viennent de la fiche et non du modèle.
 */
class ProductsTurnTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun bar(id: String, name: String, shortcut: String? = "barre prot", kcal: Double, portionKcal: Double) = NewProduct(
        productId = id,
        name = name,
        brand = "Marque A",
        nutrients = Nutrients(kcal, 36.0, 22.0, 14.0),
        basis = NutritionBasis(100.0, "g"),
        portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(portionKcal, 19.8, 12.1, 7.7)),
        shortcuts = listOfNotNull(shortcut),
        kind = ProductKind.Recurring,
    )

    private suspend fun TracklyEngine.memorize(product: NewProduct) = check(execute(MemorizeProduct(product)) is ActionOutcome.Applied)

    private fun history(user: String, outcome: TurnOutcome.Completed) = listOf(
        HistoryMessage(HistoryMessage.Role.User, user),
        HistoryMessage(HistoryMessage.Role.Assistant, (listOf(outcome.reply) + outcome.cards.map { it.summaryForModel() }).joinToString("\n")),
    )

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun requestText(transport: ScriptedTransport, index: Int): String = transport.requests[index].toString()

    private fun toolOutput(transport: ScriptedTransport, requestIndex: Int): JsonObject {
        val output = transport.requests[requestIndex]["input"]!!.jsonArray.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        return Json.parseToJsonElement(output.str("output")).jsonObject
    }

    // ───── Reconnaissance par raccourci ─────

    @Test
    fun `snack barre prot - le raccourci est reconnu et les valeurs viennent de la fiche, pas du modele`() = runBlocking {
        val engine = engine()
        engine.memorize(bar("prd-barre-choc", "Barre protéinée chocolat", kcal = 400.0, portionKcal = 220.0))
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":"prd-barre-choc","new_product":null,"quantity":1,"unit":"portion","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté pour ton snack."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("snack barre prot")) as TurnOutcome.Completed

        // Le modèle n'a donné aucune valeur : 220 kcal et les macros de la portion viennent de la fiche.
        val consumption = engine.daySummary(monday).consumptions.single()
        assertEquals("Barre protéinée chocolat", consumption.food.label)
        assertEquals("prd-barre-choc", consumption.food.productId)
        assertEquals(220.0, consumption.food.nutrition.kcal, 0.0)
        assertEquals(19.8, consumption.food.nutrition.proteinG!!, 0.0)
        assertEquals(Moment.Snack, consumption.moment)
        val card = outcome.cards.single() as RecordCard
        assertEquals("prd-barre-choc", card.items.single().productId)
        assertTrue(card.memorized.isEmpty() && card.recurrence.isEmpty() && card.doubts.isEmpty())

        // Le contexte liste le produit et son raccourci, avant les éléments variables.
        val context = requestText(transport, 0)
        assertTrue(context.contains("prd-barre-choc · Barre protéinée chocolat (Marque A) · raccourcis : barre prot"))
        assertTrue(context.indexOf("Produits récurrents") < context.indexOf("Journée de suivi en cours"))
        assertTrue(context.contains("Correspondances avec le message"))
        assertEquals(
            listOf("record_consumptions", "correct_consumption", "delete_consumption", "undo_action", "get_day", "search_products", "switch_product", "update_product", "memorize_product", "set_goal", "manage_dish", "switch_dish", "update_provisions", "get_meal_ideas", "manage_term_correction", "manage_memory", "propose_meal"),
            transport.requests[0]["tools"]!!.jsonArray.map { it.jsonObject }.filter { it.str("type") == "function" }.map { it.str("name") },
        )
    }

    @Test
    fun `l outil de recherche renvoie les produits, ignore casse et accents, et dit s il y a doute`() = runBlocking {
        val engine = engine()
        engine.memorize(bar("prd-barre-choc", "Barre protéinée chocolat", kcal = 400.0, portionKcal = 220.0))
        engine.memorize(bar("prd-barre-van", "Barre protéinée vanille", kcal = 380.0, portionKcal = 209.0))
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"BARRE Prôt","limit":null}"""),
            ModelReplies.text("Il y en a deux."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("c'est quoi mes barres protéinées ?")) as TurnOutcome.Completed

        assertEquals("Il y en a deux.", outcome.reply)
        val result = toolOutput(transport, 1)
        assertEquals("ok", result.str("status"))
        assertEquals("true", result.str("ambiguous"))
        val products = result["products"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("prd-barre-choc", "prd-barre-van"), products.map { it.str("product_id") })
        assertEquals("g, portion", products[0].str("units"))
        // Rien n'est enregistré par une recherche.
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertTrue(outcome.applied.isEmpty())
    }

    // ───── Doute : question + estimation enregistrée, puis « c'est l'autre » ─────

    @Test
    fun `doute entre deux barres - la meilleure estimation est enregistree avec la question, puis non c est l autre`() = runBlocking {
        val engine = engine()
        engine.memorize(bar("prd-barre-choc", "Barre protéinée chocolat", kcal = 400.0, portionKcal = 220.0))
        engine.memorize(bar("prd-barre-van", "Barre protéinée vanille", kcal = 380.0, portionKcal = 209.0))
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"barre prot","limit":null}"""),
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":"prd-barre-choc","new_product":null,"quantity":1,"unit":"portion","assumptions":["chocolat supposé, ta saveur la plus fréquente"],"alternatives":["prd-barre-van"]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté. Est-ce la barre chocolat ou la vanille ?"),
        )
        val ask = "snack barre prot"

        val first = interpreter(engine, transport).run(TurnInput(ask)) as TurnOutcome.Completed

        // Estimation enregistrée aussitôt : la consommation compte, au produit le plus probable.
        val entry = engine.daySummary(monday).consumptions.single()
        assertEquals("prd-barre-choc", entry.food.productId)
        assertEquals(220.0, engine.daySummary(monday).totals.kcal, 0.0)
        // La carte porte la question « est-ce celui-ci ? » avec les choix, calculée par le code.
        val card = first.cards.single() as RecordCard
        val doubt = card.doubts.single()
        assertEquals(entry.entryId.value, doubt.entryId)
        assertEquals("Barre protéinée chocolat", doubt.chosen.name)
        assertEquals(listOf("Barre protéinée vanille" to "prd-barre-van"), doubt.others.map { it.name to it.productId })
        val summary = card.summaryForModel()
        assertTrue(summary, summary.contains("QUESTION EN ATTENTE") && summary.contains("prd-barre-van") && summary.contains(entry.entryId.value))
        // La recherche a bien signalé le doute au modèle.
        assertEquals("true", toolOutput(transport, 1).str("ambiguous"))

        // « Non, c'est l'autre » : nouvelle version vers l'autre produit, valeurs recalculées.
        val second = ScriptedTransport(
            ModelReplies.call("switch_product", """{"entry_id":"${entry.entryId.value}","product_id":"prd-barre-van","quantity":null,"unit":null}"""),
            ModelReplies.text("Corrigé : c'est la vanille."),
        )
        val fixed = interpreter(engine, second).run(TurnInput("non, c'est l'autre", history = history(ask, first))) as TurnOutcome.Completed

        val versions = engine.history(entry.entryId)
        assertEquals(listOf(1, 2), versions.map { it.version })
        assertEquals("prd-barre-van", versions.last().food.productId)
        assertEquals(209.0, engine.daySummary(monday).totals.kcal, 0.0)
        val fixedCard = fixed.cards.single() as RecordCard
        assertEquals(RecordKind.Corrected, fixedCard.kind)
        // L'historique envoyé au modèle contient la question en attente et l'entry_id à corriger.
        assertTrue(requestText(second, 0).contains("QUESTION EN ATTENTE"))
        assertEquals("product_changed", toolOutput(second, 1).str("status"))

        // Annuler la correction rétablit la première estimation.
        assertTrue(engine.execute(Undo(ActionId(fixedCard.actionId))) is ActionOutcome.Applied)
        assertEquals(220.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    // ───── Deuxième consommation : proposition de récurrence ─────

    @Test
    fun `sandwich - memorise comme ponctuel, remange il propose la recurrence, le nom accepte le rend recurrent`() = runBlocking {
        val engine = engine()
        val newProduct = """{"name":"Sandwich thon baguette","brand":"Marque B","variant":null,"per_base":{"quantity":100,"unit":"g","kcal":250,"protein_g":12,"carbs_g":30,"fat_g":8,"fiber_g":null},"per_portion":null,"shortcuts":[],"source_type":"label","source_description":"étiquette photographiée","confidence":"high"}"""
        val firstTransport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":null,"new_product":$newProduct,"quantity":200,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté pour midi."),
        )
        val ask1 = "j'ai mangé ce sandwich, 200 g"

        // 1. Première consommation : fiche mémorisée comme produit ponctuel, aucune proposition.
        val first = interpreter(engine, firstTransport).run(TurnInput(ask1)) as TurnOutcome.Completed
        val product = engine.allProducts().single()
        assertEquals(ProductKind.Occasional, product.kind)
        assertEquals(500.0, engine.daySummary(monday).totals.kcal, 0.0) // 2 x 250, calculé par le code
        val card1 = first.cards.single() as RecordCard
        assertEquals(listOf(product.productId), card1.memorized.map { it.productId })
        assertTrue(card1.recurrence.isEmpty())
        assertEquals(product.productId, card1.items.single().productId)

        // 2. Le lendemain-même : « le sandwich d'hier, je l'ai remangé ». Le produit ponctuel récent est dans le contexte.
        val secondTransport = ScriptedTransport(
            ModelReplies.call("record_consumptions", """{"items":[{"product":{"product_id":"${product.productId}","new_product":null,"quantity":200,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}"""),
            ModelReplies.text("C'est noté. Tu veux en faire un produit récurrent ? Comment l'appelle-t-on ?"),
        )
        val ask2 = "le sandwich d'hier, je l'ai remangé à midi"
        val second = interpreter(engine, secondTransport).run(TurnInput(ask2, history = history(ask1, first))) as TurnOutcome.Completed

        val context = requestText(secondTransport, 0)
        assertTrue(context.contains("Produits ponctuels récents") && context.contains(product.productId))
        val card2 = second.cards.single() as RecordCard
        val offer = card2.recurrence.single()
        assertEquals(product.productId, offer.productId)
        assertEquals("Sandwich thon baguette", offer.productName)
        assertEquals(1, offer.previousUses)
        assertEquals(1000.0, engine.daySummary(monday).totals.kcal, 0.0)
        // Rien n'est renommé sans accord de l'utilisateur.
        assertEquals(ProductKind.Occasional, engine.product(product.productId)!!.kind)
        assertEquals("Sandwich thon baguette", engine.product(product.productId)!!.name)
        val result = toolOutput(secondTransport, 1)
        assertEquals(product.productId, result["recurrence_proposals"]!!.jsonArray.single().jsonObject.str("product_id"))

        // 3. L'utilisateur répond par une phrase qui donne le nom.
        val thirdTransport = ScriptedTransport(
            ModelReplies.call("update_product", """{"operation":"make_recurring","product_id":"${product.productId}","value":"Sandwich grand thon baguette"}"""),
            ModelReplies.text("D'accord, c'est maintenant ton sandwich grand thon baguette."),
        )
        val ask3 = "appelle-le sandwich grand thon baguette"
        val third = interpreter(engine, thirdTransport).run(TurnInput(ask3, history = history(ask1, first) + history(ask2, second))) as TurnOutcome.Completed

        assertTrue(requestText(thirdTransport, 0).contains("PROPOSITION EN ATTENTE"))
        val named = engine.product(product.productId)!!
        assertEquals(ProductKind.Recurring, named.kind)
        assertEquals("Sandwich grand thon baguette", named.name)
        assertEquals(listOf("Sandwich thon baguette"), named.aliases)
        val productCard = third.cards.single() as ProductCard
        assertEquals(ProductOperation.MadeRecurring, productCard.operation)
        assertEquals("avant : Sandwich thon baguette", productCard.detail)

        // 4. La fois suivante, le produit récurrent est reconnu directement, sans nouvelle proposition.
        val fourthTransport = ScriptedTransport(
            ModelReplies.call("record_consumptions", """{"items":[{"product":{"product_id":"${product.productId}","new_product":null,"quantity":200,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}"""),
            ModelReplies.text("C'est noté."),
        )
        val fourth = interpreter(engine, fourthTransport).run(TurnInput("mon sandwich grand thon baguette")) as TurnOutcome.Completed
        assertTrue((fourth.cards.single() as RecordCard).recurrence.isEmpty())
        val contextFourth = requestText(fourthTransport, 0)
        assertTrue(contextFourth.contains("${product.productId} · Sandwich grand thon baguette"))
        assertTrue(contextFourth.contains("raccourcis : Sandwich thon baguette"))

        // Le nommage s'annule d'un toucher (la fiche garde son identifiant, les consommations restent liées).
        assertTrue(engine.execute(Undo(ActionId(productCard.actionId))) is ActionOutcome.Applied)
        assertEquals(ProductKind.Occasional, engine.product(product.productId)!!.kind)
        assertEquals("Sandwich thon baguette", engine.product(product.productId)!!.name)
        assertEquals(3, engine.daySummary(monday).consumptions.count { it.food.productId == product.productId })
    }

    @Test
    fun `un produit deja connu qui revient sous un nom proche n est pas memorise deux fois`() = runBlocking {
        val engine = engine()
        val newProduct = """{"name":"Sandwich thon baguette","brand":"Marque B","variant":null,"per_base":{"quantity":100,"unit":"g","kcal":250,"protein_g":12,"carbs_g":30,"fat_g":8,"fiber_g":null},"per_portion":null,"shortcuts":[],"source_type":"label","source_description":"étiquette","confidence":"high"}"""
        val call = """{"items":[{"product":{"product_id":null,"new_product":$newProduct,"quantity":200,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"midi","day":null}],"question":null}"""
        val transport = ScriptedTransport(
            ModelReplies.call("record_consumptions", call), ModelReplies.text("Noté."),
            ModelReplies.call("record_consumptions", call.replace("Sandwich thon baguette", "sandwich THON baguette")), ModelReplies.text("Noté."),
        )
        val assistant = interpreter(engine, transport)

        val a = assistant.run(TurnInput("j'ai mangé un sandwich")) as TurnOutcome.Completed
        val b = assistant.run(TurnInput("j'ai remangé un sandwich")) as TurnOutcome.Completed

        assertEquals(1, engine.allProducts().size)
        assertTrue((b.cards.single() as RecordCard).memorized.isEmpty())
        // La reconnaissance par le code propose aussi la récurrence dans ce cas.
        assertEquals(1, (b.cards.single() as RecordCard).recurrence.size)
        assertTrue((a.cards.single() as RecordCard).recurrence.isEmpty())
    }

    // ───── Produits et plats repris ─────

    @Test
    fun `les produits et plats repris alimentent le contexte, la recherche et la reconnaissance`() = runBlocking {
        val engine = engine()
        assertTrue(HistoryImport.run(engine, SampleData.historyBundle) is ImportOutcome.Imported)
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"yaourt","limit":null}"""),
            ModelReplies.call("record_consumptions", """{"items":[{"product":{"product_id":"yaourt-nature-essai","new_product":null,"quantity":150,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"matin","day":null}],"question":null}"""),
            ModelReplies.text("C'est noté."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("un yaourt de 150 g ce matin")) as TurnOutcome.Completed

        val context = requestText(transport, 0)
        assertTrue(context.contains("yaourt-nature-essai · Yaourt nature · raccourcis : yaourt"))
        assertTrue(context.contains("porridge-essai · Porridge"))
        val search = toolOutput(transport, 1)
        assertEquals("yaourt-nature-essai", search["products"]!!.jsonArray.first().jsonObject.str("product_id"))
        val food = engine.daySummary(monday).consumptions.single().food
        assertEquals("yaourt-nature-essai", food.productId)
        assertEquals(91.5, food.nutrition.kcal, 0.0001) // 61 kcal pour 100 g, calculé par le code
        assertTrue((outcome.cards.single() as RecordCard).memorized.isEmpty())
    }

    @Test
    fun `la recherche trouve aussi un plat repris, avec son total calcule`() = runBlocking {
        val engine = engine()
        assertTrue(HistoryImport.run(engine, SampleData.historyBundle) is ImportOutcome.Imported)
        val transport = ScriptedTransport(
            ModelReplies.call("search_products", """{"query":"mon porridge","limit":null}"""),
            ModelReplies.text("Ton porridge fait environ 314 kcal en entier."),
        )

        interpreter(engine, transport).run(TurnInput("combien fait mon porridge ?"))

        val recipe = toolOutput(transport, 1)["recipes"]!!.jsonArray.single().jsonObject
        assertEquals("porridge-essai", recipe.str("recipe_id"))
        assertEquals("314.0", recipe.str("total_kcal"))
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    // ───── Refus et garde-fous ─────

    @Test
    fun `une unite absente de la fiche est refusee au modele, rien n est enregistre`() = runBlocking {
        val engine = engine()
        engine.memorize(bar("prd-barre-choc", "Barre protéinée chocolat", kcal = 400.0, portionKcal = 220.0))
        val transport = ScriptedTransport(
            ModelReplies.call("record_consumptions", """{"items":[{"product":{"product_id":"prd-barre-choc","new_product":null,"quantity":2,"unit":"ml","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}"""),
            ModelReplies.text("Désolé, je corrige."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("deux barres protéinées")) as TurnOutcome.Completed

        assertTrue(outcome.applied.isEmpty())
        val result = toolOutput(transport, 1)
        assertEquals("rejected", result.str("status"))
        assertTrue(result.str("message").contains("Unités possibles"))
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    @Test
    fun `memoriser un produit sans le manger ne compte rien et s annule`() = runBlocking {
        val engine = engine()
        val product = """{"name":"Whey vanille","brand":"Nutri","variant":null,"per_base":{"quantity":100,"unit":"g","kcal":380,"protein_g":80,"carbs_g":6,"fat_g":5,"fiber_g":null},"per_portion":{"label":"1 dose","weight":30,"weight_unit":"g","kcal":114,"protein_g":24,"carbs_g":1.8,"fat_g":1.5,"fiber_g":null},"shortcuts":["whey"],"source_type":"label","source_description":"étiquette","confidence":"high"}"""
        val transport = ScriptedTransport(
            ModelReplies.call("memorize_product", """{"product":$product,"recurring":true}"""),
            ModelReplies.text("J'ai retenu ta whey."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("retiens ma nouvelle whey vanille, je l'appelle whey")) as TurnOutcome.Completed

        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        val saved = engine.allProducts().single()
        assertEquals(ProductKind.Recurring, saved.kind)
        assertEquals(listOf("whey"), saved.aliases)
        assertEquals(114.0, saved.portion!!.nutrients.kcal!!, 0.0)
        val card = outcome.cards.single() as ProductCard
        assertEquals(ProductOperation.Memorized, card.operation)
        assertEquals("false", toolOutput(transport, 1)["counted_in_journal"]!!.toString())

        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertTrue(app.trackly.core.catalog.CatalogSearch.products(engine.allProducts(), "whey").isEmpty())
        assertNotNull(engine.product(saved.productId)) // rien n'est effacé : une version supprimée reste
        assertFalse(engine.product(saved.productId)!!.isActive)
    }

    @Test
    fun `la barre c est 12 g de proteines - meme fiche modifiee, la carte montre le changement, annulable`() = runBlocking {
        val engine = engine()
        engine.memorize(bar("prd-barre-choc", "Barre protéinée chocolat", kcal = 400.0, portionKcal = 220.0))
        val corrected = """{"name":"Barre protéinée chocolat","brand":"Marque A","variant":null,"per_base":{"quantity":100,"unit":"g","kcal":400,"protein_g":21.8,"carbs_g":22,"fat_g":14,"fiber_g":null},"per_portion":{"label":"1 barre","weight":55,"weight_unit":"g","kcal":220,"protein_g":12,"carbs_g":12.1,"fat_g":7.7,"fiber_g":null},"shortcuts":["barre prot"],"source_type":"user","source_description":"correction de l'utilisateur","confidence":"high"}"""
        val transport = ScriptedTransport(
            ModelReplies.call("memorize_product", """{"product":$corrected,"recurring":true,"product_id":"prd-barre-choc"}"""),
            ModelReplies.text("C'est corrigé."),
        )

        val outcome = interpreter(engine, transport).run(TurnInput("la barre protéinée c'est 12 g de protéines")) as TurnOutcome.Completed

        val product = engine.allProducts().single()
        assertEquals("prd-barre-choc", product.productId)
        assertEquals(12.0, product.portion!!.nutrients.proteinG!!, 0.0)
        assertEquals(listOf("barre prot"), product.aliases)
        val card = outcome.cards.single() as ProductCard
        assertEquals(ProductOperation.Corrected, card.operation)
        assertEquals("Produit modifié", card.status)
        assertEquals("protéines 19,8 → 12 g (1 barre)", card.detail)
        assertEquals("corrected", toolOutput(transport, 1).str("status"))

        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(19.8, engine.product("prd-barre-choc")!!.portion!!.nutrients.proteinG!!, 0.0)
    }
}
