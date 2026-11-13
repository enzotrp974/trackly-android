package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.chat.EffortTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Sélecteur du niveau de raisonnement : le niveau choisi part dans la requête simulée, par message. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ReasoningSelectorTest {
    @get:Rule val compose = createComposeRule()

    private val usage = mutableListOf<UsageEvent>()

    private fun open(transport: ScriptedTransport) {
        val engine: TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
        val assistant = AssistantServices.inMemory(
            engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"),
            usageSink = UsageSink { usage += it },
        )
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    private fun choose(level: ReasoningEffort) {
        compose.onNodeWithTag(EffortTags.CHIP).performClick()
        compose.onNodeWithTag(EffortTags.option(level)).performClick()
        compose.waitForIdle()
    }

    private fun send(text: String) {
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput(text)
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun effortOf(request: kotlinx.serialization.json.JsonObject) =
        request["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content

    @Test
    fun `par defaut le niveau est moyen`() {
        val transport = ScriptedTransport.replies("question-reponse.json")
        open(transport)

        compose.onNodeWithContentDescription("Niveau de raisonnement : Moyen. Toucher pour changer.").assertIsDisplayed()
        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.REPLY)

        assertEquals("medium", effortOf(transport.requests.single()))
    }

    @Test
    fun `choisir eleve puis envoyer - la requete porte reasoning effort high`() {
        val transport = ScriptedTransport.replies("question-reponse.json")
        open(transport)

        choose(ReasoningEffort.High)
        compose.onNodeWithContentDescription("Niveau de raisonnement : Élevé. Toucher pour changer.").assertIsDisplayed()
        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.REPLY)

        assertEquals("high", effortOf(transport.requests.single()))
        assertEquals(listOf(ReasoningEffort.High), usage.map { it.effort })
    }

    @Test
    fun `les cinq niveaux sont proposes, du plus bas au maximum`() {
        open(ScriptedTransport.replies("question-reponse.json"))

        compose.onNodeWithTag(EffortTags.CHIP).performClick()

        compose.onNodeWithTag(EffortTags.STRIP).assertIsDisplayed()
        assertEquals(listOf("Bas", "Moyen", "Élevé", "Très élevé", "Maximum"), ReasoningEffort.entries.map { it.label })
        for (level in ReasoningEffort.entries) {
            compose.onNodeWithTag(EffortTags.option(level)).assertIsDisplayed()
        }
    }

    @Test
    fun `tres eleve part en xhigh`() = sendAt(ReasoningEffort.XHigh, "xhigh")

    @Test
    fun `maximum part en max`() = sendAt(ReasoningEffort.Max, "max")

    @Test
    fun `bas part en low`() = sendAt(ReasoningEffort.Low, "low")

    private fun sendAt(level: ReasoningEffort, code: String) {
        val transport = ScriptedTransport.replies("question-reponse.json")
        open(transport)
        choose(level)
        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.REPLY)
        assertEquals(code, effortOf(transport.requests.single()))
    }

    @Test
    fun `le niveau reste celui choisi apres l envoi`() {
        val transport = ScriptedTransport.replies("question-reponse.json", "question-reponse.json")
        open(transport)

        choose(ReasoningEffort.XHigh)
        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.REPLY)
        compose.onNodeWithContentDescription("Niveau de raisonnement : Très élevé. Toucher pour changer.").assertIsDisplayed()

        send("et une pomme ?")
        compose.waitUntil(5_000) { transport.requests.size == 2 }
        assertEquals(listOf("xhigh", "xhigh"), transport.requests.map(::effortOf))
    }

    @Test
    fun `reessayer renvoie le message au meme niveau`() {
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet))
        open(transport)

        choose(ReasoningEffort.XHigh)
        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.ERROR)
        // Le sélecteur garde son niveau, et le nouvel essai garde celui du message.
        compose.onNodeWithContentDescription("Niveau de raisonnement : Très élevé. Toucher pour changer.").assertIsDisplayed()

        transport.enqueue(ScriptedTransport.Reply(OpenAiFixtures.json("question-reponse.json")))
        compose.onNodeWithText("Réessayer").performClick()
        waitFor(ChatTags.REPLY)

        assertEquals(listOf("xhigh", "xhigh"), transport.requests.map(::effortOf))
    }
}
