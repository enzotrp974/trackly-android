package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 11 → 12 : période du bilan. Un bilan couvre désormais du lendemain du dernier bilan jusqu'à aujourd'hui,
 * et plus seulement une semaine du lundi au dimanche. Ajoute à `weekly_reports` la colonne `period_end` (dernier jour de
 * la période).
 *
 * Les bilans déjà produits gardent `period_end` à `NULL` : l'application les lit comme des semaines du lundi au dimanche
 * (fin = `week_start` + 6 jours), exactement ce qu'ils étaient. Les fils de bilan (`review_messages`) et les informations
 * de la semaine (`week_info_versions`) sont rangés par `week_start`, qui devient le premier jour de la période : leurs
 * lignes ne changent pas. L'objectif de poids (version 11) n'est pas touché.
 *
 * Aucune ligne n'est supprimée ni modifiée. Réversible : [Migration12To11] retire la colonne et rend exactement la base
 * de la version 11 (testé). La base d'avant est de toute façon copiée dans `no_backup/db-backups/` avant la migration
 * (voir `PreMigrationBackup`).
 */
object Migration11To12 : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `weekly_reports` ADD COLUMN `period_end` TEXT")
    }
}

/**
 * Retour 12 → 11, pour revenir à une version précédente de l'app sans désinstaller : reconstruit `weekly_reports` sans
 * `period_end` (aucune ligne perdue ; un bilan produit depuis se relit alors comme une semaine). Pas dans
 * [ALL_MIGRATIONS] : Android ne remplace pas une app par une version plus ancienne. Reconstruction de la table plutôt que
 * `DROP COLUMN`, que les anciens SQLite d'Android ne connaissent pas.
 */
object Migration12To11 : Migration(12, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `weekly_reports_v11` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `week_start` TEXT NOT NULL, " +
                "`generated_at` INTEGER NOT NULL, `html` TEXT NOT NULL)",
        )
        db.execSQL("INSERT INTO `weekly_reports_v11` (`id`, `week_start`, `generated_at`, `html`) SELECT `id`, `week_start`, `generated_at`, `html` FROM `weekly_reports`")
        db.execSQL("DROP TABLE `weekly_reports`")
        db.execSQL("ALTER TABLE `weekly_reports_v11` RENAME TO `weekly_reports`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_weekly_reports_week_start` ON `weekly_reports` (`week_start`)")
    }
}
