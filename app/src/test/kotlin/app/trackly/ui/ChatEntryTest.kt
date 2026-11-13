package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.net.FixedConnectivity
import app.trackly.assistant.net.Prewarmer
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
 * Entrer dans le chat sans toucher de trop, revenir au bon jour, clavier fermé en partant,
 * bannière « Hors ligne », préchauffage à l'ouverture, « Corriger » depuis l'écran du jour.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ChatEntryTest {
    @get:Rule val compose = createComposeRule()
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    private fun show(engine: TracklyEngine, assistant: AssistantServices) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant) }
        compose.waitForIdle()
    }

    @Test
    fun `le plus ouvre le chat avec le curseur dans la saisie, et le prechauffage part`() {
        var warmed = 0
        val engine = SampleData.engine()
        show(engine, AssistantServices.inMemory(engine, keyStore = key, prewarmer = Prewarmer { warmed++ }))

        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.INPUT).assertIsFocused()
        assertEquals(1, warmed)
    }

    @Test
    fun `le plus d un moment preremplit le moment, et Envoyer ferme le clavier`() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T08:10"))
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        show(engine, AssistantServices.inMemory(engine, transport, key))

        // Le « + » d'un moment visible est dans sa feuille.
        compose.onNodeWithText("Matin").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ajouter au matin").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.INPUT).assertTextEquals("Ce matin : ")
        compose.onNodeWithTag(ChatTags.INPUT).assertIsFocused()
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("une barre Nuvo et une pomme")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { transport.requests.isNotEmpty() }
        compose.waitForIdle()

        val sent = transport.requests.first()["input"]!!.jsonArray.last().jsonObject["content"]!!.toString()
        assertTrue(sent, sent.contains("Ce matin : une barre Nuvo et une pomme"))
        // « J'envoie et je pars » : plus de curseur, donc plus de clavier sur la réponse.
        compose.onNodeWithTag(ChatTags.INPUT).assertIsNotFocused()
    }

    @Test
    fun `plus depuis un jour passe - date ecrite, puis retour au jour de la consommation en refermant`() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        show(engine, AssistantServices.inMemory(engine, transport, key))

        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("dimanche 1er novembre", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INPUT).assertTextEquals("Pour dimanche 1er novembre : ")

        // Le modèle simulé enregistre pour aujourd'hui (lundi) : en refermant, l'écran montre lundi.
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("une barre Nuvo")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("lundi 2 novembre", substring = true).assertIsDisplayed()
    }

    @Test
    fun `sans rien enregistrer, refermer le chat laisse le jour passe affiche`() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
        show(engine, AssistantServices.inMemory(engine, keyStore = key))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("dimanche 1er novembre", substring = true).assertIsDisplayed()
    }

    /** Historique dont la première lecture attend [gate] : le chat s'ouvre alors que la liste des messages est encore vide. */
    private class SlowHistory(private val inner: ChatHistory, val gate: CompletableDeferred<Unit>) : ChatHistory by inner {
        override fun observe(day: java.time.LocalDate, limit: Int): Flow<List<ChatMessage>> = flow {
            gate.await()
            emitAll(inner.observe(day, limit))
        }
    }

    @Test
    fun `chat ouvert avant la lecture de l historique - refermer sans rien faire ne saute pas au dernier enregistrement`() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
        val inner = InMemoryChatHistory()
        // Un ancien enregistrement, pour dimanche : il date d'avant cette ouverture du chat.
        runBlocking {
            inner.append(
                engine.now(), ChatRole.Assistant, "",
                listOf(RecordCard(actionId = 5, kind = RecordKind.Added, items = listOf(CardItem("e5", "Riz", moment = "midi", day = "2026-11-01", kcal = 300.0)))),
            )
        }
        val slow = SlowHistory(inner, CompletableDeferred())
        show(engine, AssistantServices.inMemory(engine, keyStore = key, history = slow))

        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        // L'historique arrive après l'ouverture du chat.
        slow.gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("lundi 2 novembre", substring = true).assertIsDisplayed()
    }

    @Test
    fun `Corriger sur une carte place aussi le curseur dans la saisie`() {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
        show(engine, AssistantServices.inMemory(engine, ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"), key))
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("comme snack j'ai pris une barre Nuvo et une pomme")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(CardTags.RECORD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ChatTags.INPUT).assertIsNotFocused()

        compose.onNodeWithTag(CardTags.CORRECT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.CORRECTING).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.INPUT).assertIsFocused()
    }

    @Test
    fun `hors ligne - la banniere apparait au-dessus de la saisie et disparait au retour du reseau`() {
        val engine = SampleData.engine()
        val network = FixedConnectivity(false)
        show(engine, AssistantServices.inMemory(engine, keyStore = key, connectivity = network))
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ChatTags.OFFLINE).assertIsDisplayed()
        compose.onNodeWithText("Hors ligne · rien ne peut partir pour l’instant. Ce que tu écris reste ici.").assertExists()

        network.set(true)
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.OFFLINE).assertDoesNotExist()
    }

    @Test
    fun `Corriger depuis l ecran du jour - le message suivant vise ces consommations`() {
        val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
        val transport = ScriptedTransport.replies("question-reponse.json")
        val assistant = AssistantServices.inMemory(engine, transport, key)
        val model = ViewModelProvider.create(ViewModelStore(), ChatViewModel.factory(assistant))[ChatViewModel::class]
        val snack = runBlocking { engine.daySummary(LocalDate.parse("2026-11-02")).consumptions.first { it.food.label.startsWith("Barre Nuvo") } }

        model.correctEntries(listOf(snack.entryId.value), snack.food.label)
        model.send("c'était 2 pommes")
        compose.waitUntil(5_000) { transport.requests.isNotEmpty() }

        val input = transport.requests.single()["input"]!!.jsonArray.map { it.jsonObject }
        val context = input.first { it["role"]!!.jsonPrimitive.content == "developer" }["content"]!!.jsonPrimitive.content
        assertTrue(context, context.contains("L'utilisateur a touché « Corriger » sur un repas de l'écran du jour."))
        assertTrue(context, context.contains(snack.entryId.value))
    }
}
