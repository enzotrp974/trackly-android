package app.trackly.core.review

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Période d'un bilan : du lendemain du dernier bilan jusqu'à aujourd'hui. Elle commence n'importe quel jour et dure
 * de 1 jour à plusieurs semaines. Le nom vient des premiers bilans, qui portaient toujours sur une semaine du lundi au
 * dimanche : sans [end], la période garde cette durée de 7 jours, ce qui relit les anciens bilans tels qu'ils étaient.
 */
data class WeekRange(val start: LocalDate, val end: LocalDate = start.plusDays(6)) {
    init {
        require(!end.isBefore(start)) { "Période à l'envers : $start > $end" }
    }

    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

    operator fun contains(day: LocalDate): Boolean = !day.isBefore(start) && !day.isAfter(end)

    companion object {
        /** La semaine civile (lundi au dimanche) qui contient [day] : les blocs de poids de la prévision d'arrivée. */
        fun containing(day: LocalDate) = WeekRange(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
    }
}
