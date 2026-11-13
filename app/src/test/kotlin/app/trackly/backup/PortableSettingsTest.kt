package app.trackly.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Réglages non secrets joints à chaque copie : ce qui part, ce qui ne part jamais, ce qui revient. */
@RunWith(AndroidJUnit4::class)
class PortableSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefsOf: (String) -> SharedPreferences = { name -> context.getSharedPreferences("reglages-$name", Context.MODE_PRIVATE) }
    private val f = BackupFixture(decorate = { copy -> PortableSettings.attach(copy, PortableSettings.read(prefsOf)) })

    @After
    fun close() {
        f.close()
        (PortableSettings.FILES.keys + "trackly-cle").forEach { prefsOf(it).edit().clear().commit() }
    }

    @Test
    fun `seuls les reglages choisis partent - jamais l etat de l appareil ni d autres fichiers`() {
        prefsOf("trackly-assistant").edit().putString("modele", "gpt-6.1-sol").putString("raisonnement", "high").commit()
        prefsOf("trackly-dictee").edit().putString("mode", "live").putBoolean("micro_demande", true).commit()
        prefsOf("trackly-bilan").edit().putBoolean("rappels_actifs", true).putInt("invitation_minutes", 540).commit()
        prefsOf("trackly-cle").edit().putString("etat", "valide").commit()

        val keys = PortableSettings.read(prefsOf).map { "${it.file}/${it.key}" }

        assertEquals(
            listOf(
                "trackly-assistant/modele", "trackly-assistant/raisonnement",
                "trackly-bilan/invitation_minutes", "trackly-bilan/rappels_actifs", "trackly-dictee/mode",
            ),
            keys,
        )
    }

    @Test
    fun `la copie Drive emporte les reglages, la base de l app jamais`() = runBlocking<Unit> {
        f.seed()
        prefsOf("trackly-assistant").edit().putString("modele", "gpt-6.1-sol").commit()
        val folder = f.connectWithFolder()
        val result = f.runner.backUp() as BackupResult.Success

        // Les réglages ne comptent pas dans le contenu (le garde-fou compare des données, pas des réglages).
        assertEquals(BackupContents.of(f.database.openHelper.writableDatabase), result.contents)
        assertFalse(hasSettingsTable(f.database.openHelper.writableDatabase))

        val entry = f.restore.listBackups().entries.single()
        assertEquals(folder.id, f.drive.files[entry.id]!!.parents.single())
        val prepared = f.restore.prepareFromDrive(entry, "Copie")
        assertTrue(prepared.carriesSettings)
        val staged = android.database.sqlite.SQLiteDatabase.openDatabase(f.restore.stagedFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        staged.use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = '${PortableSettings.TABLE}'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("retirés avant le remplacement", 0, c.getInt(0))
            }
        }
        val applied = f.restartApp() as RestoreApplication.Applied
        assertEquals(listOf(PortableSettings.Entry("trackly-assistant", "modele", "s", "gpt-6.1-sol")), applied.settings)
    }

    @Test
    fun `restauration - les reglages de la copie remplacent, les cles de l appareil restent`() {
        prefsOf("trackly-assistant").edit().putString("modele", "ancien").putString("cle-inconnue", "x").commit()
        prefsOf("trackly-dictee").edit().putBoolean("micro_demande", true).putString("mode", "live").commit()

        PortableSettings.write(
            prefsOf,
            listOf(
                PortableSettings.Entry("trackly-assistant", "modele", "s", "gpt-6.1-sol"),
                PortableSettings.Entry("trackly-dictee", "mode", "s", "after_stop"),
                PortableSettings.Entry("trackly-dictee", "micro_demande", "b", "false"),
                PortableSettings.Entry("trackly-bilan", "invitation_minutes", "i", "pas un nombre"),
                PortableSettings.Entry("trackly-cle", "etat", "s", "jamais"),
            ),
        )

        assertEquals("gpt-6.1-sol", prefsOf("trackly-assistant").getString("modele", null))
        assertEquals("hors de la liste : jamais touchée", "x", prefsOf("trackly-assistant").getString("cle-inconnue", null))
        assertEquals("after_stop", prefsOf("trackly-dictee").getString("mode", null))
        assertTrue("propre à l'appareil : gardée", prefsOf("trackly-dictee").getBoolean("micro_demande", false))
        assertFalse("valeur illisible ignorée", prefsOf("trackly-bilan").contains("invitation_minutes"))
        assertFalse("autre fichier : jamais touché", prefsOf("trackly-cle").contains("etat"))
    }

    @Test
    fun `le niveau de raisonnement choisi revient avec la restauration`() {
        prefsOf("trackly-assistant").edit().putString("raisonnement", "medium").commit()

        PortableSettings.write(prefsOf, listOf(PortableSettings.Entry("trackly-assistant", "raisonnement", "s", "xhigh")))

        assertEquals("xhigh", prefsOf("trackly-assistant").getString("raisonnement", null))
    }

    /** Figée : ajouter un réglage qui voyage est un choix, jamais un effet de bord. */
    @Test
    fun `liste des reglages qui voyagent`() {
        assertEquals(
            mapOf(
                "trackly-assistant" to setOf("modele", "raisonnement"),
                "trackly-dictee" to setOf("mode"),
                "trackly-bilan" to setOf("rappels_actifs", "invitation_minutes"),
                "trackly-appel" to setOf("reflexion", "echo"),
            ),
            PortableSettings.FILES,
        )
    }

    @Test
    fun `aller-retour JSON sans perte`() {
        val entries = listOf(
            PortableSettings.Entry("trackly-bilan", "rappels_actifs", "b", "false"),
            PortableSettings.Entry("trackly-assistant", "modele", "s", "nom « accentué » \"guillemets\""),
        )
        assertEquals(entries, PortableSettings.fromJson(PortableSettings.toJson(entries)))
    }

    private fun hasSettingsTable(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE name = '${PortableSettings.TABLE}'").use { it.moveToFirst() && it.getInt(0) == 1 }
}
