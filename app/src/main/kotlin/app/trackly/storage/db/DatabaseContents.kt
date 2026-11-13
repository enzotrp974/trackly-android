package app.trackly.storage.db

/** Ce que contient la base, sans passer par le moteur : sert à décider avant toute écriture. */
object DatabaseContents {
    /** Tables tenues par Room, Android ou SQLite : jamais des données de l'utilisateur. */
    private val INTERNAL = setOf("android_metadata", "room_master_table", "sqlite_sequence", SETTINGS_OF_A_COPY)

    /** Réglages joints à une copie Drive (`PortableSettings.TABLE`), jamais laissés dans la base de l'app. */
    private const val SETTINGS_OF_A_COPY = "trackly_reglages"

    /**
     * Vrai si une table de Trackly contient au moins une ligne : consommation, objectif, action, message, coût…
     * Toutes les tables comptent, celles des versions futures comprises, pour qu'une base en usage ne passe
     * jamais pour vide. La base est ouverte (et migrée) si elle ne l'était pas encore.
     */
    fun hasData(database: TracklyDatabase): Boolean {
        val sqlite = database.openHelper.readableDatabase
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables
            .filterNot { it in INTERNAL || it.startsWith("sqlite_") }
            .any { table ->
                val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                sqlite.query("SELECT EXISTS(SELECT 1 FROM $quoted)").use { c -> c.moveToFirst() && c.getInt(0) == 1 }
            }
    }
}
