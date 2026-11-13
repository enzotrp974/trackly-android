package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 → 3 : historique local du chat (texte et cartes, jamais de photo).
 * Aucune donnée existante n'est touchée. Pour revenir en arrière, la table peut être
 * supprimée sans perte de consommation ni d'objectif.
 */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, `text` TEXT NOT NULL, `cards` TEXT NOT NULL DEFAULT '[]')",
        )
    }
}
