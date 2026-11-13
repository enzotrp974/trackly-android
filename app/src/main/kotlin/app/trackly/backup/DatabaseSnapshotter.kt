package app.trackly.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Copie cohérente de la base, prête à être envoyée : un seul fichier, vérifié. */
data class Snapshot(
    val file: File,
    val schemaVersion: Int,
    val sizeBytes: Long,
    val md5: String,
    val sha256: String,
)

class SnapshotException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Copie cohérente de la base Room ouverte, **quelles que soient ses tables** (copie du fichier,
 * pas un export table par table).
 *
 * - SQLite ≥ 3.27 (Android 11 et plus) : `VACUUM INTO`, un instantané cohérent d'une base en cours
 *   d'utilisation (doc SQLite), compact, en un seul fichier.
 * - Sinon : copie de la base et de son journal WAL pendant qu'on détient le verrou d'écriture
 *   (aucune écriture ni point de contrôle ne peut alors toucher les fichiers), puis la copie est
 *   ramenée à un seul fichier.
 *
 * La copie est ensuite contrôlée (`integrity_check`, table Room présente) avant d'être rendue.
 */
class DatabaseSnapshotter(
    private val database: () -> TracklyDatabase,
    /** Pour les tests : force la copie sous verrou au lieu de `VACUUM INTO`. */
    private val forceLockedCopy: Boolean = false,
    /** Ajout à la copie, avant son contrôle et ses empreintes (réglages joints à une copie Drive : [PortableSettings]). */
    private val decorate: ((File) -> Unit)? = null,
) {
    suspend fun snapshot(target: File): Snapshot = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        DatabaseFiles.deleteWithSides(target)
        val db = database()
        val sqlite = db.openHelper.writableDatabase
        try {
            if (!forceLockedCopy && supportsVacuumInto(sqlite)) {
                sqlite.execSQL("VACUUM INTO ?", arrayOf<Any?>(target.path))
            } else {
                val path = sqlite.path ?: throw SnapshotException("Base en mémoire : rien à copier")
                lockedCopy(sqlite, File(path), target)
            }
            decorate?.invoke(target)
        } catch (e: SnapshotException) {
            DatabaseFiles.deleteWithSides(target)
            throw e
        } catch (e: Exception) {
            DatabaseFiles.deleteWithSides(target)
            throw SnapshotException("Copie de la base impossible", e)
        }
        val info = DatabaseFiles.inspect(target)
        if (info == null || !info.intact || !info.isRoomDatabase) {
            DatabaseFiles.deleteWithSides(target)
            throw SnapshotException("La copie de la base n'est pas intègre")
        }
        // L'inspection a pu recréer des fichiers annexes vides : la copie doit rester un seul fichier.
        DatabaseFiles.SIDE_SUFFIXES.forEach { File(target.path + it).delete() }
        Snapshot(
            file = target,
            schemaVersion = info.schemaVersion,
            sizeBytes = target.length(),
            md5 = DatabaseFiles.md5(target),
            sha256 = DatabaseFiles.sha256(target),
        )
    }

    private fun supportsVacuumInto(sqlite: SupportSQLiteDatabase): Boolean {
        val version = sqlite.query("SELECT sqlite_version()").use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return isAtLeast(version, 3, 27)
    }

    private fun lockedCopy(sqlite: SupportSQLiteDatabase, source: File, target: File) {
        // Transaction d'écriture : en mode WAL, elle bloque tout autre écrivain, donc tout commit
        // et tout point de contrôle. Base + WAL forment alors un ensemble cohérent.
        sqlite.beginTransaction()
        try {
            source.copyTo(target, overwrite = true)
            val wal = File(source.path + "-wal")
            if (wal.isFile) wal.copyTo(File(target.path + "-wal"), overwrite = true)
        } finally {
            sqlite.endTransaction()
        }
        // Rejoue le journal dans la copie, puis la referme : SQLite supprime alors le WAL.
        android.database.sqlite.SQLiteDatabase.openDatabase(
            target.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { copy ->
            copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        val leftover = File(target.path + "-wal")
        if (leftover.isFile && leftover.length() > 0) throw SnapshotException("Journal WAL de la copie non vidé")
    }

    companion object {
        internal fun isAtLeast(version: String?, major: Int, minor: Int): Boolean {
            val parts = version?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
            if (parts.size < 2) return false
            return parts[0] > major || (parts[0] == major && parts[1] >= minor)
        }
    }
}
