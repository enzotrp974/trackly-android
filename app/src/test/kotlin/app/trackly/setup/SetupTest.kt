package app.trackly.setup

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.alerts.FixedNotificationsAllowed
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.assistant.key.StaticKeyVerification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.trackly.backup.BackupStatus
import app.trackly.backup.FreshInstall
import app.trackly.backup.RestoreOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Liste de mise en route : ce qui se coche tout seul, ce qui se coche à la main, quand elle s'ouvre. */
@RunWith(AndroidJUnit4::class)
class SetupTest {
    private fun state(
        key: ApiKeyStatus = ApiKeyStatus.Missing,
        check: KeyVerificationState = KeyVerificationState.Unchecked,
        notifications: Boolean = false,
        drive: Boolean = false,
        battery: Boolean = false,
        imported: Boolean = false,
        exportDeleted: Boolean = false,
        dismissed: Boolean = false,
    ) = SetupState.of(key, check, notifications, drive, battery, imported, exportDeleted, dismissed)

    @Test
    fun `au depart, rien n est coche et le fichier d export n est pas propose sans reprise`() {
        val s = state()
        assertEquals(listOf(SetupStep.Key, SetupStep.History, SetupStep.Drive, SetupStep.Notifications, SetupStep.Battery), s.items.map { it.step })
        assertEquals(0, s.doneCount)
        assertFalse(s.finished)
    }

    @Test
    fun `la cle n est cochee que si elle est enregistree ET reconnue par OpenAI`() {
        val present = ApiKeyStatus.Present("0000")
        assertFalse(state(key = present).item(SetupStep.Key)!!.done)
        assertFalse(state(key = present, check = KeyVerificationState.Pending(noInternet = true)).item(SetupStep.Key)!!.done)
        assertFalse(state(key = present, check = KeyVerificationState.Refused).item(SetupStep.Key)!!.done)
        assertTrue(state(key = present, check = KeyVerificationState.Valid).item(SetupStep.Key)!!.done)
        assertFalse(state(key = ApiKeyStatus.Missing, check = KeyVerificationState.Valid).item(SetupStep.Key)!!.done)
    }

    @Test
    fun `le detail de la cle ne dit que le probleme, sinon rien`() {
        val present = ApiKeyStatus.Present("0000")
        fun detail(key: ApiKeyStatus, check: KeyVerificationState) = state(key = key, check = check).item(SetupStep.Key)!!.detail
        assertEquals("", detail(ApiKeyStatus.Missing, KeyVerificationState.Unchecked))
        assertEquals("", detail(present, KeyVerificationState.Valid))
        assertEquals("OpenAI refuse cette clé.", detail(present, KeyVerificationState.Refused))
        assertEquals("Pas encore vérifiée : pas de réseau.", detail(present, KeyVerificationState.Pending(noInternet = true)))
        assertEquals("Vérification…", detail(present, KeyVerificationState.Checking))
        assertEquals("Pas encore vérifiée.", detail(present, KeyVerificationState.Unchecked))
    }

    @Test
    fun `notifications, Drive et batterie`() {
        val s = state(notifications = true, drive = true, battery = true)
        assertEquals("", s.item(SetupStep.Notifications)!!.detail)
        assertEquals("", s.item(SetupStep.Drive)!!.detail)
        assertEquals("Sans restriction, et jamais en veille.", s.item(SetupStep.Battery)!!.detail)
        assertTrue(s.item(SetupStep.Notifications)!!.done)
        assertTrue(s.item(SetupStep.Drive)!!.done)
        assertTrue(s.item(SetupStep.Battery)!!.done)
        assertTrue(s.item(SetupStep.Battery)!!.manual)
    }

    @Test
    fun `apres la reprise, on propose de supprimer le fichier d export`() {
        val s = state(imported = true)
        assertEquals(SetupStep.ExportFile, s.items.last().step)
        assertEquals("Supprime-le du téléphone et de Drive.", s.items.last().detail)
        assertFalse(s.items.last().done)
        assertTrue(state(imported = true, exportDeleted = true).items.last().done)
    }

    @Test
    fun `tout coche ou Terminer - la liste est finie`() {
        val all = state(
            ApiKeyStatus.Present("0000"), KeyVerificationState.Valid,
            notifications = true, drive = true, battery = true, imported = true, exportDeleted = true,
        )
        assertTrue(all.finished)
        assertEquals("6 sur 6", all.progress)
        // Sans la reprise de l'historique, la liste n'est pas finie.
        assertFalse(state(ApiKeyStatus.Present("0000"), KeyVerificationState.Valid, notifications = true, drive = true, battery = true).finished)
        assertTrue(state(dismissed = true).finished)
        assertEquals("1 sur 5", state(drive = true).progress)
    }

    @Test
    fun `Historique se coche avec la reprise, sans detail`() {
        assertFalse(state().item(SetupStep.History)!!.done)
        assertTrue(state(imported = true).item(SetupStep.History)!!.done)
        assertEquals("", state().item(SetupStep.History)!!.detail)
        assertEquals("Historique", state().item(SetupStep.History)!!.title)
    }

    private fun checklist(
        prefs: SetupPrefs = InMemorySetupPrefs(),
        keys: InMemoryApiKeyStore = InMemoryApiKeyStore(),
        verification: StaticKeyVerification = StaticKeyVerification(),
        notifications: FixedNotificationsAllowed = FixedNotificationsAllowed(false),
        backup: MutableStateFlow<BackupStatus> = MutableStateFlow(BackupStatus()),
        imported: MutableStateFlow<Boolean> = MutableStateFlow(false),
        install: FreshInstall? = null,
    ) = SetupChecklist(keys, verification, notifications.allowed, backup, imported, prefs, CoroutineScope(Dispatchers.Unconfined), install)

    private fun freshInstall(previousInstall: Boolean = false): FreshInstall {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("installation-liste", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return FreshInstall(prefs) { previousInstall }
    }

    @Test
    fun `app neuve - Google Drive propose en premier, rouvert a chaque lancement jusqu a une reponse`() {
        val install = freshInstall()
        val prefs = InMemorySetupPrefs()
        val backup = MutableStateFlow(BackupStatus())
        val list = checklist(prefs, backup = backup, install = install)

        val first = list.state.value.items.first()
        assertEquals(SetupStep.Drive, first.step)
        assertEquals(SetupState.RESTORE_OFFER_DETAIL, first.detail)
        assertFalse(first.done)
        assertTrue(list.takeAutoOpen())
        assertTrue("pas de réponse : la proposition revient", checklist(prefs, backup = backup, install = install).takeAutoOpen())

        // Connecté, mais la recherche de la sauvegarde n'a pas abouti : la ligne reste à faire (on peut réessayer).
        backup.value = BackupStatus(connected = true)
        assertFalse(list.state.value.item(SetupStep.Drive)!!.done)

        install.answer(RestoreOffer.Answered)
        assertTrue(list.state.value.item(SetupStep.Drive)!!.done)
        assertEquals("ordre habituel une fois la réponse donnée", SetupStep.Key, list.state.value.items.first().step)
        assertFalse(checklist(prefs, backup = backup, install = install).takeAutoOpen())
    }

    @Test
    fun `app neuve - Plus tard ou Terminer, c est continuer sans Google`() {
        val install = freshInstall()
        checklist(install = install).leave()
        assertEquals(RestoreOffer.Declined, install.state.value.offer)

        val other = freshInstall()
        checklist(install = other).dismiss()
        assertEquals(RestoreOffer.Declined, other.state.value.offer)
    }

    @Test
    fun `mise a jour par-dessus - la liste reste comme avant`() {
        val list = checklist(install = freshInstall(previousInstall = true))
        assertEquals(SetupStep.Key, list.state.value.items.first().step)
        assertEquals("", list.state.value.item(SetupStep.Drive)!!.detail)
    }

    @Test
    fun `la liste suit la cle, les notifications, Drive et la reprise`() = runBlocking {
        val keys = InMemoryApiKeyStore()
        val verification = StaticKeyVerification()
        val notifications = FixedNotificationsAllowed(false)
        val backup = MutableStateFlow(BackupStatus())
        val imported = MutableStateFlow(false)
        val list = checklist(keys = keys, verification = verification, notifications = notifications, backup = backup, imported = imported)
        assertEquals(0, list.state.value.doneCount)

        keys.save("sk-test-cle-factice-0000000000")
        assertFalse(list.state.value.item(SetupStep.Key)!!.done) // enregistrée, pas encore vérifiée
        verification.set(KeyVerificationState.Valid)
        assertTrue(list.state.value.item(SetupStep.Key)!!.done)

        notifications.set(true)
        assertTrue(list.state.value.item(SetupStep.Notifications)!!.done)
        backup.value = BackupStatus(connected = true)
        assertTrue(list.state.value.item(SetupStep.Drive)!!.done)
        assertFalse(list.state.value.item(SetupStep.History)!!.done)
        imported.value = true
        assertTrue(list.state.value.item(SetupStep.History)!!.done)
        assertNotNull(list.state.value.item(SetupStep.ExportFile))
    }

    @Test
    fun `batterie et fichier d export se cochent a la main, et se decochent`() {
        val prefs = InMemorySetupPrefs()
        val list = checklist(prefs, imported = MutableStateFlow(true))
        list.markBatteryDone()
        list.markExportFileDeleted()
        assertTrue(prefs.batteryDone && prefs.exportFileDeleted)
        assertTrue(list.state.value.item(SetupStep.Battery)!!.done)
        assertTrue(list.state.value.item(SetupStep.ExportFile)!!.done)
        list.markBatteryDone(false)
        assertFalse(list.state.value.item(SetupStep.Battery)!!.done)
        // Relue au lancement suivant.
        assertTrue(checklist(prefs, imported = MutableStateFlow(true)).state.value.item(SetupStep.ExportFile)!!.done)
    }

    @Test
    fun `la liste s ouvre d elle-meme une seule fois, et jamais une fois terminee`() {
        val prefs = InMemorySetupPrefs()
        val list = checklist(prefs)
        assertTrue(list.takeAutoOpen())
        assertFalse(list.takeAutoOpen())
        assertTrue(prefs.autoOpened)

        val finished = checklist(InMemorySetupPrefs())
        finished.dismiss()
        assertFalse(finished.takeAutoOpen())
        assertTrue(finished.state.value.finished)
        // « Terminer » ne cache pas les lignes restantes : les Réglages gardent l'entrée tant qu'il en reste une.
        assertFalse(finished.state.value.items.all { it.done })
    }

    @Test
    fun `batterie Samsung - l ecran systeme le plus proche est ouvert par un intent documente`() {
        val appInfo = BatteryHelp.appInfoIntent("app.trackly")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.action)
        assertEquals("package:app.trackly", appInfo.dataString)
        assertTrue(appInfo.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, BatteryHelp.optimizationListIntent().action)
        // Pas d'intent public pour « Applications jamais en veille » : le chemin est écrit.
        assertTrue(BatteryHelp.PATH_NEVER_SLEEPING.contains("Applications jamais en veille"))
        assertTrue(BatteryHelp.PATH_UNRESTRICTED.contains("Sans restriction"))
    }
}
