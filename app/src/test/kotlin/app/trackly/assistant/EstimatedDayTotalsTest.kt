package app.trackly.assistant

import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.context.TrackingDayContext
import app.trackly.assistant.tools.MealIdeasTool
import app.trackly.assistant.tools.ReadDayTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Ce que le modèle lit pour une journée estimée ou bornée est l'énergie de la
 * journée (estimation validée), jamais la somme du détail. Jeu de parité : le 3 septembre contient
 * 300 kcal détaillées pour une estimation globale de 2 600 kcal ; le 4, une borne minimale.
 */
class EstimatedDayTotalsTest {
    private val engine: TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking { assertTrue(HistoryImport.run(engine, SampleData.historyBundle) is ImportOutcome.Imported) }
    }

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(app.trackly.core.time.TrackingDay.ZONE).toInstant()

    private fun bilan(day: String): JsonObject = runBlocking {
        val result = ReadDayTool.execute(buildJsonObject { put("day", JsonPrimitive(day)) }, ToolContext(engine, at("2026-11-02T12:30")))
        result.output["bilan"]!!.jsonObject
    }

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDouble()

    @Test
    fun `get_day d une journee estimee transmet l estimation, son statut et ses bornes, macros inconnues`() {
        val bilan = bilan("2026-09-03")
        assertEquals(2600.0, bilan.num("consumed_kcal")!!, 0.0)
        assertEquals("estimate", bilan["energy_status"]!!.jsonPrimitive.content)
        assertEquals(2400.0, bilan.num("kcal_low")!!, 0.0)
        assertEquals(2800.0, bilan.num("kcal_high")!!, 0.0)
        assertEquals(0.0, bilan.num("remaining_kcal")!!, 0.0)
        for (macro in listOf("protein_g", "carbs_g", "fat_g")) assertEquals(macro, null, bilan.num(macro))
    }

    @Test
    fun `get_day d une borne minimale annonce un minimum, sans centre ni borne haute`() {
        val bilan = bilan("2026-09-04")
        assertEquals("lower_bound", bilan["energy_status"]!!.jsonPrimitive.content)
        assertEquals(1550.0, bilan.num("consumed_kcal")!!, 0.0)
        assertEquals(true, bilan["consumed_is_minimum"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(null, bilan.num("kcal_high"))
        assertEquals(null, bilan.num("protein_g"))
    }

    @Test
    fun `une journee detaillee garde ses macros`() {
        val bilan = bilan("2026-09-06")
        assertEquals("detailed", bilan["energy_status"]!!.jsonPrimitive.content)
        assertEquals(1500.0, bilan.num("consumed_kcal")!!, 0.0)
        assertEquals(90.0, bilan.num("protein_g")!!, 0.0)
    }

    @Test
    fun `le contexte du jour annonce l estimation et non le detail`() = runBlocking {
        val text = TrackingDayContext.render(ContextEnv(engine, at("2026-09-03T22:30")))!!
        assertTrue(text, text.contains("2600 kcal"))
        assertTrue(text, text.contains("estimation"))
        assertTrue(text, text.contains("entre 2400 et 2800"))
        assertTrue(text, text.contains("macros inconnues"))
        assertTrue(text, !text.contains("Total : 300 kcal"))
    }

    @Test
    fun `get_meal_ideas sur une journee estimee ne deduit pas les macros du detail partiel`() = runBlocking {
        val result = MealIdeasTool.execute(buildJsonObject {}, ToolContext(engine, at("2026-09-03T22:30")))
        val remaining = result.output["remaining"]!!.jsonObject
        assertEquals(0.0, remaining.num("kcal")!!, 0.0)
        for (macro in listOf("protein_g", "carbs_g", "fat_g")) assertEquals(macro, null, remaining.num(macro))
    }
}
