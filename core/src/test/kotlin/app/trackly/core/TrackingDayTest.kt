package app.trackly.core

import app.trackly.core.action.CorrectConsumption
import app.trackly.core.model.Moment
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Date de rattachement : avant 5 h (heure locale), la veille, sauf date explicite. Les tests fixent le fuseau Europe/Zurich. */
class TrackingDayTest {
    private val f = Fixture()

    @Test
    fun `regle des 5 h a l heure locale`() {
        assertEquals(day("2026-11-01"), TrackingDay.of(zurich("2026-11-02T00:00")))
        assertEquals(day("2026-11-01"), TrackingDay.of(zurich("2026-11-02T04:59:59")))
        assertEquals(day("2026-11-02"), TrackingDay.of(zurich("2026-11-02T05:00")))
        assertEquals(day("2026-11-02"), TrackingDay.of(zurich("2026-11-02T23:59")))
    }

    @Test
    fun `le fuseau local s applique, ete comme hiver`() {
        // 2026-07-02 02:30 UTC = 04:30 heure locale (Europe/Zurich, UTC+2) : encore la veille.
        assertEquals(day("2026-07-01"), TrackingDay.of(java.time.Instant.parse("2026-07-02T02:30:00Z")))
        // 2026-07-02 03:30 UTC = 05:30 heure locale : nouveau jour.
        assertEquals(day("2026-07-02"), TrackingDay.of(java.time.Instant.parse("2026-07-02T03:30:00Z")))
        // Nuit du changement d'heure (25 octobre 2026) : 02:30 vaut deux fois, toujours la veille.
        assertEquals(day("2026-10-24"), TrackingDay.of(java.time.Instant.parse("2026-10-25T01:30:00Z")))
    }

    @Test
    fun `une declaration a 00 h 30 compte pour la veille et garde son heure reelle`() = runTest {
        f.clock.setLocal("2026-11-03T00:30")
        f.add("Snack de minuit", 150.0, Moment.Soir)

        val veille = f.summary("2026-11-02")
        assertEquals(150.0, veille.totals.kcal, 0.0)
        assertEquals(zurich("2026-11-03T00:30"), veille.consumptions.single().consumedAt)
        assertEquals(0.0, f.summary("2026-11-03").totals.kcal, 0.0)
    }

    @Test
    fun `pour hier a midi, la date explicite prime`() = runTest {
        f.clock.setLocal("2026-11-02T12:00")
        f.add("Oubli d'hier", 300.0, explicitDay = day("2026-11-01"))

        assertEquals(300.0, f.summary("2026-11-01").totals.kcal, 0.0)
        assertEquals(zurich("2026-11-02T12:00"), f.summary("2026-11-01").consumptions.single().consumedAt)
    }

    @Test
    fun `une date explicite prime aussi avant 5 h`() = runTest {
        f.clock.setLocal("2026-11-03T01:00")
        f.add("Petit-déjeuner avancé", 400.0, Moment.Matin, explicitDay = day("2026-11-03"))
        assertEquals(400.0, f.summary("2026-11-03").totals.kcal, 0.0)
    }

    @Test
    fun `le jour en cours suit la meme regle`() {
        f.clock.setLocal("2026-11-03T04:00")
        assertEquals(day("2026-11-02"), f.engine.today())
        f.clock.setLocal("2026-11-03T05:00")
        assertEquals(day("2026-11-03"), f.engine.today())
    }

    @Test
    fun `une correction garde l heure reelle d origine`() = runTest {
        f.clock.setLocal("2026-11-02T08:00")
        val id = f.add("Café", 10.0, Moment.Matin)
        f.clock.setLocal("2026-11-02T15:00")
        f.apply(CorrectConsumption(id, food = food("Café au lait", 60.0)))

        assertEquals(zurich("2026-11-02T08:00"), f.engine.history(id).last().consumedAt)
        assertEquals(zurich("2026-11-02T15:00"), f.engine.history(id).last().recordedAt)
    }

    @Test
    fun `proposition de moment selon l heure`() {
        assertEquals(Moment.Matin, Moment.guessFor(java.time.LocalTime.of(7, 30)))
        assertEquals(Moment.Midi, Moment.guessFor(java.time.LocalTime.of(12, 15)))
        assertEquals(Moment.Snack, Moment.guessFor(java.time.LocalTime.of(16, 0)))
        assertEquals(Moment.Soir, Moment.guessFor(java.time.LocalTime.of(19, 30)))
        assertEquals(Moment.Soir, Moment.guessFor(java.time.LocalTime.of(0, 30)))
    }
}
