package app.trackly.storage.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.trackly.storage.db.migrations.ALL_MIGRATIONS
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DatabaseFactory {
    /**
     * Ouvre la base de l'app. Une restauration préparée (Réglages › Sauvegarde) a déjà été appliquée,
     * base fermée, au démarrage de l'app (voir [PendingRestore] et `AppContainer`). Si une migration
     * va avoir lieu, une copie de la base est faite avant (voir [PreMigrationBackup]). Pas de repli
     * destructif : sans migration, Room échoue.
     *
     * [onOpen] : appelé à la première ouverture, migrations faites, avant toute autre requête.
     */
    fun open(context: Context, clock: Clock, onOpen: (SupportSQLiteDatabase) -> Unit = {}): TracklyDatabase {
        val app = context.applicationContext
        val database = app.getDatabasePath(TracklyDatabase.NAME)
        PreMigrationBackup.backupIfNeeded(
            database = database,
            targetVersion = TracklyDatabase.VERSION,
            backupDir = File(app.noBackupFilesDir, PreMigrationBackup.DIR),
            clock = clock,
        )
        return builder(app, TracklyDatabase.NAME, onOpen).build()
    }

    /**
     * Constructeur Room commun : la base de l'app et les copies à restaurer ([name] peut être un
     * chemin absolu) passent par les mêmes migrations.
     */
    fun builder(context: Context, name: String, onOpen: ((SupportSQLiteDatabase) -> Unit)? = null): RoomDatabase.Builder<TracklyDatabase> =
        Room.databaseBuilder(context.applicationContext, TracklyDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .apply {
                if (onOpen != null) {
                    addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) = onOpen(db)
                        },
                    )
                }
            }
}

/**
 * Copie de la base avant migration : si le fichier existe avec une version plus ancienne que
 * celle du code (ou illisible), ses fichiers (base, journal WAL) sont copiés, base fermée,
 * dans [DIR].
 * Les [KEEP] copies les plus récentes sont gardées ; elles sont restaurables depuis Réglages › Sauvegarde.
 */
object PreMigrationBackup {
    const val DIR = "db-backups"
    const val KEEP = 3
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** Renvoie la copie créée, ou `null` si aucune migration n'est en vue. */
    fun backupIfNeeded(database: File, targetVersion: Int, backupDir: File, clock: Clock): File? {
        if (!database.isFile) return null
        // Version illisible : on copie quand même, par prudence (marquée v0).
        val version = readUserVersion(database) ?: 0
        if (version >= targetVersion) return null
        backupDir.mkdirs()
        val name = "${database.nameWithoutExtension}-v$version-${STAMP.format(clock.instant())}.db"
        val copy = File(backupDir, name)
        database.copyTo(copy, overwrite = true)
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(database.path + suffix)
            if (side.isFile) side.copyTo(File(copy.path + suffix), overwrite = true)
        }
        prune(backupDir, database.nameWithoutExtension)
        return copy
    }

    private fun readUserVersion(file: File): Int? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    } catch (e: Exception) {
        null
    }

    private fun prune(dir: File, prefix: String) {
        val copies = dir.listFiles { f -> f.name.startsWith("$prefix-v") && f.name.endsWith(".db") }.orEmpty()
        copies.sortedByDescending { it.name.substringAfterLast("-v").substringAfter('-') }
            .drop(KEEP)
            .forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
                File(old.path + "-shm").delete()
            }
    }
}
