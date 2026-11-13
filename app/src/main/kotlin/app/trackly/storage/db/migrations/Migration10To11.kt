package app.trackly.storage.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 10 → 11 : objectif de poids modifiable (prévision d'arrivée). Ajoute la table `weight_goal_versions`,
 * vide : tant qu'aucun objectif n'y est écrit, l'objectif par défaut s'applique, comme avant.
 * Aucune autre table ne bouge.
 *
 * Réversible : [Migration11To10] supprime la table et rend exactement la base de la version 10 (testé). Depuis, la table
 * porte aussi le poids de référence (clé `reference_weight`) : ce retour le perd, et le poids repart de celui du profil, alors
 * que les objectifs réécrits gardent leurs grammes. La base d'avant est de toute façon copiée dans `no_backup/db-backups/` avant la migration (voir
 * `PreMigrationBackup`).
 */
object Migration10To11 : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `weight_goal_versions` (`goal_key` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, `target_kg` REAL NOT NULL, `target_date` TEXT NOT NULL, `losing` INTEGER NOT NULL, `recorded_at` INTEGER NOT NULL, " +
                "`action_id` INTEGER, PRIMARY KEY(`goal_key`, `version`))",
        )
    }
}

/**
 * Retour 11 → 10, pour revenir à une version précédente de l'app sans désinstaller : retire l'objectif de poids (l'objectif
 * par défaut reprend sa place) et le poids de référence (celui du profil reprend sa place). Pas dans [ALL_MIGRATIONS] : Android ne remplace pas une app par une version plus ancienne.
 */
object Migration11To10 : Migration(11, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `weight_goal_versions`")
    }
}
