package app.trackly.testing

import app.trackly.core.time.TrackingDay
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Horloge réglable (heure locale) : un test fait passer 5 h pendant qu'un tour tourne. */
class SettableClock(start: Instant) : Clock() {
    constructor(local: String) : this(zurich(local))

    @Volatile var current: Instant = start

    /** Règle l'heure locale, au format `2026-11-02T05:01`. */
    fun set(local: String) {
        current = zurich(local)
    }

    override fun getZone(): ZoneId = TrackingDay.ZONE
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = current

    companion object {
        fun zurich(local: String): Instant = LocalDateTime.parse(local).atZone(TrackingDay.ZONE).toInstant()
    }
}
