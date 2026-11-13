package app.trackly.core

import app.trackly.core.goal.GoalCoherence
import app.trackly.core.model.GoalTargets
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cohérence calories ↔ macros, contrôlée par le code avant qu'un objectif proposé par l'assistant ne soit appliqué. */
class GoalCoherenceTest {
    private val current = GoalTargets(kcal = 2600.0, proteinG = 140.0, carbsG = 352.0, fatG = 70.0) // 2 598 kcal

    @Test
    fun `des macros qui bouclent les calories passent`() {
        assertNull(GoalCoherence.problem(GoalTargets(2300.0, 150.0, 270.0, 70.0), current)) // 600 + 1 080 + 630 = 2 310
    }

    @Test
    fun `l ecart toleree est de 5 pour cent, au moins 50 kcal`() {
        assertNull(GoalCoherence.problem(GoalTargets(2300.0, 150.0, 240.0, 70.0), current)) // 2 190 : écart 110 < 115
        assertNotNull(GoalCoherence.problem(GoalTargets(2300.0, 150.0, 230.0, 70.0), current)) // 2 150 : écart 150
        assertNull(GoalCoherence.problem(GoalTargets(800.0, 50.0, 100.0, 20.0), null)) // 780 : écart 20
        assertNotNull(GoalCoherence.problem(GoalTargets(800.0, 50.0, 100.0, 40.0), null)) // 960 : écart 160 > 50
    }

    @Test
    fun `les anciennes macros avec de nouvelles calories sont refusees, avec une piste`() {
        val message = GoalCoherence.problem(GoalTargets(2300.0, 140.0, 352.0, 70.0), current)!!
        assertTrue(message, message.contains("2598")) // 4×P + 4×G + 9×L calculé par le code
        assertTrue(message, message.contains("glucides seraient d'environ 278 g")) // (2 300 - 560 - 630) / 4
    }

    @Test
    fun `calories seules alors que l objectif actuel a des macros, refuse`() {
        val message = GoalCoherence.problem(GoalTargets(kcal = 2300.0), current)!!
        assertTrue(message, message.contains("trois macros"))
    }

    @Test
    fun `calories seules passent si l objectif actuel n a pas de macros`() {
        assertNull(GoalCoherence.problem(GoalTargets(kcal = 2300.0), GoalTargets(kcal = 2600.0)))
        assertNull(GoalCoherence.problem(GoalTargets(kcal = 2300.0), null))
    }

    @Test
    fun `des macros partielles ne doivent pas depasser les calories`() {
        assertNull(GoalCoherence.problem(GoalTargets(kcal = 2300.0, proteinG = 150.0), GoalTargets(kcal = 2600.0)))
        assertNotNull(GoalCoherence.problem(GoalTargets(kcal = 1000.0, proteinG = 400.0), GoalTargets(kcal = 2600.0)))
    }

    @Test
    fun `des calories hors du plausible sont refusees (faute de frappe)`() {
        assertNotNull(GoalCoherence.problem(GoalTargets(kcal = 23.0), null))
        assertNotNull(GoalCoherence.problem(GoalTargets(kcal = 23000.0), null))
    }

    @Test
    fun `les valeurs negatives ou nulles n existent pas`() {
        for (build in listOf<() -> GoalTargets>(
            { GoalTargets(kcal = -1.0) },
            { GoalTargets(kcal = 0.0) },
            { GoalTargets(kcal = 2000.0, proteinG = -5.0) },
            { GoalTargets(kcal = Double.NaN) },
        )) {
            assertTrue(runCatching { build() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `ajuster en proportion garde les proportions et rejoint les calories`() {
        // 2 400 kcal avec des macros qui font 2 350 : 152,25 g de protéines, 296,59 g de glucides, 61,62 g de lipides.
        val off = GoalTargets(2400.0, 152.25, 296.59, 61.62)
        assertTrue(GoalCoherence.macrosOff(off))
        val scaled = GoalCoherence.scaledToKcal(off)!!
        assertEquals(2400.0, scaled.kcal, 0.0)
        assertEquals(155.0, scaled.proteinG!!, 0.0)
        assertEquals(303.0, scaled.carbsG!!, 0.0)
        assertEquals(63.0, scaled.fatG!!, 0.0)
        // Arrondi au gramme : la somme reste à moins de 10 kcal des calories, donc plus rien à signaler.
        assertTrue(abs(GoalCoherence.macroKcal(scaled) - 2400.0) < 10.0)
        assertFalse(GoalCoherence.macrosOff(scaled))
        assertNull(GoalCoherence.scaledToKcal(scaled))
    }

    @Test
    fun `ajuster en proportion ne fait rien sans les trois macros ou sans ecart`() {
        assertNull(GoalCoherence.scaledToKcal(GoalTargets(2400.0, 150.0, null, 60.0)))
        assertNull(GoalCoherence.scaledToKcal(GoalTargets(2400.0)))
        assertNull(GoalCoherence.scaledToKcal(GoalTargets(2310.0, 150.0, 270.0, 70.0))) // 2 310 : déjà bon
        assertNull(GoalCoherence.scaledToKcal(GoalTargets(2000.0, 0.0, 0.0, 0.0))) // macros sans calories : rien à mettre à l'échelle
        assertFalse(GoalCoherence.macrosOff(GoalTargets(2330.0, 150.0, 270.0, 70.0))) // 20 kcal : dans l'arrondi
    }
}
