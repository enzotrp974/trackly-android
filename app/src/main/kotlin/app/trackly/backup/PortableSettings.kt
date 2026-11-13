package app.trackly.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **Réglages non secrets** qui voyagent dans la copie Drive : modèle de l'assistant, niveau de raisonnement, mode de dictée, rappels du bilan,
 * réflexion et écho de l'appel.
 * Ils sont joints à chaque copie envoyée, dans une table à part ([TABLE]) que la base de l'app n'a jamais : à la
 * restauration, ils sont retirés de la copie avant le remplacement, puis remis dans les préférences au redémarrage.
 *
 * Jamais copiés : la clé OpenAI (fichier chiffré dans `no_backup`, hors préférences), ni ce qui dépend de l'appareil ou
 * de l'installation (permissions déjà demandées, batterie, état de la sauvegarde, app neuve). Une copie sans cette table
 * (faite avant cette version) laisse les réglages actuels tels quels.
 */
object PortableSettings {
    const val TABLE = "trackly_reglages"

    /**
     * Réglages copiés, clé par clé, par fichier de préférences. Rien d'autre ne part : un réglage ajouté plus tard ne
     * voyage que s'il est ajouté ici (jamais un identifiant ni un état propre à l'appareil, comme `micro_demande`).
     */
    val FILES: Map<String, Set<String>> = mapOf(
        "trackly-assistant" to setOf("modele", "raisonnement"),
        "trackly-dictee" to setOf("mode"),
        "trackly-bilan" to setOf("rappels_actifs", "invitation_minutes"),
        // Appel : réflexion pendant l'appel et annulation d'écho.
        "trackly-appel" to setOf("reflexion", "echo"),
    )

    data class Entry(val file: String, val key: String, val type: String, val value: String)

    private const val STRING = "s"
    private const val BOOLEAN = "b"
    private const val INT = "i"
    private const val LONG = "l"
    private const val FLOAT = "f"

    /** Les réglages copiables, tels qu'ils sont dans les préférences. */
    fun read(prefsOf: (String) -> SharedPreferences): List<Entry> = FILES.flatMap { (file, keys) ->
        prefsOf(file).all.mapNotNull { (key, value) ->
            if (key !in keys) return@mapNotNull null
            when (value) {
                is String -> Entry(file, key, STRING, value)
                is Boolean -> Entry(file, key, BOOLEAN, value.toString())
                is Int -> Entry(file, key, INT, value.toString())
                is Long -> Entry(file, key, LONG, value.toString())
                is Float -> Entry(file, key, FLOAT, value.toString())
                else -> null
            }
        }
    }.sortedWith(compareBy({ it.file }, { it.key }))

    /**
     * Remplace les réglages copiables par [entries] (ceux d'une copie restaurée). Les autres clés et les autres
     * fichiers ne sont pas touchés. Une valeur illisible, ou une clé inconnue (version future), est ignorée.
     */
    fun write(prefsOf: (String) -> SharedPreferences, entries: List<Entry>) {
        for ((file, keys) in FILES) {
            val prefs = prefsOf(file)
            val editor = prefs.edit()
            keys.forEach(editor::remove)
            for (e in entries) {
                if (e.file != file || e.key !in keys) continue
                when (e.type) {
                    STRING -> editor.putString(e.key, e.value)
                    BOOLEAN -> e.value.toBooleanStrictOrNull()?.let { editor.putBoolean(e.key, it) }
                    INT -> e.value.toIntOrNull()?.let { editor.putInt(e.key, it) }
                    LONG -> e.value.toLongOrNull()?.let { editor.putLong(e.key, it) }
                    FLOAT -> e.value.toFloatOrNull()?.let { editor.putFloat(e.key, it) }
                }
            }
            editor.commit()
        }
    }

    /** Joint [entries] à une copie (fichier fermé, hors de la base de l'app). */
    fun attach(copy: File, entries: List<Entry>) {
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS $TABLE")
                db.execSQL("CREATE TABLE $TABLE (fichier TEXT NOT NULL, cle TEXT NOT NULL, type TEXT NOT NULL, valeur TEXT NOT NULL, PRIMARY KEY (fichier, cle))")
                for (e in entries) {
                    db.execSQL("INSERT INTO $TABLE (fichier, cle, type, valeur) VALUES (?, ?, ?, ?)", arrayOf(e.file, e.key, e.type, e.value))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Une copie reste un seul fichier : rien ne doit rester dans un journal à côté.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        DatabaseFiles.SIDE_SUFFIXES.forEach { File(copy.path + it).delete() }
    }

    /** Retire les réglages joints à une copie et les rend ; `null` si la copie n'en a pas (faite avant cette version). */
    fun detach(db: SupportSQLiteDatabase): List<Entry>? {
        val present = db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$TABLE'")
            .use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        if (!present) return null
        val entries = db.query("SELECT fichier, cle, type, valeur FROM $TABLE").use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }
        db.execSQL("DROP TABLE $TABLE")
        return entries
    }

    fun toJson(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { e -> put(JSONObject().put("fichier", e.file).put("cle", e.key).put("type", e.type).put("valeur", e.value)) }
    }.toString()

    fun fromJson(text: String): List<Entry> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(o.getString("fichier"), o.getString("cle"), o.getString("type"), o.getString("valeur"))
        }
    }

    fun prefsOf(context: Context): (String) -> SharedPreferences {
        val app = context.applicationContext
        return { name -> app.getSharedPreferences(name, Context.MODE_PRIVATE) }
    }
}
