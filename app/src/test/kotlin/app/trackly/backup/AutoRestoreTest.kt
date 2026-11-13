package app.trackly.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.model.Moment
import app.trackly.core.time.TrackingDay
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Restauration automatique sur une app neuve, de bout en bout : un « ancien téléphone » envoie sa copie sur le faux
 * Drive (réglages joints), puis un « nouveau téléphone » (app vidée ou réinstallée) se connecte. Vraies bases Room,
 * faux Drive, faux `AuthorizationClient`, redémarrage simulé comme dans l'app. Jeu d'essai fictif.
 */
@RunWith(AndroidJUnit4::class)
class AutoRestoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val drive = FakeDrive()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracklyDatabase::class.java)

    /** Préférences d'un téléphone simulé (les fichiers de réglages de l'app, préfixés). */
    private fun prefsOf(phone: String): (String) -> SharedPreferences = { name ->
        context.getSharedPreferences("$phone-$name", Context.MODE_PRIVATE)
    }

    private val oldPhone = BackupFixture(drive, name = "ancien-telephone", decorate = { copy ->
        PortableSettings.attach(copy, PortableSettings.read(prefsOf("ancien")))
    })
    private lateinit var phone: BackupFixture
    private lateinit var fresh: FreshInstall
    private lateinit var controller: BackupController
    private var restarts = 0

    @After
    fun close() {
        scope.cancel()
        if (::phone.isInitialized) phone.close(shutdownDrive = false)
        oldPhone.close()
        for (name in PortableSettings.FILES.keys) {
            prefsOf("ancien")(name).edit().clear().commit()
            prefsOf("nouveau")(name).edit().clear().commit()
        }
        context.deleteDatabase("copie-v8.db")
        context.deleteDatabase("copie-v9.db")
    }

    /** L'ancien téléphone : des repas, des réglages, une copie vérifiée sur Drive. */
    private fun oldPhoneBacksUp(): FakeDrive.StoredFile = runBlocking {
        oldPhone.seed()
        prefsOf("ancien")("trackly-assistant").edit().putString("modele", "gpt-6.1-sol").commit()
        prefsOf("ancien")("trackly-dictee").edit().putString("mode", "after_stop").putBoolean("micro_demande", true).commit()
        prefsOf("ancien")("trackly-bilan").edit().putBoolean("rappels_actifs", false).putInt("invitation_minutes", 600).commit()
        val folder = oldPhone.connectWithFolder()
        assertTrue(oldPhone.runner.backUp() is BackupResult.Success)
        folder
    }

    /**
     * Le nouveau téléphone, tel que l'app l'assemble : [beforeInstall] remplit la base avant le premier lancement
     * (Android Auto Backup), [previousInstall] simule une mise à jour par-dessus.
     */
    private fun newPhone(previousInstall: Boolean = false, beforeInstall: suspend (BackupFixture) -> Unit = {}) = runBlocking {
        var installed = false
        // Le rappel d'ouverture Room de l'app, branché seulement à partir du premier lancement.
        phone = BackupFixture(drive, name = "nouveau-telephone", onOpen = { db -> if (installed) fresh.onBaseOpened(db) })
        beforeInstall(phone)
        val prefs = context.getSharedPreferences("installation-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fresh = FreshInstall(prefs) { previousInstall }
        // Premier lancement : la base (vide, ou remise par Auto Backup) est ouverte par l'app.
        phone.database.close()
        installed = true
        phone.reopen()
        phone.database.openHelper.writableDatabase
        controller = BackupController(
            runner = phone.runner,
            restore = phone.restore,
            folder = phone.folder,
            authorizer = phone.auth,
            drive = phone::api,
            status = phone.status,
            scheduling = object : BackupScheduling {
                override fun schedule() = Unit
                override fun backupSoon() = Unit
                override fun cancel() = Unit
            },
            restarter = { restarts++ },
            scope = scope,
            workDir = File(context.cacheDir, "controleur-auto"),
            zone = TrackingDay.ZONE,
            importHistory = { ImportOutcome.Refused("non") },
            autoRestore = AutoRestore(
                fresh = fresh,
                restore = phone.restore,
                drive = phone::api,
                folder = phone.folder,
                baseContents = { BackupContents.of(phone.database.openHelper.writableDatabase) },
                zone = TrackingDay.ZONE,
            ),
            fresh = fresh,
        )
    }

    private fun connect() {
        controller.connect()
        runBlocking {
            withTimeout(20_000) {
                while (controller.state.value.busy.let { it != null && it != BackupBusy.Restarting }) delay(10)
            }
        }
    }

    private var baseReplaced = 0

    /**
     * Redémarrage comme dans l'app : le processus s'arrête sans fermer la base (`exit(0)`, journal WAL non intégré),
     * puis [StartupRestore] s'applique avant la réouverture.
     */
    private fun restartLikeTheApp(): RestoreApplication? {
        val db = phone.databaseFile
        val wal = File(db.path + "-wal")
        val kept = File(context.cacheDir, "arret-brutal").apply { deleteRecursively(); mkdirs() }
        db.copyTo(File(kept, "base"), overwrite = true)
        if (wal.isFile) wal.copyTo(File(kept, "wal"), overwrite = true)
        phone.database.close()
        // L'état des fichiers au moment de l'arrêt : base et journal tels qu'ils étaient, sans intégration.
        DatabaseFiles.deleteWithSides(db)
        File(kept, "base").copyTo(db)
        File(kept, "wal").takeIf { it.isFile }?.copyTo(wal)
        kept.deleteRecursively()
        val applied = StartupRestore.apply(
            databaseFile = db,
            safetyDir = phone.safetyDir,
            clock = phone.clock,
            prefsOf = prefsOf("nouveau"),
            status = phone.status,
            fresh = fresh,
            onBaseReplaced = { baseReplaced++; true },
        )
        phone.reopen()
        return applied
    }

    private fun counts(f: BackupFixture) = BackupContents.of(f.database.openHelper.writableDatabase)

    @Test
    fun `app videe puis connectee - la derniere copie revient sans autre geste, totaux et reglages compris`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        val expectedDay = oldPhone.engine.daySummary(BackupFixture.MONDAY)
        val expectedEve = oldPhone.engine.daySummary(BackupFixture.MONDAY.minusDays(1))
        newPhone()
        assertTrue(fresh.state.value.offerPending)

        connect()

        assertEquals("un seul geste : la connexion ; l'app redémarre d'elle-même", 1, restarts)
        assertEquals(BackupBusy.Restarting, controller.state.value.busy)
        assertTrue(controller.state.value.notice!!.text.contains("redémarre"))
        assertFalse("la proposition a sa réponse", fresh.state.value.offerPending)
        assertFalse("le préchargement ne passe pas pendant la restauration", fresh.awaitPreloadChoice())

        val applied = restartLikeTheApp()
        assertTrue(applied is RestoreApplication.Applied)
        assertEquals(expectedDay, phone.engine.daySummary(BackupFixture.MONDAY))
        assertEquals(expectedEve, phone.engine.daySummary(BackupFixture.MONDAY.minusDays(1)))
        assertEquals(counts(oldPhone), counts(phone))
        // Réglages non secrets revenus ; ce qui dépend de l'appareil ne voyage pas.
        assertEquals("gpt-6.1-sol", prefsOf("nouveau")("trackly-assistant").getString("modele", null))
        assertEquals("after_stop", prefsOf("nouveau")("trackly-dictee").getString("mode", null))
        assertFalse(prefsOf("nouveau")("trackly-dictee").contains("micro_demande"))
        assertFalse(prefsOf("nouveau")("trackly-bilan").getBoolean("rappels_actifs", true))
        assertEquals(600, prefsOf("nouveau")("trackly-bilan").getInt("invitation_minutes", 0))
        // La table des réglages n'entre jamais dans la base de l'app.
        assertFalse(tableExists(phone, PortableSettings.TABLE))
        assertFalse("restaurée : l'app n'est plus neuve", fresh.state.value.fresh)
        assertEquals("le préchargement est prévenu", 1, baseReplaced)
        assertNotNull(phone.status.status.value.lastRestoreAt)
    }

    @Test
    fun `app utilisee depuis l installation - jamais remplacee, meme avec une copie Drive`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        phone.add(phone.engine, BackupFixture.MONDAY, Moment.Snack, "Pomme", 80.0, 0.4, 19.0, 0.2)
        val before = counts(phone)

        connect()

        assertEquals(0, restarts)
        assertFalse(phone.restore.stagedFile.exists())
        assertTrue(phone.status.status.value.connected)
        assertTrue(controller.state.value.notice!!.text.contains("retrouvé"))
        assertNull(phone.restartApp())
        assertEquals(before, counts(phone))
    }

    @Test
    fun `mise a jour par-dessus - jamais une app neuve, meme base vide`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone(previousInstall = true)
        assertFalse(fresh.state.value.fresh)
        assertTrue("rien n'attend une réponse : le préchargement passe", fresh.awaitPreloadChoice())

        connect()

        assertEquals(0, restarts)
        assertFalse(phone.restore.stagedFile.exists())
        assertEquals(0L, counts(phone).rows)
    }

    @Test
    fun `base remise par Auto Backup, plus ancienne que Drive - remplacee par la copie la plus recente`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone { p ->
            // Auto Backup a rendu une base d'avant : un seul repas, d'un autre jour.
            p.add(p.engine, BackupFixture.MONDAY.minusDays(3), Moment.Midi, "Riz, poulet", 650.0, 45.0, 80.0, 12.0)
        }
        assertTrue("une base remise avant le premier lancement laisse l'app neuve", fresh.state.value.fresh)

        connect()

        assertEquals(1, restarts)
        assertTrue(restartLikeTheApp() is RestoreApplication.Applied)
        assertEquals(counts(oldPhone), counts(phone))
        // La copie de sécurité garde la base remise.
        assertTrue(phone.restore.localCopies().any { it.file.name.startsWith(PendingRestore.SAFETY_PREFIX) })
    }

    @Test
    fun `base remise par Auto Backup, aussi complete que Drive - elle reste`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        val copy = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        newPhone { p -> autoBackupBase(p, copy.content) }
        val before = counts(phone)

        connect()

        assertEquals(0, restarts)
        assertFalse(phone.restore.stagedFile.exists())
        assertTrue(controller.state.value.notice!!.text.contains("restent"))
        assertEquals(before, counts(phone))
    }

    @Test
    fun `Auto Backup et ancienne copie sans compte de versions - comparee apres telechargement`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        val copy = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        // Copie d'avant la sauvegarde automatique : ni consommations ni versions notées.
        copy.appProperties.remove(BackupFiles.VERSIONS_KEY)
        copy.appProperties.remove(BackupFiles.CONSUMPTIONS_KEY)
        newPhone { p -> autoBackupBase(p, copy.content) }

        connect()

        assertEquals(0, restarts)
        assertFalse("préparée puis abandonnée : rien n'attend", phone.restore.stagedFile.exists())
        assertFalse(PendingRestore.conditionFile(phone.databaseFile).exists())
    }

    @Test
    fun `Auto Backup plus complet qu une ancienne copie sans compte - un arret pendant la decision ne la restaure jamais`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        val copy = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        // Copie d'avant la sauvegarde automatique : ni consommations ni versions notées.
        copy.appProperties.remove(BackupFiles.VERSIONS_KEY)
        copy.appProperties.remove(BackupFiles.CONSUMPTIONS_KEY)
        // Auto Backup rend une base plus complète que cette copie : la copie, plus un repas.
        newPhone { p ->
            autoBackupBase(p, copy.content)
            p.add(p.engine, BackupFixture.MONDAY, Moment.Snack, "Yaourt", 120.0, 10.0, 12.0, 3.0)
        }
        val before = counts(phone)
        // Le processus est tué dès qu'une base préparée est posée.
        val killing = phone.restoreManager { if (it == RestoreStep.Published) throw SimulatedKill(it) }
        val auto = AutoRestore(fresh, killing, phone::api, phone.folder, { counts(phone) }, TrackingDay.ZONE)

        val outcome = try {
            auto.check()
        } catch (_: SimulatedKill) {
            null
        }

        assertTrue("$outcome", outcome is AutoRestoreOutcome.KeptBase)
        assertFalse("rien n'attend le redémarrage", phone.restore.stagedFile.exists())
        assertNull(restartLikeTheApp())
        assertEquals(before, counts(phone))
    }

    @Test
    fun `ancienne copie vide sans compte note - ignoree, la precedente est restauree`() = runBlocking<Unit> {
        val folder = oldPhoneBacksUp()
        // Une copie plus récente, d'avant la sauvegarde automatique (rien de noté), qui ne contient aucune consommation.
        val empty = BackupFixture(drive, name = "telephone-vide")
        val emptyFile = File(context.cacheDir, "copie-vide.db").also { DatabaseFiles.deleteWithSides(it) }
        DatabaseSnapshotter({ empty.database }).snapshot(emptyFile)
        empty.close(shutdownDrive = false)
        drive.now = drive.now.plusSeconds(3_600)
        drive.addFile("trackly-2026-11-03_04-00-00.db", folder.id, emptyFile.readBytes(), mapOf(
            BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE,
            BackupFiles.SCHEMA_KEY to "${TracklyDatabase.VERSION}",
            BackupFiles.SHA256_KEY to DatabaseFiles.sha256(emptyFile),
        ))
        emptyFile.delete()
        newPhone()

        connect()

        assertEquals(1, restarts)
        assertTrue(restartLikeTheApp() is RestoreApplication.Applied)
        assertEquals(counts(oldPhone), counts(phone))
    }

    @Test
    fun `aucune copie sur Drive - rien a restaurer, le prechargement peut passer`() = runBlocking<Unit> {
        oldPhone.connectWithFolder() // dossier présent, vide
        newPhone()
        val preload = async(Dispatchers.Default) { fresh.awaitPreloadChoice() }
        assertFalse(preload.isCompleted)

        connect()

        assertEquals(0, restarts)
        assertTrue(controller.state.value.notice!!.text.contains("sans sauvegarde à reprendre"))
        assertNull("dossier visible : rien de caché à signaler", phone.status.status.value.hiddenCopiesAt)
        assertTrue("connecté sans copie : préchargement normal", withTimeout(5_000) { preload.await() })
    }

    @Test
    fun `dossier introuvable - l app guide vers Depuis un fichier`() = runBlocking<Unit> {
        newPhone()

        connect()

        assertEquals(0, restarts)
        val text = controller.state.value.notice!!.text
        assertTrue(text, text.contains("Depuis un fichier"))
        assertTrue("le sélecteur ouvre Drive sans passer par l'app Drive : $text", text.contains("choisir Drive directement"))
        assertTrue(text, text.contains("Nouveau dossier"))
        assertTrue(phone.status.status.value.connected)
        // Seul guide de l'utilisateur ce jour-là ; il reste dans Réglages › Sauvegarde, même après la fin du processus.
        assertNotNull(phone.status.status.value.hiddenCopiesAt)
    }

    @Test
    fun `connexion refusee - rien n est restaure, la proposition attend, Plus tard laisse passer le prechargement`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        phone.auth.needsUserAction(PendingIntent.getActivity(context, 0, Intent("app.trackly.TEST"), PendingIntent.FLAG_IMMUTABLE))
        connect()
        controller.onConsentResult(granted = false)

        assertEquals(0, restarts)
        assertFalse(phone.status.status.value.connected)
        assertTrue("sans réponse, la proposition reste", fresh.state.value.offerPending)
        val preload = async(Dispatchers.Default) { fresh.awaitPreloadChoice() }
        delay(50)
        assertFalse(preload.isCompleted)

        fresh.decline() // « Plus tard »
        assertTrue(withTimeout(5_000) { preload.await() })
        assertFalse(phone.restore.stagedFile.exists())
    }

    @Test
    fun `refus puis connexion plus tard sur une app restee vide - la copie revient quand meme`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        fresh.decline()

        connect()

        assertEquals(1, restarts)
        assertTrue(restartLikeTheApp() is RestoreApplication.Applied)
        assertEquals(counts(oldPhone), counts(phone))
    }

    @Test
    fun `saisie entre la decision et le redemarrage - remplacement annule, la saisie reste`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        connect()
        assertEquals(1, restarts)
        // Juste avant que l'app ne redémarre, une saisie arrive.
        phone.add(phone.engine, BackupFixture.MONDAY, Moment.Snack, "Yaourt", 120.0, 10.0, 12.0, 3.0)
        val typed = counts(phone)
        assertTrue("la saisie n'est encore que dans le journal WAL", File(phone.databaseFile.path + "-wal").length() > 0)

        val applied = restartLikeTheApp()

        assertTrue(applied is RestoreApplication.Discarded)
        assertEquals(typed, counts(phone))
        assertFalse(phone.restore.stagedFile.exists())
        assertFalse(PendingRestore.settingsFile(phone.databaseFile).exists())
    }

    @Test
    fun `copie vide ignoree - la plus recente qui a des consommations est restauree`() = runBlocking<Unit> {
        val folder = oldPhoneBacksUp()
        val good = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        drive.now = drive.now.plusSeconds(3_600)
        drive.addFile(
            "trackly-2026-11-03_04-00-00.db", folder.id, ByteArray(10),
            good.appProperties + mapOf(BackupFiles.CONSUMPTIONS_KEY to "0"),
        )
        newPhone()

        connect()

        assertEquals(1, restarts)
        assertTrue(restartLikeTheApp() is RestoreApplication.Applied)
        assertEquals(counts(oldPhone), counts(phone))
    }

    @Test
    fun `copie Drive en schema 8 sur une app neuve - migree jusqu a la version courante puis restauree`() = oldCopyRestored(8)

    @Test
    fun `copie Drive en schema 9 sur une app neuve - migree jusqu a la version courante puis restauree`() = oldCopyRestored(9)

    /** Copie faite par une version précédente de l'app : ni réglages joints, ni compte de versions. */
    private fun oldCopyRestored(schema: Int) = runBlocking<Unit> {
        val name = "copie-v$schema.db"
        helper.createDatabase(name, schema).use { db ->
            db.execSQL(
                "INSERT INTO actions (id, type, title, applied_at, undoes_action_id, undone_by_action_id) " +
                    "VALUES (1, 'consumption.add', 'Ajout : Pomme (80 kcal)', 1793620200000, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO consumption_versions (entry_id, version, state, day, consumed_at, moment, label, " +
                    "quantity_value, quantity_unit, kcal, protein_g, carbs_g, fat_g, fiber_g, product_id, recipe_id, " +
                    "source_type, source_description, source_url, confidence, origin, recorded_at, action_id) " +
                    "VALUES ('e1', 1, 'active', '2026-11-02', 1793620200000, 'snack', 'Pomme', 150, 'g', 80, 0.5, 21, NULL, " +
                    "3.6, NULL, NULL, NULL, NULL, NULL, NULL, 'dev_panel', 1793620200000, 1)",
            )
            // Un message du chat : la migration 9 → 10 lui donne sa date de rattachement.
            db.execSQL("INSERT INTO chat_messages (id, created_at, role, text) VALUES (1, 1793620200000, 'user', 'Une pomme')")
        }
        val old = context.getDatabasePath(name)
        val folder = oldPhone.connectWithFolder()
        val snapshot = Snapshot(old, schema, old.length(), DatabaseFiles.md5(old), DatabaseFiles.sha256(old))
        drive.addFile("trackly-2026-11-01_03-00-00.db", folder.id, old.readBytes(), mapOf(
            BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE,
            BackupFiles.SCHEMA_KEY to "$schema",
            BackupFiles.SHA256_KEY to snapshot.sha256,
        ))
        newPhone()
        prefsOf("nouveau")("trackly-assistant").edit().putString("modele", "choisi-ici").commit()

        connect()

        assertEquals(1, restarts)
        val applied = restartLikeTheApp() as RestoreApplication.Applied
        assertNull("copie sans réglages : ceux de l'app restent", applied.settings)
        assertEquals("choisi-ici", prefsOf("nouveau")("trackly-assistant").getString("modele", null))
        assertEquals("Pomme", phone.engine.daySummary(BackupFixture.MONDAY).consumptions.single().food.label)
        assertEquals(TracklyDatabase.VERSION, DatabaseFiles.inspect(phone.databaseFile)!!.schemaVersion)
        phone.database.openHelper.writableDatabase.query("SELECT day, text FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-11-02", c.getString(0))
            assertEquals("Une pomme", c.getString(1))
        }
        context.deleteDatabase(name)
    }

    @Test
    fun `apres la restauration automatique, la copie suivante part sans refus`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        connect()
        restartLikeTheApp()
        phone.clock.instant = phone.clock.instant.plusSeconds(600)

        val result = phone.runner.backUp()

        assertTrue(result.toString(), result is BackupResult.Success)
        assertNotNull(phone.status.status.value.lastSuccessAt)
    }

    /** Base rendue par Android Auto Backup : la base du téléphone, sans les réglages qu'une copie Drive emporte. */
    private fun autoBackupBase(p: BackupFixture, bytes: ByteArray) {
        p.database.close()
        DatabaseFiles.deleteWithSides(p.databaseFile)
        p.databaseFile.writeBytes(bytes)
        android.database.sqlite.SQLiteDatabase.openDatabase(p.databaseFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { it.execSQL("DROP TABLE IF EXISTS ${PortableSettings.TABLE}") }
        p.reopen()
    }

    @Test
    fun `derniere copie abimee - la precedente est restauree`() = runBlocking<Unit> {
        val folder = oldPhoneBacksUp()
        val good = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        drive.now = drive.now.plusSeconds(3_600)
        // Plus récente, mais son contenu ne correspond pas à l'empreinte notée à l'envoi.
        drive.addFile("trackly-2026-11-03_04-00-00.db", folder.id, good.content.copyOf().also { it[it.size - 1] = 1 }, good.appProperties)
        newPhone()

        connect()

        assertEquals(1, restarts)
        assertTrue(restartLikeTheApp() is RestoreApplication.Applied)
        assertEquals(counts(oldPhone), counts(phone))
    }

    @Test
    fun `derniere copie d une version plus recente de l app - rien de restaure, la proposition reste`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        val copy = drive.files.values.single { it.appProperties[BackupFiles.KIND_KEY] == BackupFiles.KIND_DATABASE }
        copy.appProperties[BackupFiles.SCHEMA_KEY] = "${TracklyDatabase.VERSION + 1}"
        newPhone()

        connect()

        assertEquals(0, restarts)
        assertFalse(phone.restore.stagedFile.exists())
        val notice = controller.state.value.notice!!
        assertTrue(notice.error)
        assertTrue(notice.text, notice.text.contains("version plus récente"))
        assertTrue("connecté quand même", phone.status.status.value.connected)
        assertTrue("la proposition reste ouverte", fresh.state.value.offerPending)
    }

    @Test
    fun `Plus tard pendant la recherche - ignore, le prechargement attend la fin`() = runBlocking<Unit> {
        oldPhoneBacksUp()
        newPhone()
        val preload = async(Dispatchers.Default) { fresh.awaitPreloadChoice() }
        fresh.startSearch()
        fresh.decline()
        assertTrue("refus ignoré pendant la recherche", fresh.state.value.offerPending)
        fresh.endSearch()

        connect()

        assertEquals(1, restarts)
        assertFalse("restauration préparée : pas de préchargement", withTimeout(5_000) { preload.await() })
    }

    private fun tableExists(f: BackupFixture, table: String): Boolean =
        f.database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .use { it.moveToFirst() && it.getInt(0) == 1 }
}
