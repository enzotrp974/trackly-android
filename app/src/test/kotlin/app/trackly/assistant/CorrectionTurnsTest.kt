package app.trackly.assistant

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.NewProduct
import app.trackly.core.action.ProductUse
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductPortion
import app.trackly.core.action.SetGoal
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Confidence
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.FoodSource
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedModel.Companion.text
import app.trackly.testing.ScriptedModel.Companion.toolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Corrections par phrase, de bout en bout : un message, un modèle simulé qui lit le
 * contexte (ou le résultat de `get_day`) pour retrouver l'`entry_id`, les actions appliquées par le
 * moteur, les cartes, l'annulation. Aucun réseau.
 */
class CorrectionTurnsTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val sunday = LocalDate.parse("2026-11-01")

    private fun engine(local: String = "2026-11-02T12:30"): TracklyEngine = SampleData.emptyEngine(SampleData.clock(local)).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun interpreter(engine: TracklyEngine, model: ScriptedModel) =
        TurnInterpreter(ResponsesClient(model, RetryPolicy.None), engine)

    private val pomme = Food(
        label = "Pomme",
        nutrition = Nutrition(80.0, 0.4, 20.0, 0.2, 3.0),
        quantity = Quantity(150.0, "g"),
        assumptions = listOf("pomme de taille moyenne"),
        productId = "produit-pomme",
        source = FoodSource("estimate", "Estimation générique"),
        confidence = Confidence.Medium,
    )

    private suspend fun seed(engine: TracklyEngine, food: Food, moment: Moment = Moment.Snack, day: LocalDate? = null): EntryId {
        val outcome = engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = day)))
        return (outcome as ActionOutcome.Applied).result.consumptionChanges.single().after.entryId
    }

    private fun food(label: String, kcal: Int) =
        """{"label": "$label", "quantity": null, "unit": null, "kcal": $kcal, "protein_g": null, "carbs_g": null, "fat_g": null, "fiber_g": null, "assumptions": [], "confidence": null}"""

    private fun correctArgs(
        entryId: String,
        changes: String = "null",
        food: String = "null",
        moment: String = "null",
        day: String = "null",
    ) = """{"entry_id": "$entryId", "changes": $changes, "food": $food, "moment": $moment, "day": $day}"""

    // --- Supprimer -------------------------------------------------------------------------------

    @Test
    fun `enleve la pomme - nouvelle version deleted, carte de suppression, Annuler la remet`() = runBlocking<Unit> {
        val engine = engine()
        seed(engine, Food("Barre protéinée", Nutrition(220.0, 20.0, 20.0, 8.0)))
        val pommeId = seed(engine, pomme)
        val model = ScriptedModel(
            { req -> toolCall("delete_consumption", """{"entry_id": "${req.entryIdInContext("Pomme")}"}""") },
            { text("C’est retiré.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("enlève la pomme")) as TurnOutcome.Completed

        assertEquals(listOf(RecordState.Active, RecordState.Deleted), engine.history(pommeId).map { it.state })
        assertEquals(220.0, engine.daySummary(monday).totals.kcal, 0.0)
        val card = outcome.cards.single() as RecordCard
        assertEquals(RecordKind.Deleted, card.kind)
        assertEquals(listOf("Pomme"), card.items.map { it.label })
        assertEquals("deleted", outcome.toolCalls.single().output["status"]!!.jsonPrimitive.content)

        // Annuler (bouton de la carte) : la pomme revient, par une nouvelle version.
        assertTrue(engine.execute(app.trackly.core.action.Undo(app.trackly.core.model.ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(300.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(3, engine.history(pommeId).size)
    }

    @Test
    fun `enleve le yaourt d hier - le modele lit hier avec get_day, puis supprime la bonne entree`() = runBlocking<Unit> {
        val engine = engine()
        val yaourtId = seed(engine, Food("Yaourt nature", Nutrition(75.0, 4.5, 6.0, 3.8)), Moment.Soir, sunday)
        seed(engine, pomme)
        val model = ScriptedModel(
            { req ->
                // Hier n'est pas dans le contexte du jour : c'est la raison d'être de get_day.
                assertTrue(runCatching { req.entryIdInContext("Yaourt nature") }.isFailure)
                assertTrue(req.context.contains("hier = dimanche 1er novembre (2026-11-01)"))
                toolCall("get_day", """{"day": "2026-11-01"}""", "a")
            },
            { req -> toolCall("delete_consumption", """{"entry_id": "${req.entryIdInToolOutput("Yaourt")}"}""", "b") },
            { text("C’est retiré d’hier soir.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("enlève le yaourt d'hier")) as TurnOutcome.Completed

        assertEquals(listOf("get_day", "delete_consumption"), outcome.toolCalls.map { it.name })
        assertEquals(RecordState.Deleted, engine.history(yaourtId).last().state)
        assertEquals(0.0, engine.daySummary(sunday).totals.kcal, 0.0)
        assertEquals(80.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(1, outcome.cards.size) // la lecture n'affiche aucune carte
    }

    @Test
    fun `un entry_id inventé est refuse avec un indice, rien n est ecrit`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        val model = ScriptedModel(
            { toolCall("delete_consumption", """{"entry_id": "entree-inventee"}""") },
            { text("Je ne retrouve pas cette entrée.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("enlève la poire")) as TurnOutcome.Completed

        val output = outcome.toolCalls.single().output
        assertEquals("rejected", output["status"]!!.jsonPrimitive.content)
        assertTrue(output["hint"]!!.jsonPrimitive.content.contains("get_day"))
        assertTrue(outcome.cards.isEmpty())
        assertEquals(1, engine.history(id).size)
    }

    // --- Corriger ----------------------------------------------------------------------------------

    @Test
    fun `deux barres au lieu d une - consommation liee a un produit, le code recalcule les kcal depuis la fiche`() = runBlocking<Unit> {
        val engine = engine()
        val bar = NewProduct(
            name = "Barre protéinée chocolat",
            brand = "Marque A",
            nutrients = Nutrients(400.0, 36.0, 22.0, 14.0, 5.0),
            basis = NutritionBasis(100.0, "g"),
            portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(220.0, 19.8, 12.1, 7.7, 2.8)),
        )
        val declared = engine.execute(DeclareConsumptions(listOf(DeclaredItem.OfProduct(ProductUse(newProduct = bar, amount = Quantity(1.0, "portion")), Moment.Snack))))
        val before = (declared as ActionOutcome.Applied).result.consumptionChanges.single().after
        // Quantité seule : pas de valeurs du modèle pour un produit connu.
        val changes = """{"label": null, "quantity": 2, "unit": null, "nutrition": null, "assumptions": null, "confidence": null}"""
        val model = ScriptedModel(
            { req ->
                // Le contexte signale la fiche liée : le modèle sait qu'il ne donne que la quantité.
                assertTrue(req.context.contains("produit ${before.food.productId}"))
                toolCall("correct_consumption", correctArgs(req.entryIdInContext("Barre protéinée chocolat"), changes = changes))
            },
            { text("Corrigé : deux barres.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("non, j'en ai pris deux")) as TurnOutcome.Completed

        val fixed = engine.history(before.entryId).last()
        assertEquals(2, fixed.version)
        assertEquals(before.food.productId, fixed.food.productId)
        assertEquals(Quantity(2.0, "portion"), fixed.food.quantity)
        assertEquals(440.0, fixed.food.nutrition.kcal, 0.0)
        assertEquals(440.0, engine.daySummary(monday).totals.kcal, 0.0)
        val output = outcome.toolCalls.single().output
        assertEquals("corrected", output["status"]!!.jsonPrimitive.content)
        assertTrue(output.toString().contains(before.food.productId!!))
    }

    @Test
    fun `non c etait 200 g - la quantite change, hypotheses, produit lie et source sont conserves, puis annule`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        val changes = """{"label": null, "quantity": 200, "unit": null, "nutrition": {"kcal": 107, "protein_g": 0.5, "carbs_g": 27, "fat_g": 0.3, "fiber_g": 4}, "assumptions": null, "confidence": null}"""
        val model = ScriptedModel(
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), changes = changes)) },
            { text("Corrigé : 200 g.") },
            { toolCall("undo_action", """{"action_id": null}""", "u") },
            { text("C’est annulé.") },
        )
        val assistant = interpreter(engine, model)

        val outcome = assistant.run(TurnInput("non, c'était 200 g")) as TurnOutcome.Completed

        val fixed = engine.history(id).last()
        assertEquals(Quantity(200.0, "g"), fixed.food.quantity)
        assertEquals(107.0, fixed.food.nutrition.kcal, 0.0)
        assertEquals(listOf("pomme de taille moyenne"), fixed.food.assumptions)
        assertEquals("produit-pomme", fixed.food.productId)
        assertEquals("Estimation générique", fixed.food.source!!.description)
        assertEquals(Confidence.Medium, fixed.food.confidence)
        assertEquals(107.0, engine.daySummary(monday).totals.kcal, 0.0)
        val card = outcome.cards.single() as RecordCard
        assertEquals(RecordKind.Corrected, card.kind)
        assertEquals("200 g", card.items.single().quantity)

        // Annuler la correction : l'état d'avant revient, rien n'est effacé.
        val undo = assistant.run(
            TurnInput("annule", history = listOf(HistoryMessage(HistoryMessage.Role.User, "non, c'était 200 g"), HistoryMessage(HistoryMessage.Role.Assistant, "Corrigé : 200 g."))),
        )
        assertEquals(card.actionId, (undo.cards.single() as UndoCard).undoneActionId)
        val versions = engine.history(id)
        assertEquals(3, versions.size)
        assertEquals(pomme, versions.last().food)
        assertEquals(80.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    @Test
    fun `tu t es trompe, c etait au diner - seul le moment change, l heure reelle et le reste sont gardes`() = runBlocking<Unit> {
        val engine = engine("2026-11-02T21:00")
        val id = seed(engine, pomme, Moment.Snack)
        val before = engine.history(id).single()
        val model = ScriptedModel(
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), moment = "\"soir\"")) },
            { text("C’est au dîner.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("tu t'es trompé, c'était au dîner")) as TurnOutcome.Completed

        val after = engine.history(id).last()
        assertEquals(Moment.Soir, after.moment)
        assertEquals(before.consumedAt, after.consumedAt)
        assertEquals(before.food, after.food)
        assertEquals("Soir", (outcome.cards.single() as RecordCard).momentsLabel)
    }

    @Test
    fun `c etait hier - la date exprimee deplace la consommation, une date future est refusee`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        val model = ScriptedModel(
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), day = "\"2026-11-03\""), "futur") },
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), day = "\"2026-11-01\""), "hier") },
            { text("C’est déplacé à hier.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("en fait c'était hier")) as TurnOutcome.Completed

        assertEquals("error", outcome.toolCalls[0].output["status"]!!.jsonPrimitive.content)
        assertEquals(sunday, engine.history(id).last().day)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(80.0, engine.daySummary(sunday).totals.kcal, 0.0)
        assertEquals(2, engine.history(id).size)
    }

    @Test
    fun `changer la quantite sans les valeurs recalculees est refuse, le modele se corrige`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        val bare = """{"label": null, "quantity": 200, "unit": null, "nutrition": null, "assumptions": null, "confidence": null}"""
        val full = """{"label": null, "quantity": 200, "unit": null, "nutrition": {"kcal": 107, "protein_g": null, "carbs_g": null, "fat_g": null, "fiber_g": null}, "assumptions": null, "confidence": null}"""
        val model = ScriptedModel(
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), changes = bare), "1") },
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), changes = full), "2") },
            { text("Corrigé.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("c'était 200 g")) as TurnOutcome.Completed

        val first = outcome.toolCalls[0].output
        assertEquals("rejected", first["status"]!!.jsonPrimitive.content)
        assertTrue(first["message"]!!.jsonPrimitive.content.contains("recalculés"))
        assertEquals(2, engine.history(id).size)
        assertEquals(107.0, engine.daySummary(monday).totals.kcal, 0.0)
        // Macros devenues inconnues plutôt que périmées.
        assertNull(engine.history(id).last().food.nutrition.proteinG)
    }

    @Test
    fun `non c est l autre barre - un autre aliment remplace l ancien sans rien garder de son produit`() = runBlocking<Unit> {
        val engine = engine()
        val barre = Food(
            "Barre chocolat", Nutrition(220.0, 20.0, 20.0, 8.0), Quantity(55.0, "g"),
            assumptions = listOf("saveur habituelle"), productId = "produit-barre-chocolat",
        )
        val id = seed(engine, barre)
        val model = ScriptedModel(
            { req ->
                toolCall("correct_consumption", correctArgs(req.entryIdInContext("Barre chocolat"), food = food("Barre protéinée vanille", 200)))
            },
            { text("C’est l’autre barre.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("non, c'est l'autre barre, la vanille")) as TurnOutcome.Completed

        val fixed = engine.history(id).last().food
        assertEquals("Barre protéinée vanille", fixed.label)
        assertNull(fixed.productId)
        assertTrue(fixed.assumptions.isEmpty())
        assertEquals(200.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(RecordKind.Corrected, (outcome.cards.single() as RecordCard).kind)
    }

    @Test
    fun `changes et food ensemble sont refuses sans rien ecrire`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        val changes = """{"label": "Poire", "quantity": null, "unit": null, "nutrition": null, "assumptions": null, "confidence": null}"""
        val model = ScriptedModel(
            { req -> toolCall("correct_consumption", correctArgs(req.entryIdInContext("Pomme"), changes = changes, food = food("Poire", 90))) },
            { text("Je réessaie.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("c'était une poire")) as TurnOutcome.Completed

        assertEquals("error", outcome.toolCalls.single().output["status"]!!.jsonPrimitive.content)
        assertEquals(1, engine.history(id).size)
    }

    // --- Bouton « Corriger » -------------------------------------------------------------------------

    @Test
    fun `le bouton Corriger donne au modele l enregistrement vise, meme d un autre jour`() = runBlocking<Unit> {
        val engine = engine()
        val yaourtId = seed(engine, Food("Yaourt nature", Nutrition(75.0, 4.5, 6.0, 3.8)), Moment.Soir, sunday)
        seed(engine, pomme)
        val model = ScriptedModel(
            { req ->
                assertTrue(req.context.contains("## Correction demandée"))
                assertTrue(req.context.contains("carte de l'action 7"))
                toolCall("correct_consumption", correctArgs(req.entryIdInContext("Yaourt nature"), moment = "\"midi\""))
            },
            { text("C’est corrigé.") },
        )

        interpreter(engine, model).run(
            TurnInput("c'était à midi", focus = CorrectionFocus(7, listOf(yaourtId.value))),
        )

        assertEquals(Moment.Midi, engine.history(yaourtId).last().moment)
        assertEquals(sunday, engine.history(yaourtId).last().day)
    }

    @Test
    fun `sans le bouton Corriger, le contexte n a pas de section de correction`() = runBlocking<Unit> {
        val engine = engine()
        seed(engine, pomme)
        val model = ScriptedModel({ req ->
            assertFalse(req.context.contains("Correction demandée"))
            text("D’accord.")
        })

        interpreter(engine, model).run(TurnInput("merci"))
    }

    @Test
    fun `Corriger sur un enregistrement deja retire le dit au modele`() = runBlocking<Unit> {
        val engine = engine()
        val id = seed(engine, pomme)
        engine.execute(app.trackly.core.action.DeleteConsumption(id))
        val model = ScriptedModel({ req ->
            assertTrue(req.context.contains("déjà retirée du journal"))
            text("Cette pomme est déjà retirée.")
        })

        interpreter(engine, model).run(TurnInput("c'était 200 g", focus = CorrectionFocus(1, listOf(id.value))))
    }

    // --- Ajouter pour hier ------------------------------------------------------------------------

    @Test
    fun `ajoute pour hier a midi - la date exprimee prime, l heure reelle reste celle du message`() = runBlocking<Unit> {
        val engine = engine("2026-11-02T12:00")
        val model = ScriptedModel(
            { req ->
                assertTrue(req.instructions.contains("PRIME toujours sur la règle des 5 h"))
                toolCall("record_consumptions", """{"items": [{"food": ${food("Pizza", 900)}, "moment": "soir", "day": "2026-11-01"}], "question": null}""")
            },
            { text("Ajouté à hier soir.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("ajoute pour hier soir une pizza")) as TurnOutcome.Completed

        val day = engine.daySummary(sunday)
        assertEquals(listOf("Pizza"), day.consumptions.map { it.food.label })
        assertEquals(SampleData.clock("2026-11-02T12:00").instant(), day.consumptions.single().consumedAt)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(sunday.toString(), (outcome.cards.single() as RecordCard).items.single().day)
    }

    @Test
    fun `une date future n est jamais enregistree en silence`() = runBlocking<Unit> {
        val engine = engine("2026-11-02T12:00")
        val model = ScriptedModel(
            { toolCall("record_consumptions", """{"items": [{"food": ${food("Pizza", 900)}, "moment": "soir", "day": "2026-11-05"}], "question": null}""") },
            { text("Je ne peux pas noter pour une date à venir. Tu parlais de quel jour ?") },
        )

        val outcome = interpreter(engine, model).run(TurnInput("ajoute une pizza pour jeudi")) as TurnOutcome.Completed

        assertEquals("error", outcome.toolCalls.single().output["status"]!!.jsonPrimitive.content)
        assertTrue(outcome.cards.isEmpty())
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(0.0, engine.daySummary(LocalDate.parse("2026-11-05")).totals.kcal, 0.0)
    }

    @Test
    fun `a 0 h 30, un aujourd hui exprime compte pour la date civile et non pour la veille`() = runBlocking<Unit> {
        val engine = engine("2026-11-03T00:30")
        val model = ScriptedModel(
            { toolCall("record_consumptions", """{"items": [{"food": ${food("Biscuit", 60)}, "moment": "snack", "day": "2026-11-03"}], "question": null}""") },
            { text("Noté pour aujourd’hui.") },
        )

        interpreter(engine, model).run(TurnInput("ajoute pour aujourd'hui un biscuit"))

        assertEquals(60.0, engine.daySummary(LocalDate.parse("2026-11-03")).totals.kcal, 0.0)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }

    // --- Longue dictée -------------------------------------------------------------------------------

    @Test
    fun `ajoute a mon petit-dejeuner - tous les aliments decrits sont enregistres sauf l exclu et l envisage, en une annulation`() = runBlocking<Unit> {
        val engine = engine("2026-11-02T08:15")
        val dictee = "ajoute à mon petit-déjeuner deux œufs brouillés avec un peu de beurre, deux tranches de pain complet, " +
            "un café noir et un kiwi, sans le jus d'orange que j'ai finalement laissé, et je prendrai peut-être un yaourt vers dix heures"
        val items = listOf(
            "Œufs brouillés au beurre" to 230, "Pain complet" to 160, "Café noir" to 2, "Kiwi" to 45,
        ).joinToString(",") { (label, kcal) -> """{"food": ${food(label, kcal)}, "moment": "matin", "day": null}""" }
        val model = ScriptedModel(
            { req ->
                // Les consignes demandent TOUS les aliments, hors exclusions et envisagés.
                assertTrue(req.instructions.contains("vaut validation de TOUS les aliments"))
                assertTrue(req.instructions.contains("N'enregistre PAS un aliment que l'utilisateur exclut"))
                assertTrue(req.instructions.contains("seulement envisagé"))
                toolCall("record_consumptions", """{"items": [$items], "question": null}""")
            },
            { text("C’est noté pour ton petit-déjeuner.") },
        )

        val outcome = interpreter(engine, model).run(TurnInput(dictee)) as TurnOutcome.Completed

        val day = engine.daySummary(monday)
        assertEquals(listOf("Œufs brouillés au beurre", "Pain complet", "Café noir", "Kiwi"), day.consumptions.map { it.food.label })
        assertTrue(day.consumptions.none { it.food.label.contains("jus") || it.food.label.contains("Yaourt") })
        assertTrue(day.consumptions.all { it.moment == Moment.Matin })
        val card = outcome.cards.single() as RecordCard
        assertEquals(4, card.items.size)
        assertEquals(437.0, card.totalKcal, 0.0)
        assertEquals(1, outcome.applied.size) // une seule action : une seule annulation

        assertTrue(engine.execute(app.trackly.core.action.Undo(app.trackly.core.model.ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(0.0, engine.daySummary(monday).totals.kcal, 0.0)
    }
}
