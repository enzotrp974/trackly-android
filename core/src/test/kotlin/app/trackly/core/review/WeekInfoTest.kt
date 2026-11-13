package app.trackly.core.review

import app.trackly.core.Fixture
import app.trackly.core.action.Rejection
import app.trackly.core.action.UndoLast
import app.trackly.core.day
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Informations déclarées de la semaine (bilan du lundi) : fusion, refus, annulation. Moteur pur. */
class WeekInfoTest {
    // Lundi 2 novembre 2026 : la période du bilan commence le 26 octobre (le lendemain du dernier bilan).
    private val week = day("2026-10-26")
    private val f = Fixture("2026-11-02T09:30")

    @Test
    fun `les pesees dites sont enregistrees telles quelles, rien n est invente`() = runBlocking {
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4), WeighIn(day("2026-10-30"), 72.1))))
        val info = f.engine.weekInfo(week)!!
        assertEquals(listOf(72.4, 72.1), info.weighIns.map { it.kg })
        assertTrue(info.steps.isEmpty())
        assertNull(info.stepsAverage)
        assertNull(info.sessions) // non fourni, pas « zéro »
    }

    @Test
    fun `une declaration en complete une autre, la meme date remplace`() = runBlocking {
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4))))
        f.apply(
            DeclareWeekInfo(
                week,
                weighIns = listOf(WeighIn(day("2026-10-27"), 72.5), WeighIn(day("2026-10-29"), 72.2)),
                steps = listOf(DaySteps(day("2026-10-26"), 19_500), DaySteps(day("2026-10-27"), 21_000)),
                sessions = listOf(SessionCount("Musculation", 2)),
            ),
        )
        f.apply(DeclareWeekInfo(week, sessions = listOf(SessionCount("course", 1), SessionCount("musculation", 3))))
        val info = f.engine.weekInfo(week)!!
        assertEquals(3, info.version)
        assertEquals(listOf(72.5, 72.2), info.weighIns.map { it.kg })
        assertEquals(listOf(19_500, 21_000), info.steps.map { it.steps })
        assertEquals(listOf("course" to 1, "musculation" to 3), info.sessions!!.map { it.type to it.count })
    }

    @Test
    fun `aucune seance - declaree explicitement, differente de non fournie`() = runBlocking {
        f.apply(DeclareWeekInfo(week, stepsAverage = 18_000, sessions = emptyList()))
        val info = f.engine.weekInfo(week)!!
        assertEquals(emptyList<SessionCount>(), info.sessions)
        assertEquals(18_000, info.stepsAverage)
    }

    @Test
    fun `aucune seance apres des seances enregistrees - remise a zero annulable, pas de Rien a changer`() = runBlocking {
        f.apply(DeclareWeekInfo(week, sessions = listOf(SessionCount("musculation", 3), SessionCount("course", 1))))
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4))))

        f.apply(DeclareWeekInfo(week, sessions = emptyList()))

        val info = f.engine.weekInfo(week)!!
        assertEquals(3, info.version)
        assertEquals(emptyList<SessionCount>(), info.sessions)
        assertEquals(listOf(72.4), info.weighIns.map { it.kg }) // le reste est conservé
        // Dire encore « aucune séance » ne change plus rien.
        assertEquals(Rejection.NoChange, f.reject(DeclareWeekInfo(week, sessions = emptyList())))
        // La remise à zéro s'annule : les séances reviennent.
        f.apply(UndoLast)
        assertEquals(listOf("course" to 1, "musculation" to 3), f.engine.weekInfo(week)!!.sessions!!.map { it.type to it.count })
    }

    @Test
    fun `retirer une pesee mal lue`() = runBlocking {
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4), WeighIn(day("2026-10-28"), 27.4))))
        f.apply(DeclareWeekInfo(week, removeWeighInDays = listOf(day("2026-10-28"))))
        assertEquals(listOf(day("2026-10-27")), f.engine.weekInfo(week)!!.weighIns.map { it.day })
    }

    @Test
    fun `refus - avant la periode, futur, poids absurde, rien a noter, rien a changer`() = runBlocking {
        assertTrue(f.reject(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-25"), 72.0)))) is Rejection.Invalid) // avant le début
        assertTrue(f.reject(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-11-03"), 72.0)))) is Rejection.Invalid) // futur
        assertTrue(f.reject(DeclareWeekInfo(day("2026-11-09"), weighIns = listOf(WeighIn(day("2026-11-09"), 72.0)))) is Rejection.Invalid) // pas commencée
        assertTrue(f.reject(DeclareWeekInfo(week)) is Rejection.Invalid)
        assertTrue(runCatching { WeighIn(day("2026-10-27"), 7.0) }.isFailure)
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4))))
        assertEquals(Rejection.NoChange, f.reject(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4)))))
        assertNull(f.engine.weekInfo(day("2026-10-19")))
    }

    @Test
    fun `annuler rend l etat d avant, puis une nouvelle declaration reprend la numerotation`() = runBlocking {
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4))))
        f.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-28"), 72.0))))
        f.apply(UndoLast)
        assertEquals(listOf(72.4), f.engine.weekInfo(week)!!.weighIns.map { it.kg })
        // Annuler la première déclaration : la semaine redevient vide (version supprimée, jamais effacée).
        val onlyOne = Fixture("2026-11-02T09:30")
        onlyOne.apply(DeclareWeekInfo(week, weighIns = listOf(WeighIn(day("2026-10-27"), 72.4))))
        onlyOne.apply(UndoLast)
        assertNull(onlyOne.engine.weekInfo(week))
        onlyOne.apply(DeclareWeekInfo(week, stepsAverage = 15_000))
        val info = onlyOne.engine.weekInfo(week)!!
        assertEquals(3, info.version)
        assertEquals(15_000, info.stepsAverage)
        assertTrue(info.weighIns.isEmpty())
    }
}
