package app.trackly.dictation

import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Recipe
import app.trackly.core.model.TermCorrections
import app.trackly.core.model.TextMatch

/**
 * Mots-clés passés au modèle de transcription (paramètre `keywords` de `gpt-transcribe` et de
 * `gpt-live-transcribe`) : les noms et raccourcis des produits et plats récurrents, pour qu'un
 * sigle de marque en capitales ne soit pas transcrit en minuscules ou déformé.
 *
 * OpenAI rejette toute la requête si un mot-clé contient `<`, `>`, un retour chariot ou un saut
 * de ligne, ou si le contexte dépasse sa limite (jamais chiffrée dans la doc). Les mots-clés sont
 * donc nettoyés, dédoublonnés sans tenir compte de la casse ni des accents, et bornés.
 * Les vrais mots des corrections passent en tête, puis les sigles ; les raccourcis passent avant les noms : ce sont les mots les plus mal reconnus.
 */
object DictationKeywords {
    /** Nombre maximal de mots-clés : un vocabulaire utile sans noyer le modèle. */
    const val MAX_COUNT = 80

    /** Longueur maximale d'un mot-clé (un nom plus long n'est pas un mot-clé, c'est une phrase). */
    const val MAX_LENGTH = 60

    /** Total de caractères, tous mots-clés ensemble : garde le contexte bien en deçà de toute limite plausible. */
    const val MAX_CHARS = 1_600

    fun build(products: List<Product>, recipes: List<Recipe>, correctWords: List<String> = emptyList()): List<String> {
        val liveProducts = CatalogSearch.visibleProducts(products).filter { it.kind == ProductKind.Recurring }
        val liveRecipes = recipes.filter { it.isActive }
        val shortcuts = liveProducts.flatMap { it.aliases } + liveRecipes.flatMap { it.aliases }
        val brands = liveProducts.mapNotNull { it.brand }
        val names = liveProducts.map { it.name } + liveRecipes.map { it.name }
        val acronyms = (shortcuts + brands + names).flatMap { acronymsIn(it) }
        // Les vrais mots des corrections (« Skyr ») passent en premier : c'est ce que la dictée écrit mal.
        return limit(correctWords + sorted(acronyms) + sorted(shortcuts) + sorted(brands) + sorted(names))
    }

    private val acronym = Regex("^[\\p{Lu}0-9]{2,6}$")

    /**
     * Sigles en capitales (« BIO », « XL ») pris isolément : un contrôle réel a montré qu'une marque en sigle
     * était mal transcrite (tronquée ou en minuscules) tant qu'elle n'apparaissait que dans un nom plus long.
     */
    fun acronymsIn(text: String): List<String> =
        text.split(spaces).map { it.trim(',', '.', '(', ')') }
            .filter { acronym.matches(it) && it.any(Char::isLetter) }

    suspend fun from(engine: TracklyEngine): List<String> =
        build(engine.allProducts(), engine.allRecipes(), TermCorrections.correctWords(engine.activeTermCorrections()))

    /** Nettoie, dédoublonne et borne une liste de mots-clés, dans l'ordre reçu. */
    fun limit(candidates: List<String>): List<String> {
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        var chars = 0
        for (raw in candidates) {
            val word = clean(raw)
            if (word.isEmpty() || word.length > MAX_LENGTH) continue
            if (!seen.add(TextMatch.normalize(word))) continue
            if (kept.size >= MAX_COUNT || chars + word.length > MAX_CHARS) break
            kept += word
            chars += word.length
        }
        return kept
    }

    private fun sorted(words: List<String>) = words.sortedBy { TextMatch.normalize(it) }

    private val forbidden = Regex("[<>\\r\\n]+")
    private val spaces = Regex("\\s+")

    /** Retire les caractères que l'API refuse et les espaces en trop. */
    fun clean(word: String): String = word.replace(forbidden, " ").replace(spaces, " ").trim()
}
