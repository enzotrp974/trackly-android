package app.trackly.core.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Export de parité partagé avec le moteur Python (`fixtures/parity/bundle.json`) :
 * jeu d'essai synthétique, totaux de contrôle calculés par le moteur Python.
 */
object ParityBundle {
    val text: String by lazy {
        checkNotNull(ParityBundle::class.java.getResource("/bundle.json")) { "bundle.json introuvable" }.readText()
    }

    val json: JsonObject get() = Json.parseToJsonElement(text).jsonObject

    /** Copie modifiée de l'export : [edit] reçoit l'objet racine et renvoie le nouveau. */
    fun edited(edit: (JsonObject) -> JsonObject): String = edit(json).toString()

    /** Remplace un élément à un chemin (clés d'objet, ou index de liste en texte). */
    fun JsonElement.replaceAt(path: List<String>, transform: (JsonElement) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(this)
        val head = path.first()
        val rest = path.drop(1)
        return when (this) {
            is JsonObject -> JsonObject(this + (head to getValue(head).replaceAt(rest, transform)))
            is JsonArray -> JsonArray(jsonArray.mapIndexed { i, e -> if (i == head.toInt()) e.replaceAt(rest, transform) else e })
            else -> error("Chemin invalide : $path")
        }
    }

    fun JsonElement.withField(key: String, value: JsonElement): JsonObject = JsonObject(jsonObject + (key to value))
}
