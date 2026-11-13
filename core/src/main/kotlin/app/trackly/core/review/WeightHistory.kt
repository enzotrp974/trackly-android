package app.trackly.core.review

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Pesées connues : celles reprises de l'ancien suivi (document `weights`) et celles déclarées
 * dans les bilans. Une pesée déclarée remplace une pesée reprise du même jour (la plus récente
 * déclaration de l'utilisateur fait foi). Aucune valeur n'est jamais déduite.
 */
class WeightHistory private constructor(private val byDay: Map<LocalDate, WeighIn>) {
    val all: List<WeighIn> get() = byDay.values.sortedBy { it.day }

    fun within(range: WeekRange): List<WeighIn> = all.filter { it.day in range }

    /** Moyenne des pesées de la semaine, ou `null` sans pesée. */
    fun average(range: WeekRange): Double? = within(range).takeIf { it.isNotEmpty() }?.let { w -> w.sumOf { it.kg } / w.size }

    /** Dernière pesée connue au plus tard le [day], ou `null`. */
    fun lastUpTo(day: LocalDate): WeighIn? = all.lastOrNull { !it.day.isAfter(day) }

    /**
     * Moyennes des semaines pesées parmi [windowWeeks] blocs de sept jours qui finissent avec la période [latest] (le plus
     * ancien d'abord ; pour une semaine du lundi au dimanche, ce sont ses semaines civiles). Un bloc sans pesée
     * n'apparaît pas : rien n'est supposé.
     */
    fun weeklyMeans(latest: WeekRange, windowWeeks: Int): List<WeekMean> =
        (windowWeeks - 1 downTo 0).mapNotNull { back ->
            val blockEnd = latest.end.minusWeeks(back.toLong())
            val range = WeekRange(blockEnd.minusDays(6), blockEnd)
            val weighIns = within(range)
            weighIns.takeIf { it.isNotEmpty() }?.let { WeekMean(range, it.sumOf { w -> w.kg } / it.size, it.size) }
        }

    companion object {
        val Empty = WeightHistory(emptyMap())

        fun of(imported: List<WeighIn>, declared: List<WeighIn>): WeightHistory =
            WeightHistory((imported + declared).associateBy { it.day })

        /**
         * Pesées du document repris `weights` (`{"measurements": [{"day": "AAAA-MM-JJ", "kg": 73.4}]}`).
         * Une ligne illisible est ignorée (jamais corrigée ni devinée).
         */
        fun parseImported(json: String?): List<WeighIn> {
            if (json.isNullOrBlank()) return emptyList()
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
            val measurements = root["measurements"] as? JsonArray ?: return emptyList()
            return measurements.mapNotNull { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val day = (item["day"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { try { LocalDate.parse(it) } catch (e: DateTimeParseException) { null } }
                    ?: return@mapNotNull null
                val kg = (item["kg"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                runCatching { WeighIn(day, kg) }.getOrNull()
            }
        }
    }
}
