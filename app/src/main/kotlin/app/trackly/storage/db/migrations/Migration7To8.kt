package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 7 → 8 : bilan du lundi. Ajoute trois tables, sans toucher aux existantes :
 * `week_info_versions` (pesées, pas et séances déclarés, versionnés), `review_messages` (conversation
 * de bilan, un fil par semaine) et `weekly_reports` (rapports HTML).
 *
 * Réversible : pour revenir en arrière, il suffit de supprimer ces trois tables ; aucune donnée de
 * suivi n'en dépend. La base d'avant est de toute façon copiée dans `no_backup/db-backups/` avant la
 * migration (voir `PreMigrationBackup`).
 */
object Migration7To8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `week_info_versions` (`week_start` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `weigh_ins` TEXT NOT NULL, `steps` TEXT NOT NULL, `steps_average` INTEGER, " +
                "`sessions` TEXT, `recorded_at` INTEGER NOT NULL, `action_id` INTEGER, PRIMARY KEY(`week_start`, `version`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`week_start` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`cards` TEXT NOT NULL DEFAULT '[]')",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_messages_week_start` ON `review_messages` (`week_start`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `weekly_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`week_start` TEXT NOT NULL, `generated_at` INTEGER NOT NULL, `html` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_weekly_reports_week_start` ON `weekly_reports` (`week_start`)")
    }
}
