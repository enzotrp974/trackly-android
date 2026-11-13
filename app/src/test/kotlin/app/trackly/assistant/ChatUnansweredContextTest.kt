package app.trackly.assistant

import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.InMemoryTurnMarkerStore
import app.trackly.assistant.chat.TurnMarker
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.chat.UNPROCESSED_NOTICE
import app.trackly.assistant.chat.UNPROCESSED_PREFIX
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.prompt.RecordingRulesSection
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.core.engine.TracklyEngine
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un message resté sans réponse (échec) est renvoyé au modèle **marqué** « non traité » quand l'utilisateur
 * en envoie un autre. Marqué, pour que « riz 200 g » puis « riz 200 g et une pomme » ne compte pas le riz deux fois ;
 * gardé, pour que « réessaie mon dernier message » ait quelque chose à reprendre. Il reste affiché dans le fil.
 */
class ChatUnansweredContextTest {
    private val engine: TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
    private val today = java.time.LocalDate.parse("2026-11-02")
    private val history = InMemoryChatHistory()

    private fun turns(transport: ScriptedTransport) = ChatTurns(
        engine, history, TurnInterpreter(ResponsesClient(transport), engine),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    /** Textes des messages `user` / `assistant` de la requête (hors contexte `developer`), dans l'ordre. */
    private fun conversation(request: JsonObject): List<Pair<String, String>> = request["input"]!!.jsonArray.map { it.jsonObject }
        .filter { it["role"]?.jsonPrimitive?.content in setOf("user", "assistant") }
        .map { item ->
            val role = item["role"]!!.jsonPrimitive.content
            val content = item["content"]!!
            role to (if (role == "user") content.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content else content.jsonPrimitive.content)
        }

    /** Message de l'utilisateur tel que le modèle le reçoit s'il est resté sans réponse. */
    private fun unprocessed(text: String) = "$UNPROCESSED_NOTICE\n$text"

    @Test
    fun `le marquage dit que rien n a ete enregistre et qu il ne faut pas compter deux fois`() {
        assertTrue(UNPROCESSED_NOTICE.startsWith(UNPROCESSED_PREFIX))
        assertTrue(UNPROCESSED_NOTICE.contains("rien n'a été enregistré"))
        assertTrue(UNPROCESSED_NOTICE.contains("explicitement"))
        assertTrue(UNPROCESSED_NOTICE.contains("ne compte qu'une fois"))
        // Les consignes citent le début du marquage : elles doivent rester d'accord avec le code.
        assertTrue(RecordingRulesSection.body.contains("« $UNPROCESSED_PREFIX »"))
        assertTrue(SystemPrompt.default().render().contains(UNPROCESSED_PREFIX))
    }

    @Test
    fun `riz hors ligne puis riz et une pomme - le premier message est renvoye marque, et reste affiche`() = runBlocking {
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet), ModelReplies.text("Noté."))
        val turns = turns(transport)

        turns.send("riz 200 g")
        turns.awaitIdle()
        turns.dismissError()
        turns.send("riz 200 g et une pomme")
        turns.awaitIdle()

        assertEquals(
            listOf("user" to unprocessed("riz 200 g"), "user" to "riz 200 g et une pomme"),
            conversation(transport.requests[1]),
        )
        // Rien n'est perdu côté écran : les deux messages sont dans le fil.
        assertEquals(listOf("riz 200 g", "riz 200 g et une pomme"), history.last(today, 10).filter { it.role == ChatRole.User }.map { it.text })
    }

    @Test
    fun `plusieurs messages restes sans reponse - tous marques, les echanges complets restent normaux`() = runBlocking {
        val transport = ScriptedTransport(
            ModelReplies.text("Bonjour."),
            ScriptedTransport.Fail(AssistantError.Timeout),
            ScriptedTransport.Fail(AssistantError.NoInternet),
            ModelReplies.text("Noté."),
        )
        val turns = turns(transport)
        turns.send("salut"); turns.awaitIdle()
        turns.send("un café"); turns.awaitIdle(); turns.dismissError()
        turns.send("un café et un croissant"); turns.awaitIdle(); turns.dismissError()
        turns.send("un croissant"); turns.awaitIdle()

        assertEquals(
            listOf(
                "user" to "salut", "assistant" to "Bonjour.",
                "user" to unprocessed("un café"), "user" to unprocessed("un café et un croissant"),
                "user" to "un croissant",
            ),
            conversation(transport.requests[3]),
        )
    }

    @Test
    fun `Reessayer renvoie le message normal, non marque, une seule fois`() = runBlocking {
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet), ModelReplies.text("Noté."))
        val turns = turns(transport)
        turns.send("riz 200 g")
        turns.awaitIdle()

        assertTrue(turns.retry())
        turns.awaitIdle()

        assertEquals(listOf("user" to "riz 200 g"), conversation(transport.requests[1]))
    }

    @Test
    fun `Reessayer apres un message plus ancien reste sans reponse - l ancien est marque, celui qu on reessaie ne l est pas`() = runBlocking {
        val transport = ScriptedTransport(
            ScriptedTransport.Fail(AssistantError.NoInternet),
            ScriptedTransport.Fail(AssistantError.Timeout),
            ModelReplies.text("Noté."),
        )
        val turns = turns(transport)
        turns.send("riz 200 g"); turns.awaitIdle(); turns.dismissError()
        turns.send("riz 200 g et une pomme"); turns.awaitIdle()

        assertTrue(turns.retry())
        turns.awaitIdle()

        assertEquals(
            listOf("user" to unprocessed("riz 200 g"), "user" to "riz 200 g et une pomme"),
            conversation(transport.requests[2]),
        )
    }

    @Test
    fun `declaration echouee puis Reessaie mon dernier message - le modele voit la declaration, marquee`() = runBlocking {
        val declaration = "Pour hier à midi : 200 g de riz et 100 g de poulet"
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet), ModelReplies.text("Je le reprends."))
        val turns = turns(transport)
        turns.send(declaration); turns.awaitIdle(); turns.dismissError()
        turns.send("Réessaie mon dernier message"); turns.awaitIdle()

        assertEquals(
            listOf("user" to unprocessed(declaration), "user" to "Réessaie mon dernier message"),
            conversation(transport.requests[1]),
        )
    }

    @Test
    fun `un message de l utilisateur qui a recu une reponse n est pas marque`() = runBlocking {
        val transport = ScriptedTransport(ModelReplies.text("Bonjour."), ModelReplies.text("Noté."))
        val turns = turns(transport)
        turns.send("salut"); turns.awaitIdle()
        turns.send("un café"); turns.awaitIdle()

        assertEquals(listOf("user" to "salut", "assistant" to "Bonjour.", "user" to "un café"), conversation(transport.requests[1]))
    }

    @Test
    fun `apres la mort du processus, Reessayer garde le niveau de raisonnement choisi`() = runBlocking {
        for (effort in listOf(ReasoningEffort.Low, ReasoningEffort.Max)) {
            val markers = InMemoryTurnMarkerStore()
            val history = InMemoryChatHistory()
            val at = engine.now().minusSeconds(60)
            val id = history.append(at, ChatRole.User, "deux oeufs")
            // Le repère écrit au début du tour par le processus qui est mort.
            markers.write(TurnMarker(id, at, effort = effort))
            val transport = ScriptedTransport(ModelReplies.text("Noté."))
            val turns = ChatTurns(
                engine, history, TurnInterpreter(ResponsesClient(transport), engine),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), markers = markers,
            )

            turns.recoverInterrupted()
            assertEquals(effort, turns.state.value.error!!.retryEffort)
            assertTrue(turns.retry())
            turns.awaitIdle()

            val sent = transport.requests[0]["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
            assertEquals(effort.code, sent)
        }
    }

    @Test
    fun `un message envoye en Bas puis repris apres un echec ordinaire reste en Bas`() = runBlocking {
        val transport = ScriptedTransport(ScriptedTransport.Fail(AssistantError.NoInternet), ModelReplies.text("Noté."))
        val turns = turns(transport)
        turns.send("riz 200 g", effort = ReasoningEffort.Low); turns.awaitIdle()
        assertTrue(turns.retry()); turns.awaitIdle()

        assertEquals("low", transport.requests[1]["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
}
