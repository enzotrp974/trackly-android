package app.trackly.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.tools.ProduceReportTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.core.store.ImportedSource
import app.trackly.core.time.TrackingDay
import app.trackly.review.page.ReferenceStatus
import app.trackly.review.page.ReportContent
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Production du bilan face à un arrêt d'Android : mise à jour du poids de référence et rapport ne se séparent
 * jamais, quel que soit l'instant de l'arrêt. Vraie base Room ; l'arrêt est une erreur que rien ne rattrape (comme un processus
 * tué : aucun code de secours ne tourne). Données fictives.
 */
@RunWith(AndroidJUnit4::class)
class ProduceReportAtomicTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evening: Instant = LocalDateTime.parse("2026-11-06T20:00").atZone(TrackingDay.ZONE).toInstant()
    private val base = GoalTargets(kcal = 2300.0, proteinG = 140.0, carbsG = 277.5, fatG = 69.2, proteinMinG = 112.0)

    /** Le processus est tué : une erreur, jamais rattrapée par un `catch (e: Exception)`. */
    private class ProcessKilled : Error("processus tué")

    /** Horloge qui « tue le processus » à son [killAt]-ième appel, et avance de [tick] à chaque appel. */
    private class KillingClock(var now: Instant) : Clock() {
        var calls = 0
        var killAt = -1
        var tick: Duration = Duration.ZERO
        override fun getZone(): ZoneId = TrackingDay.ZONE
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            calls++
            if (calls == killAt) throw ProcessKilled()
            now = now.plus(tick)
            return now
        }
    }

    private inner class Device(start: Instant = evening) {
        val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
        val clock = KillingClock(start)
        val engine = TracklyEngine(RoomTracklyStore(db), clock)
        val reports = RoomReportRepository(db)
        val notifier = RecordingReviewNotifier()

        fun seed() = runBlocking {
            RoomTracklyStore(db).transaction { imports.insert(ImportedSource("profile", "profile", 1, "sha", PROFILE, ActionId(1))) }
            assertTrue(engine.execute(SetGoal(base, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            val day = TrackingDay.of(clock.now)
            assertTrue(engine.execute(DeclareWeekInfo(LocalDate.parse("2026-10-26"), weighIns = listOf(WeighIn(day.minusDays(1), 66.0)))) is ActionOutcome.Applied)
        }

        suspend fun produce(repo: ReportRepository = reports, at: Instant = clock.now) =
            ProduceReportTool(ReviewPeriods(repo, RoomReviewHistory(db.review())), notifier).execute(buildJsonObject { put("analysis", "Une période régulière.") }, ToolContext(engine, at))

        /** Après l'arrêt, l'app redémarre : un moteur neuf sur la même base, avec une horloge sage. */
        fun restarted() = TracklyEngine(RoomTracklyStore(db), Clock.fixed(clock.now, TrackingDay.ZONE))
    }

    /**
     * Ce qui doit tenir après tout arrêt : au plus un rapport ; sans rapport, ni poids de référence changé ni objectif réécrit,
     * et pas de notification ; avec un rapport, toute mise à jour en vigueur est celle de sa page, avec Annuler.
     */
    private fun assertWhole(d: Device, label: String) = runBlocking {
        val engine = d.restarted()
        val all = d.reports.observeAll().first()
        assertTrue("$label : ${all.size} rapports", all.size <= 1)
        val reference = engine.referenceWeight(TrackingDay.of(d.clock.now))!!
        if (all.isEmpty()) {
            assertEquals("$label : mise à jour sans rapport", 70.0, reference, 0.0)
            assertEquals("$label : objectif réécrit sans rapport", listOf(LocalDate.parse("2026-10-01")), engine.goals().map { it.effectiveFrom })
            assertTrue("$label : notification d'un rapport absent", d.notifier.ready.isEmpty())
        } else if (reference != 70.0) {
            val block = ReportContent.decode(d.reports.get(all.single().id)!!.html)!!.referenceWeight!!
            assertEquals(label, ReferenceStatus.Updated, block.status)
            val action = engine.action(ActionId(block.actionId!!))
            assertTrue(label, action != null && action.undoneBy == null)
            assertEquals(label, reference, block.afterKg, 0.0)
        }
    }

    @Test
    fun `arret entre la mise a jour et l enregistrement du rapport - rien n est garde`() = runBlocking {
        val d = Device()
        d.seed()
        val killedOnSave = object : ReportRepository by d.reports {
            override suspend fun save(weekStart: LocalDate, generatedAt: Instant, html: String, periodEnd: LocalDate): ReportInfo = throw ProcessKilled()
        }
        try {
            d.produce(killedOnSave)
            fail("l'arrêt devait interrompre la production")
        } catch (e: ProcessKilled) {
            // attendu
        }
        assertWhole(d, "arrêt à l'enregistrement")
        assertEquals(70.0, d.restarted().referenceWeight(LocalDate.parse("2026-11-06"))!!, 0.0)
        d.db.close()
    }

    @Test
    fun `rapport qui ne s enregistre pas - la mise a jour est defaite avec lui`() = runBlocking {
        // La transaction défait la mise à jour, sans annulation après coup.
        val d = Device()
        d.seed()
        val failing = object : ReportRepository by d.reports {
            override suspend fun save(weekStart: LocalDate, generatedAt: Instant, html: String, periodEnd: LocalDate): ReportInfo = error("disque plein")
        }
        assertTrue(runCatching { d.produce(failing) }.isFailure)
        assertWhole(d, "disque plein")
        assertEquals(70.0, d.restarted().referenceWeight(LocalDate.parse("2026-11-06"))!!, 0.0)
        assertTrue("aucune action annulée après coup", d.restarted().observeRecentActions(20).first().none { it.type == app.trackly.core.action.Undo.TYPE })
        d.db.close()
    }

    @Test
    fun `arret a n importe quel instant de la production - jamais un etat bancal`() {
        var k = 1
        while (true) {
            val d = Device()
            d.seed()
            d.clock.calls = 0
            d.clock.killAt = k
            val killed = runBlocking {
                try {
                    d.produce(at = evening)
                    false
                } catch (e: ProcessKilled) {
                    true
                }
            }
            d.clock.killAt = -1
            assertWhole(d, "arrêt au ${k}e appel d'horloge")
            d.db.close()
            if (!killed) break
            k++
            if (k > 500) fail("la production ne finit jamais")
        }
        assertTrue("au moins un arrêt simulé pendant la production", k > 1)
    }

    @Test
    fun `production qui passe 5 h en route - un seul rapport, jamais de mise a jour orpheline`() {
        // L'horloge avance d'une seconde à chaque lecture : selon le départ, 5 h tombe à un endroit différent de la production,
        // y compris entre le jour choisi pour la mise à jour et son exécution, qui la refuse alors (« les jours passés gardent
        // leur objectif »). Ce refus a lieu dans la transaction du rapport : le rapport doit tout de même être enregistré.
        val statuses = mutableSetOf<ReferenceStatus>()
        for (before in 1L..40L) {
            val start = LocalDateTime.parse("2026-11-07T05:00").atZone(TrackingDay.ZONE).toInstant().minusSeconds(before)
            val d = Device(start)
            d.seed()
            d.clock.tick = Duration.ofSeconds(1)
            val result = runBlocking { d.produce(at = start) }
            d.clock.tick = Duration.ZERO
            assertEquals("départ $before s avant 5 h : ${result.output}", "report_ready", result.output["status"]?.toString()?.trim('"'))
            assertWhole(d, "départ $before s avant 5 h")
            runBlocking {
                val all = d.reports.observeAll().first()
                assertEquals("départ $before s avant 5 h", 1, all.size)
                ReportContent.decode(d.reports.get(all.single().id)!!.html)!!.referenceWeight?.let { statuses += it.status }
            }
            d.db.close()
        }
        assertTrue("le refus a bien été rencontré : $statuses", ReferenceStatus.NotUpdated in statuses)
        assertTrue("la mise à jour aussi : $statuses", ReferenceStatus.Updated in statuses)
    }

    @Test
    fun `production normale sur Room - rapport et mise a jour ensemble, notification apres`() = runBlocking {
        val d = Device()
        d.seed()
        d.produce()
        assertWhole(d, "production normale")
        assertEquals(1, d.notifier.ready.size)
        assertEquals(66.0, d.restarted().referenceWeight(LocalDate.parse("2026-11-06"))!!, 0.0)
        d.db.close()
    }

    private companion object {
        const val PROFILE = """{"current_reference_weight_kg":70.0,"goals":[{"effective_from":"2026-09-01","kcal":2300,"protein_ideal_g_per_kg":2.0,"protein_min_g_per_kg":1.6,"fat_g_per_kg":1.0}]}"""
    }
}
