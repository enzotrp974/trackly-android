package app.trackly.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.model.InMemoryAssistantModelSettings
import app.trackly.assistant.model.SharedAssistantModelSettings
import app.trackly.assistant.openai.AssistantModel
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.review.InMemoryReportRepository
import app.trackly.review.RecordingReviewNotifier
import app.trackly.review.ReviewServices
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.usage.CostCalculator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Réglage « Modèle de l'assistant » (comparaison Terra / Sol) : le modèle choisi part dans
 * chaque requête de l'assistant et du bilan, et le coût suit le modèle réellement utilisé.
 */
@RunWith(AndroidJUnit4::class)
class AssistantModelTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun engine() = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))

    private fun modelOf(request: JsonObject) = request["model"]!!.jsonPrimitive.content

    /** Réponse simulée dont le champ `model` est celui du modèle demandé (OpenAI renvoie le modèle utilisé). */
    private fun replyFrom(model: AssistantModel) =
        JsonObject(OpenAiFixtures.json("question-reponse.json") + ("model" to JsonPrimitive(model.code)))

    @Test
    fun `Sol par defaut, un code inconnu redonne Sol`() {
        assertEquals(AssistantModel.Sol, AssistantModel.Default)
        assertEquals("gpt-6.1-sol", AssistantModel.Default.code)
        assertEquals(AssistantModel.Sol, AssistantModel.fromCode(null))
        assertEquals(AssistantModel.Sol, AssistantModel.fromCode("gpt-9-futur"))
        assertEquals(AssistantModel.Terra, AssistantModel.fromCode("gpt-6.1-terra"))
        assertEquals("GPT-6.1 Sol", AssistantModel.Sol.label)
        assertEquals("GPT-6.1 Terra", AssistantModel.Terra.label)
    }

    @Test
    fun `sans reglage, la requete part vers GPT-6-1 Sol`() = runBlocking {
        val transport = ScriptedTransport.replies("question-reponse.json")
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine()).run(TurnInput("combien de calories dans une pizza ?"))
        assertEquals("gpt-6.1-sol", modelOf(transport.requests.single()))
    }

    @Test
    fun `le modele est relu a chaque message, sans redemarrer`() = runBlocking {
        val settings = InMemoryAssistantModelSettings()
        val transport = ScriptedTransport.replies("question-reponse.json", "question-reponse.json", "question-reponse.json")
        val interpreter = TurnInterpreter(
            ResponsesClient(transport, RetryPolicy.None), engine(),
            config = AssistantConfig(model = settings::currentCode),
        )

        interpreter.run(TurnInput("premier message"))
        settings.setModel(AssistantModel.Terra)
        interpreter.run(TurnInput("deuxieme message"))
        settings.setModel(AssistantModel.Sol)
        interpreter.run(TurnInput("troisieme message"))

        assertEquals(listOf("gpt-6.1-sol", "gpt-6.1-terra", "gpt-6.1-sol"), transport.requests.map(::modelOf))
    }

    @Test
    fun `le bilan du lundi utilise le meme reglage`() = runBlocking {
        val settings = InMemoryAssistantModelSettings(AssistantModel.Terra)
        val transport = ScriptedTransport.replies("question-reponse.json")
        val interpreter = ReviewServices.interpreter(
            ResponsesClient(transport, RetryPolicy.None), engine(), app.trackly.review.ReviewPeriods(InMemoryReportRepository(), app.trackly.review.InMemoryReviewHistory()), RecordingReviewNotifier(),
            config = AssistantConfig(model = settings::currentCode),
        )

        interpreter.run(TurnInput("fais le point sur ma semaine"))

        assertEquals("gpt-6.1-terra", modelOf(transport.requests.single()))
    }

    @Test
    fun `le compteur de cout prend le modele de la reponse, Terra coute moitie moins`() = runBlocking {
        val events = mutableListOf<UsageEvent>()
        val settings = InMemoryAssistantModelSettings(AssistantModel.Terra)
        val transport = ScriptedTransport(ScriptedTransport.Reply(replyFrom(AssistantModel.Terra)))
        val outcome = TurnInterpreter(
            ResponsesClient(transport, RetryPolicy.None), engine(),
            config = AssistantConfig(model = settings::currentCode), usageSink = UsageSink { events += it },
        ).run(TurnInput("combien de calories dans une pizza ?"))

        assertTrue(outcome is TurnOutcome.Completed)
        val event = events.single()
        assertEquals("gpt-6.1-terra", event.model)
        val terra = CostCalculator.text(event.model, event.usage, event.webSearchRequests)
        val sol = CostCalculator.text("gpt-6.1-sol", event.usage, event.webSearchRequests)
        assertTrue(terra.priced)
        assertEquals(sol.text / 2, terra.text)
    }

    @Test
    fun `sans modele dans la reponse, le modele demande sert au compteur`() = runBlocking {
        val events = mutableListOf<UsageEvent>()
        val reply = JsonObject(OpenAiFixtures.json("question-reponse.json") - "model")
        TurnInterpreter(
            ResponsesClient(ScriptedTransport(ScriptedTransport.Reply(reply)), RetryPolicy.None), engine(),
            config = AssistantConfig(model = { "gpt-6.1-terra" }), usageSink = UsageSink { events += it },
        ).run(TurnInput("combien de calories dans une pizza ?"))
        assertEquals("gpt-6.1-terra", events.single().model)
    }

    @Test
    fun `les niveaux du selecteur sont acceptes par Sol comme par Terra`() {
        // Doc OpenAI (Responses API, niveaux de raisonnement) : Sol accepte low, medium, high, xhigh, max ;
        // Terra accepte les mêmes plus `none`. Le sélecteur n'envoie ni `none` ni `minimal`.
        val accepted = setOf("low", "medium", "high", "xhigh", "max")
        assertEquals(accepted, ReasoningEffort.entries.map { it.code }.toSet())
    }

    @Test
    fun `le choix est garde apres redemarrage, un code abime redonne Sol`() {
        val prefs = context.getSharedPreferences("trackly-assistant", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(AssistantModel.Sol, SharedAssistantModelSettings(context).model.value)

        SharedAssistantModelSettings(context).setModel(AssistantModel.Terra)
        val reopened = SharedAssistantModelSettings(context)
        assertEquals(AssistantModel.Terra, reopened.model.value)
        assertEquals("gpt-6.1-terra", reopened.currentCode())

        prefs.edit().putString("modele", "n-importe-quoi").commit()
        assertEquals(AssistantModel.Sol, SharedAssistantModelSettings(context).model.value)
        prefs.edit().clear().commit()
    }
}
