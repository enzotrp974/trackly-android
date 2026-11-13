package app.trackly.assistant

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.RememberFact
import app.trackly.core.model.AgentMemory
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La mémoire de l'agent : ce qu'il entend de durable est retenu, redonné au modèle à chaque message, et s'oublie. */
class MemoryTurnTest {
    private val clock = SampleData.clock("2026-11-02T12:30")
    private val engine = SampleData.emptyEngine(clock)

    private fun turn(vararg replies: ScriptedTransport.Step) = runBlocking {
        TurnInterpreter(ResponsesClient(ScriptedTransport(*replies), RetryPolicy.None), engine).run(TurnInput("je déteste le thon"))
    }

    @Test
    fun `retenu sans le demander, avec sa carte, et annulable`() = runBlocking {
        val outcome = turn(
            ModelReplies.call("manage_memory", """{"operation":"add","memory_id":null,"topic":"Thon","text":"N'aime pas le thon."}"""),
            ModelReplies.text("Noté."),
        ) as TurnOutcome.Completed
        val note = AgentMemory.notes(engine.activePreferences()).single()
        assertEquals("Thon", note.trigger)
        val card = outcome.cards.single() as ProductCard
        assertEquals(ProductOperation.Remembered, card.operation)
        assertEquals("N'aime pas le thon.", card.detail)
        assertEquals("Retenu", card.status)
    }

    @Test
    fun `la memoire est dans le contexte, et n est pas melangee aux regles`() = runBlocking {
        engine.execute(RememberFact("Thon", "N'aime pas le thon."))
        val context = ContextBuilder.default().build(ContextEnv(engine, clock.instant()))
        assertTrue(context, context.contains("## Mémoire"))
        assertTrue(context, context.contains("Thon : N'aime pas le thon."))
        assertFalse(context, context.contains("Règles durables"))
    }

    @Test
    fun `update sans identifiant et remove inconnu ne changent rien`() = runBlocking {
        turn(
            ModelReplies.call("manage_memory", """{"operation":"update","memory_id":null,"topic":"Thon","text":"Non."}"""),
            ModelReplies.call("manage_memory", """{"operation":"remove","memory_id":"mem-absente","topic":null,"text":null}"""),
            ModelReplies.text("Rien."),
        )
        assertTrue(AgentMemory.notes(engine.activePreferences()).isEmpty())
    }
}
