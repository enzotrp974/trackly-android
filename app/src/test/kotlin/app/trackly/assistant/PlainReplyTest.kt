package app.trackly.assistant

import app.trackly.assistant.openai.ModelResponse
import app.trackly.assistant.openai.OutputItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrôle réel n° 2 (« combien de calories dans une banane ? ») : GPT-6.1 Sol a répondu
 * « apporte **environ 105 kcal** ». Le chat met le Markdown en page à l'affichage seulement ;
 * la réponse, l'historique et la sortie brute renvoyés au modèle restent intacts.
 * Le rendu est vérifié dans `MarkdownTest` et `ui/PlainReplyDisplayTest`.
 */
class PlainReplyTest {
    @Test
    fun `la reponse du modele garde son Markdown pour l historique`() {
        val json = Json.parseToJsonElement(
            """
            {"id":"resp_1","model":"gpt-6.1-sol","status":"completed","output":[
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","annotations":[],
                 "text":"Une banane moyenne, sans la peau (environ 120 g), apporte **environ 105 kcal**."}]}]}
            """.trimIndent(),
        ).jsonObject

        val response = ModelResponse.parse(json)

        assertEquals("Une banane moyenne, sans la peau (environ 120 g), apporte **environ 105 kcal**.", response.text)
        val raw = (response.output.single() as OutputItem.Message).raw.toString()
        assertEquals(true, raw.contains("**environ 105 kcal**"))
    }
}
