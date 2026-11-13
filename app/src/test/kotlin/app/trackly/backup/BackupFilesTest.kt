package app.trackly.backup

import app.trackly.backup.drive.DriveFile
import app.trackly.core.time.TrackingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Nommage, reconnaissance et rotation des sauvegardes (règles pures). */
class BackupFilesTest {
    private val zone = TrackingDay.ZONE
    private val folder = "dossier"
    private val mark = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE)

    private fun file(name: String, parents: List<String> = listOf(folder), props: Map<String, String> = mark, trashed: Boolean = false, created: Instant? = null) =
        DriveFile("id-$name", name, BackupFiles.MIME, parents, 10, "md5", created, trashed, props)

    @Test
    fun `nom date a l heure locale`() {
        assertEquals("trackly-2026-11-03_03-12-05.db", BackupFiles.nameFor(Instant.parse("2026-11-03T02:12:05Z"), zone))
        assertEquals("trackly-2026-07-01_03-00-00.db", BackupFiles.nameFor(Instant.parse("2026-07-01T01:00:00Z"), zone))
    }

    @Test
    fun `seules les sauvegardes Trackly sont reconnues`() {
        assertTrue(BackupFiles.isTracklyBackup(file("trackly-2026-11-03_03-12-05.db"), folder))
        assertFalse("autre nom", BackupFiles.isTracklyBackup(file("trackly.db"), folder))
        assertFalse("autre dossier", BackupFiles.isTracklyBackup(file("trackly-2026-11-03_03-12-05.db", parents = listOf("x")), folder))
        assertFalse("sans marque", BackupFiles.isTracklyBackup(file("trackly-2026-11-03_03-12-05.db", props = emptyMap()), folder))
        assertFalse("corbeille", BackupFiles.isTracklyBackup(file("trackly-2026-11-03_03-12-05.db", trashed = true), folder))
    }

    @Test
    fun `date de la sauvegarde - Drive, sinon le nom`() {
        val created = Instant.parse("2026-11-03T02:15:00Z")
        assertEquals(created, BackupFiles.takenAt(file("trackly-2026-11-03_03-12-05.db", created = created), zone))
        assertEquals(Instant.parse("2026-11-03T02:12:05Z"), BackupFiles.takenAt(file("trackly-2026-11-03_03-12-05.db"), zone))
    }

    @Test
    fun `rotation - les 10 plus recentes, puis la derniere de chaque jour sur 30 jours`() {
        val now = Instant.parse("2026-11-03T02:00:00Z")
        fun entry(id: String, at: Instant) = BackupEntry(id, "$id.db", at, 1, null, null, 2)
        val yesterday = (1..12).map { h -> entry("hier-$h", Instant.parse("2026-11-02T00:00:00Z").plus(Duration.ofHours(h.toLong()))) }
        val entries = listOf(entry("neuve", now)) + yesterday + listOf(
            // 20 octobre, heure locale : 23 h 30 (21 h 30 UTC) et 23 h 00 ; et 21 octobre à 0 h 30 (22 h 30 UTC).
            entry("20-oct-soir", Instant.parse("2026-10-20T21:30:00Z")),
            entry("20-oct-tot", Instant.parse("2026-10-20T21:00:00Z")),
            entry("21-oct-nuit", Instant.parse("2026-10-20T22:30:00Z")),
            entry("j45", now.minus(Duration.ofDays(45))),
        )

        val expired = BackupFiles.expired(entries, now, protectId = "neuve", zone = zone).map { it.id }

        // Gardées : la neuve et les 9 plus récentes d'hier (10 en tout), la dernière de chaque jour local d'octobre.
        // Parties : les 3 plus anciennes d'hier, la plus tôt du 20 octobre, et celle de plus de 30 jours.
        assertEquals(listOf("hier-3", "hier-2", "hier-1", "20-oct-tot", "j45"), expired)
    }

    @Test
    fun `rotation - jamais la sauvegarde qu on vient de faire, et 10 copies gardees meme anciennes`() {
        val now = Instant.parse("2026-11-03T02:00:00Z")
        fun entry(id: String, days: Long) = BackupEntry(id, "$id.db", now.minus(Duration.ofDays(days)), 1, null, null, 2)
        // Rien n'a changé depuis longtemps : les dernières copies restent, même vieilles.
        val old = (0 until 12).map { entry("v$it", 400L + it) }
        assertEquals(listOf("v10", "v11"), BackupFiles.expired(old, now, protectId = "absente", zone = zone).map { it.id })
        assertEquals(listOf("v10"), BackupFiles.expired(old, now, protectId = "v11", zone = zone).map { it.id })
        assertTrue(BackupFiles.expired(listOf(entry("vieille", 400)), now, protectId = "vieille", zone = zone).isEmpty())
    }
}
