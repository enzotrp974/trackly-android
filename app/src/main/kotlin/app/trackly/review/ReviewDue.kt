package app.trackly.review

import app.trackly.core.review.WeekRange
import java.time.Instant
import java.time.ZoneId

/**
 * Le bilan est-il à faire ? Même règle que l'invitation du lundi ([ReviewReminders]) : rappels voulus, et aucun bilan ne
 * couvre encore la semaine qui vient de finir (jusqu'au dimanche qui précède le dernier lundi, à l'heure de l'invitation).
 * À partir de cette heure et jusqu'au rapport, sans délai maximal : la ligne reste, et la période grandit chaque jour
 * ([ReviewPeriods]) tant que le bilan n'est pas fait.
 */
object ReviewDue {
    /** La période à faire à l'instant [now], ou `null`. [last] : le dernier bilan produit. */
    fun period(now: Instant, zone: ZoneId, settings: ReviewSettingsState, last: ReportInfo?): WeekRange? {
        if (!settings.enabled) return null
        val invitation = ReviewCalendar.lastRun(now, zone, settings.inviteTime)
        return ReviewPeriods.of(now, last).takeUnless { ReviewCalendar.covers(last, invitation) }
    }
}
