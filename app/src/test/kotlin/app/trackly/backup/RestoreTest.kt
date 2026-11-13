package app.trackly.backup

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Restauration à une date choisie, testée sur une vraie copie : sauvegarde → Drive → restauration
 * sur une vraie base Room, et les totaux du jour sont identiques.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {
    private val f = BackupFixture()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracklyDatabase::class.java)

    @After
    fun close() {
        f.close()
        for (v in OLD_VERSIONS) f.context.deleteDatabase("ancienne-v$v.db")
    }

    @Test
    fun `aller-retour sauvegarde puis restauration - totaux du jour identiques`() = runBlocking<Unit> {
        f.seed()
        f.connectWithFolder()
        val before = f.engine.daySummary(BackupFixture.MONDAY)
        f.clock.instant = Instant.parse("2026-11-03T02:00:00Z")
        assertTrue(f.runner.backUp() is BackupResult.Success)

        // Après la sauvegarde, la journée change (ajout), puis on veut revenir en arrière.
        f.add(f.engine, BackupFixture.MONDAY, Moment.Soir, "Pizza", 900.0, 30.0, 100.0, 35.0)
        assertNotEquals(before.totals, f.engine.daySummary(BackupFixture.MONDAY).totals)

        val listing = f.restore.listBackups()
        assertTrue(listing.folderFound)
        val entry = listing.entries.single()
        val prepared = f.restore.prepareFromDrive(entry, "Mardi 3 novembre, 03:00")
        assertEquals(TracklyDatabase.VERSION, prepared.fromSchema)
        assertEquals(3, prepared.activeConsumptions)
        // Rien n'est remplacé avant le redémarrage.
        assertEquals(3, f.engine.daySummary(BackupFixture.MONDAY).consumptions.size)

        val safety = f.restart()

        val after = f.engine.daySummary(BackupFixture.MONDAY)
        assertEquals(before.totals, after.totals)
        assertEquals(before.consumptions.map { it.food }, after.consumptions.map { it.food })
        assertEquals(before.goal, after.goal)
        // Copie de sécurité de l'état remplacé (avec la pizza), gardée sur le téléphone.
        assertNotNull(safety)
        assertTrue(safety!!.name.startsWith(PendingRestore.SAFETY_PREFIX))
        val copy = f.open(safety)
        val replaced = app.trackly.core.engine.TracklyEngine(app.trackly.storage.RoomTracklyStore(copy), f.clock)
        assertEquals(3, replaced.daySummary(BackupFixture.MONDAY).consumptions.size)
        copy.close()
        // La restauration appliquée ne se rejoue pas.
        assertFalse(f.restore.stagedFile.exists())
    }

    @Test
    fun `objectif temporaire - la restauration garde la baisse et son retour, et Annuler marche encore`() = runBlocking<Unit> {
        f.seed() // 2 600 kcal depuis le 1er octobre ; lundi 2 novembre, 12 h 30
        f.connectWithFolder()
        val plan = app.trackly.core.goal.SetTemporaryGoal(java.time.LocalDate.parse("2026-11-03"), days = 4, dropKcal = 150.0)
        assertTrue(f.engine.execute(plan) is ActionOutcome.Applied)
        f.clock.instant = Instant.parse("2026-11-03T02:00:00Z")
        assertTrue(f.runner.backUp() is BackupResult.Success)

        // Après la copie, l'objectif temporaire est annulé ; la restauration doit le ramener.
        val status = f.engine.temporaryGoal(java.time.LocalDate.parse("2026-11-03"))!!
        assertTrue(f.engine.execute(app.trackly.core.action.Undo(status.head)) is ActionOutcome.Applied)
        val entry = f.restore.listBackups().entries.single()
        f.restore.prepareFromDrive(entry, "Mardi 3 novembre, 03:00")
        f.restart()

        fun day(iso: String) = java.time.LocalDate.parse(iso)
        assertEquals(2450.0, f.engine.daySummary(day("2026-11-04")).goal!!.kcal, 0.0)
        assertEquals(2600.0, f.engine.daySummary(day("2026-11-07")).goal!!.kcal, 0.0)
        // Le retour se déduit de la date : rien à relancer après la restauration.
        f.clock.instant = Instant.parse("2026-11-05T08:00:00Z")
        val restored = f.engine.temporaryGoal(day("2026-11-03"))!!
        assertEquals(app.trackly.core.goal.TemporaryPhase.Running, restored.phase)
        assertTrue(f.engine.execute(app.trackly.core.action.Undo(restored.head)) is ActionOutcome.Applied)
        assertEquals(2450.0, f.engine.daySummary(day("2026-11-04")).goal!!.kcal, 0.0) // jour passé : gardé
        assertEquals(2600.0, f.engine.daySummary(day("2026-11-05")).goal!!.kcal, 0.0)
    }

    @Test
    fun `la copie de securite locale est elle-meme restaurable`() = runBlocking<Unit> {
        f.seed()
        val path = File(f.context.cacheDir, "etat.db")
        DatabaseSnapshotter({ f.database }).snapshot(path)
        f.add(f.engine, BackupFixture.MONDAY, Moment.Snack, "Pomme", 80.0, 0.5, 21.0, null)

        f.restore.prepareFromFile(path, "Copie")
        f.restart()

        assertEquals(2, f.engine.daySummary(BackupFixture.MONDAY).consumptions.size)
        path.delete()
    }

    @Test
    fun `une sauvegarde v1 passe par les migrations Room`() = restoreOld(1)

    @Test
    fun `une sauvegarde v2 passe par les migrations Room`() = restoreOld(2)

    @Test
    fun `une sauvegarde v3 passe par les migrations Room`() = restoreOld(3)

    /** Une base ancienne (version [from], schéma exporté) est migrée jusqu'à la version courante avant remplacement. */
    private fun restoreOld(from: Int) = runBlocking<Unit> {
        assertTrue(from < TracklyDatabase.VERSION)
        helper.createDatabase("ancienne-v$from.db", from).use { db ->
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
        }
        val old = f.context.getDatabasePath("ancienne-v$from.db")

        val prepared = f.restore.prepareFromFile(old, "Ancienne")
        assertEquals(from, prepared.fromSchema)
        assertEquals(1, prepared.activeConsumptions)
        assertEquals(TracklyDatabase.VERSION, DatabaseFiles.inspect(f.restore.stagedFile)!!.schemaVersion)

        f.restart()
        val day = f.engine.daySummary(BackupFixture.MONDAY)
        assertEquals(80.0, day.totals.kcal, 0.0)
        assertEquals("Pomme", day.consumptions.single().food.label)
    }

    /**
     * Une copie Drive faite avant le minimum de protéines (schéma 8, fixture fictive) se restaure. La migration
     * 8 → 9 donne à l'objectif le minimum de son profil repris, et un changement de calories le garde.
     */
    @Test
    fun `une copie Drive en schema 8 se restaure avec le minimum de proteines`() = runBlocking<Unit> {
        val name = "ancienne-v8.db"
        helper.createDatabase(name, 8).use { db ->
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
            db.execSQL(
                "INSERT INTO imported_sources (kind, record_key, version, sha256, json, action_id) VALUES ('profile', 'profile', 1, 'x', ?, 1)",
                arrayOf("""{"current_reference_weight_kg":70,"goals":[{"effective_from":"2026-10-01","protein_min_g_per_kg":1.6}]}"""),
            )
            db.execSQL(
                "INSERT INTO goal_versions (effective_from, version, state, kcal, protein_g, carbs_g, fat_g, recorded_at, action_id) " +
                    "VALUES ('2026-10-01', 1, 'active', 2600, 140, 352, 70, 1793620200000, NULL)",
            )
        }
        val old = f.context.getDatabasePath(name)
        val folder = f.connectWithFolder()
        val snapshot = Snapshot(old, 8, old.length(), DatabaseFiles.md5(old), DatabaseFiles.sha256(old))
        f.drive.addFile("trackly-2026-11-01_03-00-00.db", folder.id, old.readBytes(), BackupFiles.backupProperties(snapshot, BackupContents.of(old)))

        val entry = f.restore.listBackups().entries.single()
        val prepared = f.restore.prepareFromDrive(entry, "Copie d'avant")
        assertEquals(8, prepared.fromSchema)
        assertEquals(TracklyDatabase.VERSION, DatabaseFiles.inspect(f.restore.stagedFile)!!.schemaVersion)
        f.restart()

        val day = f.engine.daySummary(BackupFixture.MONDAY)
        assertEquals("Pomme", day.consumptions.single().food.label)
        assertEquals(140.0, day.goal!!.proteinG!!, 0.0)
        assertEquals(112.0, day.goal!!.proteinMinG!!, 0.0) // 70 kg × 1,6
        // Un changement de calories après la restauration garde le minimum.
        assertTrue(f.engine.execute(SetGoal(GoalTargets(2200.0, 140.0, 268.0, 60.0))) is ActionOutcome.Applied)
        assertEquals(112.0, f.engine.daySummary(BackupFixture.MONDAY).goal!!.proteinMinG!!, 0.0)
        f.context.deleteDatabase(name)
    }

    /**
     * Une copie Drive faite avant la période libre du bilan (schéma 10, fixture fictive : un ancien bilan de semaine,
     * son fil et ses informations) se restaure sans rien perdre. L'ancien bilan reste une semaine du lundi au dimanche, et
     * le prochain commence le lendemain de son dimanche.
     */
    @Test
    fun `une copie Drive en schema 10 se restaure avec ses bilans de semaine, leur fil et leurs informations`() = runBlocking<Unit> {
        val name = "ancienne-v10.db"
        helper.createDatabase(name, 10).use { db ->
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
            db.execSQL(
                "INSERT INTO week_info_versions (week_start, version, state, weigh_ins, steps, steps_average, sessions, recorded_at, action_id) " +
                    "VALUES ('2026-10-26', 1, 'active', '[{\"day\":\"2026-10-27\",\"kg\":72.4}]', '[]', NULL, NULL, 1793620200000, NULL)",
            )
            db.execSQL("INSERT INTO review_messages (id, week_start, created_at, role, text, cards) VALUES (1, '2026-10-26', 1793620200000, 'user', 'mes pesées', '[]')")
            db.execSQL("INSERT INTO review_messages (id, week_start, created_at, role, text, cards) VALUES (2, '2026-10-26', 1793620201000, 'assistant', 'Noté.', '[]')")
            db.execSQL("INSERT INTO weekly_reports (id, week_start, generated_at, html) VALUES (1, '2026-10-26', 1793620300000, '<html>ancien bilan</html>')")
        }
        val old = f.context.getDatabasePath(name)
        val folder = f.connectWithFolder()
        val snapshot = Snapshot(old, 10, old.length(), DatabaseFiles.md5(old), DatabaseFiles.sha256(old))
        f.drive.addFile("trackly-2026-11-01_03-00-00.db", folder.id, old.readBytes(), BackupFiles.backupProperties(snapshot, BackupContents.of(old)))

        val prepared = f.restore.prepareFromDrive(f.restore.listBackups().entries.single(), "Copie d'avant")
        assertEquals(10, prepared.fromSchema)
        assertEquals(TracklyDatabase.VERSION, DatabaseFiles.inspect(f.restore.stagedFile)!!.schemaVersion)
        f.restart()

        val reports = app.trackly.review.RoomReportRepository(f.database)
        val info = reports.latest()!!
        assertEquals(java.time.LocalDate.parse("2026-10-26"), info.weekStart)
        assertEquals(java.time.LocalDate.parse("2026-11-01"), info.periodEnd) // une semaine du lundi au dimanche
        assertEquals("<html>ancien bilan</html>", reports.get(info.id)!!.html)
        assertEquals(
            listOf("mes pesées", "Noté."),
            app.trackly.review.RoomReviewHistory(f.database.review()).last(java.time.LocalDate.parse("2026-10-26"), 10).map { it.text },
        )
        assertEquals(listOf(72.4), f.engine.weekInfo(java.time.LocalDate.parse("2026-10-26"))!!.weighIns.map { it.kg })
        assertEquals("Pomme", f.engine.daySummary(BackupFixture.MONDAY).consumptions.single().food.label)
        // Le prochain bilan commence le lendemain de ce dimanche et va jusqu'à aujourd'hui.
        val wednesday = java.time.ZonedDateTime.parse("2026-11-04T10:00:00+01:00[Europe/Zurich]").toInstant()
        assertEquals(
            app.trackly.core.review.WeekRange(java.time.LocalDate.parse("2026-11-02"), java.time.LocalDate.parse("2026-11-04")),
            app.trackly.review.ReviewPeriods(reports, app.trackly.review.RoomReviewHistory(f.database.review())).at(wednesday),
        )
        f.context.deleteDatabase(name)
    }

    @Test
    fun `une sauvegarde plus recente que l app est refusee, rien ne change`() = runBlocking<Unit> {
        f.seed()
        val newer = File(f.context.cacheDir, "future.db")
        DatabaseSnapshotter({ f.database }).snapshot(newer)
        android.database.sqlite.SQLiteDatabase.openDatabase(newer.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = TracklyDatabase.VERSION + 1 }

        try {
            f.restore.prepareFromFile(newer, "Future")
            fail("refus attendu")
        } catch (e: RestoreException) {
            assertTrue(e.message!!.contains("plus récente"))
        }
        assertFalse(f.restore.stagedFile.exists())
        newer.delete()
    }

    @Test
    fun `un fichier qui n est pas une sauvegarde Trackly est refuse`() = runBlocking<Unit> {
        val other = File(f.context.cacheDir, "autre.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(other, null).use { it.execSQL("CREATE TABLE t (x)") }
        val text = File(f.context.cacheDir, "texte.db").apply { writeText("pas une base") }
        for (file in listOf(other, text)) {
            try {
                f.restore.prepareFromFile(file, "x")
                fail("refus attendu pour ${file.name}")
            } catch (e: RestoreException) {
                // attendu
            }
        }
        assertFalse(f.restore.stagedFile.exists())
        other.delete()
        text.delete()
    }

    @Test
    fun `telechargement abime - refuse avant tout remplacement`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        f.runner.backUp()
        val stored = f.drive.childrenOf(folder.id).single()
        stored.content = stored.content.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        stored.reportedMd5 = FakeDrive.md5Of(stored.content) // Drive cohérent, mais le SHA-256 Trackly ne l'est plus

        val entry = f.restore.listBackups().entries.single()
        try {
            f.restore.prepareFromDrive(entry, "x")
            fail("refus attendu")
        } catch (e: RestoreException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(f.restore.stagedFile.exists())
    }

    @Test
    fun `liste des sauvegardes de la plus recente a la plus ancienne, fichiers etrangers ignores`() = runBlocking<Unit> {
        f.seed()
        val folder = f.connectWithFolder()
        f.clock.instant = Instant.parse("2026-11-01T02:00:00Z")
        f.drive.now = f.clock.instant
        f.runner.backUp()
        f.clock.instant = Instant.parse("2026-11-02T02:00:00Z")
        f.drive.now = f.clock.instant
        f.runner.backUp()
        f.drive.addFile("photo.jpg", folder.id, byteArrayOf(1))

        val entries = f.restore.listBackups().entries
        assertEquals(listOf("trackly-2026-11-02_03-00-00.db", "trackly-2026-11-01_03-00-00.db"), entries.map { it.name })
    }

    @Test
    fun `dossier introuvable - liste vide signalee`() = runBlocking<Unit> {
        f.status.markConnected(null)
        val listing = f.restore.listBackups()
        assertFalse(listing.folderFound)
        assertTrue(listing.entries.isEmpty())
    }

    private companion object {
        val OLD_VERSIONS = 1..3
    }
}
