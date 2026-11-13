package app.trackly.review

import app.trackly.core.review.WeekRange
import app.trackly.core.time.TrackingDay
import java.time.Instant
import java.time.LocalDate

/**
 * Période sur laquelle porte le bilan quand l'utilisateur l'ouvre à l'instant [at] : du lendemain de la fin du dernier
 * bilan jusqu'à aujourd'hui, ou, sans bilan précédent, depuis le début du fil de bilan déjà commencé, sinon depuis
 * [firstStart]. Elle grandit chaque jour tant qu'aucun rapport n'est produit ; le jour où un rapport
 * est produit, la rouvrir reprend la même période (un nouveau rapport s'ajoute, rien n'est écrasé), puis le lendemain
 * une nouvelle période commence.
 */
class ReviewPeriods(internal val reports: ReportRepository, private val threads: ReviewHistory) {
    suspend fun at(now: Instant): WeekRange = of(now, reports.latest(), threads.latestStart())

    /** La période [given] choisie par l'écran pour l'envoi (ECR-03), sinon celle de l'instant [now]. */
    suspend fun at(now: Instant, given: WeekRange?): WeekRange = given ?: at(now)

    /**
     * Comme [at], pour un outil qui écrit sous la période (informations, rapport) : refusé si un rapport validé pendant le tour
     * (autre écran) l'a fait changer de premier jour. Écrire sous l'ancien début perdrait pas et séances ; rien n'est écrit.
     */
    suspend fun forWriting(now: Instant, given: WeekRange?): WeekRange {
        val current = at(now)
        require(given == null || given.start == current.start) { PERIOD_MOVED }
        return given ?: current
    }

    companion object {
        const val PERIOD_MOVED = "Un bilan vient d'être produit et une nouvelle période a commencé : rien n'est noté. " +
            "Dis-le à l'utilisateur et demande-lui de renvoyer son message, il partira dans la nouvelle période."

        /** « Aujourd'hui » : la date de rattachement du jour (règle des 5 h), comme pour les repas. */
        fun today(now: Instant): LocalDate = TrackingDay.of(now)

        /**
         * Premier jour de la période quand aucun bilan ne précède : le lundi qui tombe 6 jours avant aujourd'hui ou plus tôt
         * (le lundi 2, c'est le lundi 26 d'avant ; le dimanche 8, le lundi 2). Un début qui ne glisse pas d'un jour à l'autre :
         * le fil et les informations de la période sont rangés sous lui, et ne doivent pas disparaître le lendemain. Il ne
         * change que le dimanche, comme le lundi de l'ancien bilan hebdomadaire.
         */
        fun firstStart(today: LocalDate): LocalDate = today.minusDays(6).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

        /**
         * Période à l'instant [now], sachant que le dernier bilan produit est [last] (ou `null`) et que le fil de bilan
         * commencé le plus récemment l'a été sous [started] (ou `null`).
         */
        fun of(now: Instant, last: ReportInfo?, started: LocalDate? = null): WeekRange {
            val today = today(now)
            return when {
                // Premier bilan laissé en attente (ECR-07) : son fil et ses informations gardent leur début jusqu'au rapport,
                // même quand le lundi de [firstStart] change.
                last == null -> WeekRange(started?.let { minOf(it, firstStart(today)) } ?: firstStart(today), today)
                last.periodEnd.isBefore(today) -> WeekRange(last.periodEnd.plusDays(1), today)
                else -> WeekRange(last.weekStart, maxOf(today, last.periodEnd))
            }
        }
    }
}
