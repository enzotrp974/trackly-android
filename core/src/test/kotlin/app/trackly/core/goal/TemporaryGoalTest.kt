package app.trackly.core.goal

import app.trackly.core.Fixture
import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.day
import app.trackly.core.model.GoalTargets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objectif temporaire : deux versions datées de l'objectif, un retour déduit de la date (aucune tâche de fond), et une
 * annulation qui marche à chaque étape sans jamais réécrire un jour passé.
 *
 * Bilan le vendredi 6 novembre 2026 au soir : −100 kcal du samedi 7 au mardi 10, retour à 2 300 le mercredi 11.
 */
class TemporaryGoalTest {
    private val f = Fixture("2026-11-06T20:00")
    private val base = GoalTargets(kcal = 2300.0, proteinG = 140.0, carbsG = 270.0, fatG = 70.0, proteinMinG = 120.0)
    private val lowered = base.copy(kcal = 2200.0, carbsG = 245.0)
    private val plan = SetTemporaryGoal(start = day("2026-11-07"), days = 4, dropKcal = 100.0)

    private suspend fun setUp() {
        f.apply(SetGoal(base, from = day("2026-10-01")))
    }

    private suspend fun goalOn(iso: String) = f.summary(iso).goal

    private suspend fun status() = f.engine.temporaryGoal(day("2026-11-07"))!!

    @Test
    fun `appliquer - plus bas les 4 jours, retour tout seul le 5e, les jours d avant inchanges`() = runTest {
        setUp()
        f.apply(plan)

        assertEquals(base, goalOn("2026-11-06"))
        for (d in listOf("2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10")) assertEquals(d, lowered, goalOn(d))
        assertEquals(base, goalOn("2026-11-11"))
        assertEquals(base, goalOn("2026-12-31"))
        // Protéines, minimum et lipides ne bougent pas : les glucides portent la baisse.
        assertEquals(base.proteinMinG, goalOn("2026-11-08")!!.proteinMinG)

        val s = status()
        assertEquals(TemporaryPhase.Scheduled, s.phase)
        assertEquals(day("2026-11-11"), s.returnOn)
        assertEquals(2200.0, s.temporaryKcal, 0.0)
        assertEquals(2300.0, s.baseKcal, 0.0)
        assertTrue(s.canToggle)
    }

    @Test
    fun `retour deduit de la date - aucune tache, le jour du retour l objectif d avant est la`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-09T09:00")
        assertEquals(TemporaryPhase.Running, status().phase)
        f.clock.setLocal("2026-11-11T06:00")
        assertEquals(TemporaryPhase.Finished, status().phase)
        assertEquals(base, goalOn("2026-11-11"))
        assertEquals(lowered, goalOn("2026-11-10"))
        assertFalse(status().canToggle)
    }

    @Test
    fun `annuler avant le premier jour - tout disparait, puis retablir`() = runTest {
        setUp()
        f.apply(plan)
        f.apply(Undo(status().head))

        assertEquals(TemporaryPhase.Cancelled, status().phase)
        for (d in listOf("2026-11-07", "2026-11-10", "2026-11-11")) assertEquals(base, goalOn(d))
        assertTrue(status().canToggle)

        f.apply(Undo(status().head))
        assertEquals(TemporaryPhase.Scheduled, status().phase)
        assertEquals(lowered, goalOn("2026-11-08"))
    }

    @Test
    fun `annuler le premier jour - aujourd hui n est pas passe, tout disparait`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-07T12:00")
        f.apply(Undo(status().head))
        assertEquals(TemporaryPhase.Cancelled, status().phase)
        assertEquals(base, goalOn("2026-11-07"))
    }

    @Test
    fun `annuler en cours de route - les jours ecoules gardent leur objectif, l objectif d avant revient des aujourd hui`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-09T08:00")
        f.apply(Undo(status().head))

        assertEquals(lowered, goalOn("2026-11-07"))
        assertEquals(lowered, goalOn("2026-11-08"))
        assertEquals(base, goalOn("2026-11-09"))
        assertEquals(base, goalOn("2026-11-10"))
        assertEquals(base, goalOn("2026-11-11"))
        val s = status()
        assertEquals(TemporaryPhase.Stopped, s.phase)
        assertEquals(day("2026-11-09"), s.stoppedOn)
        // Le même jour, rien de passé ne change : rétablir est encore possible…
        assertTrue(s.canToggle)
        f.apply(Undo(s.head))
        assertEquals(lowered, goalOn("2026-11-09"))
        assertEquals(base, goalOn("2026-11-11"))
        assertEquals(TemporaryPhase.Running, status().phase)

        // … puis on arrête de nouveau ; le lendemain, rétablir réécrirait le 9 : refusé, rien ne change.
        f.apply(Undo(status().head))
        f.clock.setLocal("2026-11-10T08:00")
        assertFalse(status().canToggle)
        val rejection = f.reject(Undo(status().head))
        assertTrue(rejection.message, rejection is Rejection.Invalid)
        assertEquals(lowered, goalOn("2026-11-08"))
        assertEquals(base, goalOn("2026-11-09"))
    }

    @Test
    fun `annuler apres le retour - refuse, rien ne change`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-12T09:00")
        val rejection = f.reject(Undo(status().head))
        assertTrue(rejection.message, rejection.message.contains("terminé"))
        assertEquals(lowered, goalOn("2026-11-08"))
        assertEquals(base, goalOn("2026-11-11"))
    }

    @Test
    fun `annule par le chat - meme regle, les jours passes gardent leur objectif`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-08T10:00")
        f.apply(UndoLast)
        assertEquals(lowered, goalOn("2026-11-07"))
        assertEquals(base, goalOn("2026-11-08"))
        assertEquals(TemporaryPhase.Stopped, status().phase)
    }

    @Test
    fun `limites de securite`() = runTest {
        setUp()
        fun invalid(r: Rejection) = assertTrue(r.message, r is Rejection.Invalid)
        invalid(f.reject(plan.copy(dropKcal = 250.0))) // plus de 200 par jour
        invalid(f.reject(plan.copy(dropKcal = 10.0))) // trop peu pour valoir un changement
        invalid(f.reject(plan.copy(days = 8))) // plus d'une semaine
        invalid(f.reject(plan.copy(days = 0)))
        invalid(f.reject(plan.copy(start = day("2026-11-05")))) // un jour passé
        invalid(f.reject(plan.copy(start = day("2026-11-20")))) // trop loin

        // 10 % de l'objectif au plus : 180 pour 1 800.
        f.apply(SetGoal(GoalTargets(1800.0), from = day("2026-11-06"), allowPast = false))
        invalid(f.reject(plan.copy(dropKcal = 190.0)))
        f.apply(plan.copy(dropKcal = 180.0))
        assertEquals(1620.0, goalOn("2026-11-07")!!.kcal, 0.0)
    }

    @Test
    fun `jamais sous le plancher de 1 500 kcal`() = runTest {
        f.apply(SetGoal(GoalTargets(1550.0), from = day("2026-10-01")))
        val rejection = f.reject(plan.copy(dropKcal = 100.0))
        assertTrue(rejection.message, rejection.message.contains("1500"))
    }

    @Test
    fun `un seul a la fois, et jamais par-dessus un objectif deja prevu`() = runTest {
        setUp()
        f.apply(plan)
        assertTrue(f.reject(plan.copy(start = day("2026-11-08"))) is Rejection.Invalid)

        val other = Fixture("2026-11-06T20:00")
        other.apply(SetGoal(base, from = day("2026-10-01")))
        other.apply(SetGoal(base.copy(kcal = 2250.0), from = day("2026-11-09"), allowPast = false))
        val rejection = other.reject(plan)
        assertTrue(rejection.message, rejection.message.contains("2026-11-09"))
    }

    /** Poids de référence au jour [iso] : nouvelles macros sur l'objectif en vigueur et chaque objectif daté après, chacun avec ses calories. */
    private suspend fun referenceUpdate(iso: String) {
        for ((from, g) in GoalTimeline.rewriteFrom(day(iso), f.engine.goals())) {
            f.apply(SetGoal(g.targets.copy(proteinG = 145.0, proteinMinG = 125.0), from = from, allowPast = false))
        }
    }

    @Test
    fun `poids de reference pendant l objectif temporaire - ni permanent, ni retour casse, calories gardees, Annuler marche`() = runTest {
        setUp()
        f.apply(plan)
        f.clock.setLocal("2026-11-08T21:00")
        referenceUpdate("2026-11-08")

        assertEquals(2200.0, goalOn("2026-11-09")!!.kcal, 0.0)
        assertEquals(125.0, goalOn("2026-11-09")!!.proteinMinG)
        assertEquals(2300.0, goalOn("2026-11-11")!!.kcal, 0.0)
        assertEquals(125.0, goalOn("2026-11-11")!!.proteinMinG)
        assertEquals(TemporaryPhase.Running, status().phase)

        f.clock.setLocal("2026-11-09T08:00")
        f.apply(Undo(status().head))
        assertEquals(2200.0, goalOn("2026-11-08")!!.kcal, 0.0)
        assertEquals(base.copy(proteinG = 145.0, proteinMinG = 125.0), goalOn("2026-11-09"))
        assertEquals(TemporaryPhase.Stopped, status().phase)
    }

    @Test
    fun `poids de reference avant le premier jour - Annuler retire tout et garde les nouvelles macros`() = runTest {
        setUp()
        f.apply(plan)
        referenceUpdate("2026-11-06")
        assertEquals(TemporaryPhase.Scheduled, status().phase)
        assertEquals(2200.0, goalOn("2026-11-07")!!.kcal, 0.0)

        f.apply(Undo(status().head))
        val updated = base.copy(proteinG = 145.0, proteinMinG = 125.0)
        for (d in listOf("2026-11-06", "2026-11-07", "2026-11-11")) assertEquals(d, updated, goalOn(d))
    }

    @Test
    fun `pendant qu il court, un autre objectif aux calories differentes est refuse - le retour ne l ecrasera jamais`() = runTest {
        setUp()
        f.apply(plan)
        // Prévu : un objectif dès aujourd'hui ne vaudrait que jusqu'au premier jour.
        val scheduled = f.reject(SetGoal(base.copy(kcal = 2500.0), allowPast = false))
        assertTrue(scheduled.message, scheduled.message.contains("annule-le d'abord"))
        // En cours : idem, et rien ne change.
        f.clock.setLocal("2026-11-08T10:00")
        assertTrue(f.reject(SetGoal(base.copy(kcal = 2500.0), allowPast = false)) is Rejection.Invalid)
        assertEquals(lowered, goalOn("2026-11-08"))
        assertEquals(base, goalOn("2026-11-11"))
        // Un objectif qui commence au retour ou après reste permis.
        f.apply(SetGoal(base.copy(kcal = 2500.0), from = day("2026-11-12"), allowPast = false))
        // Annulé : l'objectif se change de nouveau librement.
        f.apply(Undo(status().head))
        f.apply(SetGoal(base.copy(kcal = 2500.0), allowPast = false))
        assertEquals(2500.0, goalOn("2026-11-09")!!.kcal, 0.0)
    }

    @Test
    fun `jamais deux a la fois - ni meme premier jour plus court, ni chevauchement`() = runTest {
        setUp()
        f.apply(plan)
        assertTrue(f.reject(plan.copy(days = 2, dropKcal = 200.0)) is Rejection.Invalid)
        assertTrue(f.reject(plan.copy(start = day("2026-11-08"), days = 2)) is Rejection.Invalid)
        assertEquals(lowered, goalOn("2026-11-07"))
    }

    @Test
    fun `retablir refait les controles - un objectif choisi apres l annulation n est jamais ecrase`() = runTest {
        setUp()
        f.apply(plan)
        f.apply(Undo(status().head))
        f.apply(SetGoal(base.copy(kcal = 2500.0), allowPast = false))
        assertFalse(status().canToggle)
        assertTrue(f.reject(Undo(status().head)) is Rejection.UndoConflict)
        assertEquals(2500.0, goalOn("2026-11-08")!!.kcal, 0.0)
        assertEquals(2500.0, goalOn("2026-11-12")!!.kcal, 0.0)
    }

    @Test
    fun `le bouton applique ce que la carte affiche - objectif change depuis, refuse`() = runTest {
        setUp()
        val stale = f.reject(plan.copy(expectedBaseKcal = 2000.0))
        assertTrue(stale.message, stale.message.contains("a changé"))
        f.apply(plan.copy(expectedBaseKcal = 2300.0))
        assertEquals(lowered, goalOn("2026-11-08"))
    }

    @Test
    fun `sans objectif temporaire a cette date - aucun statut`() = runTest {
        setUp()
        assertNull(f.engine.temporaryGoal(day("2026-11-07")))
        f.apply(SetGoal(base.copy(kcal = 2250.0), from = day("2026-11-07"), allowPast = false))
        assertNull(f.engine.temporaryGoal(day("2026-11-07")))
    }
}
