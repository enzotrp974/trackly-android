package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.Usage
import app.trackly.assistant.turn.UsageEvent
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.db.UsageDao
import app.trackly.storage.db.UsageEventEntity
import app.trackly.storage.db.UsageTotalsRow
import app.trackly.usage.MonthCost
import app.trackly.usage.RoomUsageLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.YearMonth

/** Compteur de coût persistant : lignes enregistrées, total par mois civil (Europe/Zurich), échecs ignorés. */
@RunWith(AndroidJUnit4::class)
class UsageLedgerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "usage-test.db"
    private var db = open()
    private var ledger = RoomUsageLedger(db.usage())

    private fun open() = Room.databaseBuilder(context, TracklyDatabase::class.java, name).allowMainThreadQueries().build()

    @After
    fun cleanUp() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun usage(input: Long = 10_000, cached: Long = 0, written: Long = 0, output: Long = 2_000, reasoning: Long = 1_500) = Usage.parse(
        Json.parseToJsonElement(
            """{"input_tokens":$input,"input_tokens_details":{"cached_tokens":$cached,"cache_write_tokens":$written},
               "output_tokens":$output,"output_tokens_details":{"reasoning_tokens":$reasoning},"total_tokens":${input + output}}""",
        ).jsonObject,
    )

    private fun event(at: String, round: Int = 0, searches: Int? = null, effort: ReasoningEffort = ReasoningEffort.High, model: String = "gpt-6.1-sol") =
        UsageEvent(model, effort, usage(), searches, Instant.parse(at), round)

    private fun month(year: Int, month: Int): MonthCost = runBlocking { ledger.observeMonth(YearMonth.of(year, month)).first() }

    @Test
    fun `chaque champ de l usage est enregistre tel quel, avec le cout du jour`() = runBlocking {
        ledger.record(
            UsageEvent("gpt-6.1-sol", ReasoningEffort.XHigh, usage(input = 12_000, cached = 4_000, written = 3_000, output = 500, reasoning = 300), 2, Instant.parse("2026-11-02T15:10:00Z"), 1),
        )

        val row = db.usage().all().single()
        assertEquals(Instant.parse("2026-11-02T15:10:00Z").toEpochMilli(), row.recordedAt)
        assertEquals("text", row.kind)
        assertEquals("gpt-6.1-sol", row.model)
        assertEquals("xhigh", row.effort)
        assertEquals(1, row.toolRound)
        assertEquals(12_000L, row.inputTokens)
        assertEquals(4_000L, row.cachedTokens)
        assertEquals(3_000L, row.cacheWriteTokens)
        assertEquals(500L, row.outputTokens)
        assertEquals(300L, row.reasoningTokens)
        assertEquals(2, row.webSearches)
        // 5 000 ordinaires x 2 + 4 000 lus x 0,10 + 3 000 écrits x 2,50 + 500 en sortie x 10, par million
        assertEquals(10_000L + 400L + 7_500L + 5_000L, row.textMicros)
        assertEquals(20_000L, row.searchMicros)
        assertEquals(0L, row.transcriptionMicros)
    }

    @Test
    fun `les lignes survivent a la fermeture et a la reouverture de la base`() = runBlocking {
        ledger.record(event("2026-11-02T15:10:00Z"))
        ledger.recordTranscription("gpt-transcribe", 90_000, Instant.parse("2026-11-02T15:20:00Z"))
        db.close()

        db = open()
        ledger = RoomUsageLedger(db.usage())
        val cost = month(2026, 11)
        assertEquals(40_000L, cost.text)
        assertEquals(6_750L, cost.transcription)
        assertEquals(1, cost.exchanges)
        assertEquals(1, cost.dictations)
    }

    @Test
    fun `total du mois - repartition texte, recherche et transcription`() {
        runBlocking {
            ledger.record(event("2026-11-03T08:00:00Z", searches = 2))
            ledger.record(event("2026-11-04T08:00:00Z", searches = null))
            ledger.recordTranscription("gpt-live-transcribe", 120_000, Instant.parse("2026-11-04T09:00:00Z"))
        }

        val cost = month(2026, 11)
        assertEquals(80_000L, cost.text)
        assertEquals(20_000L, cost.search)
        assertEquals(34_000L, cost.transcription)
        assertEquals(134_000L, cost.total)
        assertEquals(2, cost.exchanges)
        assertEquals(2, cost.requests)
        assertEquals(1, cost.dictations)
    }

    @Test
    fun `un message avec appels d outils compte pour un seul echange`() {
        runBlocking {
            ledger.record(event("2026-11-03T08:00:00Z", round = 0))
            ledger.record(event("2026-11-03T08:00:05Z", round = 1))
            ledger.record(event("2026-11-03T08:00:09Z", round = 2))
            ledger.record(event("2026-11-03T09:00:00Z", round = 0))
        }
        val cost = month(2026, 11)
        assertEquals(2, cost.exchanges)
        assertEquals(4, cost.requests)
    }

    @Test
    fun `le mois civil suit le fuseau local - minuit local, pas minuit UTC`() {
        runBlocking {
            // Heure d'hiver (UTC+1) : minuit local = 23:00 UTC la veille.
            ledger.record(event("2026-10-31T22:59:59Z")) // 23:59:59 le 31 octobre → octobre
            ledger.record(event("2026-10-31T23:00:00Z")) // 00:00:00 le 1er novembre → novembre
            ledger.record(event("2026-11-30T22:59:59Z")) // 23:59:59 le 30 novembre → novembre
            ledger.record(event("2026-11-30T23:00:00Z")) // 00:00:00 le 1er décembre → décembre
        }
        assertEquals(1, month(2026, 10).exchanges)
        assertEquals(2, month(2026, 11).exchanges)
        assertEquals(1, month(2026, 12).exchanges)
        assertEquals(0, month(2026, 9).exchanges)
    }

    @Test
    fun `changement de mois en heure d ete - decalage de deux heures`() {
        runBlocking {
            ledger.record(event("2026-06-30T21:59:59Z")) // 23:59:59 le 30 juin (UTC+2) → juin
            ledger.record(event("2026-06-30T22:00:00Z")) // 00:00:00 le 1er juillet → juillet
        }
        assertEquals(1, month(2026, 6).exchanges)
        assertEquals(1, month(2026, 7).exchanges)
    }

    @Test
    fun `le mois en cours est celui du fuseau local`() {
        assertEquals(YearMonth.of(2026, 11), ledger.monthOf(Instant.parse("2026-10-31T23:30:00Z")))
        assertEquals(YearMonth.of(2026, 10), ledger.monthOf(Instant.parse("2026-10-31T22:30:00Z")))
    }

    @Test
    fun `un mois sans usage vaut zero`() {
        val cost = month(2026, 11)
        assertEquals(0L, cost.total)
        assertEquals(0, cost.exchanges)
    }

    @Test
    fun `modele sans tarif connu - ligne gardee et signalee`() {
        runBlocking { ledger.record(event("2026-11-03T08:00:00Z", model = "gpt-9")) }
        val cost = month(2026, 11)
        assertEquals(0L, cost.text)
        assertEquals(1, cost.unpriced)
        assertEquals(1, cost.exchanges)
    }

    @Test
    fun `la transcription n a pas de niveau de raisonnement`() = runBlocking {
        ledger.recordTranscription("gpt-transcribe", 30_000, Instant.parse("2026-11-03T08:00:00Z"))
        val row = db.usage().all().single()
        assertEquals("transcription", row.kind)
        assertNull(row.effort)
        assertEquals(30_000L, row.audioMs)
        assertEquals(2_250L, row.transcriptionMicros)
    }

    @Test
    fun `un echec d ecriture du compteur ne fait pas echouer le message`() = runBlocking {
        val broken = RoomUsageLedger(object : UsageDao {
            override suspend fun insert(event: UsageEventEntity): Long = error("disque plein")
            override fun observeTotals(from: Long, to: Long): Flow<UsageTotalsRow> = error("inutilisé")
            override suspend fun all(): List<UsageEventEntity> = emptyList()
        })
        broken.record(event("2026-11-03T08:00:00Z"))
        broken.recordTranscription("gpt-transcribe", 1_000, Instant.parse("2026-11-03T08:00:00Z"))
        assertFalse(db.usage().all().isNotEmpty())
    }

    @Test
    fun `un appel vocal compte a part de la dictee`() = runBlocking {
        ledger.recordTranscription("gpt-live-transcribe", 60_000, Instant.parse("2026-11-06T10:00:00Z"))
        ledger.recordLiveCall("gpt-live-1", 120_000, Instant.parse("2026-11-06T11:00:00Z"))
        ledger.recordLiveCall("gpt-live-1", 3_000, Instant.parse("2026-11-06T12:00:00Z"))
        val month = ledger.observeMonth(YearMonth.of(2026, 11)).first()
        assertEquals(17_000L, month.transcription)
        // 2 min = 0,10 $ ; 3 s comptées comme 15 s = 0,0125 $.
        assertEquals(112_500L, month.live)
        assertEquals(2, month.calls)
        assertEquals(1, month.dictations)
        assertEquals(129_500L, month.total)
        assertEquals("live", db.usage().all().last().kind)
    }
}
