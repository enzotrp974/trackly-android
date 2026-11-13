package app.trackly.core

import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.UndoLast
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.ProteinMinimum
import app.trackly.core.model.GoalTargets
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minimum de protéines : un plancher enregistré avec l'objectif daté, à côté de l'idéal. Un changement de
 * calories ne le fait jamais baisser, et l'idéal ne descend jamais dessous.
 */
class ProteinMinimumTest {
    private val f = Fixture()

    /** 70 kg : idéal 2 g/kg = 140 g, minimum 1,6 g/kg = 112 g. */
    private val base = GoalTargets(kcal = 2600.0, proteinG = 140.0, carbsG = 352.0, fatG = 70.0, proteinMinG = 112.0)

    @Test
    fun `l objectif garde le minimum a cote de l ideal`() = runTest {
        f.apply(SetGoal(base))
        val goal = f.summary().goal!!
        assertEquals(140.0, goal.proteinG!!, 0.0)
        assertEquals(112.0, goal.proteinMinG!!, 0.0)
    }

    @Test
    fun `changer les calories garde le minimum de l objectif en vigueur`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        // L'assistant et les Réglages ne donnent que kcal et macros : le minimum suit tout seul.
        f.apply(SetGoal(GoalTargets(2200.0, 140.0, 268.0, 60.0)))
        assertEquals(112.0, f.summary().goal!!.proteinMinG!!, 0.0)
        assertEquals(112.0, f.summary("2026-10-01").goal!!.proteinMinG!!, 0.0)
    }

    @Test
    fun `un objectif prevu plus tard garde aussi le minimum`() = runTest {
        f.apply(SetGoal(base))
        f.apply(SetGoal(GoalTargets(2400.0, 140.0, 302.0, 70.0), from = day("2026-11-10")))
        assertEquals(112.0, f.summary("2026-11-10").goal!!.proteinMinG!!, 0.0)
    }

    @Test
    fun `des proteines sous le minimum sont refusees, rien n est ecrit`() = runTest {
        f.apply(SetGoal(base))
        val rejection = f.reject(SetGoal(GoalTargets(2200.0, 100.0, 308.0, 60.0)))
        assertTrue(rejection.toString(), rejection is Rejection.Invalid)
        assertTrue(rejection.toString(), (rejection as Rejection.Invalid).message.contains("112"))
        assertEquals(base, f.summary().goal)
    }

    @Test
    fun `l arrondi au gramme sous le minimum passe`() = runTest {
        f.apply(SetGoal(base.copy(proteinMinG = 112.4)))
        f.apply(SetGoal(GoalTargets(2600.0, 112.0, 380.0, 70.0)))
        assertEquals(112.0, f.summary().goal!!.proteinG!!, 0.0)
    }

    @Test
    fun `annuler un changement remet l objectif d avant, minimum compris`() = runTest {
        f.apply(SetGoal(base))
        f.apply(SetGoal(GoalTargets(2200.0, 140.0, 268.0, 60.0)))
        f.apply(UndoLast)
        assertEquals(base, f.summary().goal)
    }

    @Test
    fun `l assistant qui propose moins que le minimum est refuse, avec le plancher et l ideal`() {
        val message = GoalCoherence.problem(GoalTargets(2200.0, 100.0, 308.0, 60.0), base)!!
        assertTrue(message, message.contains("112"))
        assertTrue(message, message.contains("plancher"))
        // Le message renvoie vers l'idéal, jamais vers le minimum comme valeur à redonner.
        assertTrue(message, message.contains("Garde les protéines à l'idéal (140 g) et ajuste glucides et lipides"))
        assertTrue(message, !message.contains("au moins"))
        assertNull(GoalCoherence.problem(GoalTargets(2200.0, 140.0, 268.0, 60.0), base))
    }

    @Test
    fun `ajuster en proportion ne fait jamais descendre les proteines sous le minimum`() {
        // 1 500 kcal : la mise à l'échelle simple donnerait 81 g de protéines, sous les 112 g.
        val scaled = GoalCoherence.scaledToKcal(base.copy(kcal = 1500.0))!!
        assertEquals(112.0, scaled.proteinG!!, 0.0)
        assertEquals(112.0, scaled.proteinMinG!!, 0.0)
        // Le reste des calories se répartit entre glucides et lipides, dans leurs proportions d'avant.
        assertEquals(1500.0, GoalCoherence.macroKcal(scaled), 25.0)
        assertEquals(352.0 / 70.0, scaled.carbsG!! / scaled.fatG!!, 0.1)
    }

    @Test
    fun `un seul plancher au gramme, partout`() {
        assertEquals(123.0, GoalCoherence.floorG(123.25), 0.0)
        assertEquals(124.0, GoalCoherence.floorG(123.6), 0.0)
        assertEquals(112.0, GoalCoherence.floorG(112.0), 0.0)
        val scaled = GoalCoherence.scaledToKcal(base.copy(kcal = 1500.0, proteinMinG = 123.25))!!
        assertEquals(123.0, scaled.proteinG!!, 0.0)
        assertEquals(false, GoalCoherence.belowMinimum(scaled)) // le plancher arrondi est accepté
    }

    @Test
    fun `ajuster en proportion vers le haut reste proportionnel`() {
        val scaled = GoalCoherence.scaledToKcal(base.copy(kcal = 3000.0))!!
        assertTrue(scaled.proteinG!! > 140.0)
        assertEquals(112.0, scaled.proteinMinG!!, 0.0)
    }

    @Test
    fun `minimum du profil repris - regle en vigueur et poids de reference`() {
        val profile = Json.parseToJsonElement(
            """
            {"current_reference_weight_kg": 65.8,
             "goals": [
               {"effective_from": "2026-09-01", "protein_min_g_per_kg": 1.6, "reference_weight_kg": 74.0},
               {"effective_from": "2026-10-15", "protein_min_g_per_kg": 1.7}
             ]}
            """.trimIndent(),
        ).jsonObject
        assertNull(ProteinMinimum.fromProfile(profile, day("2026-08-31")))
        assertEquals(118.4, ProteinMinimum.fromProfile(profile, day("2026-09-01"))!!, 0.0) // 74 × 1,6
        assertEquals(118.4, ProteinMinimum.fromProfile(profile, day("2026-10-14"))!!, 0.0)
        assertEquals(111.86, ProteinMinimum.fromProfile(profile, day("2026-10-15"))!!, 0.0) // 65,8 × 1,7
        assertEquals(111.86, ProteinMinimum.fromProfile(profile, day("2027-01-01"))!!, 0.0)
    }

    @Test
    fun `profil sans minimum ou sans poids - pas de minimum invente`() {
        val noMin = Json.parseToJsonElement("""{"current_reference_weight_kg": 70, "goals": [{"effective_from": "2026-09-01"}]}""").jsonObject
        assertNull(ProteinMinimum.fromProfile(noMin, day("2026-10-01")))
        val noWeight = Json.parseToJsonElement("""{"goals": [{"effective_from": "2026-09-01", "protein_min_g_per_kg": 1.6}]}""").jsonObject
        assertNull(ProteinMinimum.fromProfile(noWeight, day("2026-10-01")))
        assertNotNull(ProteinMinimum.fromProfile(Json.parseToJsonElement("""{"current_reference_weight_kg": 70, "goals": [{"effective_from": "2026-09-01", "protein_min_g_per_kg": 1.6}]}""").jsonObject, day("2026-10-01")))
    }
}
