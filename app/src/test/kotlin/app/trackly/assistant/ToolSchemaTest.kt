package app.trackly.assistant

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fous des points d'extension : tout outil enregistré respecte le mode strict
 * d'OpenAI (sinon la requête entière serait rejetée), et les consignes restent stables.
 */
class ToolSchemaTest {
    private val registry = ToolRegistry.default()

    @Test
    fun `chaque outil a un nom valide et unique`() {
        val names = registry.tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("^[a-z0-9_]{1,64}$").matches(it) })
    }

    @Test
    fun `chaque schema respecte le mode strict`() {
        for (tool in registry.tools) {
            val definition = tool.definition()
            assertEquals("function", definition["type"]!!.jsonPrimitive.content)
            assertEquals("true", definition["strict"]!!.jsonPrimitive.content)
            assertTrue("${tool.name} : description vide", tool.description.isNotBlank())
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
        schema["enum"]?.let { values ->
            if ("null" in types) assertTrue("$path : enum nullable doit contenir null", values.jsonArray.any { it is kotlinx.serialization.json.JsonNull })
        }
    }

    @Test
    fun `les consignes ne contiennent aucune donnee variable`() {
        val text = SystemPrompt.default().render()
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(text))
        assertEquals(text, SystemPrompt.default().render())
        assertTrue(text.contains("IMMÉDIATEMENT"))
    }

    @Test
    fun `sections et elements de contexte ont des identifiants uniques`() {
        val sections = SystemPrompt.default().sections.map { it.id }
        assertEquals(sections.size, sections.toSet().size)
        val providers = ContextBuilder.default().providers.map { it.id }
        assertEquals(providers.size, providers.toSet().size)
    }
}
