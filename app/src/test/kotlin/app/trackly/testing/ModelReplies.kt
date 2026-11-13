package app.trackly.testing

import app.trackly.assistant.openai.ResponsesTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Réponses de modèle simulées **calculées** : au lieu d'un JSON figé, chaque étape reçoit la requête
 * envoyée (contexte, résultats d'outils) et fabrique la réponse, comme le ferait un modèle qui lit
 * le contexte pour retrouver l'`entry_id` d'une consommation. Même format que les réponses de
 * `resources/openai/` (écrites d'après la doc de la Responses API). Aucun réseau.
 */
class ScriptedModel(private vararg val steps: (Request) -> JsonObject) : ResponsesTransport {
    val requests = mutableListOf<JsonObject>()

    override suspend fun send(body: JsonObject): JsonObject {
        requests += body
        val step = steps.getOrNull(requests.size - 1) ?: error("Requête inattendue n° ${requests.size}")
        return step(Request(body))
    }

    /** Requête vue par le modèle. */
    class Request(val body: JsonObject) {
        private val input: JsonArray get() = body["input"]!!.jsonArray

        /** Message `developer` : le contexte calculé par l'app. */
        val context: String
            get() = input.map { it.jsonObject }.first { it["role"]?.jsonPrimitive?.content == "developer" }["content"]!!.jsonPrimitive.content

        val instructions: String get() = body["instructions"]!!.jsonPrimitive.content

        /** Dernier résultat d'outil renvoyé au modèle. */
        val lastToolOutput: JsonObject
            get() = Json.parseToJsonElement(
                input.map { it.jsonObject }.last { it["type"]?.jsonPrimitive?.content == "function_call_output" }["output"]!!.jsonPrimitive.content,
            ).jsonObject

        /** `entry_id` de la ligne du contexte qui commence par [label] : ce que ferait le modèle pour désigner l'entrée. */
        fun entryIdInContext(label: String): String =
            Regex("(?m)^.*${Regex.escape(label)}.*\\[entry_id ([^,\\]]+)").find(context)?.groupValues?.get(1)
                ?: error("« $label » n'est pas dans le contexte :\n$context")

        /** `entry_id` d'une entrée du dernier résultat de `get_day`. */
        fun entryIdInToolOutput(label: String): String =
            lastToolOutput["entries"]!!.jsonArray.map { it.jsonObject }
                .first { it["label"]!!.jsonPrimitive.content.contains(label) }["entry_id"]!!.jsonPrimitive.content
    }

    companion object {
        fun toolCall(name: String, arguments: String, id: String = "1"): JsonObject = response(
            buildJsonObject {
                put("type", "function_call")
                put("id", "fc_$id")
                put("call_id", "call_$id")
                put("name", name)
                put("arguments", arguments)
                put("status", "completed")
            },
        )

        fun text(text: String): JsonObject = response(
            buildJsonObject {
                put("type", "message")
                put("id", "msg_texte")
                put("status", "completed")
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "output_text")
                        put("text", text)
                        putJsonArray("annotations") {}
                        putJsonArray("logprobs") {}
                    }
                }
            },
        )

        private fun response(item: JsonObject): JsonObject = buildJsonObject {
            put("id", "resp_simule")
            put("object", "response")
            put("status", "completed")
            put("model", "gpt-6.1-sol")
            put("output", buildJsonArray { add(item) })
            put(
                "usage",
                buildJsonObject {
                    put("input_tokens", 3000)
                    put("output_tokens", 100)
                    put("total_tokens", 3100)
                },
            )
        }
    }
}

/**
 * Réponses de modèle simulées, construites dans le code, avec la forme décrite par la doc de
 * la Responses API (`output` : raisonnement, `function_call` ou `message`). Pour les scénarios
 * où un identifiant n'est connu qu'en cours de test (un produit mémorisé, une consommation).
 * Aucun appel réseau.
 */
object ModelReplies {
    private var counter = 0

    /** Réponse qui appelle un outil avec [arguments] (objet JSON sous forme de texte). */
    fun call(name: String, arguments: String): ScriptedTransport.Step {
        val n = ++counter
        return ScriptedTransport.Reply(
            response(
                buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_$n")
                    put("summary", JsonArray(emptyList()))
                    put("encrypted_content", "gAAAA-raisonnement-chiffre-simule-$n")
                },
                buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_$n")
                    put("call_id", "call_$n")
                    put("name", name)
                    put("arguments", arguments)
                    put("status", "completed")
                },
            ),
        )
    }

    /** Réponse finale en texte. */
    fun text(text: String): ScriptedTransport.Step {
        val n = ++counter
        return ScriptedTransport.Reply(
            response(
                buildJsonObject {
                    put("type", "message")
                    put("id", "msg_$n")
                    put("status", "completed")
                    put("role", "assistant")
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "output_text")
                                    put("text", text)
                                    put("annotations", JsonArray(emptyList()))
                                    put("logprobs", JsonArray(emptyList()))
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

    /**
     * Recherche web, forme décrite par la doc : un item `web_search_call` par recherche
     * (`action.type` = `search`, puis `open_page` pour une page consultée), puis l'appel d'outil.
     * `tool_usage.web_search.num_requests` compte les recherches facturables.
     */
    fun searchThenCall(queries: List<String>, name: String, arguments: String): ScriptedTransport.Step {
        val n = ++counter
        val items = buildList {
            add(
                buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_$n")
                    put("summary", JsonArray(emptyList()))
                    put("encrypted_content", "gAAAA-raisonnement-chiffre-simule-$n")
                },
            )
            queries.forEachIndexed { i, query ->
                add(
                    buildJsonObject {
                        put("type", "web_search_call")
                        put("id", "ws_${n}_$i")
                        put("status", "completed")
                        put("action", buildJsonObject { put("type", "search"); put("query", query) })
                    },
                )
            }
            add(
                buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_$n")
                    put("call_id", "call_$n")
                    put("name", name)
                    put("arguments", arguments)
                    put("status", "completed")
                },
            )
        }
        return ScriptedTransport.Reply(response(items, searches = queries.size))
    }

    /** Une source citée : titre, adresse (avec `utm_source=openai` comme OpenAI l'ajoute) et phrase qui la porte. */
    data class Source(val title: String, val url: String)

    /**
     * Réponse finale avec citations : le texte porte le balisage « ([titre](url)) » à la fin, et chaque
     * annotation `url_citation` en donne la plage (`start_index`, `end_index`), comme dans la doc.
     */
    fun textWithCitations(text: String, sources: List<Source>): ScriptedTransport.Step {
        val n = ++counter
        val markup = sources.map { " ([${it.title}](${it.url}))" }
        val full = text + markup.joinToString("")
        var cursor = text.length
        val annotations = sources.mapIndexed { i, source ->
            val start = cursor + 1 // après l'espace
            cursor += markup[i].length
            buildJsonObject {
                put("type", "url_citation")
                put("start_index", start)
                put("end_index", cursor)
                put("url", source.url)
                put("title", source.title)
            }
        }
        val message = buildJsonObject {
            put("type", "message")
            put("id", "msg_$n")
            put("status", "completed")
            put("role", "assistant")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", full)
                            put("annotations", JsonArray(annotations))
                            put("logprobs", JsonArray(emptyList()))
                        },
                    )
                },
            )
        }
        return ScriptedTransport.Reply(response(listOf(message), searches = null))
    }

    private fun response(vararg items: JsonObject) = response(items.toList(), searches = null)

    private fun response(items: List<JsonObject>, searches: Int?) = buildJsonObject {
        put("id", "resp_${counter}")
        put("object", "response")
        put("status", "completed")
        put("model", "gpt-6.1-sol")
        put("output", JsonArray(items))
        if (searches != null) put("tool_usage", buildJsonObject { put("web_search", buildJsonObject { put("num_requests", searches) }) })
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", 3000)
                put("input_tokens_details", buildJsonObject { put("cached_tokens", 0); put("cache_write_tokens", 0) })
                put("output_tokens", 120)
                put("output_tokens_details", buildJsonObject { put("reasoning_tokens", 40) })
                put("total_tokens", 3120)
            },
        )
        put("error", JsonPrimitive(null as String?))
    }
}
