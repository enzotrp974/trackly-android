package app.trackly.review

import app.trackly.core.review.WeekRange
import app.trackly.core.time.TrackingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Le bilan est « à faire » selon la règle de l'invitation du lundi : jour, heure, bilan déjà produit. Il couvre
 * du lendemain du dernier bilan à aujourd'hui, et un bilan fait dimanche ou plus tard dispense du rappel du lundi.
 */
class ReviewDueTest {
    private val zone = TrackingDay.ZONE
    private val settings = ReviewSettingsState(inviteTime = LocalTime.of(9, 0))

    private fun day(iso: String) = LocalDate.parse(iso)

    private fun at(local: String): Instant = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    /** Un bilan produit, dont la période va de [start] à [end] (sans [end] : une semaine du lundi au dimanche). */
    private fun report(start: String, end: String? = null) = ReportInfo(1, day(start), Instant.EPOCH, end?.let(::day) ?: day(start).plusDays(6))

    private fun due(local: String, state: ReviewSettingsState = settings, last: ReportInfo? = report("2026-10-19")): WeekRange? =
        ReviewDue.period(at(local), zone, state, last)

    @Test
    fun `pas avant l heure de l invitation du lundi`() {
        // Le bilan de la semaine du 19 octobre est fait (jusqu'au 25) : la semaine du 26 attend l'invitation du lundi 2, à 9 h.
        assertNull(due("2026-11-02T08:59"))
        assertNull(due("2026-11-02T00:10"))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), due("2026-11-02T09:00"))
    }

    @Test
    fun `des l invitation du lundi et tous les jours suivants jusqu au rapport, la periode grandit jusqu a aujourd hui`() {
        // Dernier bilan : semaine du 19 au 25 octobre. Il manque la semaine du 26 octobre.
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), due("2026-11-02T09:00"))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), due("2026-11-02T23:30"))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-04")), due("2026-11-04T12:00"))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-08")), due("2026-11-08T22:00"))
    }

    @Test
    fun `la semaine d avant, non faite, reste a faire le mardi - sans delai maximal`() {
        // Dernier bilan : semaine du 12 au 18 octobre. La semaine du 19 n'a jamais été faite : la période la contient aussi.
        assertEquals(WeekRange(day("2026-10-19"), day("2026-10-27")), due("2026-10-27T10:00", last = report("2026-10-12")))
    }

    @Test
    fun `disparait une fois le rapport produit`() {
        // Bilan produit le mercredi 4 novembre sur toute la période.
        val done = report("2026-10-26", "2026-11-04")
        assertNull(due("2026-11-04T12:00", last = done))
        assertNull(due("2026-11-08T22:00", last = done))
        // Le lundi suivant, ce bilan couvre le dimanche : pas de rappel.
        assertNull(due("2026-11-09T09:30", last = report("2026-10-26", "2026-11-08")))
        // Mais un bilan qui s'arrête le samedi laisse le dimanche et le lundi à faire.
        assertEquals(WeekRange(day("2026-11-08"), day("2026-11-09")), due("2026-11-09T09:30", last = report("2026-10-26", "2026-11-07")))
    }

    @Test
    fun `un bilan fait le dimanche soir dispense du rappel du lundi`() {
        assertNull(due("2026-11-09T12:00", last = report("2026-11-02", "2026-11-08")))
    }

    @Test
    fun `sans aucun bilan, le premier part du lundi qui precede de six jours ou plus, des l invitation`() {
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), due("2026-11-02T09:00", last = null))
    }

    @Test
    fun `heure d invitation reglable, rappels coupes - rien`() {
        val late = ReviewSettingsState(inviteTime = LocalTime.of(17, 30))
        assertNull(due("2026-11-02T12:00", late))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), due("2026-11-02T17:30", late))
        assertNull(due("2026-11-04T12:00", ReviewSettingsState(enabled = false)))
    }
}
