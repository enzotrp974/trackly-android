package app.trackly.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Flux SSE simulés de la Responses API (`stream: true`), fabriqués à partir d'une réponse complète (les
 * fixtures de `resources/openai/`) avec les événements documentés : `response.created`,
 * `response.output_item.added` / `.done`, `response.web_search_call.*`, `response.function_call_arguments.*`,
 * `response.output_text.delta` / `.done`, puis `response.completed` qui porte la réponse entière (avec `usage`).
 */
object SseStreams {
    /**
     * Flux complet de [response]. [cutBefore] : le flux s'arrête juste avant le premier événement de ce type
     * (coupure simulée). [comments] glisse des lignes de commentaire (« : keep-alive »), [done] ajoute `data: [DONE]`.
     */
    fun of(
        response: JsonObject,
        textChunk: Int = 7,
        cutBefore: String? = null,
        comments: Boolean = true,
        done: Boolean = false,
    ): String {
        val events = mutableListOf<Pair<String, JsonObject>>()
        fun add(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
            events += type to buildJsonObject {
                put("type", type)
                put("sequence_number", events.size)
                build()
            }
        }
        val id = response["id"]?.jsonPrimitive?.content ?: "resp"
        add("response.created") { put("response", JsonObject(mapOf("id" to JsonPrimitive(id), "status" to JsonPrimitive("in_progress")))) }
        add("response.in_progress") { put("response", JsonObject(mapOf("id" to JsonPrimitive(id), "status" to JsonPrimitive("in_progress")))) }
        val output = (response["output"] as? JsonArray).orEmpty().map { it.jsonObject }
        output.forEachIndexed { index, item ->
            val type = item["type"]?.jsonPrimitive?.content
            val itemId = item["id"]?.jsonPrimitive?.content ?: "item_$index"
            val start = when (type) {
                "function_call" -> JsonObject(item + ("arguments" to JsonPrimitive("")))
                "message" -> JsonObject(item + ("content" to JsonArray(emptyList())))
                else -> item
            }
            add("response.output_item.added") {
                put("output_index", index)
                put("item", start)
            }
            when (type) {
                "web_search_call" -> {
                    for (step in listOf("in_progress", "searching", "completed")) {
                        add("response.web_search_call.$step") {
                            put("output_index", index)
                            put("item_id", itemId)
                        }
                    }
                }
                "function_call" -> {
                    val arguments = item["arguments"]!!.jsonPrimitive.content
                    for (piece in arguments.chunked(textChunk * 3)) {
                        add("response.function_call_arguments.delta") {
                            put("output_index", index)
                            put("item_id", itemId)
                            put("delta", piece)
                        }
                    }
                    add("response.function_call_arguments.done") {
                        put("output_index", index)
                        put("item_id", itemId)
                        put("arguments", arguments)
                    }
                }
                "message" -> {
                    item["content"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { part, content ->
                        val text = content["text"]?.jsonPrimitive?.content ?: return@forEachIndexed
                        for (piece in text.chunked(textChunk)) {
                            add("response.output_text.delta") {
                                put("output_index", index)
                                put("item_id", itemId)
                                put("content_index", part)
                                put("delta", piece)
                            }
                        }
                        add("response.output_text.done") {
                            put("output_index", index)
                            put("item_id", itemId)
                            put("content_index", part)
                            put("text", text)
                        }
                    }
                }
            }
            add("response.output_item.done") {
                put("output_index", index)
                put("item", item)
            }
        }
        val status = response["status"]?.jsonPrimitive?.content
        add(if (status == "incomplete") "response.incomplete" else if (status == "failed") "response.failed" else "response.completed") {
            put("response", response)
        }
        return buildString {
            for ((index, event) in events.withIndex()) {
                val (type, json) = event
                if (type == cutBefore) return@buildString
                if (comments && index % 5 == 0) append(": keep-alive\n\n")
                append("event: ").append(type).append('\n')
                append("data: ").append(json.toString()).append("\n\n")
            }
            if (done) append("data: [DONE]\n\n")
        }
    }

    /** Événement `error` au milieu d'un flux, après quelques morceaux de texte. */
    fun errorMidStream(code: String, message: String = "Erreur simulée"): String = buildString {
        append("event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_err\"}}\n\n")
        append("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"Je comm\"}\n\n")
        append("event: error\ndata: {\"type\":\"error\",\"sequence_number\":2,\"code\":\"$code\",\"message\":\"$message\",\"param\":null}\n\n")
    }
}
