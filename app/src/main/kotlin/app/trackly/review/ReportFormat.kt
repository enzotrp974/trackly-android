package app.trackly.review

import app.trackly.core.review.WeekRange
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** Mise en forme française des chiffres et dates du bilan (rapport HTML, contexte du modèle, notifications). */
object ReportFormat {
    private const val THIN = ' '
    private const val MINUS = '−'

    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private val WEEKDAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

    /** `2067.17` → « 2 067 » (entier, espace fine insécable). */
    fun int(value: Double): String = group(value.roundToLong().toString())

    /** Une décimale, virgule : `72.04` → « 72,0 ». */
    fun one(value: Double): String {
        val negative = value < 0 && abs(value) >= 0.05
        val tenths = (abs(value) * 10).roundToLong()
        val text = group((tenths / 10).toString()) + "," + (tenths % 10)
        return if (negative) "$MINUS$text" else text
    }

    /** Deux décimales : `-0.4556` → « −0,46 ». */
    fun two(value: Double): String {
        val hundredths = (abs(value) * 100).roundToLong()
        val text = group((hundredths / 100).toString()) + "," + "%02d".format(hundredths % 100)
        return if (value < 0 && hundredths > 0) "$MINUS$text" else text
    }

    /** Arrondi au multiple de [step] le plus proche, puis mis en forme : `2 983` → « 2 980 » (pas de 10). */
    fun rounded(value: Double, step: Int): String = int(Math.round(value / step) * step.toDouble())

    /** Dépense estimée : « environ 2 980 kcal par jour (entre 2 650 et 3 300) ». */
    fun approxRange(value: Double, low: Double, high: Double): String =
        "environ ${rounded(value, 10)} kcal par jour (entre ${rounded(low, 50)} et ${rounded(high, 50)})"

    /** Fourchette de rythme : « de −0,71 à −0,01 kg par semaine ». */
    fun rateRange(low: Double, high: Double): String = "de ${signedTwo(low)} à ${signedTwo(high)} kg par semaine"

    /** Entier signé : « +100 », « −449 », « 0 ». */
    fun signedInt(value: Double): String {
        val rounded = value.roundToLong()
        return when {
            rounded > 0 -> "+${group(rounded.toString())}"
            rounded < 0 -> "$MINUS${group((-rounded).toString())}"
            else -> "0"
        }
    }

    fun signedTwo(value: Double): String = if (value > 0 && (value * 100).roundToLong() > 0) "+${two(value)}" else two(value)

    /** « 1er novembre », « 26 octobre ». */
    fun dayMonth(day: LocalDate): String = (if (day.dayOfMonth == 1) "1er" else day.dayOfMonth.toString()) + " " + MONTHS[day.monthValue - 1]

    fun dayMonthYear(day: LocalDate): String = "${dayMonth(day)} ${day.year}"

    /** « du 26 octobre au 1er novembre 2026 » (l'année au bout, ou aux deux si elle change). */
    fun weekLabel(range: WeekRange): String {
        val sameYear = range.start.year == range.end.year
        val start = if (sameYear) dayMonth(range.start) else dayMonthYear(range.start)
        return "$start au ${dayMonthYear(range.end)}"
    }

    fun weekdayName(day: LocalDate): String = WEEKDAYS[day.dayOfWeek.value - 1]

    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(THIN.toString()).reversed()
}
