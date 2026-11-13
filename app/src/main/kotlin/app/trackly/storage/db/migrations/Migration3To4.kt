package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 3 → 4 : reprise de l'historique. Ajoute, sans toucher aux tables existantes :
 * estimations journalières validées, suivi déclaré des journées, produits, plats, préférences
 * et registre des documents d'origine repris.
 *
 * Réversible : la migration ne fait qu'ajouter des tables vides. La base d'avant est de toute
 * façon copiée dans `no_backup/db-backups/` avant la migration (voir `PreMigrationBackup`).
 *
 * Requêtes écrites en entier (pas de constantes), comme le recommande la doc Room.
 */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_estimate_versions` (`assessment_id` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `day` TEXT NOT NULL, `kind` TEXT NOT NULL, `kcal` REAL, `kcal_low` REAL NOT NULL, " +
                "`kcal_high` REAL, `description` TEXT NOT NULL, `replaces_entry_ids` TEXT NOT NULL, `recorded_at` INTEGER NOT NULL, " +
                "`action_id` INTEGER, PRIMARY KEY(`assessment_id`, `version`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_estimate_versions_day` ON `daily_estimate_versions` (`day`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_tracking_versions` (`day` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `closed_explicitly` INTEGER NOT NULL, `tracking_complete` INTEGER, " +
                "`detailed_tracking_complete` INTEGER, `recorded_at` INTEGER NOT NULL, `action_id` INTEGER, " +
                "PRIMARY KEY(`day`, `version`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `product_versions` (`product_id` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `name` TEXT NOT NULL, `brand` TEXT, `variant` TEXT, `country` TEXT, `barcode` TEXT, " +
                "`aliases` TEXT NOT NULL, `sold_state` TEXT, `kcal` REAL, `protein_g` REAL, `carbs_g` REAL, `fat_g` REAL, " +
                "`fiber_g` REAL, `basis_value` REAL, `basis_unit` TEXT, `basis_state` TEXT, `nutrition_status` TEXT, " +
                "`source_type` TEXT, `source_description` TEXT, `source_url` TEXT, `confidence` TEXT, " +
                "`verified` INTEGER NOT NULL, `favorite` INTEGER NOT NULL, `active` INTEGER NOT NULL, `redirect_to` TEXT, " +
                "`default_match` INTEGER NOT NULL, `recorded_at` INTEGER NOT NULL, `action_id` INTEGER, " +
                "PRIMARY KEY(`product_id`, `version`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recipe_versions` (`recipe_id` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `name` TEXT NOT NULL, `aliases` TEXT NOT NULL, `active` INTEGER NOT NULL, " +
                "`default_match` INTEGER NOT NULL, `ingredients` TEXT NOT NULL, `source_template` TEXT, " +
                "`recorded_at` INTEGER NOT NULL, `action_id` INTEGER, PRIMARY KEY(`recipe_id`, `version`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `preference_versions` (`preference_id` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `kind` TEXT NOT NULL, `trigger` TEXT NOT NULL, `rule` TEXT NOT NULL, " +
                "`active` INTEGER NOT NULL, `recorded_at` INTEGER NOT NULL, `action_id` INTEGER, " +
                "PRIMARY KEY(`preference_id`, `version`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `imported_sources` (`kind` TEXT NOT NULL, `record_key` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `json` TEXT NOT NULL, `action_id` INTEGER NOT NULL, " +
                "PRIMARY KEY(`kind`, `record_key`, `version`))",
        )
    }
}
