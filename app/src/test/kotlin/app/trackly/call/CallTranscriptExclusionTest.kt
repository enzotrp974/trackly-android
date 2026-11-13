package app.trackly.call

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.SharedUnseenQuestionsStore
import app.trackly.assistant.chat.Unseen
import app.trackly.backup.DatabaseSnapshotter
import app.trackly.backup.PortableSettings
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * La transcription du dernier appel et la relecture en attente restent sur le téléphone : exclue de la sauvegarde Android (Auto Backup et transfert
 * d'appareil) et de la copie Drive, qui n'emportent que la base et les réglages non secrets.
 */
@RunWith(AndroidJUnit4::class)
class CallTranscriptExclusionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "appel-exclusion.db"
    private val secret = "TRANSCRIPTION-SECRETE-DU-DERNIER-APPEL"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
        File(context.filesDir, LastCallTranscript.DIR).deleteRecursively()
        SharedUnseenQuestionsStore(context).write(null)
    }

    /** Les règles n'incluent que la base Trackly : tout le reste, dont `files/appel`, est exclu. */
    @Test
    fun `sauvegarde Android - seule la base est incluse`() {
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name"))
            val includes = doc.getElementsByTagName("include").let { list -> (0 until list.length).map { list.item(it) as Element } }
            assertTrue("$name doit avoir des règles <include>", includes.isNotEmpty())
            for (rule in includes) {
                assertEquals(name, "database", rule.getAttribute("domain"))
                assertTrue(name, rule.getAttribute("path").startsWith(TracklyDatabase.NAME))
            }
        }
        // Relectures en attente, compteur de lectures ratées et fichier illisible mis de côté : tous dans `files/appel`.
        val names = listOf(
            LastCallTranscript.FILE_NAME, FilePendingReviewStore.FILE_NAME,
            FilePendingReviewStore.FILE_NAME + FilePendingReviewStore.UNREADABLE_SUFFIX, FilePendingReviewStore.SET_ASIDE_PREFIX + "1.json",
        )
        for (name in names) {
            val file = File(File(context.filesDir, LastCallTranscript.DIR), name)
            assertTrue(file.path.startsWith(context.filesDir.path))
            assertFalse(file.path.startsWith(context.getDatabasePath(TracklyDatabase.NAME).parent!!))
        }
    }

    @Test
    fun `copie Drive - ni la transcription ni les mesures`() = runBlocking {
        LastCallTranscript(File(File(context.filesDir, LastCallTranscript.DIR), LastCallTranscript.FILE_NAME))
            .write(listOf(CallLine(Speaker.User, secret)), Instant.parse("2026-11-06T12:00:00Z"))
        CallMetricsStore(File(File(context.filesDir, LastCallTranscript.DIR), CallMetricsStore.FILE_NAME)).add(
            CallMetrics(Instant.parse("2026-11-06T12:00:00Z"), true, emptyList(), emptySet(), 777_777L, emptyList(), "MESURE-SECRETE", 1),
        )
        // Relecture en attente et pastille des questions : elles restent sur le téléphone, elles aussi.
        FilePendingReviewStore(File(File(context.filesDir, LastCallTranscript.DIR), FilePendingReviewStore.FILE_NAME)).put(
            PendingReview(
                Instant.parse("2026-11-06T12:00:00Z"), Instant.parse("2026-11-06T12:03:00Z"), CallReflection.Medium,
                listOf(CallLine(Speaker.User, secret)), unprocessed = listOf(secret), openQuestions = listOf("QUESTION-SECRETE"),
            ),
        )
        SharedUnseenQuestionsStore(context).write(Unseen(java.time.LocalDate.parse("2026-11-06"), 7))
        SharedCallSettings(context).setReflection(CallReflection.High)
        val db = Room.databaseBuilder(context, TracklyDatabase::class.java, dbName).allowMainThreadQueries().build()
        try {
            val copy = File(context.cacheDir, "copie-drive.db")
            DatabaseSnapshotter({ db }, decorate = { file ->
                PortableSettings.attach(file, PortableSettings.read(PortableSettings.prefsOf(context)))
            }).snapshot(copy)
            val bytes = copy.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("la transcription ne part pas sur Drive", bytes.contains(secret))
            assertFalse("les mesures non plus", bytes.contains("MESURE-SECRETE"))
            assertFalse("ni la relecture en attente", bytes.contains("QUESTION-SECRETE"))
            // Les réglages de l'appel voyagent, eux (non secrets).
            val entries = PortableSettings.read(PortableSettings.prefsOf(context))
            assertTrue(entries.any { it.file == SharedCallSettings.FILE && it.key == SharedCallSettings.KEY_REFLECTION && it.value == "elevee" })
            assertTrue(entries.none { it.value.contains(secret) })
            assertTrue("la pastille ne voyage pas", entries.none { it.file == SharedUnseenQuestionsStore.FILE })
        } finally {
            db.close()
        }
    }
}
