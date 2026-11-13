package app.trackly.storage.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.trackly.core.time.TrackingDay
import java.time.Instant

/**
 * Version 9 → 10 : fil du jour du chat. Ajoute à `chat_messages` la colonne `day` (date de rattachement du
 * message, règle des 5 h) et la colonne `hidden_at` (instant où « Effacer la discussion » a masqué le message).
 *
 * Chaque message déjà en base reçoit la date de rattachement de son horodatage (`created_at`), calculée comme pour
 * une consommation ([TrackingDay.of]). `hidden_at` reste `NULL` : rien n'est masqué. Aucune ligne n'est supprimée ni
 * modifiée dans ses colonnes d'origine, aucune autre table ne bouge.
 *
 * Réversible : supprimer les colonnes `day` et `hidden_at` rend exactement la base de la version 9. La base d'avant est
 * de toute façon copiée dans `no_backup/db-backups/` avant la migration (voir `PreMigrationBackup`).
 */
object Migration9To10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `day` TEXT")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `hidden_at` INTEGER")

        val rows = db.query("SELECT id, created_at FROM chat_messages").use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }
        for ((id, createdAt) in rows) {
            db.update(
                "chat_messages",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply { put("day", TrackingDay.of(Instant.ofEpochMilli(createdAt)).toString()) },
                "id = ?",
                arrayOf<Any>(id),
            )
        }
    }
}
