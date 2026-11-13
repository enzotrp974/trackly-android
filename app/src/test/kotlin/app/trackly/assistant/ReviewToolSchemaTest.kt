package app.trackly.assistant

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.review
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.prompt.review
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.assistant.tools.review
import app.trackly.review.InMemoryReportRepository
import app.trackly.review.RecordingReviewNotifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garde-fous des outils, consignes et contexte de la conversation de bilan (mode strict d'OpenAI, consignes stables). */
class ReviewToolSchemaTest {
    private val registry = ToolRegistry.review(app.trackly.review.ReviewPeriods(InMemoryReportRepository(), app.trackly.review.InMemoryReviewHistory()), RecordingReviewNotifier())

    @Test
    fun `noms valides et uniques, jamais d outil de consommation`() {
        val names = registry.tools.map { it.name }
        assertEquals(listOf("record_week_info", "set_weight_goal", "propose_temporary_goal", "produce_weekly_report"), names)
        assertTrue(names.all { Regex("^[a-z0-9_]{1,64}$").matches(it) })
        val chat = ToolRegistry.default().tools.map { it.name }
        assertTrue(chat.none { it in names }) // aucun recoupement avec le chat de saisie
        assertTrue("record_week_info" !in chat)
    }

    @Test
    fun `l objectif temporaire ne prend aucun chiffre de calories du modele`() {
        val tool = registry.tools.single { it.name == "propose_temporary_goal" }
        assertEquals(setOf("days", "with_steps"), (tool.parameters["properties"] as JsonObject).keys)
    }

    @Test
    fun `chaque schema respecte le mode strict`() {
        for (tool in registry.tools) {
            assertEquals("true", tool.definition()["strict"]!!.jsonPrimitive.content)
            assertTrue(tool.description.isNotBlank())
            checkStrict(tool.name, tool.parameters)
        }
    }

    private fun checkStrict(path: String, schema: JsonElement) {
        if (schema !is JsonObject) return
        val types = when (val type = schema["type"]) {
            is JsonArray -> type.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(type.content)
            else -> emptyList()
        }
        if ("object" in types) {
            val properties = schema["properties"] as JsonObject
            val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$path : tous les champs sont requis", properties.keys, required.toSet())
            assertEquals("$path : additionalProperties", "false", schema["additionalProperties"]!!.jsonPrimitive.content)
            properties.forEach { (name, child) -> checkStrict("$path.$name", child) }
        }
        if ("array" in types) checkStrict("$path[]", schema["items"]!!)
        schema["enum"]?.let { values -> if ("null" in types) assertTrue(values.jsonArray.any { it is JsonNull }) }
    }

    @Test
    fun `consignes stables et sections uniques`() {
        val prompt = SystemPrompt.review()
        val text = prompt.render()
        assertEquals(text, SystemPrompt.review().render())
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(text))
        assertEquals(prompt.sections.size, prompt.sections.map { it.id }.toSet().size)
        assertTrue(text.contains("JAMAIS de valeur inventée"))
        assertTrue(text.contains("Tu ne modifies jamais l'objectif nutritionnel"))
        val providers = ContextBuilder.review(app.trackly.review.ReviewPeriods(InMemoryReportRepository(), app.trackly.review.InMemoryReviewHistory())).providers.map { it.id }
        assertEquals(providers.size, providers.toSet().size)
    }
}
