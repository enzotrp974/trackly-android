package app.trackly.assistant.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Ce que l'interface peut montrer pendant qu'une réponse arrive en streaming (`stream: true`).
 * Seuls les événements utiles sont traduits ; les autres types (la liste s'allonge) sont ignorés.
 */
sealed interface StreamEvent {
    /** Le modèle réfléchit (raisonnement, ou recherche web terminée). */
    data object Thinking : StreamEvent

    /** Recherche web en cours (`response.web_search_call.in_progress` / `.searching`). */
    data object SearchingWeb : StreamEvent

    /** Le modèle prépare un appel à l'outil [name] de l'app (`function_call`). */
    data class CallingTool(val name: String) : StreamEvent

    /** Morceau du texte de la réponse (`response.output_text.delta`, ou d'un refus). */
    data class TextDelta(val delta: String) : StreamEvent

    /**
     * Le flux a été coupé ou va être rejoué : la même requête repart (sans streaming en cas de coupure).
     * Le texte déjà reçu pour cette requête est à oublier.
     */
    data object Restarted : StreamEvent
}

/** Reçoit les événements d'une réponse en streaming. */
fun interface StreamListener {
    suspend fun onEvent(event: StreamEvent)
}

/**
 * Le flux s'est arrêté avant l'événement final (`response.completed`, `.incomplete` ou `.failed`) :
 * connexion coupée, ou fin de flux prématurée. Rien n'a été exécuté (les outils ne le sont que sur une
 * réponse complète) : le client peut redemander la même réponse sans streaming.
 */
class StreamInterruptedException(cause: Throwable? = null) : Exception("Flux interrompu avant la fin de la réponse", cause)

/**
 * Lecture des événements d'une réponse en streaming de la Responses API. Pur : reçoit les messages SSE
 * décodés ([SseDecoder]), rend les [StreamEvent] à montrer et garde la réponse finale, identique à celle
 * d'un appel sans streaming (`response` de l'événement final, avec `output` et `usage`).
 */
class ResponseStream {
    private var terminal: JsonObject? = null

    /** Éléments terminés (`response.output_item.done`), par rang : secours si la réponse finale n'a pas d'`output`. */
    private val doneItems = sortedMapOf<Int, JsonObject>()

    /** Vrai dès l'événement final reçu. */
    val finished: Boolean get() = terminal != null

    /**
     * Traite un message SSE. Lève [AssistantException] sur un événement `error` (classé comme une
     * erreur HTTP : crédit épuisé, plafond, débit, serveur).
     */
    fun accept(message: SseMessage): List<StreamEvent> {
        val data = message.data.trim()
        if (data.isEmpty() || data == DONE) return emptyList()
        val json = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return emptyList()
        val type = json.string("type") ?: message.event ?: return emptyList()
        return when (type) {
            "response.output_item.added" -> {
                val item = json.obj("item")
                when (item?.string("type")) {
                    "function_call" -> listOf(StreamEvent.CallingTool(item.string("name").orEmpty()))
                    "web_search_call" -> listOf(StreamEvent.SearchingWeb)
                    "reasoning" -> listOf(StreamEvent.Thinking)
                    else -> emptyList()
                }
            }
            "response.output_item.done" -> {
                val index = (json["output_index"] as? JsonPrimitive)?.intOrNull
                val item = json.obj("item")
                if (index != null && item != null) doneItems[index] = item
                emptyList()
            }
            "response.web_search_call.in_progress", "response.web_search_call.searching" -> listOf(StreamEvent.SearchingWeb)
            "response.web_search_call.completed" -> listOf(StreamEvent.Thinking)
            "response.output_text.delta", "response.refusal.delta" ->
                json.string("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(StreamEvent.TextDelta(it)) }.orEmpty()
            "response.completed", "response.incomplete", "response.failed" -> {
                terminal = json.obj("response") ?: JsonObject(emptyMap())
                emptyList()
            }
            "error" -> throw AssistantException(errorOf(json))
            else -> emptyList()
        }
    }

    /**
     * Réponse finale, ou `null` si le flux s'est arrêté avant. Si elle arrive sans `output` (forme non
     * documentée), les éléments terminés reçus en cours de route en tiennent lieu.
     */
    fun result(): JsonObject? {
        val response = terminal ?: return null
        val output = response["output"] as? JsonArray
        if (!output.isNullOrEmpty() || doneItems.isEmpty()) return response
        return JsonObject(response + ("output" to JsonArray(doneItems.values.toList())))
    }

    private fun errorOf(json: JsonObject): AssistantError {
        // Forme documentée : `code`, `message`, `param` à la racine ; on accepte aussi un objet `error`.
        val error = json.obj("error") ?: json
        return OpenAiErrors.classifyStreamError(
            code = error.string("code"),
            type = error.string("type")?.takeIf { it != "error" },
            message = error.string("message"),
        )
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}
