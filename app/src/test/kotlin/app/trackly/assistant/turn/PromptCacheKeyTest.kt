package app.trackly.assistant.turn

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.openai.ServiceTier
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.prompt.review
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `prompt_cache_key` : 3 requêtes sur 13 ont raté le cache d'entrée sur la vraie API (0,22 $ chacune en Fast, 0,11 $
 * en Standard), souvent juste après une requête qui l'avait relu. La clé oriente les requêtes d'un même préfixe vers les
 * mêmes machines. Elle est tirée des consignes et des outils : stable tant qu'ils ne changent pas, la même pour le chat et
 * la réflexion de l'appel (même préfixe), une autre pour le bilan (autre préfixe).
 */
class PromptCacheKeyTest {
    private suspend fun keyOf(
        prompt: SystemPrompt = SystemPrompt.default(),
        text: String = "une pomme",
        engine: app.trackly.core.engine.TracklyEngine = SampleData.engine(),
        history: List<HistoryMessage> = emptyList(),
    ): String {
        val model = ScriptedModel({ ScriptedModel.text("Noté.") })
        TurnInterpreter(ResponsesClient(model, RetryPolicy.None), engine, prompt = prompt).run(TurnInput(text, history = history))
        return model.requests.single()["prompt_cache_key"]!!.jsonPrimitive.content
    }

    @Test
    fun `la cle part avec chaque requete, stable d un message a l autre`() = runBlocking {
        val key = keyOf()
        assertTrue(key.matches(Regex("trackly-[0-9a-f]{16}")))
        assertEquals(key, keyOf(text = "un yaourt"))
        // Ni le jour, ni les données, ni l'historique n'entrent dans la clé.
        val otherDay = SampleData.emptyEngine(SampleData.clock("2026-12-24T19:00"))
        val past = listOf(HistoryMessage(HistoryMessage.Role.User, "ce matin un skyr"), HistoryMessage(HistoryMessage.Role.Assistant, "Noté."))
        assertEquals(key, keyOf(text = "un café", engine = otherDay, history = past))
    }

    @Test
    fun `le bilan, autre prefixe, a une autre cle`() = runBlocking {
        assertNotEquals(keyOf(), keyOf(SystemPrompt.review()))
    }

    @Test
    fun `niveau de service refuse - meme requete sans, puis plus jamais`() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val transport = ResponsesTransport { body ->
            bodies += body
            if (body["service_tier"] != null) throw AssistantException(AssistantError.Unexpected(400, "Unsupported value: 'service_tier' does not support 'priority'"))
            ScriptedModel.text("Noté.")
        }
        val interpreter = TurnInterpreter(
            ResponsesClient(transport, RetryPolicy.None), SampleData.engine(), config = AssistantConfig(serviceTier = ServiceTier(ServiceTier.PRIORITY)),
        )
        interpreter.run(TurnInput("une pomme"))
        interpreter.run(TurnInput("un yaourt"))
        assertEquals(3, bodies.size)
        assertTrue(bodies[0].containsKey("service_tier"))
        assertFalse(bodies[1].containsKey("service_tier"))
        assertFalse(bodies[2].containsKey("service_tier"))
    }
}
