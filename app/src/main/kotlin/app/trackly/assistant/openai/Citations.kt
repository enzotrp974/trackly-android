package app.trackly.assistant.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Citations de la recherche web. Un `output_text` porte des `annotations` de type `url_citation`
 * (`start_index`, `end_index`, `url`, `title`) : la plage est le balisage de la citation dans le texte
 * (souvent « ([site](https://…)) »). L'app l'ôte du texte affiché et montre les sources, cliquables, sous la réponse.
 */
internal object Citations {
    private fun annotations(part: JsonObject): List<JsonObject> =
        (part["annotations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.string("type") == "url_citation" }

    fun of(part: JsonObject): List<Citation> = annotations(part).mapNotNull { a ->
        val url = a.string("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Citation(url, a.string("title")?.takeIf { it.isNotBlank() })
    }

    /**
     * Repère de citation interne (« \uE200cite\uE202turn…view0\uE201 », caractères à usage privé) que le modèle
     * laisse parfois dans le texte sans annotation `url_citation` (vu avec gpt-6.1-terra, banc d'évaluation) :
     * jamais destiné à l'affichage.
     */
    private val MARKER = Regex("\\s?\uE200[^\uE201]{0,200}\uE201")

    /** Texte sans le balisage des citations, ou le texte tel quel si les plages ne correspondent à aucun lien. */
    fun visibleText(part: JsonObject): String = withoutMarkers(linkFree(part))

    /** Espaces en trop à l'intérieur d'une ligne ; le retrait en début de ligne (listes imbriquées) est gardé. */
    private val innerSpaces = Regex("""(?<=\S) {2,}""")

    private fun withoutMarkers(text: String): String =
        if (text.any(::isPrivate)) stripMarkers(text).replace(innerSpaces, " ").trim() else text

    /**
     * Repère encore ouvert en fin de texte (le flux s'est arrêté avant « \uE201 »), borné comme [MARKER] : un repère
     * jamais fermé ne doit pas cacher toute la suite du texte pendant le streaming.
     */
    private val OPEN_MARKER = Regex("\\s?\uE200[^\uE201]{0,200}$")

    /** Caractères à usage privé laissés par les repères (« \uE200 », « \uE201 », « \uE202 »…). */
    private fun isPrivate(c: Char) = c in '\uE200'..'\uE2FF'

    /**
     * Ôte les repères de citation. Avec [unfinished], ôte aussi un repère ouvert et pas encore fermé en fin de
     * texte (streaming : la fin du repère n'est pas arrivée). Tout caractère de repère resté orphelin (jamais fermé,
     * ou trop long pour être un repère) est retiré aussi : il n'est jamais destiné à l'affichage. Ne touche à rien
     * d'autre : pas d'espaces recomptés.
     */
    fun stripMarkers(text: String, unfinished: Boolean = false): String {
        if (text.none(::isPrivate)) return text
        val closed = text.replace(MARKER, "")
        val open = if (unfinished) closed.replace(OPEN_MARKER, "") else closed
        return open.filterNot(::isPrivate)
    }

    private fun linkFree(part: JsonObject): String {
        val text = part.string("text").orEmpty()
        val spans = annotations(part).mapNotNull { a ->
            val start = (a["start_index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (a["end_index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            span(text, start, end, a.string("url").orEmpty())
        }.sortedByDescending { it.first }
        if (spans.isEmpty()) return text
        val out = StringBuilder(text)
        var lastStart = Int.MAX_VALUE
        for ((start, end) in spans) {
            if (end > lastStart) continue // plages qui se chevauchent : on garde la plus tardive
            out.delete(start, end)
            lastStart = start
        }
        return out.toString().replace(innerSpaces, " ").replace(" .", ".").replace(" ,", ",").trim()
    }

    /**
     * Plage en indices UTF-16 si elle recouvre bien un lien. Les indices sont d'abord lus comme des points de code
     * (sans effet pour le français, hors emojis), puis comme des indices UTF-16.
     */
    private fun span(text: String, start: Int, end: Int, url: String): Pair<Int, Int>? {
        val candidates = buildList {
            runCatching { add(text.offsetByCodePoints(0, start) to text.offsetByCodePoints(0, end)) }
            add(start to end)
        }
        return candidates.firstOrNull { (s, e) ->
            s in 0 until e && e <= text.length && text.substring(s, e).let { "http" in it || (url.isNotEmpty() && url in it) }
        }
    }
}
