package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 → 2 : objectifs nutritionnels datés et hypothèses d'estimation.
 * Les consommations existantes sont conservées telles quelles ; elles reçoivent une liste
 * d'hypothèses vide et une fourchette inconnue (`NULL`), sans valeur inventée.
 *
 * Requêtes écrites en entier (pas de constantes), comme le recommande la doc Room.
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `consumption_versions` ADD COLUMN `assumptions` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `consumption_versions` ADD COLUMN `kcal_low` REAL")
        db.execSQL("ALTER TABLE `consumption_versions` ADD COLUMN `kcal_high` REAL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal_versions` (" +
                "`effective_from` TEXT NOT NULL, `version` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                "`kcal` REAL NOT NULL, `protein_g` REAL, `carbs_g` REAL, `fat_g` REAL, " +
                "`recorded_at` INTEGER NOT NULL, `action_id` INTEGER, " +
                "PRIMARY KEY(`effective_from`, `version`))",
        )
    }
}
