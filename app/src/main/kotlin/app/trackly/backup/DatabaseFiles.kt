package app.trackly.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Ce qu'on sait d'un fichier de base SQLite, lu sans Room. */
data class DatabaseFileInfo(
    /** `PRAGMA user_version` : la version du schéma Room. */
    val schemaVersion: Int,
    /** `PRAGMA integrity_check` a répondu « ok ». */
    val intact: Boolean,
    /** La table interne de Room est présente : c'est bien une base Trackly. */
    val isRoomDatabase: Boolean,
)

/** Outils sur les fichiers de base : inspection, empreintes, fichiers annexes (WAL). */
object DatabaseFiles {
    /** Fichiers annexes d'une base SQLite : journal WAL, index partagé, journal classique. */
    val SIDE_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** Ouvre la base en lecture seule et contrôle version, intégrité et origine. `null` si illisible. */
    fun inspect(file: File): DatabaseFileInfo? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val intact = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToFirst() && c.count == 1 && c.getString(0).equals("ok", ignoreCase = true)
            }
            val room = db.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'",
                null,
            ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
            DatabaseFileInfo(schemaVersion = db.version, intact = intact, isRoomDatabase = room)
        }
    } catch (e: Exception) {
        null
    }

    fun sha256(file: File): String = digest(file, "SHA-256")

    /** Somme MD5 : c'est celle que Drive calcule et renvoie (`md5Checksum`). */
    fun md5(file: File): String = digest(file, "MD5")

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Supprime la base et ses fichiers annexes. */
    fun deleteWithSides(file: File) {
        file.delete()
        for (suffix in SIDE_SUFFIXES) File(file.path + suffix).delete()
    }

    /** Copie la base et ses fichiers annexes existants (base fermée). */
    fun copyWithSides(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        for (suffix in SIDE_SUFFIXES) {
            val side = File(source.path + suffix)
            val targetSide = File(target.path + suffix)
            if (side.isFile) side.copyTo(targetSide, overwrite = true) else targetSide.delete()
        }
    }
}

/**
 * Ce que contient une copie, pour le garde-fou de la sauvegarde et pour retrouver la bonne copie sur Drive.
 *
 * - [activeConsumptions] : consommations dont la dernière version est active.
 * - [versions] : lignes de toutes les tables versionnées (`…_versions`, celles des versions futures comprises). Ces
 *   tables ne font que grandir (corriger ou supprimer écrit une version de plus) : un recul signale une mauvaise base.
 */
data class BackupContents(
    val activeConsumptions: Int,
    val versions: Long,
    /** Lignes de toutes les tables de Trackly. */
    val rows: Long = versions,
) {
    /** Empreinte comparée à celle de la dernière copie réussie (filet de nuit). */
    val fingerprint: String get() = "$activeConsumptions/$versions/$rows"

    companion object {
        /** Tables qui ne sont pas des données de l'utilisateur : celles de Room, d'Android, de SQLite, et les réglages joints à une copie. */
        private val INTERNAL = setOf("android_metadata", "room_master_table", "sqlite_sequence", PortableSettings.TABLE)
        private const val CONSUMPTIONS = "consumption_versions"
        private const val VERSIONED_SUFFIX = "_versions"

        /** Lit une base fermée (une copie), en lecture seule. */
        fun of(file: File): BackupContents =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> of { sql -> db.rawQuery(sql, null) } }

        /** Lit une base ouverte (celle de l'app). */
        fun of(db: SupportSQLiteDatabase): BackupContents = of { sql -> db.query(sql) }

        private fun of(query: (String) -> Cursor): BackupContents {
            val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            fun count(sql: String): Long = query(sql).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
            fun rowsOf(table: String) = count("SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\"")
            val versions = tables.filter { it.endsWith(VERSIONED_SUFFIX) }.sumOf(::rowsOf)
            val rows = tables.filterNot { it in INTERNAL || it.startsWith("sqlite_") }.sumOf(::rowsOf)
            val active = if (CONSUMPTIONS in tables) {
                count(
                    "SELECT COUNT(*) FROM $CONSUMPTIONS c WHERE c.state = 'active' AND " +
                        "c.version = (SELECT MAX(v.version) FROM $CONSUMPTIONS v WHERE v.entry_id = c.entry_id)",
                ).toInt()
            } else {
                0
            }
            return BackupContents(active, versions, rows)
        }
    }
}
