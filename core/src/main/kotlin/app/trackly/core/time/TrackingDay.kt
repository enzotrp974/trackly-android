package app.trackly.core.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Date de rattachement : une saisie entre minuit inclus et 5 h exclu,
 * heure locale du téléphone, compte pour la veille. Une date exprimée par l'utilisateur prime toujours.
 */
object TrackingDay {
    /** Fuseau du téléphone (les tests fixent Europe/Zurich par `user.timezone`). */
    val ZONE: ZoneId = ZoneId.systemDefault()

    /** Heure locale à partir de laquelle un nouveau jour de suivi commence. */
    val DAY_START: LocalTime = LocalTime.of(5, 0)

    fun of(at: Instant, explicitDay: LocalDate? = null): LocalDate {
        if (explicitDay != null) return explicitDay
        val local = at.atZone(ZONE)
        return if (local.toLocalTime() < DAY_START) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /**
     * Dernier jour qu'une date exprimée peut désigner : la date civile locale de [at].
     * Entre minuit et 5 h, « aujourd'hui » est donc permis alors que le jour de suivi est encore la veille ;
     * au-delà, la date est dans le futur et le moteur la refuse.
     */
    fun latestExplicitDay(at: Instant): LocalDate = at.atZone(ZONE).toLocalDate()

    /** Jour de suivi en cours selon l'horloge. */
    fun today(clock: Clock): LocalDate = of(clock.instant())

    fun localTime(at: Instant): LocalTime = at.atZone(ZONE).toLocalTime()
}
