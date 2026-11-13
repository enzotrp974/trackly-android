package app.trackly.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import app.trackly.core.model.Moment
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.io.File

/**
 * Restauration interrompue (SAUV-01 et SAUV-03) : le processus est tué à une étape du démarrage, puis encore une
 * fois à une autre (ou la même), puis l'app se lance normalement. Quel que soit l'instant, la restauration finit, une
 * seule fois, la base est celle de la copie, la copie de sécurité reste celle de la base d'origine (journal WAL compris),
 * et ce qui suit une restauration (garde-fou levé, préchargement prévenu, app utilisée) est fait.
 *
 * Restauration automatique (base remise par Auto Backup, un repas encore dans son journal WAL) et restauration choisie
 * (base utilisée). Vraies bases Room sur fichier, faux Drive, jeu d'essai fictif.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RestoreInterruptionTest(
    private val automatic: Boolean,
    private val first: RestoreStep,
    private val second: RestoreStep,
) {
    companion object {
        /** Étapes du démarrage (la publication de la base préparée a lieu avant, app en marche). */
        private val STARTUP_STEPS = RestoreStep.entries.filter { it != RestoreStep.Published && it != RestoreStep.RollingBack }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "auto={0}, arrêts {1} puis {2}")
        fun parameters(): List<Array<Any>> = listOf(true, false).flatMap { auto ->
            STARTUP_STEPS.flatMap { a -> STARTUP_STEPS.map { b -> arrayOf<Any>(auto, a, b) } }
        }
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val drive = FakeDrive()

    private fun prefsOf(phone: String): (String) -> SharedPreferences = { name ->
        context.getSharedPreferences("$phone-$name", Context.MODE_PRIVATE)
    }

    private val oldPhone = BackupFixture(drive, name = "ancien-telephone", decorate = { copy ->
        PortableSettings.attach(copy, PortableSettings.read(prefsOf("ancien")))
    })
    private lateinit var phone: BackupFixture
    private lateinit var fresh: FreshInstall
    private var installed = false
    private var open = true
    private var baseReplaced = 0
    private lateinit var original: String
    private val label = "Sauvegarde Drive du 2 novembre"

    @Before
    fun setUp() = runBlocking<Unit> {
        oldPhone.seed()
        prefsOf("ancien")("trackly-assistant").edit().putString("modele", "gpt-6.1-sol").commit()
        oldPhone.connectWithFolder()
        check(oldPhone.runner.backUp() is BackupResult.Success)

        phone = BackupFixture(drive, name = "nouveau-telephone", onOpen = { db -> if (installed) fresh.onBaseOpened(db) })
        phone.status.markConnected("test@example.com")
        // Un repas dans la base du téléphone, laissé dans le journal WAL (base remise par Auto Backup, ou saisie).
        phone.add(phone.engine, BackupFixture.MONDAY.minusDays(3), Moment.Midi, "Riz, poulet", 650.0, 45.0, 80.0, 12.0)
        closeWithoutCheckpoint()
        val prefs = context.getSharedPreferences("installation-interruption", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fresh = FreshInstall(prefs) { !automatic }
        installed = true
        phone.reopen()
        open = true
        original = BackupContents.of(phone.database.openHelper.writableDatabase).fingerprint
        assertTrue("le repas n'est que dans le journal WAL", File(phone.databaseFile.path + "-wal").length() > 0)

        val entry = phone.restore.listBackups().entries.first()
        if (automatic) {
            val outcome = AutoRestore(
                fresh = fresh,
                restore = phone.restore,
                drive = phone::api,
                folder = phone.folder,
                baseContents = { BackupContents.of(phone.database.openHelper.writableDatabase) },
                zone = TrackingDay.ZONE,
            ).check()
            assertTrue(outcome.toString(), outcome is AutoRestoreOutcome.Prepared)
            phone.status.pendingRestoreLabel = label
        } else {
            phone.restore.prepareFromDrive(entry, label)
        }
        // Une copie bloquait l'envoi : la restauration doit la lever, même interrompue.
        phone.status.blockingCopyId = "copie-bloquante"
    }

    @After
    fun close() {
        if (::phone.isInitialized) phone.close(shutdownDrive = false)
        oldPhone.close()
        for (name in PortableSettings.FILES.keys) {
            prefsOf("ancien")(name).edit().clear().commit()
            prefsOf("nouveau")(name).edit().clear().commit()
        }
    }

    /** Arrêt comme dans l'app : la base n'est pas fermée proprement, son journal WAL reste tel quel. */
    private fun closeWithoutCheckpoint() {
        val db = phone.databaseFile
        val wal = File(db.path + "-wal")
        val kept = File(context.cacheDir, "arret-brutal").apply { deleteRecursively(); mkdirs() }
        db.copyTo(File(kept, "base"), overwrite = true)
        if (wal.isFile) wal.copyTo(File(kept, "wal"), overwrite = true)
        phone.database.close()
        DatabaseFiles.deleteWithSides(db)
        File(kept, "base").copyTo(db)
        File(kept, "wal").takeIf { it.isFile }?.copyTo(wal)
        kept.deleteRecursively()
    }

    private sealed interface Launch {
        data object Killed : Launch
        data class Done(val result: RestoreApplication?) : Launch
    }

    /** Un lancement de l'app ; le processus est tué à [killAt] s'il y passe. */
    private fun launch(killAt: RestoreStep?): Launch {
        if (open) closeWithoutCheckpoint()
        open = false
        phone.clock.instant = phone.clock.instant.plusSeconds(1)
        return try {
            val result = StartupRestore.apply(
                databaseFile = phone.databaseFile,
                safetyDir = phone.safetyDir,
                clock = phone.clock,
                prefsOf = prefsOf("nouveau"),
                status = phone.status,
                fresh = fresh,
                onBaseReplaced = { baseReplaced++; true },
                onStep = { if (it == killAt) throw SimulatedKill(it) },
            )
            phone.reopen()
            open = true
            Launch.Done(result)
        } catch (_: SimulatedKill) {
            Launch.Killed
        }
    }

    @Test
    fun `la restauration finit une seule fois, et la copie de securite garde la base d origine`() = runBlocking<Unit> {
        val launches = listOf(launch(first), launch(second), launch(null), launch(null))
        val results = launches.filterIsInstance<Launch.Done>().map { it.result }

        assertEquals("$launches", Launch.Killed, launches[0])
        assertEquals("appliquée une seule fois : $launches", 1, results.count { it is RestoreApplication.Applied })
        assertTrue("$launches", results.all { it == null || it is RestoreApplication.Applied })
        assertEquals(Launch.Done(null), launches.last())

        // La base est celle de la copie Drive.
        assertEquals(
            BackupContents.of(oldPhone.database.openHelper.writableDatabase),
            BackupContents.of(phone.database.openHelper.writableDatabase),
        )
        // Chaque copie de sécurité est la base d'origine, repas du journal WAL compris.
        val safety = phone.safetyDir.listFiles { f -> f.name.startsWith(PendingRestore.SAFETY_PREFIX) && f.name.endsWith(".db") }.orEmpty()
        assertTrue("au moins une copie de sécurité", safety.isNotEmpty())
        for (copy in safety) assertEquals(copy.name, original, PendingRestore.fingerprintOf(copy))

        // Ce qui suit une restauration est fait.
        val status = phone.status.status.value
        assertEquals(label, status.lastRestoreLabel)
        assertNull("la copie bloquante est levée", phone.status.blockingCopyId)
        assertNull(phone.status.pendingRestoreLabel)
        assertTrue("à copier sur Drive", status.pending)
        assertTrue("le préchargement est prévenu", baseReplaced >= 1)
        assertFalse("l'app n'est plus neuve", fresh.state.value.fresh)
        assertEquals("gpt-6.1-sol", prefsOf("nouveau")("trackly-assistant").getString("modele", null))

        // Rien ne reste en attente à côté de la base.
        val leftovers = phone.databaseFile.parentFile!!.listFiles { f -> f.name.startsWith(phone.databaseFile.name + ".restore") }.orEmpty()
        assertTrue(leftovers.joinToString { it.name }, leftovers.isEmpty())
    }
}

/**
 * Restauration en échec au démarrage (disque plein, fichier illisible…), pas un arrêt : l'app s'ouvre quoi qu'il arrive,
 * jamais en boucle. Avant le remplacement, la base reste ; pendant, le journal fait reprendre au lancement suivant.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class RestoreFailureTest {
    private val f = BackupFixture()
    private val context: Context = f.context
    private val fresh = FreshInstall(context.getSharedPreferences("installation-echec", Context.MODE_PRIVATE).also { it.edit().clear().commit() }) { true }
    private lateinit var copy: BackupContents

    @Before
    fun setUp() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        check(f.runner.backUp() is BackupResult.Success)
        copy = BackupContents.of(f.database.openHelper.writableDatabase)
        f.add(f.engine, BackupFixture.MONDAY, Moment.Snack, "Yaourt", 120.0, 10.0, 12.0, 3.0)
        f.restore.prepareFromDrive(f.restore.listBackups().entries.first(), "Sauvegarde Drive du 2 novembre")
    }

    @After
    fun close() = f.close()

    private fun launch(vararg failAt: RestoreStep): RestoreApplication? {
        f.database.close()
        val result = StartupRestore.apply(
            databaseFile = f.databaseFile,
            safetyDir = f.safetyDir,
            clock = f.clock,
            prefsOf = { name -> context.getSharedPreferences("echec-$name", Context.MODE_PRIVATE) },
            status = f.status,
            fresh = fresh,
            onBaseReplaced = { true },
            onStep = { if (it in failAt) throw java.io.IOException("disque plein") },
        )
        f.reopen()
        return result
    }

    private fun contents() = BackupContents.of(f.database.openHelper.writableDatabase)

    @Test
    fun `echec avant le remplacement - la base reste, rien n attend plus`() = runBlocking<Unit> {
        val before = contents()

        val result = launch(RestoreStep.SafetyCopied)

        assertTrue("$result", result is RestoreApplication.Discarded)
        assertEquals(before, contents())
        assertTrue(f.status.status.value.lastRestoreDiscardedMessage!!.contains("disque plein"))
        assertFalse(f.restore.stagedFile.exists())
        assertFalse(PendingRestore.journalFile(f.databaseFile).exists())
        assertTrue("copie de sécurité commencée retirée", f.restore.localCopies().isEmpty())
        assertNull(launch())
    }

    @Test
    fun `echec pendant le remplacement - la base d origine revient avant toute ecriture, une saisie ensuite reste (SAUV-06)`() = runBlocking<Unit> {
        val before = contents()

        val result = launch(RestoreStep.SidesDeleted)

        assertTrue("$result", result is RestoreApplication.Discarded)
        assertEquals("la base d'origine est remise", before, contents())
        assertTrue(f.status.status.value.lastRestoreDiscardedMessage!!.contains("disque plein"))
        assertFalse("rien n'attend plus", PendingRestore.journalFile(f.databaseFile).exists())
        assertFalse(f.restore.stagedFile.exists())
        // L'utilisateur note un repas, puis l'app redémarre : rien ne l'écrase.
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Soupe", 300.0, 12.0, 30.0, 10.0)
        val typed = contents()
        assertNull(launch())
        assertEquals(typed, contents())
        assertEquals(before.activeConsumptions + 1, typed.activeConsumptions)
    }

    @Test
    fun `echec du remplacement puis du retour - donnees presentes gardees, sauvegarde preparee gardee a part, jamais appliquee seule (SAUV-06)`() = runBlocking<Unit> {
        val result = launch(RestoreStep.SidesDeleted, RestoreStep.RollingBack)

        assertTrue("$result", result is RestoreApplication.Discarded)
        val message = f.status.status.value.lastRestoreDiscardedMessage!!
        assertTrue(message, message.contains("Choisir une sauvegarde"))
        assertFalse(PendingRestore.journalFile(f.databaseFile).exists())
        assertFalse("plus rien ne s'appliquera tout seul", f.restore.stagedFile.exists())
        val kept = f.restore.localCopies().single { it.file.name.startsWith(PendingRestore.UNAPPLIED_PREFIX) }
        assertTrue(kept.label, kept.label.contains("non appliquée"))
        assertEquals("la sauvegarde préparée reste récupérable", copy, BackupContents.of(kept.file))
        // Une saisie, puis un redémarrage : elle reste.
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Soupe", 300.0, 12.0, 30.0, 10.0)
        val typed = contents()
        assertNull(launch())
        assertEquals(typed, contents())
    }

    @Test
    fun `finalisation non enregistree - le journal reste, l app s ouvre, et le lancement suivant la refait sans rien ecraser (SAUV-03)`() = runBlocking<Unit> {
        val disk = FlakyPrefs.Disk()
        BackupStatusStore(FlakyPrefs(disk, "statut")).apply {
            pendingRestoreLabel = "Sauvegarde Drive du 2 novembre"
            blockingCopyId = "copie-bloquante"
        }
        FreshInstall(FlakyPrefs(disk, "installation")) { false }
        disk.failing = true

        fun startup(): RestoreApplication? {
            f.database.close()
            val result = StartupRestore.apply(
                databaseFile = f.databaseFile,
                safetyDir = f.safetyDir,
                clock = f.clock,
                prefsOf = { name -> context.getSharedPreferences("echec-$name", Context.MODE_PRIVATE) },
                // Le processus lit ses préférences sur le disque : de nouvelles instances à chaque lancement.
                status = BackupStatusStore(FlakyPrefs(disk, "statut")),
                fresh = FreshInstall(FlakyPrefs(disk, "installation")) { false },
                onBaseReplaced = { FlakyPrefs(disk, "mise-en-route").edit().putBoolean("historique_embarque", true).commit() },
            )
            f.reopen()
            return result
        }

        val first = startup()
        assertTrue("$first", first is RestoreApplication.Interrupted)
        assertTrue("le journal reste", PendingRestore.journalFile(f.databaseFile).exists())
        assertEquals("la base est remplacée, l'app s'ouvre", copy, contents())
        // Une saisie avant le lancement suivant : la reprise ne remplace plus la base.
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Soupe", 300.0, 12.0, 30.0, 10.0)
        val typed = contents()

        disk.failing = false
        assertTrue(startup() is RestoreApplication.Applied)
        assertEquals(typed, contents())
        val status = BackupStatusStore(FlakyPrefs(disk, "statut"))
        assertEquals("Sauvegarde Drive du 2 novembre", status.status.value.lastRestoreLabel)
        assertNull(status.blockingCopyId)
        assertFalse(FreshInstall(FlakyPrefs(disk, "installation")) { false }.state.value.fresh)
        assertTrue(FlakyPrefs(disk, "mise-en-route").getBoolean("historique_embarque", false))
        assertFalse(PendingRestore.journalFile(f.databaseFile).exists())
        assertNull(startup())
    }
}

/**
 * Préférences dont l'écriture sur le disque peut échouer (`commit()` renvoie faux) : le processus garde ses valeurs en
 * mémoire, une nouvelle instance relit le disque. Comme Android, qui met à jour la mémoire avant d'écrire.
 */
class FlakyPrefs(private val disk: Disk, private val name: String) : SharedPreferences {
    class Disk {
        @Volatile var failing = false
        val files = mutableMapOf<String, MutableMap<String, Any?>>()
    }

    private val memory: MutableMap<String, Any?> = HashMap(disk.files[name].orEmpty())

    override fun getAll(): MutableMap<String, *> = HashMap(memory)
    override fun getString(key: String, defValue: String?) = memory[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = memory[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = memory[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = memory[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = memory[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = memory[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in memory
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { changes[key] = values }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { changes[key] = REMOVED }
        override fun clear() = also { clear = true }
        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            synchronized(disk) {
                if (clear) memory.clear()
                for ((k, v) in changes) if (v === REMOVED || v == null) memory.remove(k) else memory[k] = v
                if (disk.failing) return false
                disk.files[name] = HashMap(memory)
                return true
            }
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
