package app.trackly.backup

import android.app.PendingIntent
import android.content.Intent
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ImportReport
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Parcours de l'écran Sauvegarde : connexion (consentement unique), sauvegarde, restauration. */
@RunWith(AndroidJUnit4::class)
class BackupControllerTest {
    private lateinit var f: BackupFixture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var scheduled = 0
    private var soon = 0
    private var cancelled = 0
    private var restarts = 0
    private val importedTexts = mutableListOf<String>()
    private var importOutcome: ImportOutcome = ImportOutcome.Refused("Fichier refusé")
    private lateinit var controller: BackupController

    @Before
    fun setUp() = runBlocking {
        f = BackupFixture()
        f.seed()
        controller = BackupController(
            runner = f.runner,
            restore = f.restore,
            folder = f.folder,
            authorizer = f.auth,
            drive = f::api,
            status = f.status,
            scheduling = object : BackupScheduling {
                override fun schedule() {
                    scheduled++
                }
                override fun backupSoon() {
                    soon++
                }
                override fun cancel() {
                    cancelled++
                }
            },
            restarter = { restarts++ },
            scope = scope,
            workDir = File(f.context.cacheDir, "controleur"),
            zone = app.trackly.core.time.TrackingDay.ZONE,
            importHistory = { text ->
                importedTexts += text
                importOutcome
            },
        )
    }

    @After
    fun close() {
        scope.cancel()
        f.close()
    }

    private fun awaitIdle() = runBlocking {
        withTimeout(10_000) {
            while (controller.state.value.busy.let { it != null && it != BackupBusy.Restarting }) delay(10)
        }
    }

    private fun pendingIntent() =
        PendingIntent.getActivity(f.context, 0, Intent("app.trackly.TEST"), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun `premiere connexion - consentement Google puis dossier Trackly cree, nuit et copie automatique planifiees`() {
        f.auth.needsUserAction(pendingIntent())
        controller.connect()
        awaitIdle()
        assertNotNull("l'écran Google doit être demandé", controller.state.value.consent)
        assertFalse(f.status.status.value.connected)

        f.auth.granted() // l'utilisateur a choisi son compte et accepté drive.file
        controller.onConsentResult(granted = true)
        awaitIdle()

        val state = controller.state.value
        assertNull(state.consent)
        assertTrue(state.status.connected)
        assertEquals("test@example.com", state.status.accountEmail)
        val folder = f.drive.folders().single()
        assertEquals("Trackly", folder.name)
        assertEquals(BackupFiles.ROLE_BACKUPS, folder.appProperties[BackupFiles.ROLE_KEY])
        assertEquals(folder.id, f.status.folderId)
        assertEquals(1, scheduled)
        // Des changements attendent peut-être déjà : une copie part bientôt, sans geste.
        assertEquals(1, soon)
        assertTrue(state.notice!!.text.contains("créé"))
        assertTrue(state.notice!!.text.contains("après chaque changement"))
    }

    @Test
    fun `dossier deja present sur Drive - retrouve, pas de doublon`() {
        f.drive.addFolder("Trackly", BackupFiles.folderProperties())
        controller.connect()
        awaitIdle()
        assertEquals(1, f.drive.folders().size)
        assertTrue(controller.state.value.notice!!.text.contains("retrouvé"))
    }

    @Test
    fun `Drive ignore la marque a la creation - elle est posee puis verifiee`() {
        f.drive.ignoreFolderPropertiesOnCreate = true
        controller.connect()
        awaitIdle()
        assertTrue(BackupFiles.isTracklyFolder(DriveFileOf(f.drive.folders().single())))
        assertTrue(controller.state.value.status.connected)
    }

    @Test
    fun `consentement refuse - message clair, rien n est connecte`() {
        f.auth.needsUserAction(pendingIntent())
        controller.connect()
        awaitIdle()
        controller.onConsentResult(granted = false)
        assertFalse(f.status.status.value.connected)
        assertTrue(controller.state.value.notice!!.error)
        // Sans motif donné par Google : le message dit qu'il peut s'agir du projet Google Cloud pas prêt.
        assertEquals(CONSENT_UNFINISHED, controller.state.value.notice!!.text)
    }

    @Test
    fun `consentement refuse avec un motif de Google - le motif est affiche`() {
        f.auth.needsUserAction(pendingIntent())
        controller.connect()
        awaitIdle()
        controller.onConsentResult(false, "motif de Google")
        assertFalse(f.status.status.value.connected)
        val notice = controller.state.value.notice!!
        assertTrue(notice.error)
        assertEquals("motif de Google", notice.text)
        assertFalse(notice.text.contains("Google n’a pas terminé"))
    }

    @Test
    fun `message sans motif - la constante nomme l ecran ferme et le projet Google Cloud pas pret`() {
        assertTrue(CONSENT_UNFINISHED.contains("projet Google Cloud de Trackly n’est pas prêt"))
    }

    @Test
    fun `configuration Google Cloud absente - le motif de Google est affiche`() {
        f.auth.next = { app.trackly.backup.drive.DriveAuthorization.Failed("Configuration Google Cloud incomplète", misconfigured = true) }
        controller.connect()
        awaitIdle()
        assertTrue(controller.state.value.notice!!.text.contains("Google Cloud"))
    }

    @Test
    fun `sauvegarder maintenant puis restaurer a une date choisie`() {
        controller.connect()
        awaitIdle()
        controller.backupNow()
        awaitIdle()
        assertTrue(controller.state.value.notice!!.text.startsWith("Sauvegarde envoyée et vérifiée"))
        assertNotNull(controller.state.value.status.lastSuccessAt)

        controller.openRestore()
        awaitIdle()
        val choice = controller.state.value.choices!!.first()
        assertTrue(choice.source is RestoreChoice.Source.Drive)
        controller.choose(choice)
        assertEquals(choice, controller.state.value.confirming)
        controller.confirmRestore()
        awaitIdle()

        assertEquals(BackupBusy.Restarting, controller.state.value.busy)
        assertEquals(1, restarts)
        assertTrue(f.restore.stagedFile.isFile)
        assertEquals(choice.title, f.status.pendingRestoreLabel)
    }

    @Test
    fun `sauvegarde qui demande une reconnexion - ecran Google puis reprise`() {
        controller.connect()
        awaitIdle()
        f.auth.needsUserAction(pendingIntent())
        controller.backupNow()
        awaitIdle()
        assertNotNull(controller.state.value.consent)

        f.auth.granted()
        controller.onConsentResult(true)
        awaitIdle()
        assertTrue(controller.state.value.notice!!.text.startsWith("Sauvegarde envoyée"))
    }

    @Test
    fun `export d historique choisi dans Restaurer - repris directement avec son resultat a cote`() {
        importOutcome = ImportOutcome.Imported(
            ImportReport(61, null, null, 300, 70, 10, 5, 400, 0, 61, null, emptyList()),
        )
        controller.pickFile("trackly-export.json") { "  {\"format\": \"x\"}".byteInputStream() }
        awaitIdle()

        assertEquals(listOf("  {\"format\": \"x\"}"), importedTexts)
        val state = controller.state.value
        assertEquals("Historique repris : 61 journées.", state.notice!!.text)
        assertTrue(state.notice!!.nearRestore)
        assertFalse(state.notice!!.error)
        assertNull("rien à confirmer", state.confirming)
        assertEquals("aucun redémarrage", 0, restarts)
    }

    @Test
    fun `export refuse dans Restaurer - motif visible a cote des boutons`() {
        importOutcome = ImportOutcome.Refused("Fichier refusé", listOf("format inconnu"))
        controller.pickFile("trackly-export.json") { "{}".byteInputStream() }
        awaitIdle()

        val notice = controller.state.value.notice!!
        assertEquals("Fichier refusé. format inconnu. Rien n’a été repris.", notice.text)
        assertTrue(notice.error && notice.nearRestore)
    }

    @Test
    fun `fichier illisible confirme - la confirmation se ferme et le motif s affiche a cote`() {
        controller.pickFile("photo.jpg") { "pas une base".byteInputStream() }
        awaitIdle()
        assertNotNull(controller.state.value.confirming)

        controller.confirmRestore()
        awaitIdle()

        val state = controller.state.value
        assertNull(state.confirming)
        assertEquals("Ce fichier n'est pas une base lisible", state.notice!!.text)
        assertTrue(state.notice!!.error && state.notice!!.nearRestore)
        assertEquals(0, restarts)
    }

    @Test
    fun `deconnecter - nuit et copie automatique annulees, l acces Google n est jamais retire`() {
        controller.connect()
        awaitIdle()
        val callsBefore = f.auth.authorizeCalls

        controller.disconnect()
        awaitIdle()

        assertFalse(f.status.status.value.connected)
        assertEquals(1, cancelled)
        // Retirer drive.file peut cacher à Trackly ses anciennes sauvegardes. DriveAuthorizer n'a plus de retrait
        // d'accès, et Déconnecter ne parle pas du tout à Google.
        assertEquals("Déconnecter ne parle pas à Google", callsBefore, f.auth.authorizeCalls)
        assertTrue(f.auth.cleared.isEmpty())
        val text = controller.state.value.notice!!.text
        assertTrue(text, text.contains("restent dans ton Drive"))
        assertTrue(text, text.contains("Connecter Google Drive"))
        assertTrue("prévient du retrait d'accès fait depuis le compte Google : $text", text.contains("compte Google"))
        assertFalse("plus d'invitation à retirer l'accès : $text", text.contains("myaccount"))
    }

    @Test
    fun `deconnecter puis reconnecter - meme dossier, memes copies, compte et planification revenus`() {
        controller.connect()
        awaitIdle()
        val folder = f.drive.folders().single()
        controller.backupNow()
        awaitIdle()
        val copy = f.drive.childrenOf(folder.id).single()

        controller.disconnect()
        awaitIdle()
        assertNull(f.status.status.value.accountEmail)
        controller.connect()
        awaitIdle()

        val state = controller.state.value
        assertTrue(state.status.connected)
        assertFalse(state.status.needsReconnect)
        assertEquals("test@example.com", state.status.accountEmail)
        assertEquals("aucun dossier en double", listOf(folder.id), f.drive.folders().map { it.id })
        assertEquals(folder.id, f.status.folderId)
        assertTrue(state.notice!!.text, state.notice!!.text.contains("retrouvé"))
        assertEquals("nuit replanifiée à la reconnexion", 2, scheduled)
        assertEquals("copie automatique relancée à la reconnexion", 2, soon)

        controller.openRestore()
        awaitIdle()
        val listed = controller.state.value.choices!!.mapNotNull { (it.source as? RestoreChoice.Source.Drive)?.entry?.id }
        assertEquals("la copie d'avant la déconnexion est proposée", listOf(copy.id), listed)
        assertFalse(controller.state.value.folderMissing)
    }

    /** La copie Drive qui a refusé un envoi reste la référence à travers une déconnexion. */
    @Test
    fun `deconnecter puis reconnecter - le garde-fou reste actif`() = runBlocking<Unit> {
        controller.connect()
        awaitIdle()
        val folder = f.drive.folders().single()
        // Copie faite ailleurs, plus complète que l'app (4 versions) : l'envoi est refusé et elle devient la référence.
        val props = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.CONSUMPTIONS_KEY to "3", BackupFiles.VERSIONS_KEY to "6")
        val good = f.drive.addFile("trackly-2026-11-02_11-00-00.db", folder.id, byteArrayOf(7), props, createdTime = f.clock.instant.minusSeconds(1800))
        controller.backupNow()
        awaitIdle()
        assertEquals(good.id, f.status.blockingCopyId)
        assertEquals(0, f.drive.uploads())

        controller.disconnect()
        awaitIdle()
        controller.connect()
        awaitIdle()
        // Saisies pendant ou après la déconnexion : l'app finit par avoir plus de versions que la copie.
        for (i in 1..3) f.add(f.engine, BackupFixture.MONDAY, app.trackly.core.model.Moment.Snack, "Fruit $i", 60.0, 1.0, 14.0, 0.2)
        controller.backupNow()
        awaitIdle()

        assertEquals("toujours rien envoyé : la bonne copie n'a pas été reprise", 0, f.drive.uploads())
        assertEquals(good.id, f.status.blockingCopyId)
        val refusal = f.status.status.value.lastRefusalMessage!!
        assertTrue(refusal, refusal.contains("n'a pas repris la copie Drive"))
        assertFalse(f.drive.files[good.id]!!.trashed)
    }

    /** Des copies avaient réussi, mais la reconnexion ne voit plus le dossier et en crée un neuf. */
    @Test
    fun `dossier devenu invisible a la reconnexion - anciennes copies signalees, etat garde jusqu a une restauration ou Fermer`() {
        controller.connect()
        awaitIdle()
        controller.backupNow()
        awaitIdle()
        assertNotNull(f.status.status.value.lastSuccessAt)
        controller.disconnect()
        awaitIdle()
        // Accès retiré depuis le compte Google, ou dossier supprimé : Trackly ne voit plus rien de l'ancien dossier.
        f.drive.files.clear()

        controller.connect()
        awaitIdle()

        val text = controller.state.value.notice!!.text
        assertTrue(text, text.contains("ne voit plus ses anciennes copies"))
        assertTrue(text, text.contains("Depuis un fichier"))
        assertTrue(text, text.contains("Drive dans le sélecteur"))
        assertNotNull("le guide survit à la notice, qui disparaît avec le processus", f.status.status.value.hiddenCopiesAt)

        // Une autre opération efface la notice, pas l'état affiché dans Réglages.
        controller.openRestore()
        awaitIdle()
        assertNotNull(f.status.status.value.hiddenCopiesAt)

        controller.dismissHiddenCopies()
        assertNull(f.status.status.value.hiddenCopiesAt)
    }

    @Test
    fun `anciennes copies invisibles - une restauration appliquee leve l etat`() {
        f.status.markHiddenCopies(f.clock.instant())
        assertNotNull(f.status.status.value.hiddenCopiesAt)
        f.status.recordRestore(f.clock.instant(), "Fichier choisi sur le téléphone")
        assertNull(f.status.status.value.hiddenCopiesAt)
    }

    @Test
    fun `premiere connexion ou dossier retrouve - rien n est signale`() {
        controller.connect()
        awaitIdle()
        assertTrue(controller.state.value.notice!!.text.contains("créé"))
        assertNull("aucune copie n'avait réussi : rien de caché", f.status.status.value.hiddenCopiesAt)
        controller.backupNow()
        awaitIdle()
        controller.disconnect()
        awaitIdle()
        controller.connect()
        awaitIdle()
        assertTrue(controller.state.value.notice!!.text.contains("retrouvé"))
        assertNull(f.status.status.value.hiddenCopiesAt)
    }

    @Test
    fun `sans Drive - les copies du telephone restent proposees`() {
        File(f.safetyDir, "trackly-avant-restauration-20261101-120000.db").apply { parentFile!!.mkdirs(); writeText("x") }
        controller.openRestore()
        awaitIdle()
        val choices = controller.state.value.choices!!
        assertEquals(1, choices.size)
        assertTrue(choices.single().title.startsWith("Copie avant la restauration"))
    }
}

/** Conversion des fichiers du faux Drive vers le modèle de l'app. */
private fun DriveFileOf(file: FakeDrive.StoredFile) = app.trackly.backup.drive.DriveFile(
    id = file.id,
    name = file.name,
    mimeType = file.mimeType,
    parents = file.parents,
    trashed = file.trashed,
    appProperties = file.appProperties,
)
