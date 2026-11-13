package app.trackly.backup

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.goal.ReferenceWeight
import app.trackly.core.goal.ReferenceWeightPlan
import app.trackly.core.goal.SetWeightGoal
import app.trackly.core.goal.WeightGoal
import app.trackly.core.goal.WeightTarget
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeighIn
import app.trackly.core.store.ImportedSource
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Poids de référence sur une vraie base Room : il partage la table `weight_goal_versions` avec l'objectif de poids,
 * chacun sous sa clé, sans jamais se mélanger ; une copie restaurée garde la mise à jour et son Annuler ; une copie
 * de schéma 12, d'avant le poids de référence, se restaure et repart du poids du profil. Données fictives.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceWeightRestoreTest {
    private val f = BackupFixture(name = "reference-weight")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracklyDatabase::class.java)

    private val day = LocalDate.parse("2026-11-02")
    private val base = GoalTargets(kcal = 2300.0, proteinG = 140.0, carbsG = 277.5, fatG = 70.0, proteinMinG = 112.0)

    @After
    fun close() {
        f.close()
        f.context.deleteDatabase(OLD)
    }

    private suspend fun seed() {
        RoomTracklyStore(f.database).transaction { imports.insert(ImportedSource("profile", "profile", 1, "sha", PROFILE, ActionId(1))) }
        applied(SetGoal(base, from = LocalDate.parse("2026-10-01")))
        applied(DeclareWeekInfo(LocalDate.parse("2026-10-26"), weighIns = listOf(WeighIn(day, 65.0))))
        // Un repas : sans consommation, le garde-fou n'envoie aucune copie.
        f.add(f.engine, day, app.trackly.core.model.Moment.Midi, "Pâtes", 600.0, 20.0, 90.0, 15.0)
    }

    private suspend fun applied(action: app.trackly.core.action.Action): ActionId =
        (f.engine.execute(action) as ActionOutcome.Applied).result.id

    @Test
    fun `objectif de poids et poids de reference - deux cles, aucune lecture croisee, annulations independantes`() = runBlocking<Unit> {
        seed()
        val goal = applied(SetWeightGoal(60.0, LocalDate.parse("2027-04-01")))
        val reference = f.engine.updateReferenceWeight(day).action!!
        val target = WeightTarget(60.0, LocalDate.parse("2027-04-01"))

        assertEquals(target, f.engine.weightTarget())
        assertEquals(target, f.engine.weeklyFigures(WeekRange(LocalDate.parse("2026-10-26"), day)).forecast.target)
        assertEquals(65.0, f.engine.referenceWeight(day)!!, 0.0)
        RoomTracklyStore(f.database).transaction {
            assertEquals(listOf(WeightGoal.KEY), weightGoals.allCurrent().map { it.key })
            assertEquals(listOf(ReferenceWeight.KEY), referenceWeights.allCurrent().map { it.key })
            assertNull(weightGoals.current(ReferenceWeight.KEY))
            assertNull(weightGoals.version(ReferenceWeight.KEY, 1))
            assertNull(referenceWeights.current(WeightGoal.KEY))
            assertNull(referenceWeights.version(WeightGoal.KEY, 1))
        }

        // Annuler l'objectif de poids ne touche pas au poids de référence…
        applied(Undo(goal))
        assertEquals(WeightTarget.Default, f.engine.weightTarget())
        assertEquals(65.0, f.engine.referenceWeight(day)!!, 0.0)
        // … et, avec un nouvel objectif de poids, annuler le poids de référence ne touche pas à l'objectif de poids.
        applied(SetWeightGoal(59.0, LocalDate.parse("2027-05-01")))
        applied(Undo(reference))
        assertEquals(70.0, f.engine.referenceWeight(day)!!, 0.0)
        assertEquals(WeightTarget(59.0, LocalDate.parse("2027-05-01")), f.engine.weightTarget())
        assertEquals(WeightTarget(59.0, LocalDate.parse("2027-05-01")), f.engine.weeklyFigures(WeekRange(LocalDate.parse("2026-10-26"), day)).forecast.target)
    }

    @Test
    fun `une copie restauree garde la mise a jour et son Annuler`() = runBlocking<Unit> {
        seed()
        val before = f.engine.goals()
        val reference = f.engine.updateReferenceWeight(day).action!!
        f.connectWithFolder()
        f.clock.instant = Instant.parse("2026-11-03T02:00:00Z")
        f.runner.backUp().let { assertTrue(it.toString(), it is BackupResult.Success) }
        applied(Undo(reference)) // après la copie : annulée sur le téléphone
        assertEquals(70.0, f.engine.referenceWeight(day)!!, 0.0)

        f.restore.prepareFromDrive(f.restore.listBackups().entries.single(), "Copie")
        f.restart()
        assertEquals(65.0, f.engine.referenceWeight(day)!!, 0.0) // la copie avait la mise à jour
        assertEquals(130.0, f.engine.goals().last().targets.proteinG!!, 1e-9)
        applied(Undo(reference)) // et son Annuler marche encore
        assertEquals(70.0, f.engine.referenceWeight(day)!!, 0.0)
        assertEquals(before.map { it.effectiveFrom to it.targets }, f.engine.goals().map { it.effectiveFrom to it.targets })
    }

    @Test
    fun `une copie de schema 12 d avant le poids de reference se restaure, l objectif de poids reste lu par sa cle`() = runBlocking<Unit> {
        helper.createDatabase(OLD, 12).use { db ->
            db.execSQL(
                "INSERT INTO imported_sources (kind, record_key, version, sha256, json, action_id) VALUES ('profile', 'profile', 1, 'x', ?, 1)",
                arrayOf(PROFILE),
            )
            db.execSQL(
                "INSERT INTO goal_versions (effective_from, version, state, kcal, protein_g, carbs_g, fat_g, protein_min_g, recorded_at, action_id) " +
                    "VALUES ('2026-10-01', 1, 'active', 2300, 140, 277.5, 70, 112, 1793620200000, NULL)",
            )
            db.execSQL(
                "INSERT INTO weight_goal_versions (goal_key, version, state, target_kg, target_date, losing, recorded_at, action_id) " +
                    "VALUES ('weight_goal', 1, 'active', 60.5, '2027-04-01', 1, 1793620200000, NULL)",
            )
            db.execSQL(
                "INSERT INTO week_info_versions (week_start, version, state, weigh_ins, steps, steps_average, sessions, recorded_at, action_id) " +
                    "VALUES ('2026-10-26', 1, 'active', '[{\"day\":\"2026-11-02\",\"kg\":65.0}]', '[]', NULL, NULL, 1793620200000, NULL)",
            )
        }
        val old = f.context.getDatabasePath(OLD)
        val folder = f.connectWithFolder()
        val snapshot = Snapshot(old, 12, old.length(), DatabaseFiles.md5(old), DatabaseFiles.sha256(old))
        f.drive.addFile("trackly-2026-11-01_03-00-00.db", folder.id, old.readBytes(), BackupFiles.backupProperties(snapshot, BackupContents.of(old)))
        f.restore.prepareFromDrive(f.restore.listBackups().entries.single(), "Copie d'avant")
        f.restart()

        val target = WeightTarget(60.5, LocalDate.parse("2027-04-01"))
        assertEquals(target, f.engine.weightTarget())
        assertEquals(70.0, f.engine.referenceWeight(day)!!, 0.0) // aucune version : celui du profil
        val change = f.engine.updateReferenceWeight(day)
        assertTrue(change.plan is ReferenceWeightPlan.Change)
        assertEquals(65.0, f.engine.referenceWeight(day)!!, 0.0)
        assertEquals(target, f.engine.weightTarget()) // l'objectif de poids n'a pas bougé
        applied(Undo(change.action!!))
        assertEquals(target, f.engine.weightTarget())
        assertEquals(70.0, f.engine.referenceWeight(day)!!, 0.0)
    }

    private companion object {
        const val OLD = "ancienne-v12-reference.db"
        const val PROFILE = """{"current_reference_weight_kg":70.0,"goals":[{"effective_from":"2026-09-01","kcal":2300,"protein_ideal_g_per_kg":2.0,"protein_min_g_per_kg":1.6,"fat_g_per_kg":1.0}]}"""
    }
}
