package app.trackly.testing

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Réponses d'OpenAI simulées (`src/test/resources/openai/`), écrites d'après la doc. */
object OpenAiFixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("openai/$name")) { "Fixture absente : $name" }.readText()

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject
}

/**
 * Faux transport : rejoue un scénario de réponses (ou d'erreurs) dans l'ordre et garde les
 * requêtes reçues. Aucun appel réseau.
 */
class ScriptedTransport(vararg steps: Step) : ResponsesTransport {
    sealed interface Step
    data class Reply(val json: JsonObject) : Step
    data class Fail(val error: AssistantError) : Step

    private val pending = ArrayDeque(steps.toList())
    val requests = mutableListOf<JsonObject>()

    override suspend fun send(body: JsonObject): JsonObject {
        requests += body
        return when (val step = pending.removeFirstOrNull() ?: error("Requête inattendue n° ${requests.size}")) {
            is Reply -> step.json
            is Fail -> throw AssistantException(step.error)
        }
    }

    fun enqueue(step: Step) {
        pending.addLast(step)
    }

    val remaining: Int get() = pending.size

    companion object {
        fun replies(vararg fixtures: String) = ScriptedTransport(*fixtures.map { Reply(OpenAiFixtures.json(it)) }.toTypedArray())
    }
}
