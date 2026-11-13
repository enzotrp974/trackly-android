package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.action.UndoLast
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.storage.db.TracklyDatabase
import app.trackly.storage.db.migrations.ALL_MIGRATIONS
import app.trackly.storage.db.migrations.Migration1To2
import app.trackly.storage.db.migrations.Migration2To3
import app.trackly.storage.db.migrations.Migration3To4
import app.trackly.storage.db.migrations.Migration4To5
import app.trackly.storage.db.migrations.Migration5To6
import app.trackly.storage.db.migrations.Migration6To7
import app.trackly.storage.db.migrations.Migration7To8
import app.trackly.storage.db.migrations.Migration8To9
import app.trackly.storage.db.migrations.Migration9To10
import app.trackly.storage.db.migrations.Migration10To11
import app.trackly.storage.db.migrations.Migration11To10
import app.trackly.storage.db.migrations.Migration11To12
import app.trackly.storage.db.migrations.Migration12To11
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Migrations Room testées sans appareil : `MigrationTestHelper` sous Robolectric (SDK 34),
 * avec les schémas exportés dans `app/schemas`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracklyDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    /** Une consommation et l'action qui l'a créée, telles qu'écrites par la version 1. */
    private fun createVersion1() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO actions (id, type, title, applied_at, undoes_action_id, undone_by_action_id) " +
                    "VALUES (1, 'consumption.add', 'Ajout : Pomme (80 kcal)', 1793620200000, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO action_effects (action_id, position, kind, record_key, before_version, after_version) " +
                    "VALUES (1, 0, 'consumption', 'e1', NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO consumption_versions (entry_id, version, state, day, consumed_at, moment, label, " +
                    "quantity_value, quantity_unit, kcal, protein_g, carbs_g, fat_g, fiber_g, product_id, recipe_id, " +
                    "source_type, source_description, source_url, confidence, origin, recorded_at, action_id) " +
                    "VALUES ('e1', 1, 'active', '2026-11-02', 1793620200000, 'snack', 'Pomme', 150, 'g', 80, 0.5, 21, NULL, " +
                    "3.6, NULL, NULL, NULL, NULL, NULL, NULL, 'dev_panel', 1793620200000, 1)",
            )
        }
    }

    @Test
    fun `migration 1 vers 2 conserve les consommations et ajoute les objectifs`() {
        createVersion1()

        val db = helper.runMigrationsAndValidate(name, 2, true, Migration1To2)

        db.query("SELECT label, kcal, fat_g, assumptions, kcal_low, kcal_high FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
            assertEquals(80.0, c.getDouble(1), 0.0)
            assertTrue("macro inconnue conservée inconnue", c.isNull(2))
            assertEquals("[]", c.getString(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))
        }
        db.execSQL(
            "INSERT INTO goal_versions (effective_from, version, state, kcal, protein_g, carbs_g, fat_g, recorded_at, action_id) " +
                "VALUES ('2026-11-02', 1, 'active', 2600, 140, 352, 70, 0, NULL)",
        )
        db.query("SELECT COUNT(*) FROM goal_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 2 vers 3 ajoute l historique du chat sans toucher aux donnees`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()

        val db = helper.runMigrationsAndValidate(name, 3, true, Migration2To3)

        db.query("SELECT label, kcal FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
            assertEquals(80.0, c.getDouble(1), 0.0)
        }
        db.execSQL("INSERT INTO chat_messages (created_at, role, text) VALUES (1793620200000, 'user', 'une pomme')")
        db.query("SELECT id, role, text, cards FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("user", c.getString(1))
            assertEquals("une pomme", c.getString(2))
            assertEquals("[]", c.getString(3))
        }
        db.close()
    }

    @Test
    fun `migration 3 vers 4 conserve tout et ajoute les tables de la reprise`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).use { db ->
            db.execSQL("INSERT INTO chat_messages (created_at, role, text) VALUES (1793620200000, 'user', 'une pomme')")
            db.execSQL(
                "INSERT INTO goal_versions (effective_from, version, state, kcal, protein_g, carbs_g, fat_g, recorded_at, action_id) " +
                    "VALUES ('2026-11-02', 1, 'active', 2600, 140, 352, 70, 0, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(name, 4, true, Migration3To4)

        db.query("SELECT label, kcal, fat_g FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
            assertEquals(80.0, c.getDouble(1), 0.0)
            assertTrue(c.isNull(2))
        }
        db.query("SELECT kcal FROM goal_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2600.0, c.getDouble(0), 0.0)
        }
        db.query("SELECT text FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("une pomme", c.getString(0))
        }
        for (table in listOf("daily_estimate_versions", "day_tracking_versions", "product_versions", "recipe_versions", "preference_versions", "imported_sources")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(table, 0, c.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun `migration 4 vers 5 garde les produits repris comme recurrents et ajoute portion et index`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).close()
        helper.runMigrationsAndValidate(name, 4, true, Migration3To4).use { db ->
            db.execSQL(
                "INSERT INTO product_versions (product_id, version, state, name, brand, variant, country, barcode, aliases, sold_state, " +
                    "kcal, protein_g, carbs_g, fat_g, fiber_g, basis_value, basis_unit, basis_state, nutrition_status, source_type, " +
                    "source_description, source_url, confidence, verified, favorite, active, redirect_to, default_match, recorded_at, action_id) " +
                    "VALUES ('yaourt-essai', 1, 'active', 'Yaourt nature', NULL, NULL, NULL, NULL, '[\"yaourt\"]', NULL, 61, 4.8, 4.7, 2.2, 0, " +
                    "100, 'g', NULL, 'complete', 'generic_estimate', 'Essai', NULL, 'medium', 0, 1, 1, NULL, 0, 1793620200000, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, Migration4To5)

        db.query("SELECT name, kcal, aliases, kind, portion_label, portion_kcal FROM product_versions WHERE product_id = 'yaourt-essai'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Yaourt nature", c.getString(0))
            assertEquals(61.0, c.getDouble(1), 0.0)
            assertEquals("[\"yaourt\"]", c.getString(2))
            assertEquals("recurring", c.getString(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))
        }
        db.query("SELECT label FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_consumption_versions_product_id'").use { c ->
            assertTrue(c.moveToFirst())
        }
        db.close()
    }

    @Test
    fun `migration 5 vers 6 conserve tout et ajoute le compteur de cout`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).close()
        helper.runMigrationsAndValidate(name, 4, true, Migration3To4).close()
        helper.runMigrationsAndValidate(name, 5, true, Migration4To5).use { db ->
            db.execSQL("INSERT INTO chat_messages (created_at, role, text) VALUES (1793620200000, 'user', 'une pomme')")
        }

        val db = helper.runMigrationsAndValidate(name, 6, true, Migration5To6)

        db.query("SELECT label, kcal FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
            assertEquals(80.0, c.getDouble(1), 0.0)
        }
        db.query("SELECT text FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("une pomme", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM usage_events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // La table accepte une ligne complète et l'index sur la date existe.
        db.execSQL(
            "INSERT INTO usage_events (recorded_at, kind, model, effort, tool_round, input_tokens, cached_tokens, cache_write_tokens, " +
                "output_tokens, reasoning_tokens, web_searches, audio_ms, text_micros, search_micros, transcription_micros, priced) " +
                "VALUES (1793620200000, 'text', 'gpt-6.1-sol', 'medium', 0, 3120, 2400, 0, 60, 20, 0, 0, 7000, 0, 0, 1)",
        )
        db.query("SELECT effort, text_micros, priced FROM usage_events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("medium", c.getString(0))
            assertEquals(7000L, c.getLong(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_usage_events_recorded_at'").use { c ->
            assertTrue(c.moveToFirst())
        }
        db.close()
    }

    @Test
    fun `migration 6 vers 7 garde les plats sans variante et ajoute variantes et provisions`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).close()
        helper.runMigrationsAndValidate(name, 4, true, Migration3To4).close()
        helper.runMigrationsAndValidate(name, 5, true, Migration4To5).close()
        helper.runMigrationsAndValidate(name, 6, true, Migration5To6).use { db ->
            db.execSQL(
                "INSERT INTO recipe_versions (recipe_id, version, state, name, aliases, active, default_match, ingredients, source_template, recorded_at, action_id) " +
                    "VALUES ('rcp-old', 1, 'active', 'Salade', '[\"ma salade\"]', 1, 1, " +
                    "'[{\"ingredient_id\":\"i1\",\"label\":\"Laitue\",\"quantity_g\":100,\"per_100g\":{\"kcal\":15}}]', NULL, 1793620200000, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(name, 7, true, Migration6To7)

        // Le plat repris est intact : un plat sans variante, avec les trois nouvelles colonnes vides.
        db.query("SELECT name, aliases, active, variant_of, variant_name, default_variant_id FROM recipe_versions WHERE recipe_id = 'rcp-old'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Salade", c.getString(0))
            assertEquals("[\"ma salade\"]", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertTrue(c.isNull(3) && c.isNull(4) && c.isNull(5))
        }
        db.query("SELECT label FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
        }
        // Une variante et une provision s'écrivent.
        db.execSQL(
            "INSERT INTO recipe_versions (recipe_id, version, state, name, aliases, active, default_match, ingredients, source_template, variant_of, variant_name, default_variant_id, recorded_at, action_id) " +
                "VALUES ('rcp-b', 1, 'active', 'Salade', '[]', 1, 0, '[]', NULL, 'rcp-old', 'sauce B', NULL, 1793620200000, NULL)",
        )
        db.execSQL("INSERT INTO provision_versions (provision_id, version, state, name, product_id, recorded_at, action_id) VALUES ('prv-1', 1, 'active', 'pommes', NULL, 1793620200000, NULL)")
        db.query("SELECT variant_name FROM recipe_versions WHERE variant_of = 'rcp-old'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("sauce B", c.getString(0))
        }
        db.query("SELECT name FROM provision_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("pommes", c.getString(0))
        }
        db.close()
    }

    @Test
    fun `migration 7 vers 8 conserve tout et ajoute le bilan du lundi`() {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).close()
        helper.runMigrationsAndValidate(name, 4, true, Migration3To4).close()
        helper.runMigrationsAndValidate(name, 5, true, Migration4To5).close()
        helper.runMigrationsAndValidate(name, 6, true, Migration5To6).close()
        helper.runMigrationsAndValidate(name, 7, true, Migration6To7).use { db ->
            db.execSQL("INSERT INTO chat_messages (created_at, role, text) VALUES (1793620200000, 'user', 'une pomme')")
        }

        val db = helper.runMigrationsAndValidate(name, 8, true, Migration7To8)

        db.query("SELECT label, kcal FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
            assertEquals(80.0, c.getDouble(1), 0.0)
        }
        db.query("SELECT text FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("une pomme", c.getString(0))
        }
        for (table in listOf("week_info_versions", "review_messages", "weekly_reports")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
        // Les tables acceptent des lignes complètes : sessions NULL = séances non fournies.
        db.execSQL(
            "INSERT INTO week_info_versions (week_start, version, state, weigh_ins, steps, steps_average, sessions, recorded_at, action_id) " +
                "VALUES ('2026-10-26', 1, 'active', '[{\"day\":\"2026-10-27\",\"kg\":72.4}]', '[]', NULL, NULL, 1793620200000, NULL)",
        )
        db.execSQL("INSERT INTO review_messages (week_start, created_at, role, text) VALUES ('2026-10-26', 1793620200000, 'user', 'mes pesées')")
        db.execSQL("INSERT INTO weekly_reports (week_start, generated_at, html) VALUES ('2026-10-26', 1793620200000, '<html></html>')")
        db.query("SELECT sessions, steps_average FROM week_info_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT cards FROM review_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[]", c.getString(0)) // valeur par défaut
        }
        db.close()
    }

    /** Base fictive en version 8 : versions 1 à 8 appliquées, avec [fill] pour la remplir. */
    private fun createVersion8(fill: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        createVersion1()
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).close()
        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).close()
        helper.runMigrationsAndValidate(name, 4, true, Migration3To4).close()
        helper.runMigrationsAndValidate(name, 5, true, Migration4To5).close()
        helper.runMigrationsAndValidate(name, 6, true, Migration5To6).close()
        helper.runMigrationsAndValidate(name, 7, true, Migration6To7).close()
        helper.runMigrationsAndValidate(name, 8, true, Migration7To8).use(fill)
    }

    private fun insertGoal(db: androidx.sqlite.db.SupportSQLiteDatabase, from: String, version: Int, state: String, kcal: Int, protein: String) {
        db.execSQL(
            "INSERT INTO goal_versions (effective_from, version, state, kcal, protein_g, carbs_g, fat_g, recorded_at, action_id) " +
                "VALUES ('$from', $version, '$state', $kcal, $protein, 300, 70, 1793620200000, NULL)",
        )
    }

    /** Toutes les colonnes de la version 8, ligne par ligne, pour prouver qu'aucune n'a bougé. */
    private fun goalRows(db: androidx.sqlite.db.SupportSQLiteDatabase): List<List<String?>> =
        db.query(
            "SELECT effective_from, version, state, kcal, protein_g, carbs_g, fat_g, recorded_at, action_id " +
                "FROM goal_versions ORDER BY effective_from, version",
        ).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    private fun minimum(db: androidx.sqlite.db.SupportSQLiteDatabase, from: String, version: Int): Double? =
        db.query("SELECT protein_min_g FROM goal_versions WHERE effective_from = '$from' AND version = $version").use { c ->
            assertTrue(c.moveToFirst())
            if (c.isNull(0)) null else c.getDouble(0)
        }

    @Test
    fun `migration 8 vers 9 reprend le minimum de proteines du profil, sans rien toucher d autre`() {
        lateinit var before: List<List<String?>>
        createVersion8 { db ->
            // Profil fictif repris de l'ancien suivi : 1,6 g/kg à 74 kg, puis 1,7 g/kg au poids du profil (72,5 kg).
            val profile = """{"current_reference_weight_kg":72.5,"goals":[""" +
                """{"effective_from":"2026-09-01","kcal":2600,"protein_ideal_g_per_kg":2.0,"protein_min_g_per_kg":1.6,"reference_weight_kg":74.0},""" +
                """{"effective_from":"2026-09-04","kcal":2350,"protein_ideal_g_per_kg":2.1,"protein_min_g_per_kg":1.7}]}"""
            db.execSQL(
                "INSERT INTO imported_sources (kind, record_key, version, sha256, json, action_id) VALUES ('profile', 'profile', 1, 'x', ?, 1)",
                arrayOf(profile),
            )
            insertGoal(db, "2026-08-01", 1, "active", 2500, "140") // avant toute règle du profil
            insertGoal(db, "2026-09-01", 1, "active", 2600, "148") // objectif repris
            insertGoal(db, "2026-09-04", 1, "active", 2350, "152.25")
            insertGoal(db, "2026-10-20", 1, "active", 2200, "152") // fixé ensuite dans le chat
            insertGoal(db, "2026-10-20", 2, "deleted", 2200, "152")
            insertGoal(db, "2026-10-27", 1, "active", 2300, "100") // idéal choisi sous le minimum : pas de minimum inventé
            insertGoal(db, "2026-11-01", 1, "active", 2300, "NULL") // sans macros
            before = goalRows(db)
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, Migration8To9)

        assertEquals(before, goalRows(db))
        assertNull(minimum(db, "2026-08-01", 1))
        assertEquals(118.4, minimum(db, "2026-09-01", 1)!!, 0.0)
        assertEquals(123.25, minimum(db, "2026-09-04", 1)!!, 0.0)
        assertEquals(123.25, minimum(db, "2026-10-20", 1)!!, 0.0)
        assertEquals(123.25, minimum(db, "2026-10-20", 2)!!, 0.0)
        assertNull(minimum(db, "2026-10-27", 1))
        assertEquals(123.25, minimum(db, "2026-11-01", 1)!!, 0.0)
        db.query("SELECT label FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
        }
        db.close()
    }

    @Test
    fun `migration 8 vers 9 sans profil repris laisse le minimum inconnu`() {
        lateinit var before: List<List<String?>>
        createVersion8 { db ->
            insertGoal(db, "2026-11-02", 1, "active", 2600, "140")
            before = goalRows(db)
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, Migration8To9)

        assertEquals(before, goalRows(db))
        assertNull(minimum(db, "2026-11-02", 1))
        db.close()
    }

    @Test
    fun `migration 8 vers 9 lit la derniere version du profil archive`() {
        createVersion8 { db ->
            for ((version, weight) in listOf(1 to 80, 2 to 70)) {
                db.execSQL(
                    "INSERT INTO imported_sources (kind, record_key, version, sha256, json, action_id) VALUES ('profile', 'profile', ?, 'x', ?, 1)",
                    arrayOf<Any>(version, """{"current_reference_weight_kg":$weight,"goals":[{"effective_from":"2026-09-01","protein_min_g_per_kg":1.6}]}"""),
                )
            }
            insertGoal(db, "2026-11-02", 1, "active", 2600, "140")
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, Migration8To9)

        assertEquals(112.0, minimum(db, "2026-11-02", 1)!!, 0.0) // 70 kg (version 2), pas 80 kg
        db.close()
    }

    @Test
    fun `migration 8 vers 9 avec un profil illisible ne bloque pas l app`() {
        createVersion8 { db ->
            db.execSQL("INSERT INTO imported_sources (kind, record_key, version, sha256, json, action_id) VALUES ('profile', 'profile', 1, 'x', 'pas du json', 1)")
            insertGoal(db, "2026-11-02", 1, "active", 2600, "140")
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, Migration8To9)

        assertNull(minimum(db, "2026-11-02", 1))
        db.close()
    }

    /** Base fictive en version 9, avec [fill] pour la remplir. */
    private fun createVersion9(fill: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        createVersion8 { }
        helper.runMigrationsAndValidate(name, 9, true, Migration8To9).use(fill)
    }

    private fun zurich(local: String): Long =
        java.time.LocalDateTime.parse(local).atZone(app.trackly.core.time.TrackingDay.ZONE).toInstant().toEpochMilli()

    private fun insertMessage(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Int, local: String, text: String) {
        db.execSQL("INSERT INTO chat_messages (id, created_at, role, text, cards) VALUES ($id, ${zurich(local)}, 'user', '$text', '[]')")
    }

    private fun messageRows(db: androidx.sqlite.db.SupportSQLiteDatabase): List<List<String?>> =
        db.query("SELECT id, created_at, role, text, cards FROM chat_messages ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    @Test
    fun `migration 9 vers 10 rattache chaque message a sa date de rattachement, avec la regle de 5 h`() {
        lateinit var before: List<List<String?>>
        createVersion9 { db ->
            insertMessage(db, 1, "2026-11-01T12:00", "midi")
            insertMessage(db, 2, "2026-11-02T04:59", "avant 5 h")
            insertMessage(db, 3, "2026-11-02T05:00", "à 5 h pile")
            insertMessage(db, 4, "2026-11-02T00:00", "minuit")
            before = messageRows(db)
        }

        val db = helper.runMigrationsAndValidate(name, 10, true, Migration9To10)

        // Aucune ligne perdue ni changée : seules deux colonnes s'ajoutent.
        assertEquals(before, messageRows(db))
        val days = db.query("SELECT id, day, hidden_at FROM chat_messages ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getInt(0), c.getString(1), c.isNull(2))) }
        }
        assertEquals(
            listOf(Triple(1, "2026-11-01", true), Triple(2, "2026-11-01", true), Triple(3, "2026-11-02", true), Triple(4, "2026-11-01", true)),
            days,
        )
        db.close()
    }

    @Test
    fun `migration 9 vers 10 ne touche ni aux consommations ni aux autres tables`() {
        createVersion9 { db -> insertMessage(db, 1, "2026-11-02T12:00", "une pomme") }

        val db = helper.runMigrationsAndValidate(name, 10, true, Migration9To10)

        db.query("SELECT label FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM actions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 9 vers 10 sur un chat vide ne bloque pas l app`() {
        createVersion9 { }

        val db = helper.runMigrationsAndValidate(name, 10, true, Migration9To10)

        db.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    /** Base fictive en version 10, avec [fill] pour la remplir. */
    private fun createVersion10(fill: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        createVersion9 { db -> insertMessage(db, 1, "2026-11-02T12:00", "une pomme") }
        helper.runMigrationsAndValidate(name, 10, true, Migration9To10).use(fill)
    }

    private fun allRows(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table`").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    @Test
    fun `migration 10 vers 11 ajoute l objectif de poids, vide, sans rien toucher d autre`() {
        val tables = listOf("consumption_versions", "goal_versions", "actions", "action_effects", "chat_messages", "week_info_versions")
        lateinit var before: Map<String, List<List<String?>>>
        createVersion10 { db ->
            db.execSQL(
                "INSERT INTO week_info_versions (week_start, version, state, weigh_ins, steps, steps_average, sessions, recorded_at, action_id) " +
                    "VALUES ('2026-11-02', 1, 'active', '[{\"day\":\"2026-11-03\",\"kg\":66.0}]', '[]', NULL, NULL, 1, 1)",
            )
            before = tables.associateWith { allRows(db, it) }
        }

        val db = helper.runMigrationsAndValidate(name, 11, true, Migration10To11)

        assertEquals(before, tables.associateWith { allRows(db, it) })
        db.query("SELECT COUNT(*) FROM weight_goal_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 11 vers 10 retire l objectif de poids et rend exactement la base 10`() {
        createVersion10 { }
        helper.runMigrationsAndValidate(name, 11, true, Migration10To11).use { db ->
            db.execSQL(
                "INSERT INTO weight_goal_versions (goal_key, version, state, target_kg, target_date, losing, recorded_at, action_id) " +
                    "VALUES ('weight_goal', 1, 'active', 60.0, '2027-04-01', 1, 1, 2)",
            )
        }

        // Validé contre le schéma exporté de la version 10.
        val db = helper.runMigrationsAndValidate(name, 10, true, Migration11To10)
        db.query("SELECT label FROM consumption_versions WHERE entry_id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Pomme", c.getString(0))
        }
        db.close()
    }

    @Test
    fun `apres la migration 11, l objectif de poids par defaut vaut, se change et s annule`() = runBlocking {
        createVersion10 { }
        helper.runMigrationsAndValidate(name, 11, true, Migration10To11).close()

        val room = Room.databaseBuilder(context, TracklyDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val clock = Clock.fixed(Instant.parse("2026-12-14T09:00:00Z"), ZoneOffset.UTC)
            val engine = TracklyEngine(RoomTracklyStore(room), clock)
            assertEquals(app.trackly.core.goal.WeightTarget.Default, engine.weightTarget())

            val set = engine.execute(app.trackly.core.goal.SetWeightGoal(60.0, LocalDate.parse("2027-04-01")))
            assertTrue(set is ActionOutcome.Applied)
            assertEquals(app.trackly.core.goal.WeightTarget(60.0, LocalDate.parse("2027-04-01")), engine.weightTarget())

            assertTrue(engine.execute(UndoLast) is ActionOutcome.Applied)
            assertEquals(app.trackly.core.goal.WeightTarget.Default, engine.weightTarget())
            // Rien n'est effacé : les deux versions restent, la dernière est « deleted ».
            assertEquals(listOf("active", "deleted"), room.openHelper.readableDatabase.query("SELECT state FROM weight_goal_versions ORDER BY version").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            })
        } finally {
            room.close()
        }
    }

    /** Base fictive en version 11, avec [fill] pour la remplir. */
    private fun createVersion11(fill: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        createVersion10 { }
        helper.runMigrationsAndValidate(name, 11, true, Migration10To11).use(fill)
    }

    private fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): List<List<String?>> =
        db.query(sql).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    private fun fillReview(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO week_info_versions (week_start, version, state, weigh_ins, steps, steps_average, sessions, recorded_at, action_id) " +
                "VALUES ('2026-10-26', 1, 'active', '[{\"day\":\"2026-10-27\",\"kg\":72.4}]', '[]', NULL, NULL, 1793620200000, NULL)",
        )
        db.execSQL("INSERT INTO review_messages (id, week_start, created_at, role, text, cards) VALUES (1, '2026-10-26', 1793620200000, 'user', 'mes pesées', '[]')")
        db.execSQL("INSERT INTO weekly_reports (id, week_start, generated_at, html) VALUES (1, '2026-10-26', 1793620200000, '<html>ancien</html>')")
        db.execSQL("INSERT INTO weekly_reports (id, week_start, generated_at, html) VALUES (2, '2026-10-19', 1793000000000, '<html>plus ancien</html>')")
    }

    @Test
    fun `migration 11 vers 12 garde les bilans, leurs fils et leurs informations, sans fin de periode`() {
        lateinit var reportsBefore: List<List<String?>>
        lateinit var threadBefore: List<List<String?>>
        lateinit var infosBefore: List<List<String?>>
        createVersion11 { db ->
            fillReview(db)
            reportsBefore = rows(db, "SELECT id, week_start, generated_at, html FROM weekly_reports ORDER BY id")
            threadBefore = rows(db, "SELECT id, week_start, created_at, role, text, cards FROM review_messages ORDER BY id")
            infosBefore = rows(db, "SELECT * FROM week_info_versions ORDER BY week_start, version")
        }

        val db = helper.runMigrationsAndValidate(name, 12, true, Migration11To12)

        // Aucune ligne perdue ni changée : une colonne s'ajoute, vide (NULL = bilan d'une semaine du lundi au dimanche).
        assertEquals(reportsBefore, rows(db, "SELECT id, week_start, generated_at, html FROM weekly_reports ORDER BY id"))
        assertEquals(threadBefore, rows(db, "SELECT id, week_start, created_at, role, text, cards FROM review_messages ORDER BY id"))
        assertEquals(infosBefore, rows(db, "SELECT * FROM week_info_versions ORDER BY week_start, version"))
        assertEquals(listOf(listOf<String?>(null), listOf<String?>(null)), rows(db, "SELECT period_end FROM weekly_reports ORDER BY id"))
        db.close()
    }

    @Test
    fun `migration 11 vers 12 accepte un nouveau bilan avec sa fin de periode`() {
        createVersion11 { fillReview(it) }

        val db = helper.runMigrationsAndValidate(name, 12, true, Migration11To12)
        db.execSQL("INSERT INTO weekly_reports (id, week_start, generated_at, html, period_end) VALUES (3, '2026-11-03', 1793620200000, '<html></html>', '2026-11-09')")

        assertEquals(listOf(listOf<String?>("2026-11-09")), rows(db, "SELECT period_end FROM weekly_reports WHERE id = 3"))
        db.close()
    }

    @Test
    fun `migration 11 vers 12 sur une base sans bilan ne bloque pas l app, et garde l objectif de poids`() {
        createVersion11 { db ->
            db.execSQL(
                "INSERT INTO weight_goal_versions (goal_key, version, state, target_kg, target_date, losing, recorded_at, action_id) " +
                    "VALUES ('weight_goal', 1, 'active', 60.0, '2027-04-01', 1, 1, 2)",
            )
        }

        val db = helper.runMigrationsAndValidate(name, 12, true, Migration11To12)

        assertEquals(listOf(listOf<String?>("0")), rows(db, "SELECT COUNT(*) FROM weekly_reports"))
        assertEquals(listOf(listOf<String?>("weight_goal", "60")), rows(db, "SELECT goal_key, target_kg FROM weight_goal_versions"))
        db.close()
    }

    @Test
    fun `migration 12 vers 11 retire la fin de periode et rend exactement la base 11, bilans compris`() {
        lateinit var reportsBefore: List<List<String?>>
        createVersion11 { db ->
            fillReview(db)
            reportsBefore = rows(db, "SELECT id, week_start, generated_at, html FROM weekly_reports ORDER BY id")
        }
        helper.runMigrationsAndValidate(name, 12, true, Migration11To12).use { db ->
            db.execSQL("INSERT INTO weekly_reports (id, week_start, generated_at, html, period_end) VALUES (3, '2026-11-03', 1793620200000, '<html>x</html>', '2026-11-09')")
        }

        // Validé contre le schéma exporté de la version 11.
        val db = helper.runMigrationsAndValidate(name, 11, true, Migration12To11)

        // Les bilans d'avant sont intacts ; le nouveau garde son contenu, sans sa fin (il se relit comme une semaine).
        assertEquals(reportsBefore, rows(db, "SELECT id, week_start, generated_at, html FROM weekly_reports WHERE id < 3 ORDER BY id"))
        assertEquals(listOf(listOf<String?>("3", "2026-11-03", "<html>x</html>")), rows(db, "SELECT id, week_start, html FROM weekly_reports WHERE id = 3"))
        assertEquals(listOf(listOf<String?>("mes pesées")), rows(db, "SELECT text FROM review_messages"))
        db.close()
    }

    @Test
    fun `apres toutes les migrations, le moteur relit l historique et continue`() = runBlocking {
        createVersion1()

        val room = Room.databaseBuilder(context, TracklyDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val clock = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC)
            val engine = TracklyEngine(RoomTracklyStore(room), clock)
            val day = LocalDate.parse("2026-11-02")

            val before = engine.daySummary(day)
            assertEquals(listOf("Pomme"), before.consumptions.map { it.food.label })
            assertEquals(emptyList<String>(), before.consumptions.single().food.assumptions)

            assertTrue(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0))) is ActionOutcome.Applied)
            assertEquals(2520.0, engine.daySummary(day).remainingKcal!!, 0.0)

            // L'ajout fait en version 1 reste annulable après migration.
            assertTrue(engine.execute(UndoLast) is ActionOutcome.Applied) // objectif
            assertTrue(engine.execute(UndoLast) is ActionOutcome.Applied) // pomme
            assertEquals(0.0, engine.daySummary(day).totals.kcal, 0.0)
        } finally {
            room.close()
        }
    }
}
