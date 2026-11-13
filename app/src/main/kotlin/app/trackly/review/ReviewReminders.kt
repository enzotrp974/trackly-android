package app.trackly.review

import app.trackly.core.review.WeekRange
import app.trackly.core.time.TrackingDay
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Les deux rappels du lundi. */
enum class ReminderKind(val code: String) {
    /** Le lundi à l'heure choisie : ouvre la conversation de bilan. */
    Invitation("invitation"),

    /** Le lundi à 19 h, seulement si le bilan n'est pas fait. */
    Reminder("relance"),
    ;

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/**
 * Calendrier des rappels : le lundi, à l'heure locale. L'heure d'Android reste approximative
 * (Doze, mise en veille) : un rappel exécuté trop tard n'est pas envoyé (voir [ReviewReminders]).
 */
object ReviewCalendar {
    /** Prochain lundi à [at], strictement après [now]. */
    fun nextRun(now: Instant, zone: ZoneId, at: LocalTime): ZonedDateTime {
        val local = now.atZone(zone)
        var next = local.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).with(at)
        if (!next.toInstant().isAfter(now)) next = next.plusWeeks(1).with(at)
        return next
    }

    /**
     * Un bilan produit couvre-t-il la semaine qui précède le lundi [run] ? Oui s'il finit au plus tôt le dimanche d'avant
     * (même règle pour l'invitation, la relance du soir et la ligne « Bilan » de l'écran du jour : voir [ReviewDue]).
     */
    fun covers(last: ReportInfo?, run: ZonedDateTime): Boolean = last != null && !last.periodEnd.isBefore(run.toLocalDate().minusDays(1))

    /** Lundi à [at] le plus récent, au plus égal à [now]. */
    fun lastRun(now: Instant, zone: ZoneId, at: LocalTime): ZonedDateTime {
        val local = now.atZone(zone)
        var last = local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(at)
        if (last.toInstant().isAfter(now)) last = last.minusWeeks(1).with(at)
        return last
    }
}

/**
 * Logique des rappels, hors du `Worker` pour être testée simplement : l'invitation du lundi et la
 * relance du soir, **seulement si aucun bilan ne couvre la semaine qui vient de finir**. Rien n'est envoyé si les
 * rappels sont désactivés, ou si l'exécution a pris plus de [maxLate] de retard.
 */
class ReviewReminders(
    private val periods: ReviewPeriods,
    private val notifier: ReviewNotifier,
    private val settings: ReviewSettings,
    private val clock: Clock,
    private val zone: ZoneId = TrackingDay.ZONE,
    private val maxLate: Duration = Duration.ofHours(10),
) {
    suspend fun run(kind: ReminderKind): Boolean {
        val state = settings.state.value
        if (!state.enabled) return false
        val now = clock.instant()
        val planned = ReviewCalendar.lastRun(now, zone, if (kind == ReminderKind.Invitation) state.inviteTime else REMINDER_TIME)
        if (Duration.between(planned.toInstant(), now) > maxLate) return false
        val last = periods.reports.latest()
        if (ReviewCalendar.covers(last, planned)) return false
        val period: WeekRange = periods.at(now)
        when (kind) {
            ReminderKind.Invitation -> notifier.invite(period)
            ReminderKind.Reminder -> notifier.remind(period)
        }
        return true
    }
}
