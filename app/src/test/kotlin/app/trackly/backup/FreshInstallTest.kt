package app.trackly.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** « App neuve » : décidée une fois, jamais rendue par une sauvegarde, perdue à la première écriture. */
@RunWith(AndroidJUnit4::class)
class FreshInstallTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("installation-unitaire", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

    @After
    fun close() {
        prefs.edit().clear().commit()
        FreshInstall.PREVIOUS_PREFS.forEach { File(prefsDir, "$it.xml").delete() }
    }

    @Test
    fun `premiere installation - neuve, et la decision ne change plus aux lancements suivants`() {
        assertTrue(FreshInstall(prefs) { false }.state.value.fresh)
        // Lancement suivant : la trace d'installation n'est plus consultée.
        assertTrue(FreshInstall(prefs) { true }.state.value.fresh)
    }

    @Test
    fun `mise a jour par-dessus - jamais neuve`() {
        val install = FreshInstall(prefs) { true }
        assertFalse(install.state.value.fresh)
        assertFalse(install.state.value.offerPending)
        install.onBaseOpened("0/0/0")
        assertFalse(install.isNew("0/0/0"))
    }

    @Test
    fun `base du premier lancement notee, toute difference rend l app utilisee pour toujours`() {
        val install = FreshInstall(prefs) { false }
        install.onBaseOpened("3/12/20") // base remise par Auto Backup
        assertTrue(install.isNew("3/12/20"))
        install.onBaseOpened("3/12/20") // relancement, base inchangée
        assertTrue(install.isNew("3/12/20"))
        assertFalse(install.isNew("4/13/22"))
        assertFalse("jamais l'inverse", install.isNew("3/12/20"))
        assertFalse(FreshInstall(prefs) { false }.state.value.fresh)
    }

    @Test
    fun `ecriture signalee - l app n est plus neuve`() {
        val install = FreshInstall(prefs) { false }
        install.onBaseOpened("0/0/0")
        install.markUsed()
        assertFalse(install.isNew("0/0/0"))
        assertFalse(install.state.value.offerPending)
    }

    @Test
    fun `base jamais ouverte - pas de restauration automatique possible`() {
        assertFalse(FreshInstall(prefs) { false }.isNew("0/0/0"))
    }

    @Test
    fun `prechargement - attend la reponse a la proposition, non pendant une restauration`() = runBlocking<Unit> {
        val install = FreshInstall(prefs) { false }
        val waiting = async(Dispatchers.Default) { install.awaitPreloadChoice() }
        delay(50)
        assertFalse(waiting.isCompleted)
        install.answer(RestoreOffer.Answered)
        assertTrue(withTimeout(5_000) { waiting.await() })

        install.restoring = true
        assertFalse(install.awaitPreloadChoice())
    }

    @Test
    fun `refus - garde pour les lancements suivants`() {
        FreshInstall(prefs) { false }.decline()
        val next = FreshInstall(prefs) { false }
        assertEquals(RestoreOffer.Declined, next.state.value.offer)
        assertFalse(next.state.value.offerPending)
        assertTrue("toujours neuve : une connexion plus tard peut encore restaurer", next.state.value.fresh)
    }

    @Test
    fun `trace d une installation precedente - preferences de Trackly presentes`() {
        assertFalse(FreshInstall.previousInstall(context))
        prefsDir.mkdirs()
        File(prefsDir, "trackly-bilan.xml").writeText("<map />")
        assertTrue(FreshInstall.previousInstall(context))
    }
}
