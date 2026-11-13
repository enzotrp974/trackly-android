package app.trackly.storage.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.ProteinMinimum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Version 8 → 9 : minimum de protéines. Ajoute à `goal_versions` la colonne `protein_min_g`, un plancher
 * enregistré à côté de l'idéal (`protein_g`).
 *
 * Les objectifs déjà en base reçoivent le minimum que le profil repris de l'ancien suivi leur donnait déjà
 * (`imported_sources`, document `profile`) : `protein_min_g_per_kg` de la règle en vigueur à leur date × poids de
 * référence, le même calcul que la reprise ([ProteinMinimum]). Rien n'est inventé : sans profil, sans règle à cette
 * date, ou si ce minimum dépasse l'idéal de la ligne (choisi à la main plus bas), la colonne reste `NULL`. Aucune autre
 * colonne ne change, aucune version n'est ajoutée ni retirée.
 *
 * Réversible : supprimer la colonne `protein_min_g` rend exactement la base de la version 8. La base d'avant est de
 * toute façon copiée dans `no_backup/db-backups/` avant la migration (voir `PreMigrationBackup`).
 */
object Migration8To9 : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `goal_versions` ADD COLUMN `protein_min_g` REAL")

        val profile = db.query(
            "SELECT json FROM imported_sources WHERE kind = 'profile' AND record_key = 'profile' ORDER BY version DESC LIMIT 1",
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
        val document = runCatching { Json.parseToJsonElement(profile) as? JsonObject }.getOrNull() ?: return

        data class Row(val from: String, val version: Int, val proteinG: Double?)
        val rows = db.query("SELECT effective_from, version, protein_g FROM goal_versions").use { c ->
            buildList {
                while (c.moveToNext()) add(Row(c.getString(0), c.getInt(1), if (c.isNull(2)) null else c.getDouble(2)))
            }
        }
        for (row in rows) {
            val day = runCatching { LocalDate.parse(row.from) }.getOrNull() ?: continue
            val minimum = ProteinMinimum.fromProfile(document, day) ?: continue
            if (row.proteinG != null && row.proteinG + GoalCoherence.MINIMUM_TOLERANCE_G < minimum) continue
            db.update(
                "goal_versions",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply { put("protein_min_g", minimum) },
                "effective_from = ? AND version = ?",
                arrayOf<Any>(row.from, row.version),
            )
        }
    }
}
