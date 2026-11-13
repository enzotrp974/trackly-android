package app.trackly.assistant

import app.trackly.assistant.chat.ChatError
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le repas est noté, puis la réponse suivante d'OpenAI échoue. La carte est là et « Réessayer » n'est
 * pas proposé ; le texte ne doit pas pousser à renvoyer le message (« Réessaie plus tard »), ce qui doublerait le repas.
 */
class ChatErrorAfterRecordTest {
    private val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))

    private fun errorAfterRecord(error: AssistantError): ChatError = runBlocking {
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"food":{"label":"Pomme","quantity":150,"unit":"g","kcal":78,"protein_g":0.4,"carbs_g":21,"fat_g":0.3,"fiber_g":3.6,"assumptions":[],"confidence":"medium"},"moment":"snack","day":null}],"question":null}""",
            ),
            ScriptedTransport.Fail(error),
            ScriptedTransport.Fail(error),
            ScriptedTransport.Fail(error),
        )
        val turns = ChatTurns(
            engine, InMemoryChatHistory(), TurnInterpreter(ResponsesClient(transport, sleep = {}), engine),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        turns.send("une pomme au goûter")
        withTimeout(5_000) { turns.awaitIdle() }
        turns.state.value.error!!
    }

    @Test
    fun `erreur d OpenAI apres un enregistrement - le texte dit de regarder le journal avant de renvoyer`() {
        val error = errorAfterRecord(AssistantError.ServerError(500))
        assertNull("le repas est noté : pas de « Réessayer »", error.retryText)
        assertFalse(error.notice.body, error.notice.body.contains("Réessaie"))
        assertTrue(error.notice.body, error.notice.body.contains("regarde ton journal avant de renvoyer ton message"))
    }

    @Test
    fun `plus de reseau apres un enregistrement - pas de Saisir a la main, qui doublerait le repas`() {
        val error = errorAfterRecord(AssistantError.NoInternet)
        assertNull(error.retryText)
        assertFalse(error.notice.network)
        assertTrue(error.notice.body.contains("regarde ton journal"))
    }

    @Test
    fun `credit epuise apres un enregistrement - la consigne pour recharger reste`() {
        val body = errorAfterRecord(AssistantError.CreditExhausted).notice.body
        assertTrue(body, body.contains("platform.openai.com"))
        assertTrue(body, body.contains("regarde ton journal"))
    }
}
