package app.trackly.core.importing

import app.trackly.core.action.SeedKnowledge
import app.trackly.core.action.SeedMemory
import app.trackly.core.action.SeedTerm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lit le fichier des connaissances de départ embarqué dans l'APK (facultatif, TRACKLY_PRELOAD_DIR au build). */
object KnowledgeSeedReader {
    fun read(text: String): SeedKnowledge {
        val document = Json.parseToJsonElement(text).jsonObject
        fun items(name: String): List<JsonElement> = document[name]?.jsonArray.orEmpty()
        fun JsonElement.field(name: String): String = jsonObject.getValue(name).jsonPrimitive.content
        return SeedKnowledge(
            memories = items("memories").map { SeedMemory(it.field("id"), it.field("topic"), it.field("text")) },
            terms = items("terms").map { SeedTerm(it.field("heard"), it.field("correct")) },
            stock = items("stock").map { it.jsonPrimitive.content },
        )
    }
}
