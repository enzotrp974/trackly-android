package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Nutrition
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Cartes du chat après coup : « Rétablir » sur la carte annulée (sans appel au modèle), réponse barrée,
 * carte « Rétabli » d'un « remets-le », carte périmée désactivée avec un message humain.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatUndoRestoreTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun open(engine: TracklyEngine, transport: ScriptedTransport) {
        val assistant = AssistantServices.inMemory(engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
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

    private fun kcal(engine: TracklyEngine) = runBlocking { engine.daySummary(monday).totals.kcal }

    @Test
    fun `Annuler puis Retablir sur la carte, sans appel au modele, la reponse barree puis rendue`() {
        val engine = engine()
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        open(engine, transport)
        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)

        compose.onNodeWithTag(CardTags.UNDO).performClick()
        waitFor(CardTags.RESTORE)
        assertEquals(0.0, kcal(engine), 0.0)
        // La réponse « C'est noté pour ton snack… » ne contredit plus la carte : elle est barrée.
        compose.onNodeWithTag(ChatTags.REPLY_CANCELLED).assertExists()

        compose.onNodeWithTag(CardTags.RESTORE).performClick()
        waitFor(CardTags.RECORD)
        assertEquals(300.0, kcal(engine), 0.001)
        compose.onNodeWithContentDescription("Ajouté, Snack, 300 kilocalories").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.REPLY_CANCELLED).assertDoesNotExist()
        compose.onNodeWithText("C’est noté pour ton snack. Il te reste 2 300 kcal aujourd’hui.").assertExists()

        // La même carte s'annule encore : c'est le rétablissement qui est défait.
        compose.onNodeWithTag(CardTags.UNDO).performClick()
        waitFor(CardTags.RESTORE)
        assertEquals(0.0, kcal(engine), 0.0)
        // Aucune requête de plus que les deux du message.
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `remets-le - la reponse du modele donne une vraie carte Retabli avec Annuler et Corriger`() {
        val engine = engine()
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        open(engine, transport)
        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)
        compose.onNodeWithTag(CardTags.UNDO).performClick()
        waitFor(CardTags.RESTORE)
        val undo = runBlocking { engine.observeRecentActions(1).first().single().id.value }

        transport.enqueue(ModelReplies.call("undo_action", """{"action_id":$undo}"""))
        transport.enqueue(ModelReplies.text("C’est remis."))
        send("remets-le")
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().size == 2 }

        compose.onNodeWithContentDescription("Rétabli, Snack, 300 kilocalories").assertExists()
        assertEquals(300.0, kcal(engine), 0.001)
        assertTrue(compose.onAllNodes(hasTestTag(CardTags.CORRECT)).fetchSemanticsNodes().size == 2)
    }

    @Test
    fun `carte perimee - corrigee depuis, boutons desactives et message humain sur la carte`() {
        val engine = engine()
        open(engine, ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"))
        send("comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
        waitFor(CardTags.RECORD)

        // Correction faite ailleurs (autre carte, écran du jour) : la carte n'est plus à jour.
        val pomme = runBlocking { engine.daySummary(monday).consumptions.first { it.food.label == "Pomme" }.entryId }
        runBlocking { engine.execute(CorrectConsumption(pomme, food = Food("Pomme verte", Nutrition(70.0, 0.4, 17.0, 0.3)))) }
        waitFor(CardTags.SUPERSEDED)

        compose.onNodeWithText("Corrigé depuis. Pour revenir en arrière, annule plutôt la correction.").assertIsDisplayed()
        compose.onNodeWithTag(CardTags.UNDO).assertIsNotEnabled()
        compose.onNodeWithTag(CardTags.CORRECT).assertIsNotEnabled()
        // Aucune erreur technique dans le fil.
        compose.onNodeWithText("Impossible d'annuler : une action plus récente a modifié les mêmes données").assertDoesNotExist()
    }
}
