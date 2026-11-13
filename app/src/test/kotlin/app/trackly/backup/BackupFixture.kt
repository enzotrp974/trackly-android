package app.trackly.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.trackly.backup.drive.AuthorizerTokenSource
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.time.TrackingDay
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.DatabaseFactory
import app.trackly.storage.db.TracklyDatabase
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Horloge réglable des tests. */
class MutableClock(var instant: Instant, private val zone: ZoneId = TrackingDay.ZONE) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
}

/** Arrêt brutal du processus, simulé à une étape de la restauration (une `Error` : aucun `catch (Exception)` ne l'arrête). */
class SimulatedKill(val step: RestoreStep) : Error("arrêt simulé : $step")

/**
 * Une vraie base Room sur fichier (Robolectric), le faux Drive, et la sauvegarde assemblée
 * comme dans l'app. Jeu d'essai anonymisé.
 */
class BackupFixture(
    val drive: FakeDrive = FakeDrive(),
    /** Nom de l'installation simulée : deux fixtures sur le même faux Drive font deux téléphones. */
    name: String = "sauvegarde-test",
    /** Ajout à chaque copie envoyée (réglages joints, comme dans l'app). */
    decorate: ((File) -> Unit)? = null,
    /** Rappel d'ouverture de la base, comme dans l'app (empreinte d'une app neuve). */
    private val onOpen: ((androidx.sqlite.db.SupportSQLiteDatabase) -> Unit)? = null,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = MutableClock(Instant.parse("2026-11-02T11:30:00Z")) // 12 h 30 heure locale
    val databaseFile: File = context.getDatabasePath("$name.db").also { DatabaseFiles.deleteWithSides(it) }
    private val root = File(context.cacheDir, name).apply { deleteRecursively(); mkdirs() }
    val safetyDir = File(root, "db-backups")
    val restoreDir = File(root, "restauration")
    val status = BackupStatusStore(
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    )
    val auth = FakeAuthorizer(drive)
    val folder = BackupFolder(status)

    var database: TracklyDatabase = open(databaseFile)
        private set

    val engine: TracklyEngine get() = TracklyEngine(RoomTracklyStore(database), clock)

    fun api() = drive.api(AuthorizerTokenSource(auth))

    fun open(file: File): TracklyDatabase = DatabaseFactory.builder(context, file.absolutePath, onOpen).build()

    val runner = BackupRunner(
        snapshotter = DatabaseSnapshotter({ database }, decorate = decorate),
        workDir = File(root, "travail"),
        drive = ::api,
        folder = folder,
        status = status,
        clock = clock,
        zone = TrackingDay.ZONE,
    )

    val restore = restoreManager()

    /** Le chemin de restauration de ce téléphone ; [onStep] reçoit chaque étape (arrêt simulé). */
    fun restoreManager(onStep: (RestoreStep) -> Unit = {}) = RestoreManager(
        databaseFile = databaseFile,
        workDir = restoreDir,
        localCopiesDir = safetyDir,
        drive = ::api,
        folder = folder,
        status = status,
        // Une copie à restaurer n'est pas la base de l'app : jamais le rappel d'ouverture.
        openWithRoom = { file -> DatabaseFactory.builder(context, file.absolutePath).build() },
        zone = TrackingDay.ZONE,
        onStep = onStep,
    )

    /** Connecté avec un dossier Trackly existant. */
    fun connectWithFolder(): FakeDrive.StoredFile {
        status.markConnected("test@example.com")
        val f = drive.addFolder("Trackly", BackupFiles.folderProperties(), createdTime = Instant.parse("2026-10-01T00:00:00Z"))
        status.folderId = f.id
        return f
    }

    /** Redémarrage simulé : la base est fermée, la restauration en attente appliquée, puis rouverte. */
    fun restart(): File? {
        database.close()
        val safety = PendingRestore.applyIfPending(databaseFile, safetyDir, clock)
        database = open(databaseFile)
        return safety
    }

    /** Rouvre la base (après un remplacement du fichier par le test). */
    fun reopen() {
        database = open(databaseFile)
    }

    /** Redémarrage simulé, comme l'app : la restauration en attente est appliquée (ou écartée), puis la base rouverte. */
    fun restartApp(): RestoreApplication? {
        database.close()
        val applied = PendingRestore.applyPending(databaseFile, safetyDir, clock)
        database = open(databaseFile)
        return applied
    }

    fun close(shutdownDrive: Boolean = true) {
        runCatching { database.close() }
        if (shutdownDrive) drive.shutdown()
        DatabaseFiles.deleteWithSides(databaseFile)
        DatabaseFiles.deleteWithSides(PendingRestore.stagedFile(databaseFile))
        PendingRestore.discardSides(databaseFile)
        root.deleteRecursively()
    }

    suspend fun seed() {
        val engine = engine
        check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        add(engine, MONDAY, Moment.Matin, "Œufs brouillés, pain complet, café", 520.0, 32.0, 48.0, 20.0)
        add(engine, MONDAY, Moment.Midi, "Salade sauce A, poulet", 640.0, 44.0, 89.0, null)
        add(engine, MONDAY.minusDays(1), Moment.Soir, "Pâtes complètes, bolognaise", 770.0, 38.0, 102.0, 24.0)
    }

    suspend fun add(engine: TracklyEngine, day: LocalDate, moment: Moment, label: String, kcal: Double, p: Double?, c: Double?, f: Double?) {
        val food = Food(label, Nutrition(kcal, p, c, f))
        check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = day))) is ActionOutcome.Applied)
    }

    companion object {
        val MONDAY: LocalDate = LocalDate.parse("2026-11-02")
    }
}
