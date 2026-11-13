package app.trackly.backup

import app.trackly.core.time.TrackingDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/** Date d'une sauvegarde : majuscule en tête de ligne, minuscule au milieu d'une phrase. */
class BackupLabelsTest {
    private val zone = TrackingDay.ZONE
    private val at = ZonedDateTime.of(2026, 11, 6, 1, 17, 0, 0, zone).toInstant()

    @Test
    fun `en tête de ligne le jour garde sa majuscule`() {
        assertEquals("Vendredi 6 novembre, 01:17", BackupLabels.moment(at, zone))
    }

    @Test
    fun `au milieu d'une phrase le jour passe en minuscule`() {
        assertEquals("vendredi 6 novembre, 01:17", BackupLabels.inSentence(at, zone))
    }
}
