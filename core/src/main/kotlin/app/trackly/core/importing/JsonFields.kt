package app.trackly.core.importing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Fichier d'export refusé : message en français, avec l'endroit du problème. */
class BundleFormatException(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw BundleFormatException(message)

/** Lecture typée d'un objet JSON ; [where] situe l'objet dans le fichier pour les messages. */
internal class Fields(val obj: JsonObject, val where: String) {
    private fun bad(field: String, expected: String): Nothing = fail("$where : « $field » doit être $expected")

    fun has(field: String) = obj.containsKey(field)

    private fun required(field: String): JsonElement = obj[field] ?: fail("$where : champ « $field » manquant")

    private fun nullable(field: String): JsonPrimitive? = when (val e = obj[field]) {
        null, JsonNull -> null
        is JsonPrimitive -> e
        else -> bad(field, "une valeur simple")
    }

    fun string(field: String): String {
        val e = required(field) as? JsonPrimitive
        return e?.takeIf { it.isString }?.content ?: bad(field, "un texte")
    }

    fun stringOrNull(field: String): String? = nullable(field)?.let { if (it.isString) it.content else bad(field, "un texte") }

    fun number(field: String): Double {
        val e = required(field) as? JsonPrimitive
        return e?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: bad(field, "un nombre")
    }

    fun numberOrNull(field: String): Double? =
        nullable(field)?.let { p -> p.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: bad(field, "un nombre") }

    fun int(field: String): Int {
        val e = required(field) as? JsonPrimitive
        return e?.takeIf { !it.isString }?.intOrNull ?: bad(field, "un entier")
    }

    fun boolean(field: String): Boolean {
        val e = required(field) as? JsonPrimitive
        return e?.takeIf { !it.isString }?.booleanOrNull ?: bad(field, "vrai ou faux")
    }

    fun booleanOrNull(field: String): Boolean? =
        nullable(field)?.let { p -> p.takeIf { !it.isString }?.booleanOrNull ?: bad(field, "vrai ou faux") }

    fun obj(field: String): Fields = Fields(required(field) as? JsonObject ?: bad(field, "un objet"), "$where › $field")

    fun objOrNull(field: String): Fields? = when (val e = obj[field]) {
        null, JsonNull -> null
        is JsonObject -> Fields(e, "$where › $field")
        else -> bad(field, "un objet")
    }

    fun array(field: String): JsonArray = required(field) as? JsonArray ?: bad(field, "une liste")

    fun arrayOrEmpty(field: String): JsonArray = when (val e = obj[field]) {
        null, JsonNull -> JsonArray(emptyList())
        is JsonArray -> e
        else -> bad(field, "une liste")
    }

    fun strings(field: String): List<String> = arrayOrEmpty(field).map { e ->
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad(field, "une liste de textes")
    }

    fun objects(field: String): List<Fields> = array(field).mapIndexed { i, e ->
        Fields(e as? JsonObject ?: fail("$where › $field : l'élément ${i + 1} doit être un objet"), "$where › $field ${i + 1}")
    }

    fun date(field: String): LocalDate = string(field).let { text ->
        runCatching { LocalDate.parse(text) }.getOrElse { bad(field, "une date AAAA-MM-JJ") }
    }

    fun instant(field: String): Instant = string(field).let(::parseInstant) ?: bad(field, "une date et heure ISO")

    fun instantOrNull(field: String): Instant? = stringOrNull(field)?.let { parseInstant(it) ?: bad(field, "une date et heure ISO") }

    /** Exécute [block] en transformant une donnée refusée par le modèle en erreur située. */
    inline fun <T> build(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        fail("$where : ${e.message}")
    }
}

private fun parseInstant(text: String): Instant? =
    runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull() ?: runCatching { Instant.parse(text) }.getOrNull()

/**
 * JSON canonique : clés triées, sans espaces, nombres tels qu'écrits dans le fichier.
 * Deux exports du même contenu ont la même forme canonique, donc la même empreinte.
 */
internal fun canonical(element: JsonElement): String = StringBuilder().also { appendCanonical(it, element) }.toString()

private fun appendCanonical(out: StringBuilder, element: JsonElement) {
    when (element) {
        is JsonObject -> {
            out.append('{')
            element.keys.sorted().forEachIndexed { i, key ->
                if (i > 0) out.append(',')
                out.append(JsonPrimitive(key).toString()).append(':')
                appendCanonical(out, element.getValue(key))
            }
            out.append('}')
        }
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { i, e ->
                if (i > 0) out.append(',')
                appendCanonical(out, e)
            }
            out.append(']')
        }
        // JsonPrimitive.toString() : texte échappé entre guillemets, nombre et booléen tels quels.
        is JsonPrimitive -> out.append(element.toString())
    }
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Arrondi à 2 décimales identique à `round(x, 2)` de Python (valeur exacte, demi au pair). */
fun pythonRound2(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toDouble()
