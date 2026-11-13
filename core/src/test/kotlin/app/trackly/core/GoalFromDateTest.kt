package app.trackly.core

import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Action « objectif à partir d'une date » : suites d'actions sur plusieurs jours, avant/après, annulation, historique. */
class GoalFromDateTest {
    private val f = Fixture()
    private val base = GoalTargets(kcal = 2600.0, proteinG = 140.0, carbsG = 352.0, fatG = 70.0)
    private val lower = GoalTargets(kcal = 2300.0, proteinG = 150.0, carbsG = 270.0, fatG = 70.0)
    private val lowest = GoalTargets(kcal = 2100.0, proteinG = 150.0, carbsG = 220.0, fatG = 70.0)

    @Test
    fun `a partir d aujourd hui, la journee entiere change et les jours passes gardent leur objectif`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.add("Petit-déjeuner", 600.0, Moment.Matin) // saisi avant le changement, le même jour
        assertEquals(2000.0, f.summary().remainingKcal!!, 0.0)

        f.apply(SetGoal(lower, allowPast = false)) // 2 novembre, 12 h 30

        assertEquals(base, f.summary("2026-11-01").goal)
        assertEquals(lower, f.summary("2026-11-02").goal)
        // La journée entière est concernée : le repas du matin est déjà compté contre le nouvel objectif.
        assertEquals(1700.0, f.summary("2026-11-02").remainingKcal!!, 0.0)
    }

    @Test
    fun `avant 5 h, aujourd hui est encore la veille et demain est le jour calendaire`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.clock.setLocal("2026-11-03T01:30") // jour de suivi : le 2 novembre

        val now = f.apply(SetGoal(lower, allowPast = false)).goalChanges.single()
        assertEquals(day("2026-11-02"), now.after.effectiveFrom)
        assertEquals(base, now.inForceBefore)

        val tomorrow = f.apply(SetGoal(lowest, from = day("2026-11-03"), allowPast = false)).goalChanges.single()
        assertEquals(day("2026-11-03"), tomorrow.after.effectiveFrom)
        assertEquals(lower, tomorrow.inForceBefore)
        assertEquals(lower, f.summary("2026-11-02").goal)
        assertEquals(lowest, f.summary("2026-11-03").goal)
    }

    @Test
    fun `suite d actions sur plusieurs jours - chaque jour garde l objectif de son epoque`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.clock.setLocal("2026-11-02T12:30")
        f.apply(SetGoal(lower, allowPast = false))
        f.clock.setLocal("2026-11-05T09:00")
        f.apply(SetGoal(lowest, allowPast = false))

        assertEquals(base, f.summary("2026-11-01").goal)
        assertEquals(lower, f.summary("2026-11-02").goal)
        assertEquals(lower, f.summary("2026-11-04").goal)
        assertEquals(lowest, f.summary("2026-11-05").goal)
        assertEquals(lowest, f.summary("2026-11-30").goal)
    }

    @Test
    fun `le recapitulatif garde l objectif d avant, meme s il vient d une date plus ancienne`() = runTest {
        assertNull(f.apply(SetGoal(base, from = day("2026-10-01"))).goalChanges.single().inForceBefore)

        val change = f.apply(SetGoal(lower, allowPast = false)).goalChanges.single()
        assertEquals(base, change.inForceBefore) // l'objectif du 1er octobre, pas de version du 2 novembre
        assertNull(change.before)
        assertEquals(lower, change.after.targets)
    }

    @Test
    fun `corriger le meme jour ecrit une version suivante, l ancienne reste`() = runTest {
        f.apply(SetGoal(lower, allowPast = false))
        val change = f.apply(SetGoal(lowest, allowPast = false)).goalChanges.single()
        assertEquals(lower, change.inForceBefore)
        assertEquals(1, change.before!!.version)
        assertEquals(2, change.after.version)
        assertEquals(lower, f.store.transaction { goals.version(day("2026-11-02"), 1)!!.targets })
    }

    @Test
    fun `annuler rend l objectif d avant, a la date de depart seulement`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        val change = f.apply(SetGoal(lower, allowPast = false))
        f.apply(SetGoal(lowest, from = day("2026-11-10"), allowPast = false))

        // Annuler l'objectif du jour alors qu'un plus récent existe sur une autre date : permis, pas de conflit.
        f.apply(Undo(change.id))
        assertEquals(base, f.summary("2026-11-02").goal)
        assertEquals(base, f.summary("2026-11-09").goal)
        assertEquals(lowest, f.summary("2026-11-10").goal)
        assertEquals(listOf(day("2026-10-01"), day("2026-11-10")), f.engine.goals().map { it.effectiveFrom })
    }

    @Test
    fun `annuler puis retablir`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(lower, allowPast = false))
        val undo = f.apply(UndoLast)
        assertEquals(base, f.summary().goal)
        f.apply(Undo(undo.id)) // rétablir : annuler l'annulation
        assertEquals(lower, f.summary().goal)
    }

    @Test
    fun `l historique reste intact - aucune version n est ecrasee ni supprimee`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(lower, allowPast = false))
        f.apply(SetGoal(lowest, allowPast = false))
        f.apply(UndoLast) // l'objectif du jour redevient `lower`, en version 3

        val versions = f.store.transaction { listOf(1, 2, 3).map { goals.version(day("2026-11-02"), it) } }
        assertEquals(listOf(lower, lowest, lower), versions.map { it!!.targets })
        assertEquals(base, f.store.transaction { goals.version(day("2026-10-01"), 1)!!.targets })
        assertEquals(2, f.store.observeGoals().first().size) // une version courante par date
    }

    @Test
    fun `une date passee est refusee quand l appelant l interdit, sans rien ecrire`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        val rejection = f.reject(SetGoal(lower, from = day("2026-11-01"), allowPast = false))
        assertTrue(rejection is Rejection.Invalid)
        assertEquals(base, f.summary("2026-11-01").goal)
        assertEquals(1, f.engine.goals().size)
    }

    @Test
    fun `une date tres lointaine est refusee`() = runTest {
        assertTrue(f.reject(SetGoal(lower, from = day("2028-01-01"))) is Rejection.Invalid)
    }

    @Test
    fun `reappliquer l objectif deja en vigueur est refuse, y compris depuis une date plus ancienne`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        assertEquals(Rejection.NoChange, f.reject(SetGoal(base, allowPast = false)))
        assertEquals(1, f.engine.goals().size)
    }

    @Test
    fun `retrouver l objectif d avant malgre un objectif a venir est permis`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(lowest, from = day("2026-11-10")))
        f.apply(SetGoal(base, allowPast = false)) // même chiffres qu'aujourd'hui, mais un changement est déjà prévu après
        assertEquals(3, f.engine.goals().size)
    }

    @Test
    fun `la chronologie ignore les dates retirees`() = runTest {
        f.apply(SetGoal(base, from = day("2026-10-01")))
        f.apply(SetGoal(lower, allowPast = false))
        f.apply(UndoLast)
        val goals = f.store.observeGoals().first()
        assertEquals(listOf(day("2026-10-01")), GoalTimeline.effective(goals).map { it.effectiveFrom })
        assertEquals(base, GoalTimeline.inForce(day("2026-12-01"), goals)!!.targets)
        assertNull(GoalTimeline.inForce(day("2026-09-30"), goals))
    }
}
