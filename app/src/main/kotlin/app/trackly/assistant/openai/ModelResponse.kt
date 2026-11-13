package app.trackly.assistant.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Élément de `output`. Chaque élément garde son JSON brut ([raw]) : il est renvoyé tel quel
 * au tour suivant (raisonnement chiffré compris), sans passer par un modèle typé incomplet.
 */
sealed interface OutputItem {
    val raw: JsonObject

    /**
     * Message de l'assistant : texte visible (concaténation des `output_text`, sans le balisage des
     * citations) ou refus. [citations] : les sources de la recherche web (`url_citation`).
     */
    data class Message(
        val text: String,
        val refusal: String?,
        override val raw: JsonObject,
        val citations: List<Citation> = emptyList(),
    ) : OutputItem

    /** Appel d'un outil de l'app. [arguments] est une chaîne JSON à décoder. */
    data class FunctionCall(
        val callId: String,
        val name: String,
        val arguments: String,
        override val raw: JsonObject,
    ) : OutputItem

    /** Tout le reste (`reasoning`, `web_search_call`…), renvoyé sans modification. */
    data class Other(val type: String, override val raw: JsonObject) : OutputItem
}

/** Source citée par la recherche web (annotation `url_citation`). */
data class Citation(val url: String, val title: String?)

/**
 * Consommation de jetons d'une réponse (champ `usage`). Le compteur de coût s'appuie
 * dessus ; [raw] garde les détails non modélisés (écritures de cache par durée…).
 */
data class Usage(
    val inputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val raw: JsonObject,
) {
    companion object {
        fun parse(json: JsonObject): Usage {
            val input = json.obj("input_tokens_details")
            val output = json.obj("output_tokens_details")
            return Usage(
                inputTokens = json.long("input_tokens"),
                cachedTokens = input?.long("cached_tokens") ?: 0,
                cacheWriteTokens = input?.long("cache_write_tokens") ?: 0,
                outputTokens = json.long("output_tokens"),
                reasoningTokens = output?.long("reasoning_tokens") ?: 0,
                totalTokens = json.long("total_tokens"),
                raw = json,
            )
        }
    }
}

/** Réponse de `POST /v1/responses`, réduite à ce que l'app exploite. */
data class ModelResponse(
    val id: String?,
    val model: String?,
    /** `completed`, `incomplete`, `failed`… */
    val status: String?,
    val incompleteReason: String?,
    val output: List<OutputItem>,
    val usage: Usage?,
    /** Recherches web facturables (`tool_usage.web_search.num_requests`), si présent. */
    val webSearchRequests: Int?,
    /** Niveau de service réellement appliqué (`service_tier`), si la réponse le donne. */
    val serviceTier: String? = null,
) {
    val functionCalls: List<OutputItem.FunctionCall> get() = output.filterIsInstance<OutputItem.FunctionCall>()

    /** Texte visible : les `output_text` des messages, dans l'ordre. */
    val text: String get() = output.filterIsInstance<OutputItem.Message>().joinToString("\n\n") { it.text }.trim()

    val refusal: String? get() = output.filterIsInstance<OutputItem.Message>().firstNotNullOfOrNull { it.refusal }

    /** Sources citées par la recherche web, sans doublon d'adresse, dans l'ordre. */
    val citations: List<Citation> get() = output.filterIsInstance<OutputItem.Message>().flatMap { it.citations }.distinctBy { it.url }

    companion object {
        fun parse(json: JsonObject): ModelResponse {
            val output = (json["output"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseItem) }
            return ModelResponse(
                id = json.string("id"),
                model = json.string("model"),
                status = json.string("status"),
                incompleteReason = json.obj("incomplete_details")?.string("reason"),
                output = output,
                usage = json.obj("usage")?.let(Usage::parse),
                webSearchRequests = json.obj("tool_usage")?.obj("web_search")?.let { (it["num_requests"] as? JsonPrimitive)?.intOrNull }
                    ?: searchesIn(output),
                serviceTier = json.string("service_tier"),
            )
        }

        /**
         * `tool_usage` peut manquer : on compte alors les recherches (`web_search_call` d'action `search`) de la
         * réponse, pour que le coût ne les ignore pas. `null` s'il n'y en a aucune.
         */
        private fun searchesIn(output: List<OutputItem>): Int? =
            output.count { it is OutputItem.Other && it.type == "web_search_call" && it.raw.obj("action")?.string("type") == "search" }
                .takeIf { it > 0 }

        private fun parseItem(item: JsonObject): OutputItem {
            val type = item.string("type") ?: (if (item.containsKey("role")) "message" else "unknown")
            return when (type) {
                "message" -> {
                    val parts = (item["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    val texts = parts.filter { it.string("type") == "output_text" }
                    OutputItem.Message(
                        text = texts.joinToString("") { Citations.visibleText(it) },
                        refusal = parts.firstOrNull { it.string("type") == "refusal" }?.string("refusal"),
                        raw = item,
                        citations = texts.flatMap { Citations.of(it) },
                    )
                }
                "function_call" -> OutputItem.FunctionCall(
                    callId = item.string("call_id").orEmpty(),
                    name = item.string("name").orEmpty(),
                    arguments = item.string("arguments").orEmpty(),
                    raw = item,
                )
                else -> OutputItem.Other(type, item)
            }
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
