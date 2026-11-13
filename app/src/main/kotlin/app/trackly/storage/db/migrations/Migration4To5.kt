package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 4 → 5 : produits ponctuels et récurrents. Ajoute à `product_versions` le type
 * du produit et sa portion usuelle, et un index sur les consommations par produit (pour savoir
 * si un produit ponctuel a déjà été mangé).
 *
 * Les produits déjà repris deviennent `recurring` : l'utilisateur les avait déjà nommés
 * dans l'ancien catalogue. Aucune ligne n'est modifiée ni supprimée ; la portion est inconnue
 * (`NULL`), sans valeur inventée.
 *
 * Réversible : seules des colonnes et un index sont ajoutés ; la base d'avant est de toute façon
 * copiée dans `no_backup/db-backups/` avant la migration (voir `PreMigrationBackup`).
 *
 * Requêtes écrites en entier (pas de constantes), comme le recommande la doc Room.
 */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'recurring'")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_label` TEXT")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_weight_value` REAL")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_weight_unit` TEXT")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_kcal` REAL")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_protein_g` REAL")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_carbs_g` REAL")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_fat_g` REAL")
        db.execSQL("ALTER TABLE `product_versions` ADD COLUMN `portion_fiber_g` REAL")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_consumption_versions_product_id` ON `consumption_versions` (`product_id`)")
    }
}
