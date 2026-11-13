package app.trackly.storage

import app.trackly.core.model.ActionId
import app.trackly.core.model.RecordState
import app.trackly.core.review.DaySteps
import app.trackly.core.review.SessionCount
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.core.store.WeekInfoVersions
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.db.WeekInfoEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

// Informations déclarées de la semaine (version 8), utilisées par RoomTracklyStore.

internal class RoomWeekInfos(db: TracklyDatabase) : WeekInfoVersions {
    private val dao = db.weekInfos()

    override suspend fun current(key: String) = dao.current(key)?.toModel()
    override suspend fun version(key: String, version: Int) = dao.version(key, version)?.toModel()
    override suspend fun allCurrent() = dao.allCurrent().map { it.toModel() }
    override suspend fun insert(record: WeekInfo) = dao.insert(record.toEntity())
}

private fun WeekInfo.toEntity() = WeekInfoEntity(
    weekStart = weekStart.toString(),
    version = version,
    state = state.code,
    weighIns = JSONArray().also { a -> weighIns.forEach { a.put(JSONObject().put("day", it.day.toString()).put("kg", it.kg)) } }.toString(),
    steps = JSONArray().also { a -> steps.forEach { a.put(JSONObject().put("day", it.day.toString()).put("steps", it.steps)) } }.toString(),
    stepsAverage = stepsAverage,
    sessions = sessions?.let { list -> JSONArray().also { a -> list.forEach { a.put(JSONObject().put("type", it.type).put("count", it.count)) } }.toString() },
    recordedAt = recordedAt.toEpochMilli(),
    actionId = actionId?.value,
)

private fun WeekInfoEntity.toModel(): WeekInfo {
    fun <T> objects(json: String, build: (JSONObject) -> T): List<T> =
        JSONArray(json).let { a -> List(a.length()) { build(a.getJSONObject(it)) } }
    return WeekInfo(
        weekStart = LocalDate.parse(weekStart),
        version = version,
        state = RecordState.fromCode(state),
        weighIns = objects(weighIns) { WeighIn(LocalDate.parse(it.getString("day")), it.getDouble("kg")) },
        steps = objects(steps) { DaySteps(LocalDate.parse(it.getString("day")), it.getInt("steps")) },
        stepsAverage = stepsAverage,
        sessions = sessions?.let { json -> objects(json) { SessionCount(it.getString("type"), it.getInt("count")) } },
        recordedAt = Instant.ofEpochMilli(recordedAt),
        actionId = actionId?.let(::ActionId),
    )
}
