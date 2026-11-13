package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.InMemoryKeyVerificationPrefs
import app.trackly.assistant.key.KeyChecker
import app.trackly.assistant.key.KeyVerifier
import app.trackly.assistant.key.VerifyingApiKeyStore
import app.trackly.assistant.openai.AssistantError
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.settings.KeyTools
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Clé refusée en plein message : « Saisir ma clé » ouvre les Réglages sur la section Clé, la clé corrigée est
 * vérifiée gratuitement (serveur local), puis le chat propose « Renvoyer mon dernier message ». Aucun appel réel.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class KeyResendTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monday = LocalDate.parse("2026-11-02")

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun setUp(engine: TracklyEngine, transport: ScriptedTransport) {
        val store = InMemoryApiKeyStore("sk-ancienne-cle-revoquee-000000")
        val verifier = KeyVerifier(store, KeyChecker(OkHttpClient(), server.url("/v1/"), isOnline = { true }, io = Dispatchers.IO), scope, InMemoryKeyVerificationPrefs())
        val assistant = AssistantServices.inMemory(engine, transport, VerifyingApiKeyStore(store, verifier))
        compose.setContent {
            TracklyRoot(
                engine = engine,
                motion = MotionPolicy(reduced = true),
                assistant = assistant,
                settings = SettingsSlot(keyTools = KeyTools(verification = verifier)),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `cle refusee puis corrigee et reconnue - Renvoyer mon dernier message envoie le meme texte et compte une fois`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())
        val engine = engine()
        val transport = ScriptedTransport(
            ScriptedTransport.Fail(AssistantError.KeyRejected),
            ScriptedTransport.Reply(OpenAiFixtures.json("snack-1-appel-outil.json")),
            ScriptedTransport.Reply(OpenAiFixtures.json("snack-2-reponse.json")),
        )
        setUp(engine, transport)

        val message = "comme snack j'ai pris une barre Nuvo et une pomme, 150 g"
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput(message)
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        waitFor(ChatTags.ERROR)
        // Tant que la clé n'a pas été corrigée, pas de renvoi proposé.
        compose.onNodeWithText("Renvoyer mon dernier message").assertDoesNotExist()

        compose.onNodeWithText("Saisir ma clé").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput("sk-proj-nouvelle-cle-1234abcd")
        compose.onNodeWithText("Enregistrer la clé").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Clé valide")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Renvoyer mon dernier message").assertIsDisplayed()
        compose.onNodeWithText("Renvoyer mon dernier message").performClick()
        waitFor(CardTags.RECORD)

        // Le même texte est reparti (une seule fois chez OpenAI avec la nouvelle clé) et le snack n'est compté qu'une fois.
        assertEquals(3, transport.requests.size)
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)
        compose.onNodeWithTag(ChatTags.ERROR).assertDoesNotExist()
        assertEquals(1, server.requestCount)
    }
}
