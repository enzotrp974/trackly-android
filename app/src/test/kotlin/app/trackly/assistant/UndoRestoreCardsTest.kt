package app.trackly.assistant

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.tools.CorrectConsumptionTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.tools.UndoActionTool
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cartes « Rétabli » et correction « avant → après », construites par le code. */
class UndoRestoreCardsTest {
    private val engine: TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
    private val context = ToolContext(engine, engine.now())

    private fun add(): Long = runBlocking {
        val outcome = engine.execute(
            AddConsumptions(
                listOf(
                    NewConsumption(Food("Barre de céréales", Nutrition(120.0, 3.0, 20.0, 4.0)), Moment.Snack),
                    NewConsumption(Food("Pomme", Nutrition(78.0, 0.4, 19.0, 0.3)), Moment.Snack),
                ),
            ),
        )
        (outcome as ActionOutcome.Applied).result.id.value
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `remets-le - annuler l annulation donne une vraie carte Retabli, avec aliments et kcal`() = runBlocking {
        val added = add()
        val undo = (engine.execute(Undo(app.trackly.core.model.ActionId(added))) as ActionOutcome.Applied).result.id.value

        val result = UndoActionTool.execute(args("""{"action_id":$undo}"""), context)

        val card = result.card as RecordCard
        assertEquals(RecordKind.Restored, card.kind)
        assertEquals(listOf("Barre de céréales", "Pomme"), card.items.map { it.label })
        assertEquals(198.0, card.totalKcal, 0.0)
        assertEquals("Snack", card.momentsLabel)
        assertEquals("restored", (result.output["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(198.0, engine.daySummary(java.time.LocalDate.parse("2026-11-02")).totals.kcal, 0.0)
    }

    @Test
    fun `annule - la ligne d annulation, au titre lisible`() = runBlocking {
        val added = add()
        val result = UndoActionTool.execute(args("""{"action_id":$added}"""), context)
        val card = result.card as UndoCard
        assertEquals("Annulation : Ajout : Barre de céréales (120 kcal), Pomme (78 kcal)", card.title)
        assertEquals("Ajout : Barre de céréales (120 kcal), Pomme (78 kcal)", card.subject)
        assertEquals(false, card.restores)
    }

    @Test
    fun `correction - la carte garde la quantite et les kcal d avant`() = runBlocking {
        val outcome = engine.execute(
            AddConsumptions(NewConsumption(Food("Riz basmati cuit", Nutrition(260.0, 5.4, 56.0, 0.6), quantity = Quantity(200.0, "g"), assumptions = listOf("Riz basmati assimilé au riz blanc cuit de ta fiche.", "Poids supposé cuit")), Moment.Midi)),
        ) as ActionOutcome.Applied
        val entry = outcome.result.consumptionChanges.single().after.entryId.value

        val result = CorrectConsumptionTool.execute(
            args("""{"entry_id":"$entry","changes":{"label":null,"quantity":250,"unit":"g","nutrition":{"kcal":325,"protein_g":6.8,"carbs_g":70,"fat_g":0.8,"fiber_g":null},"assumptions":null,"confidence":null},"food":null,"moment":null,"day":null}"""),
            context,
        )

        val card = result.card as RecordCard
        val item = card.items.single()
        assertEquals("200 g", item.beforeQuantity)
        assertEquals("250 g", item.quantity)
        assertEquals(260.0, item.beforeKcal!!, 0.0)
        assertTrue(item.kcalChanged)
        assertEquals(260.0, card.beforeTotalKcal!!, 0.0)
        // Une hypothèse par ligne, sans le « ; » final du modèle.
        assertEquals(listOf("Riz basmati assimilé au riz blanc cuit de ta fiche.", "Poids supposé cuit"), card.assumptionLines)
    }

    @Test
    fun `une carte d ajout n a pas d avant`() = runBlocking {
        add()
        val card = RecordCard.from(
            (engine.execute(AddConsumptions(NewConsumption(Food("Yaourt", Nutrition(60.0, 5.0, 6.0, 1.0)), Moment.Snack))) as ActionOutcome.Applied).result,
            RecordKind.Added,
        )
        assertNull(card.beforeTotalKcal)
        assertNull(card.items.single().beforeKcal)
    }
}
