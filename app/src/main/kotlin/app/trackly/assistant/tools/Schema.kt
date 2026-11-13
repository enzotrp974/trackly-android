package app.trackly.assistant.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Petit constructeur de schémas JSON au format **strict** d'OpenAI : chaque objet a
 * `additionalProperties: false` et tous ses champs dans `required` ; un champ facultatif
 * accepte `null` (`nullable = true`). `ToolSchemaTest` vérifie ces règles pour chaque outil.
 */
object Schema {
    fun obj(vararg properties: Pair<String, JsonObject>, description: String? = null, nullable: Boolean = false): JsonObject =
        buildJsonObject {
            put("type", type("object", nullable))
            description?.let { put("description", it) }
            put("properties", JsonObject(properties.toMap()))
            put("required", JsonArray(properties.map { JsonPrimitive(it.first) }))
            put("additionalProperties", false)
        }

    fun string(description: String, nullable: Boolean = false, enum: List<String>? = null, pattern: String? = null): JsonObject =
        buildJsonObject {
            put("type", type("string", nullable))
            put("description", description)
            enum?.let { values ->
                put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) }; if (nullable) add(JsonPrimitive(null as String?)) })
            }
            pattern?.let { put("pattern", it) }
        }

    fun number(description: String, nullable: Boolean = false, minimum: Double? = null): JsonObject = buildJsonObject {
        put("type", type("number", nullable))
        put("description", description)
        minimum?.let { put("minimum", it) }
    }

    fun boolean(description: String, nullable: Boolean = false): JsonObject = buildJsonObject {
        put("type", type("boolean", nullable))
        put("description", description)
    }

    fun integer(description: String, nullable: Boolean = false, minimum: Long? = null): JsonObject = buildJsonObject {
        put("type", type("integer", nullable))
        put("description", description)
        minimum?.let { put("minimum", it) }
    }

    fun array(items: JsonObject, description: String, minItems: Int? = null, nullable: Boolean = false): JsonObject = buildJsonObject {
        put("type", type("array", nullable))
        put("description", description)
        put("items", items)
        minItems?.let { put("minItems", it) }
    }

    /** Date au format `AAAA-MM-JJ`, ou `null`. */
    fun date(description: String): JsonObject = string(description, nullable = true, pattern = "^\\d{4}-\\d{2}-\\d{2}$")

    private fun type(name: String, nullable: Boolean) =
        if (nullable) buildJsonArray { add(JsonPrimitive(name)); add(JsonPrimitive("null")) } else JsonPrimitive(name)
}
