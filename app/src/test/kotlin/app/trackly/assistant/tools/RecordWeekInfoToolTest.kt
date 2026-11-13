package app.trackly.assistant.tools

import app.trackly.core.engine.TracklyEngine
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import app.trackly.review.InMemoryReportRepository
import app.trackly.review.InMemoryReviewHistory
import app.trackly.review.ReviewPeriods
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Séances d'une longue période (ECR-06) : la limite suit la durée de la période. Données fictives. */
class RecordWeekInfoToolTest {
    private val now: Instant = LocalDateTime.parse("2026-11-05T20:00").atZone(TrackingDay.ZONE).toInstant()
    private val engine = TracklyEngine(InMemoryStore(), Clock.fixed(now, TrackingDay.ZONE))
    private val reports = InMemoryReportRepository()
    private val periods = ReviewPeriods(reports, InMemoryReviewHistory())

    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    private suspend fun record(sessions: Int) = RecordWeekInfoTool(periods).execute(
        args("""{"weigh_ins":[{"date":"2026-11-05","kg":70.0}],"steps_average":15000,"sessions":[{"type":"musculation","count":$sessions}]}"""),
        ToolContext(engine, now),
    )

    @Test
    fun `ECR-06 - six semaines, 24 seances - tout est enregistre, pesee et pas compris`() = runBlocking {
        // Dernier bilan fini le 24 septembre : la période va du 25 septembre au 5 novembre, 42 jours.
        reports.save(LocalDate.parse("2026-09-18"), now.minusSeconds(3_600L * 24 * 42), "{}", LocalDate.parse("2026-09-24"))
        assertEquals(42, periods.at(now).days.size)

        val result = record(24)

        assertEquals(result.output.toString(), "recorded", result.output["status"]!!.jsonPrimitive.content)
        val info = engine.weekInfo(LocalDate.parse("2026-09-25"))!!
        assertEquals(24, info.sessions!!.single().count)
        assertEquals(70.0, info.weighIns.single().kg, 0.0)
        assertEquals(15_000, info.stepsAverage)
    }

    @Test
    fun `ECR-06 - une semaine - plus de trois seances par jour reste refuse`() = runBlocking {
        reports.save(LocalDate.parse("2026-10-23"), now.minusSeconds(3_600L * 24 * 7), "{}", LocalDate.parse("2026-10-29"))
        assertEquals(7, periods.at(now).days.size)

        val result = record(22)

        assertEquals("error", result.output["status"]!!.jsonPrimitive.content)
        assertTrue(result.output.toString(), result.output["error"]!!.jsonPrimitive.content.contains("22"))
        assertNull(engine.weekInfo(LocalDate.parse("2026-10-30")))
    }
}
