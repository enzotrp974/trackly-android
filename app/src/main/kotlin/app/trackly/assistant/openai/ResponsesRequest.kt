package app.trackly.assistant.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Niveau de raisonnement d'une requête. `medium` par défaut ; gpt-6.1-sol refuse `none`
 * et `minimal`. Le sélecteur par message choisit parmi ces valeurs.
 */
enum class ReasoningEffort(val code: String, val label: String) {
    Low("low", "Bas"),
    Medium("medium", "Moyen"),
    High("high", "Élevé"),
    XHigh("xhigh", "Très élevé"),
    Max("max", "Maximum"),
    ;

    companion object {
        val Default = Medium
    }
}

/** Image envoyée au modèle. Jamais conservée : seule la donnée extraite l'est. */
data class ImageInput(
    /** URL `data:image/jpeg;base64,...` (image réduite sur le téléphone) ou URL complète. */
    val url: String,
    /** `high` par défaut : `auto` équivaut à `original`, sans plafond de jetons. */
    val detail: String = "high",
)

/**
 * Outil intégré d'OpenAI, exécuté chez OpenAI (pas par l'app). La recherche web
 * s'active en ajoutant [WebSearch] au registre d'outils.
 */
sealed interface HostedTool {
    fun toJson(): JsonObject

    data class WebSearch(
        val country: String = SearchLocation.COUNTRY,
        val city: String? = SearchLocation.CITY,
        val timezone: String = SearchLocation.TIMEZONE,
        val contextSize: String = "medium",
    ) : HostedTool {
        override fun toJson() = buildJsonObject {
            put("type", "web_search")
            put("search_context_size", contextSize)
            putJsonObject("user_location") {
                put("type", "approximate")
                put("country", country)
                city?.let { put("city", it) }
                put("timezone", timezone)
            }
        }
    }
}

/**
 * Niveau de service demandé pour un usage (« priority » ; plus aucun usage ne le demande), refusé une fois pour toutes si le modèle ne
 * l'accepte pas : la même requête repart alors sans, et les suivantes s'en passent.
 */
class ServiceTier(val code: String) {
    @Volatile
    var refused: Boolean = false

    /** Le niveau à demander maintenant, ou `null` s'il a été refusé. */
    fun current(): String? = code.takeUnless { refused }

    companion object {
        const val PRIORITY = "priority"

        /** 400 qui parle du niveau de service : le modèle ne l'accepte pas. */
        fun refuses(error: AssistantError): Boolean =
            error is AssistantError.Unexpected && error.status == 400 && error.detail.orEmpty().contains("service_tier")
    }
}

/** Fabrique des éléments d'entrée (`input`) de la Responses API. */
object InputItems {
    fun user(text: String, images: List<ImageInput> = emptyList()): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            addJsonObject {
                put("type", "input_text")
                put("text", text)
            }
            for (image in images) {
                addJsonObject {
                    put("type", "input_image")
                    put("image_url", image.url)
                    put("detail", image.detail)
                }
            }
        }
    }

    fun assistant(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    /** Message `developer` : contexte du jour, prioritaire sur les messages de l'utilisateur. */
    fun developer(text: String): JsonObject = buildJsonObject {
        put("role", "developer")
        put("content", text)
    }

    fun functionCallOutput(callId: String, output: String): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }
}

/**
 * Requête `POST /v1/responses`, sans état côté OpenAI (`store: false`) : l'historique est
 * renvoyé à chaque appel. Ordre pensé pour le cache d'entrée : [instructions] et [tools]
 * stables, puis [input] qui ne fait que s'allonger pendant un tour.
 */
data class ResponsesRequest(
    val instructions: String,
    val input: List<JsonObject>,
    val tools: List<JsonObject> = emptyList(),
    val effort: ReasoningEffort = ReasoningEffort.Default,
    val model: String = AssistantModel.Default.code,
    val parallelToolCalls: Boolean = true,
    /** Plafond de sortie, raisonnement compris. `null` : défaut d'OpenAI. */
    val maxOutputTokens: Int? = null,
    val include: List<String> = emptyList(),
    /** Plafond des appels d'outils intégrés (recherches web) dans la réponse : borne le coût. `null` : défaut d'OpenAI. */
    val maxToolCalls: Int? = null,
    val promptCacheKey: String? = null,
    /** Durée de rétention du cache d'entrée (`prompt_cache_options.ttl`, « 30m » ou « 12h »). `null` : défaut d'OpenAI (30 min). */
    val promptCacheTtl: String? = null,
    /** Niveau de service (`service_tier`, « priority »). `null` : défaut d'OpenAI. */
    val serviceTier: String? = null,
    /**
     * Format imposé au texte final (`text.format`, sortie structurée `json_schema` stricte), ou `null` : texte libre. Les appels
     * d'outils restent permis ; seule la réponse finale suit le schéma (réflexion pendant l'appel).
     */
    val textFormat: JsonObject? = null,
    /** `tool_choice` : « auto », ou « none » pour une réponse finale qui ne peut plus appeler d'outil (outils gardés pour le cache). */
    val toolChoice: String = "auto",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("store", false)
        put("instructions", instructions)
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools))
            put("tool_choice", toolChoice)
            put("parallel_tool_calls", parallelToolCalls)
        }
        putJsonObject("reasoning") { put("effort", effort.code) }
        maxOutputTokens?.let { put("max_output_tokens", it) }
        maxToolCalls?.let { put("max_tool_calls", it) }
        if (include.isNotEmpty()) put("include", buildJsonArray { include.forEach { add(it) } })
        promptCacheKey?.let { put("prompt_cache_key", JsonPrimitive(it)) }
        promptCacheTtl?.let { ttl -> putJsonObject("prompt_cache_options") { put("ttl", ttl) } }
        serviceTier?.let { put("service_tier", it) }
        textFormat?.let { format -> putJsonObject("text") { put("format", format) } }
        put("input", JsonArray(input))
    }
}
