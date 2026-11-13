package app.trackly.call

import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'état des barres et le trait de moyenne restent pour les yeux de l'utilisateur : ni la voix ni la réflexion de l'appel
 * ne les reçoivent. Même garde que `BarStatePrivacyTest` et `AverageMarkPrivacyTest`, étendue au paquet de l'appel.
 */
class CallPrivacyTest {
    @Test
    fun `aucun fichier de l appel ne connait l etat des barres ni le trait de moyenne`() {
        val files = java.io.File("src/main/kotlin/app/trackly/call").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 10)
        val names = listOf("BarState", "DayOverviews", "LimitAlerts", "exceeded(", "core.overview", "overLimit")
        val offenders = files.filter { f -> names.any { f.readText().contains(it) } }.map { it.name }
        assertEquals("ces fichiers de l'appel touchent à l'état des barres : $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `la requete de la reflexion ne porte ni l etat des barres ni le trait`() = runBlocking {
        val model = ScriptedModel({ CallReplies.answer() })
        val thinker = AgentCallThinker(ResponsesClient(model, RetryPolicy.None), SampleData.engine(), InMemoryChatHistory())
        thinker.think(ThinkRequest(emptyList(), listOf(CallLine(Speaker.User, "Il me reste combien ?")), CallReflection.Medium))
        val body = model.requests.single().toString()
        for (word in listOf("limite dépassée", "minimum non atteint", "hors objectif", "overLimit", "over_limit", "BarState", "bar_state", "trait de moyenne", "average_mark")) {
            assertFalse("« $word » ne doit pas sortir de l'app", body.contains(word, ignoreCase = true))
        }
    }
}
