package app.trackly.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.R
import app.trackly.storage.db.TracklyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Règles de la sauvegarde automatique Android : la base part (fichier + WAL), rien d'autre —
 * ni préférences, ni fichiers, ni secret. Le dossier `no_backup` est exclu par Android.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** (section, balise, domaine, chemin) de chaque règle. */
    private fun rules(xml: Int): List<List<String>> {
        val parser = context.resources.getXml(xml)
        val rules = mutableListOf<List<String>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer", "full-backup-content" -> section = parser.name
                "include", "exclude" -> rules += listOf(
                    section,
                    parser.name,
                    parser.getAttributeValue(null, "domain"),
                    parser.getAttributeValue(null, "path"),
                )
            }
        }
        return rules
    }

    private val base = listOf("database" to TracklyDatabase.NAME, "database" to "${TracklyDatabase.NAME}-wal")

    @Test
    fun `Android 12 et plus - seule la base est incluse, en cloud et d appareil a appareil`() {
        val rules = rules(R.xml.data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val included = rules.filter { it[0] == section && it[1] == "include" }.map { it[2] to it[3] }
            assertEquals(section, base, included)
        }
        assertTrue(rules.all { it[1] == "include" && it[2] == "database" })
    }

    @Test
    fun `Android 11 et moins - memes regles`() {
        val included = rules(R.xml.backup_rules).filter { it[1] == "include" }.map { it[2] to it[3] }
        assertEquals(base, included)
    }

    @Test
    fun `sauvegarde automatique activee dans le manifeste`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
