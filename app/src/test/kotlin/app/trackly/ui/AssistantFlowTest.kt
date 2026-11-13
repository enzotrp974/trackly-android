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
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.InMemoryApiKeyStore
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
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Parcours de bout en bout dans l'interface, avec des réponses de modèle simulées :
 * « + », message, carte d'enregistrement, bilan mis à jour, Annuler ; et les erreurs.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class AssistantFlowTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun open(engine: TracklyEngine, transport: ScriptedTransport, keyStore: InMemoryApiKeyStore = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")) {
        val assistant = AssistantServices.inMemory(engine, transport, keyStore)
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
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

    @Test
    fun `snack - carte d enregistrement, bilan du jour mis a jour, puis Annuler`() {
        val engine = engine()
        open(engine, ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"))

        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)

        compose.onNodeWithTag(ChatTags.BUBBLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouté, Snack, 300 kilocalories").assertIsDisplayed()
        // Hypothèses repliées par défaut : un toucher les déroule.
        compose.onNodeWithText("Nuvo chocolat-cacahuète, ta saveur habituelle").assertDoesNotExist()
        compose.onNodeWithTag(CardTags.ASSUMPTIONS_TOGGLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nuvo chocolat-cacahuète, ta saveur habituelle").assertExists()
        compose.onNodeWithContentDescription("Protéines 20 grammes, glucides 31 grammes, lipides 8 grammes").assertExists()
        compose.onNodeWithText("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.").assertExists()
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)

        // Le bilan du jour, derrière le chat, a suivi.
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Barre protéinée Nuvo, Pomme").assertIsDisplayed()
        compose.onNodeWithContentDescription("Protéines : 20 sur 140 grammes").assertIsDisplayed()

        // Annuler depuis la carte : l'action d'annulation du moteur retire le snack.
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CardTags.UNDO).performClick()
        waitFor(CardTags.UNDONE)
        compose.onNodeWithContentDescription("Annulé, Snack · 300 kcal retiré du journal").assertExists()
        assertEquals(0.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.0)
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Barre protéinée Nuvo, Pomme").assertDoesNotExist()
    }

    @Test
    fun `Corriger cible l enregistrement de la carte - rappel, contexte envoye au modele, puis retour a la normale`() {
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        open(engine(), transport)
        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)

        compose.onNodeWithTag(CardTags.CORRECT).performClick()
        compose.waitForIdle()

        // Le champ reste libre ; un rappel dit quel enregistrement est visé.
        compose.onNodeWithTag(ChatTags.CORRECTING).assertIsDisplayed()
        compose.onNodeWithText("Je corrige : Barre protéinée Nuvo, Pomme").assertExists()
        compose.onNodeWithText("Dis ce qu’il faut changer…").assertExists()

        transport.enqueue(ScriptedTransport.Reply(OpenAiFixtures.json("question-reponse.json")))
        send("la pomme pesait 200 g")
        compose.waitUntil(5_000) { transport.requests.size >= 3 }

        val input = transport.requests.last()["input"]!!.jsonArray.map { it.jsonObject }
        val context = input.first { it["role"]!!.jsonPrimitive.content == "developer" }["content"]!!.jsonPrimitive.content
        assertTrue(context.contains("## Correction demandée"))
        assertTrue(context.contains("Barre protéinée Nuvo"))
        assertTrue(context.contains("Pomme"))
        // Le ciblage vaut pour ce message seulement.
        compose.onNodeWithTag(ChatTags.CORRECTING).assertDoesNotExist()
    }

    @Test
    fun `Retirer enleve le rappel de correction`() {
        open(engine(), ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"))
        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)
        compose.onNodeWithTag(CardTags.CORRECT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.CORRECTING_CANCEL).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.CORRECTING).assertDoesNotExist()
        compose.onNodeWithText("Dis ce que tu as mangé…").assertExists()
    }

    @Test
    fun `une question n enregistre rien et ne montre aucune carte`() {
        val engine = engine()
        open(engine, ScriptedTransport.replies("question-reponse.json"))

        send("combien de calories si je mange une pizza ?")
        waitFor(ChatTags.REPLY)

        compose.onNodeWithTag(CardTags.RECORD).assertDoesNotExist()
        assertEquals(0, runBlocking { engine.daySummary(monday).consumptions.size })
    }

    @Test
    fun `pas d Internet - message clair, puis Reessayer`() {
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet))
        open(engine(), transport)

        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(ChatTags.ERROR)
        compose.onNodeWithText("Pas de connexion Internet").assertIsDisplayed()

        transport.enqueue(ScriptedTransport.Reply(OpenAiFixtures.json("snack-1-appel-outil.json")))
        transport.enqueue(ScriptedTransport.Reply(OpenAiFixtures.json("snack-2-reponse.json")))
        compose.onNodeWithText("Réessayer").performClick()
        waitFor(CardTags.RECORD)
        compose.onNodeWithTag(ChatTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `credit epuise - message distinct, sans Reessayer`() {
        open(engine(), ScriptedTransport(ScriptedTransport.Fail(AssistantError.CreditExhausted)))

        send("j'ai mangé une pomme")
        waitFor(ChatTags.ERROR)

        compose.onNodeWithText("Crédit OpenAI épuisé").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertDoesNotExist()
    }

    @Test
    fun `cle refusee - le message mene a la saisie d une nouvelle cle`() {
        val keys = InMemoryApiKeyStore("sk-ancienne-cle-revoquee-000000")
        open(engine(), ScriptedTransport(ScriptedTransport.Fail(AssistantError.KeyRejected)), keys)

        send("j'ai mangé une pomme")
        waitFor(ChatTags.ERROR)
        compose.onNodeWithText("Clé OpenAI refusée").assertIsDisplayed()
        compose.onNodeWithText("Saisir ma clé").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput("sk-proj-nouvelle-cle-1234abcd")
        compose.onNodeWithText("Enregistrer la clé").performClick()
        compose.waitForIdle()

        assertEquals(ApiKeyStatus.Present("abcd"), keys.status.value)
        compose.onNodeWithText("Clé enregistrée, finit par « abcd ».").assertIsDisplayed()
    }

    @Test
    fun `sans cle - le chat invite a l ajouter`() {
        open(engine(), ScriptedTransport(), InMemoryApiKeyStore())

        compose.onNodeWithText("Pour commencer, ajoute ta clé OpenAI.").assertIsDisplayed()
        compose.onNodeWithText("Ajouter ma clé OpenAI").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aucune clé.").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput("pas-une-cle-openai-du-tout-12345")
        compose.onNodeWithText("Enregistrer la clé").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Une clé OpenAI commence par « sk- ».").assertIsDisplayed()
    }
}
