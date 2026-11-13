package app.trackly.review

import androidx.room.withTransaction
import app.trackly.core.review.WeekRange
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.db.WeeklyReportEntity
import app.trackly.storage.db.WeeklyReportRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * Un bilan enregistré (sans son contenu) : de quoi lister les bilans passés. [weekStart] est le premier jour de sa période
 * et [periodEnd] le dernier ; un bilan enregistré avant l'ajout de la fin de période n'en a pas, c'est une semaine du lundi au dimanche.
 */
data class ReportInfo(
    val id: Long,
    val weekStart: LocalDate,
    val generatedAt: Instant,
    val periodEnd: LocalDate = weekStart.plusDays(6),
) {
    val period: WeekRange get() = WeekRange(weekStart, periodEnd)
}

data class StoredReport(val info: ReportInfo, val html: String)

/**
 * Rapports HTML des bilans, dans le stockage privé de l'app. Un rapport n'est jamais écrasé :
 * un nouveau bilan de la même semaine s'ajoute.
 */
interface ReportRepository {
    /** Tous les bilans, la semaine la plus récente d'abord. */
    fun observeAll(): Flow<List<ReportInfo>>

    /** Enregistre un rapport pour la période [weekStart] à [periodEnd] (une semaine du lundi au dimanche par défaut). */
    suspend fun save(weekStart: LocalDate, generatedAt: Instant, html: String, periodEnd: LocalDate = weekStart.plusDays(6)): ReportInfo

    /** Le dernier bilan : celui dont la période finit le plus tard (à fin égale, le dernier produit), ou `null`. */
    suspend fun latest(): ReportInfo?

    suspend fun get(id: Long): StoredReport?

    /** Le bilan de cette semaine est-il fait (au moins un rapport produit) ? */
    suspend fun isDone(weekStart: LocalDate): Boolean

    /** Dernier rapport produit pour cette semaine, ou `null`. */
    suspend fun latestFor(weekStart: LocalDate): ReportInfo?

    /**
     * Exécute [block] en une seule transaction avec les données du moteur, qui sont dans la même base : un arrêt
     * de l'app en route ne garde rien de ce que [block] a écrit, rapport compris.
     */
    suspend fun <T> atomically(block: suspend () -> T): T
}

class RoomReportRepository(private val db: TracklyDatabase) : ReportRepository {
    private val dao = db.weeklyReports()

    override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction { block() }

    override fun observeAll(): Flow<List<ReportInfo>> = dao.observeRows().map { rows -> rows.map(::info) }

    override suspend fun save(weekStart: LocalDate, generatedAt: Instant, html: String, periodEnd: LocalDate): ReportInfo {
        val id = dao.insert(
            WeeklyReportEntity(weekStart = weekStart.toString(), generatedAt = generatedAt.toEpochMilli(), html = html, periodEnd = periodEnd.toString()),
        )
        return ReportInfo(id, weekStart, generatedAt, periodEnd)
    }

    override suspend fun latest(): ReportInfo? = dao.latestRow()?.let(::info)

    override suspend fun get(id: Long): StoredReport? = dao.get(id)?.let {
        StoredReport(info(WeeklyReportRow(it.id, it.weekStart, it.generatedAt, it.periodEnd)), it.html)
    }

    private fun info(row: WeeklyReportRow): ReportInfo {
        val start = LocalDate.parse(row.weekStart)
        return ReportInfo(row.id, start, Instant.ofEpochMilli(row.generatedAt), row.periodEnd?.let(LocalDate::parse) ?: start.plusDays(6))
    }

    override suspend fun isDone(weekStart: LocalDate) = dao.countForWeek(weekStart.toString()) > 0

    override suspend fun latestFor(weekStart: LocalDate): ReportInfo? =
        dao.latestIdForWeek(weekStart.toString())?.let { dao.get(it) }?.let {
            info(WeeklyReportRow(it.id, it.weekStart, it.generatedAt, it.periodEnd))
        }
}

/** Rapports en mémoire : tests et aperçus. */
class InMemoryReportRepository : ReportRepository {
    private val reports = MutableStateFlow<List<StoredReport>>(emptyList())

    /** Pas de transaction en mémoire : l'atomicité se vérifie sur Room (`ProduceReportAtomicTest`). */
    override suspend fun <T> atomically(block: suspend () -> T): T = block()

    override fun observeAll(): Flow<List<ReportInfo>> =
        reports.map { list -> list.map { it.info }.sortedWith(compareByDescending<ReportInfo> { it.weekStart }.thenByDescending { it.id }) }

    override suspend fun save(weekStart: LocalDate, generatedAt: Instant, html: String, periodEnd: LocalDate): ReportInfo {
        val info = ReportInfo((reports.value.maxOfOrNull { it.info.id } ?: 0) + 1, weekStart, generatedAt, periodEnd)
        reports.value = reports.value + StoredReport(info, html)
        return info
    }

    override suspend fun latest(): ReportInfo? =
        reports.value.map { it.info }.maxWithOrNull(compareBy<ReportInfo> { it.periodEnd }.thenBy { it.id })

    override suspend fun get(id: Long) = reports.value.firstOrNull { it.info.id == id }

    override suspend fun isDone(weekStart: LocalDate) = reports.value.any { it.info.weekStart == weekStart }

    override suspend fun latestFor(weekStart: LocalDate) =
        reports.value.filter { it.info.weekStart == weekStart }.maxByOrNull { it.info.id }?.info
}
