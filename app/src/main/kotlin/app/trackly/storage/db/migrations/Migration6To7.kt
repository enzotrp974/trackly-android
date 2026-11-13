package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 6 → 7 : plats avec variantes et provisions.
 *
 * - `recipe_versions` reçoit trois colonnes facultatives (`variant_of`, `variant_name`,
 *   `default_variant_id`) : les plats existants restent des plats sans variante, rien n'est réécrit.
 *   Les ingrédients gardent leur JSON ; un lien vers un produit y est ajouté sous `product_id` quand il existe.
 * - Nouvelle table `provision_versions` (« j'ai » / « je n'ai plus », en versions).
 *
 * Réversible : revenir en arrière = supprimer la table `provision_versions` et recréer `recipe_versions`
 * sans les trois colonnes (SQLite ne sait pas supprimer une colonne simplement) en copiant les autres.
 * Aucune donnée de suivi n'en dépend. La base d'avant est copiée dans `no_backup/db-backups/` avant la migration.
 */
object Migration6To7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recipe_versions` ADD COLUMN `variant_of` TEXT")
        db.execSQL("ALTER TABLE `recipe_versions` ADD COLUMN `variant_name` TEXT")
        db.execSQL("ALTER TABLE `recipe_versions` ADD COLUMN `default_variant_id` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `provision_versions` (`provision_id` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `name` TEXT NOT NULL, `product_id` TEXT, `recorded_at` INTEGER NOT NULL, " +
                "`action_id` INTEGER, PRIMARY KEY(`provision_id`, `version`))",
        )
    }
}
