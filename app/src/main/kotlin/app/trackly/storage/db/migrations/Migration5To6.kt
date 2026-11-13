package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 5 → 6 : compteur de coût local. Ajoute la table `usage_events` (une ligne par
 * requête à OpenAI ou par transcription), sans toucher aux tables existantes.
 *
 * Réversible : pour revenir en arrière, il suffit de supprimer la table ; aucune donnée de suivi
 * n'en dépend. La base d'avant est de toute façon copiée dans `no_backup/db-backups/` avant la
 * migration (voir `PreMigrationBackup`).
 */
object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recorded_at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `model` TEXT NOT NULL, `effort` TEXT, " +
                "`tool_round` INTEGER NOT NULL, `input_tokens` INTEGER NOT NULL, `cached_tokens` INTEGER NOT NULL, " +
                "`cache_write_tokens` INTEGER NOT NULL, `output_tokens` INTEGER NOT NULL, `reasoning_tokens` INTEGER NOT NULL, " +
                "`web_searches` INTEGER NOT NULL, `audio_ms` INTEGER NOT NULL, `text_micros` INTEGER NOT NULL, " +
                "`search_micros` INTEGER NOT NULL, `transcription_micros` INTEGER NOT NULL, `priced` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_events_recorded_at` ON `usage_events` (`recorded_at`)")
    }
}
