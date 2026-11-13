package app.trackly.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.review.WeekRange
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Période du bilan : du lendemain du dernier bilan jusqu'à aujourd'hui. Le bilan s'ouvre à tout moment : la
 * période grandit chaque jour jusqu'à ce qu'un rapport soit produit.
 */
@RunWith(AndroidJUnit4::class)
class ReviewPeriodsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()

    @After
    fun close() {
        db.close()
    }

    private fun day(iso: String) = LocalDate.parse(iso)

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(app.trackly.core.time.TrackingDay.ZONE).toInstant()

    private fun report(start: String, end: String?, id: Long = 1) =
        ReportInfo(id, day(start), Instant.EPOCH, end?.let(::day) ?: day(start).plusDays(6))

    @Test
    fun `apres un ancien bilan de semaine, la periode commence le lendemain de son dimanche et va jusqu a aujourd hui`() {
        val legacy = report("2026-10-19", null) // semaine du 19 au 25 octobre

        val period = ReviewPeriods.of(at("2026-11-04T10:00"), legacy)

        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-04")), period)
    }

    @Test
    fun `apres un bilan sur une periode libre, la periode commence le lendemain de sa fin`() {
        val last = report("2026-10-26", "2026-11-03")

        assertEquals(WeekRange(day("2026-11-04"), day("2026-11-06")), ReviewPeriods.of(at("2026-11-06T18:00"), last))
    }

    @Test
    fun `le bilan est ouvert tout de suite apres le dernier - il est le lendemain de la fin, d un seul jour`() {
        val last = report("2026-10-26", "2026-11-03")

        assertEquals(WeekRange(day("2026-11-04"), day("2026-11-04")), ReviewPeriods.of(at("2026-11-04T08:00"), last))
    }

    @Test
    fun `rouvrir le bilan le jour ou il vient d etre produit reprend la meme periode`() {
        // Le dernier rapport finit aujourd'hui : demain commence la suivante, mais aujourd'hui on reste sur la même.
        val last = report("2026-10-26", "2026-11-06")

        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-06")), ReviewPeriods.of(at("2026-11-06T21:00"), last))
    }

    @Test
    fun `sans bilan precedent, le debut ne glisse pas d un jour a l autre - le fil et les informations restent visibles`() {
        // Du dimanche 1er au samedi 7 novembre : toujours le lundi 26 octobre. Il ne change que le dimanche 8.
        for (iso in listOf("2026-11-01", "2026-11-02", "2026-11-04", "2026-11-07")) {
            assertEquals(iso, day("2026-10-26"), ReviewPeriods.of(at("${iso}T12:00"), null).start)
        }
        assertEquals(day("2026-11-02"), ReviewPeriods.of(at("2026-11-08T12:00"), null).start)
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-06")), ReviewPeriods.of(at("2026-11-06T12:00"), null))
    }

    @Test
    fun `avant 5 h, aujourd hui est encore la veille - comme pour les repas`() {
        val last = report("2026-10-26", "2026-11-03")

        assertEquals(WeekRange(day("2026-11-04"), day("2026-11-05")), ReviewPeriods.of(at("2026-11-06T03:30"), last))
    }

    @Test
    fun `le bilan qui finit le plus tard fait foi, meme s il a ete produit avant un autre`() = runBlocking {
        val reports = RoomReportRepository(db)
        reports.save(day("2026-10-26"), Instant.parse("2026-11-04T10:00:00Z"), "<p>fin le 3</p>", day("2026-11-03"))
        reports.save(day("2026-10-19"), Instant.parse("2026-11-05T10:00:00Z"), "<p>vieux, produit en retard</p>")

        assertEquals(day("2026-11-03"), reports.latest()!!.periodEnd)
        assertEquals(WeekRange(day("2026-11-04"), day("2026-11-06")), ReviewPeriods(reports, RoomReviewHistory(db.review())).at(at("2026-11-06T12:00")))
    }

    @Test
    fun `un ancien bilan sans fin de periode se relit comme une semaine du lundi au dimanche`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO weekly_reports (id, week_start, generated_at, html) VALUES (1, '2026-10-26', 1793620200000, '<html>ancien</html>')",
        )
        val reports = RoomReportRepository(db)

        val info = reports.observeAll().first().single()

        assertEquals(day("2026-11-01"), info.periodEnd)
        assertEquals(WeekRange(day("2026-10-26")), info.period)
        assertEquals("<html>ancien</html>", reports.get(1)!!.html) // le contenu reste lisible
    }

    @Test
    fun `un nouveau bilan garde sa fin de periode`() = runBlocking {
        val reports = RoomReportRepository(db)

        val saved = reports.save(day("2026-11-02"), Instant.parse("2026-11-06T10:00:00Z"), "<p>x</p>", day("2026-11-06"))

        assertEquals(day("2026-11-06"), saved.periodEnd)
        assertEquals(day("2026-11-06"), reports.get(saved.id)!!.info.periodEnd)
        assertEquals(day("2026-11-06"), reports.latest()!!.periodEnd)
    }

    @Test
    fun `premier bilan commence un samedi, laisse en attente - le dimanche il reprend le meme fil et les memes informations`() = runBlocking {
        val reports = RoomReportRepository(db)
        val history = RoomReviewHistory(db.review())
        val periods = ReviewPeriods(reports, history)
        // Samedi 7 : premier bilan ouvert, rangé sous le lundi 26 octobre.
        val saturday = periods.at(at("2026-11-07T18:00"))
        assertEquals(day("2026-10-26"), saturday.start)
        history.append(saturday.start, at("2026-11-07T18:00"), app.trackly.assistant.chat.ChatRole.User, "Samedi 71,8 kg")

        // Dimanche 8, puis la semaine suivante : toujours le même début, tant qu'aucun rapport n'est produit.
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-08")), periods.at(at("2026-11-08T12:00")))
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-16")), periods.at(at("2026-11-16T12:00")))
        assertEquals(1, history.last(periods.at(at("2026-11-08T12:00")).start, 10).size)
    }

    @Test
    fun `sans fil commence, le debut reste celui d avant, et un rapport fait toujours foi`() = runBlocking {
        val reports = RoomReportRepository(db)
        val history = RoomReviewHistory(db.review())
        val periods = ReviewPeriods(reports, history)
        assertNull(history.latestStart())
        assertEquals(day("2026-11-02"), periods.at(at("2026-11-08T12:00")).start)

        history.append(day("2026-10-26"), at("2026-11-07T18:00"), app.trackly.assistant.chat.ChatRole.User, "Samedi 71,8 kg")
        reports.save(day("2026-10-26"), at("2026-11-07T19:00"), "{}", day("2026-11-07"))
        assertEquals(WeekRange(day("2026-11-08"), day("2026-11-08")), periods.at(at("2026-11-08T12:00")))
    }

    @Test
    fun `sans rapport, il n y a pas de dernier bilan`() = runBlocking {
        assertNull(RoomReportRepository(db).latest())
        assertNull(InMemoryReportRepository().latest())
    }
}
