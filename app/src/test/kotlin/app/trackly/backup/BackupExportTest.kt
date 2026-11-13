package app.trackly.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.time.TrackingDay
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/** Un « document » choisi dans le sélecteur de fichiers Android, simulé par un fichier. */
private class FileTarget(
    private val file: File,
    override val displayName: String? = file.name,
    private val output: Boolean = true,
    private val failWriting: Boolean = false,
    private val crashOpening: Boolean = false,
) : ExportTarget {
    var discarded = false
        private set

    override fun openOutput(): OutputStream? {
        if (crashOpening) throw SecurityException("autorisation retirée")
        if (!output) return null
        val stream = file.outputStream()
        if (failWriting) {
            return object : OutputStream() {
                override fun write(b: Int) = throw IOException("disque plein")
            }.also { stream.close() }
        }
        return stream
    }

    override fun openInput(): InputStream? = file.inputStream()

    override fun discard() {
        discarded = true
        file.delete()
    }

    override fun discardIfEmpty() {
        if (file.length() == 0L) discard()
    }
}

/** Export en fichier : la même copie cohérente et vérifiée que la sauvegarde, reprise par « Depuis un fichier ». */
@RunWith(AndroidJUnit4::class)
class BackupExportTest {
    private lateinit var f: BackupFixture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var restarts = 0
    private lateinit var controller: BackupController
    private lateinit var out: File

    @Before
    fun setUp() {
        f = BackupFixture()
        out = File(f.context.cacheDir, "export-test").apply { deleteRecursively(); mkdirs() }
        controller = BackupController(
            runner = f.runner,
            restore = f.restore,
            folder = f.folder,
            authorizer = f.auth,
            drive = f::api,
            status = f.status,
            scheduling = object : BackupScheduling {
                override fun schedule() = Unit
                override fun backupSoon() = Unit
                override fun cancel() = Unit
            },
            restarter = { restarts++ },
            scope = scope,
            workDir = File(f.context.cacheDir, "controleur-export"),
            zone = TrackingDay.ZONE,
            importHistory = { ImportOutcome.Refused("non utilisé") },
        )
    }

    @After
    fun close() {
        scope.cancel()
        f.close()
        out.deleteRecursively()
    }

    private fun awaitIdle() = runBlocking {
        withTimeout(10_000) {
            while (controller.state.value.busy.let { it != null && it != BackupBusy.Restarting }) delay(10)
        }
    }

    private fun target(name: String = "trackly-export.db") = FileTarget(File(out, name))

    @Test
    fun `export puis Depuis un fichier - les totaux du jour reviennent identiques`() = runBlocking<Unit> {
        f.seed()
        val before = f.engine.daySummary(BackupFixture.MONDAY)
        val destination = target()

        val result = f.runner.exportTo(destination)

        assertTrue(result.toString(), result is ExportResult.Success)
        assertTrue(File(out, "trackly-export.db").length() > 0)
        assertFalse(destination.discarded)

        // La journée change après l'export, puis on reprend le fichier comme l'écran « Depuis un fichier ».
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Pizza", 900.0, 30.0, 100.0, 35.0)
        assertNotEquals(before.totals, f.engine.daySummary(BackupFixture.MONDAY).totals)

        controller.pickFile("trackly-export.db") { destination.openInput() }
        awaitIdle()
        assertEquals("trackly-export.db", controller.state.value.confirming?.title)
        controller.confirmRestore()
        awaitIdle()
        assertEquals(1, restarts)
        f.restart()

        val after = f.engine.daySummary(BackupFixture.MONDAY)
        assertEquals(before.totals, after.totals)
        assertEquals(before.consumptions.map { it.food }, after.consumptions.map { it.food })
        assertEquals(before.goal, after.goal)
    }

    @Test
    fun `le fichier exporte est la copie de la sauvegarde - meme contenu, meme controle`() = runBlocking<Unit> {
        f.seed()
        val exported = File(out, "export.db")
        val snapshot = File(out, "snapshot-attendu.db")
        val expected = DatabaseSnapshotter({ f.database }).snapshot(snapshot)

        val result = f.runner.exportTo(FileTarget(exported)) as ExportResult.Success

        assertEquals(expected.sizeBytes, exported.length())
        assertEquals(expected.sha256, DatabaseFiles.sha256(exported))
        assertEquals(expected.sizeBytes, result.sizeBytes)
        val info = DatabaseFiles.inspect(exported)!!
        assertTrue(info.intact && info.isRoomDatabase)
        assertEquals(3, BackupContents.of(exported).activeConsumptions)
        // Un seul fichier : pas de journal WAL à côté.
        assertFalse(File(exported.path + "-wal").exists())
    }

    @Test
    fun `nom propose - celui des sauvegardes datees, reconnu par la liste de Drive`() {
        val name = f.runner.exportName()

        assertEquals("trackly-2026-11-02_12-30-00.db", name)
        val file = app.trackly.backup.drive.DriveFile(
            id = "x",
            name = name,
            mimeType = BackupFiles.MIME,
            parents = listOf("dossier"),
            trashed = false,
            appProperties = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE),
        )
        assertTrue(BackupFiles.isTracklyBackup(file, "dossier"))
    }

    @Test
    fun `sans Google Drive - l export marche, rien n est envoye et l etat de la sauvegarde ne bouge pas`() = runBlocking<Unit> {
        f.seed()
        assertFalse(f.status.status.value.connected)

        assertTrue(f.runner.exportTo(target()) is ExportResult.Success)

        assertTrue("aucune requête vers Drive", f.drive.requests.isEmpty())
        val status = f.status.status.value
        assertNull(status.lastSuccessAt)
        assertNull(status.lastRefusalAt)
        assertNull(status.lastFailureAt)
    }

    @Test
    fun `base entierement vide - rien a exporter, aucun fichier garde`() = runBlocking<Unit> {
        val destination = target()
        File(out, "trackly-export.db").createNewFile() // le sélecteur vient de créer le document, vide

        assertEquals(ExportResult.NothingToExport, f.runner.exportTo(destination))

        assertTrue(destination.discarded)
        assertFalse(File(out, "trackly-export.db").exists())
        assertFalse(f.runner.hasDataToExport())
    }

    @Test
    fun `fichier existant choisi pour etre remplace - jamais detruit tant que rien n a ete ecrit`() = runBlocking<Unit> {
        val file = File(out, "trackly-export.db").apply { writeText("mon dernier bon export") }

        // Base vide : rien à exporter, et le fichier existant reste intact.
        assertEquals(ExportResult.NothingToExport, f.runner.exportTo(FileTarget(file)))
        assertEquals("mon dernier bon export", file.readText())
    }

    @Test
    fun `fichier existant qui refuse l ouverture - jamais detruit (CHAT-04)`() = runBlocking<Unit> {
        f.seed()
        val file = File(out, "trackly-export.db").apply { writeText("mon dernier bon export") }
        val destination = FileTarget(file, crashOpening = true)

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertFalse(destination.discarded)
        assertEquals("mon dernier bon export", file.readText())
    }

    @Test
    fun `fichier existant dont l emplacement ne s ouvre pas - jamais detruit (CHAT-04)`() = runBlocking<Unit> {
        f.seed()
        val file = File(out, "trackly-export.db").apply { writeText("mon dernier bon export") }
        val destination = FileTarget(file, output = false)

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertFalse(destination.discarded)
        assertEquals("mon dernier bon export", file.readText())
    }

    @Test
    fun `erreur autre qu une IOException a l ouverture - echec clair et fichier retire`() = runBlocking<Unit> {
        f.seed()
        val destination = FileTarget(File(out, "export.db"), crashOpening = true)

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertTrue((result as ExportResult.Failure).message.contains("autorisation retirée"))
        assertTrue(destination.discarded)
    }

    @Test
    fun `objectif sans aucun repas - exporte quand meme, le garde-fou de Drive ne s applique pas`() = runBlocking<Unit> {
        check(f.engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)

        assertTrue(f.runner.hasDataToExport())
        assertTrue(f.runner.exportTo(target()) is ExportResult.Success)
        assertEquals(0, BackupContents.of(File(out, "trackly-export.db")).activeConsumptions)
    }

    @Test
    fun `base plus ancienne que la copie Drive - l export part quand meme`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        f.drive.addFile(
            "trackly-2026-11-01_03-00-00.db",
            folder.id,
            ByteArray(10),
            properties = mapOf(BackupFiles.KIND_KEY to BackupFiles.KIND_DATABASE, BackupFiles.VERSIONS_KEY to "100000"),
        )
        val requestsBefore = f.drive.requests.size

        assertTrue(f.runner.exportTo(target()) is ExportResult.Success)
        assertEquals("aucune requête vers Drive", requestsBefore, f.drive.requests.size)
    }

    @Test
    fun `ecriture impossible - echec clair, fichier retire`() = runBlocking<Unit> {
        f.seed()
        val destination = FileTarget(File(out, "export.db"), failWriting = true)

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertTrue((result as ExportResult.Failure).message.contains("disque plein"))
        assertTrue(destination.discarded)
    }

    @Test
    fun `emplacement qui ne s ouvre pas - echec clair`() = runBlocking<Unit> {
        f.seed()
        val destination = FileTarget(File(out, "export.db"), output = false)

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertTrue(destination.discarded)
    }

    @Test
    fun `relecture differente - echec, le fichier abime n est pas laisse`() = runBlocking<Unit> {
        f.seed()
        val file = File(out, "export.db")
        val destination = object : ExportTarget by FileTarget(file) {
            override fun openInput(): InputStream {
                file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))
                return file.inputStream()
            }

            override fun discard() {
                file.delete()
            }
        }

        val result = f.runner.exportTo(destination)

        assertTrue(result is ExportResult.Failure)
        assertTrue((result as ExportResult.Failure).message.contains("ne correspond pas"))
        assertFalse(file.exists())
    }

    @Test
    fun `la copie de travail n est pas laissee sur le telephone`() = runBlocking<Unit> {
        f.seed()
        f.runner.exportTo(target())
        f.runner.exportTo(FileTarget(File(out, "x"), output = false))

        val leftovers = File(f.context.cacheDir, "sauvegarde-test/travail").listFiles().orEmpty()
        assertTrue(leftovers.joinToString { it.name }, leftovers.isEmpty())
    }

    @Test
    fun `la cle OpenAI n est jamais dans le fichier`() = runBlocking<Unit> {
        f.seed()
        val key = "sk-proj-cle-factice-export-9f3a"
        InMemoryApiKeyStore().save(key)

        f.runner.exportTo(target())

        val bytes = File(out, "trackly-export.db").readBytes()
        assertFalse("la clé ne doit pas apparaître", String(bytes, Charsets.ISO_8859_1).contains(key))
        assertFalse("aucun jeton de type clé", String(bytes, Charsets.ISO_8859_1).contains("sk-proj-"))
        val columns = android.database.sqlite.SQLiteDatabase.openDatabase(File(out, "trackly-export.db").path, null, 1).use { db ->
            db.rawQuery("SELECT m.name || '.' || p.name FROM sqlite_master m, pragma_table_info(m.name) p WHERE m.type = 'table'", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }
        assertTrue(columns.isNotEmpty())
        assertTrue(columns.toString(), columns.none { Regex("api_?key|secret|password|(access|auth|bearer)_?token|\\.token$", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }

    @Test
    fun `l export emporte les reglages non secrets comme une copie Drive, jamais la cle - et Depuis un fichier les remet`() = runBlocking<Unit> {
        val context = f.context
        val prefsOf: (String) -> android.content.SharedPreferences = { n -> context.getSharedPreferences("export-reglages-$n", android.content.Context.MODE_PRIVATE) }
        prefsOf("trackly-assistant").edit().putString("modele", "gpt-6.1-sol").commit()
        prefsOf("trackly-cle").edit().putString("etat", "valide").commit()
        val g = BackupFixture(name = "export-reglages", decorate = { copy -> PortableSettings.attach(copy, PortableSettings.read(prefsOf)) })
        try {
            g.seed()
            val file = File(out, "avec-reglages.db")

            assertTrue(g.runner.exportTo(FileTarget(file)) is ExportResult.Success)

            val prepared = g.restore.prepareFromFile(file, "Fichier")
            assertTrue(prepared.carriesSettings)
            val applied = g.restartApp() as RestoreApplication.Applied
            assertEquals(listOf(PortableSettings.Entry("trackly-assistant", "modele", "s", "gpt-6.1-sol")), applied.settings)
        } finally {
            g.close()
            listOf("trackly-assistant", "trackly-cle").forEach { prefsOf(it).edit().clear().commit() }
        }
    }

    // ───── Parcours de l'écran ─────

    @Test
    fun `bouton - le nom est propose, le selecteur s ouvre une fois, puis l export est annonce`() {
        runBlocking { f.seed() }

        controller.startExport()
        awaitIdle()
        assertEquals("trackly-2026-11-02_12-30-00.db", controller.state.value.exportName)
        // Rien n'est encore créé : seul le sélecteur de fichiers le fera.
        assertTrue(out.listFiles().orEmpty().isEmpty())

        controller.exportPickerOpened()
        assertNull("le sélecteur ne se rouvre pas à la rotation", controller.state.value.exportName)

        controller.onExportDestination(target("trackly-2026-11-02_12-30-00.db"))
        awaitIdle()

        val notice = controller.state.value.notice!!
        assertFalse(notice.error)
        assertTrue(notice.nearExport && !notice.nearRestore)
        assertTrue(notice.text, notice.text.startsWith("Fichier exporté et vérifié : trackly-2026-11-02_12-30-00.db"))
        assertTrue(notice.text, notice.text.contains("Restaurer › Depuis un fichier"))
        assertEquals(0, restarts)
    }

    @Test
    fun `copie locale impossible - le message parle de la base, pas de Drive`() {
        val g = BackupFixture(name = "export-panne", decorate = { throw IOException("verrou") })
        try {
            val c = BackupController(
                runner = g.runner, restore = g.restore, folder = g.folder, authorizer = g.auth, drive = g::api, status = g.status,
                scheduling = object : BackupScheduling {
                    override fun schedule() = Unit
                    override fun backupSoon() = Unit
                    override fun cancel() = Unit
                },
                restarter = { }, scope = scope, workDir = File(g.context.cacheDir, "controleur-panne"),
                zone = TrackingDay.ZONE, importHistory = { ImportOutcome.Refused("non utilisé") },
            )
            runBlocking { g.seed() }
            c.startExport()
            runBlocking { withTimeout(10_000) { while (c.state.value.busy != null) delay(10) } }

            val notice = c.state.value.notice!!
            assertTrue(notice.text, notice.text.startsWith("Copie de la base impossible"))
            assertFalse(notice.text.contains("Drive"))
            assertTrue(notice.error && notice.nearExport)
            assertNull(c.state.value.exportName)
        } finally {
            g.close()
        }
    }

    @Test
    fun `sans selecteur de fichiers sur le telephone - message clair a cote du bouton`() {
        controller.exportUnavailable()
        val notice = controller.state.value.notice!!
        assertTrue(notice.error && notice.nearExport)
        assertTrue(notice.text.contains("sélecteur de fichiers"))
    }

    @Test
    fun `nom du fichier inconnu - l annonce n invente pas de nom`() {
        runBlocking { f.seed() }
        controller.onExportDestination(FileTarget(File(out, "export.db"), displayName = null))
        awaitIdle()

        val text = controller.state.value.notice!!.text
        assertTrue(text, text.startsWith("Fichier exporté et vérifié ("))
    }

    @Test
    fun `bouton sur une base vide - le dit clairement et n ouvre pas le selecteur`() {
        controller.startExport()
        awaitIdle()

        val state = controller.state.value
        assertNull(state.exportName)
        val notice = state.notice!!
        assertTrue(notice.text, notice.text.startsWith("Rien à exporter"))
        assertTrue(notice.nearExport)
        assertFalse("ce n'est pas une panne, mais le message reste visible", notice.error)
    }

    @Test
    fun `selecteur ferme sans choix - rien ne se passe`() {
        runBlocking { f.seed() }
        controller.startExport()
        awaitIdle()
        controller.exportPickerOpened()

        controller.onExportDestination(null)
        awaitIdle()

        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.busy)
    }

    @Test
    fun `export en echec - motif a cote du bouton`() {
        runBlocking { f.seed() }
        controller.onExportDestination(FileTarget(File(out, "export.db"), failWriting = true))
        awaitIdle()

        val notice = controller.state.value.notice!!
        assertTrue(notice.error && notice.nearExport)
        assertTrue(notice.text, notice.text.contains("disque plein"))
    }

    @Test
    fun `deux appuis rapproches - une seule demande de fichier`() {
        runBlocking { f.seed() }
        controller.startExport()
        controller.startExport()
        awaitIdle()
        assertEquals("trackly-2026-11-02_12-30-00.db", controller.state.value.exportName)
    }
}
