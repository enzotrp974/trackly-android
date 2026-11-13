package app.trackly.review

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.testing.ScriptedModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Une exception autre qu'une erreur d'OpenAI pendant un tour du bilan (base de données, outil…) ne ferme plus
 * l'app : erreur humaine avec « Réessayer », comme dans le chat. L'annulation passe toujours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelFaultTest {
    @Before
    fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    /** Horloge qui avance d'une minute une fois le jeu d'essai saisi : ses actions datent d'avant le message. */
    private class LaterClock(private val base: Clock) : Clock() {
        var offset: Duration = Duration.ZERO
        override fun getZone(): ZoneId = base.zone
        override fun withZone(zone: ZoneId): Clock = base.withZone(zone)
        override fun instant(): Instant = base.instant().plus(offset)
    }

    private fun viewModel(transport: ResponsesTransport) = runBlocking {
        val clock = LaterClock(ReviewSample.clock())
        val engine = ReviewSample.engine(clock)
        clock.offset = Duration.ofMinutes(1)
        ReviewViewModel(ReviewServices.inMemory(engine, RecordingReviewNotifier(), transport), ReviewSample.week)
    }

    @Test
    fun `exception inattendue pendant un tour - erreur humaine avec Reessayer, le tour est termine`() = runBlocking<Unit> {
        val model = viewModel { throw IllegalStateException("secret: 72,4 kg") }
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        model.send("Mardi 72,4 kg")

        val state = model.state.value
        assertFalse("le tour est terminé", state.thinking)
        val error = state.error!!
        assertEquals("Un problème est survenu", error.notice.title)
        assertTrue(error.notice.retryable)
        assertEquals("Mardi 72,4 kg", error.retryText)
        assertFalse("le détail technique n'est jamais montré", error.notice.body.contains("secret"))
        collector.cancel()
    }

    /** Rapport produit puis erreur d'OpenAI : pas de « Réessayer », et le texte n'invite pas à renvoyer le message. */
    @Test
    fun `erreur d OpenAI apres la production du rapport - le texte dit de regarder avant de renvoyer`() = runBlocking<Unit> {
        var requests = 0
        val model = viewModel { _ ->
            requests++
            if (requests == 1) {
                ScriptedModel.toolCall("produce_weekly_report", """{"analysis":"Belle période, continue comme ça."}""")
            } else {
                throw AssistantException(AssistantError.Timeout)
            }
        }
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        model.produceReport()

        val error = model.state.value.error!!
        assertNull(error.retryText)
        assertFalse(error.notice.body, error.notice.body.contains("Réessaie"))
        assertTrue(error.notice.body, error.notice.body.contains("regarde ton journal avant de renvoyer"))
        collector.cancel()
    }

    @Test
    fun `annulation pendant un tour - jamais transformee en erreur`() = runBlocking<Unit> {
        val model = viewModel { throw CancellationException("fin de l'écran") }
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        model.send("Mardi 72,4 kg")

        assertFalse(model.state.value.thinking)
        assertNull(model.state.value.error)
        collector.cancel()
    }
}
