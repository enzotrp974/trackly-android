package app.trackly.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un aliment flou n'est noté qu'après la réponse de l'utilisateur. Si la réflexion qui suit sa réponse n'aboutit pas
 * (appel raccroché avant, échec), la question reste au rapport : la relecture note l'aliment, avec sa réponse ou la portion
 * habituelle, au lieu de l'oublier.
 */
class CallMealsTest {
    private val q1 = "Ta pomme était petite, moyenne ou grosse ?"

    private fun askedAndAnswered(answer: String): CallMeals = CallMeals().apply {
        onUser("Ce midi j'ai mangé une pomme.", newLine = true)
        request(ThinkTrigger.Voice, at = 0)
        startIfReady(at = 0) { true }
        finish(ThinkResult.Done(questions = listOf(q1)))
        assertEquals(q1, takeQuestion())
        onUser(answer, newLine = true)
        assertTrue("dernière question répondue : la réflexion repart", onUtteranceEnded())
        request(ThinkTrigger.Answer, at = 1)
    }

    @Test
    fun `reponse donnee puis raccroche avant la reflexion - la question reste au rapport`() {
        val meals = askedAndAnswered("Attends, je sais pas trop.")
        meals.dropPending()
        assertEquals(listOf(q1), meals.report(emptyList()).openQuestions)
    }

    @Test
    fun `reponse donnee, reflexion en echec - la question reste au rapport`() {
        val meals = askedAndAnswered("Une grosse.")
        meals.startIfReady(at = 2) { true }
        meals.finish(ThinkResult.Failed("NoInternet"))
        assertEquals(listOf(q1), meals.report(emptyList()).openQuestions)
    }

    @Test
    fun `reponse couverte par une reflexion aboutie - plus rien d ouvert`() {
        val meals = askedAndAnswered("Une grosse.")
        meals.startIfReady(at = 2) { true }
        meals.finish(ThinkResult.Done())
        assertTrue(meals.report(emptyList()).openQuestions.isEmpty())
    }
}
