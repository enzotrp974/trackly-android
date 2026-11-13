package app.trackly.core.catalog

import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Recipe
import app.trackly.core.model.TextMatch

/** Comment une fiche correspond à la recherche, du plus sûr au plus vague. */
enum class MatchKind(val code: String) {
    /** Le raccourci (ou un alias) est exactement ce qui a été cherché, ou est contenu dans la phrase. */
    Shortcut("shortcut"),
    Name("name"),

    /** Tous les mots cherchés se retrouvent dans la fiche (nom, marque, variante, raccourcis). */
    Words("words"),

    /** Une partie seulement des mots, ou des débuts de mots. */
    Partial("partial"),
}

data class ProductHit(val product: Product, val score: Int, val match: MatchKind)

/** [covered] : nombre de mots cherchés qui se retrouvent dans le nom complet (plat et variante) ; départage les ex æquo. */
data class RecipeHit(val recipe: Recipe, val score: Int, val match: MatchKind, val covered: Int = 0)

/**
 * Recherche dans le catalogue (produits et plats repris ou mémorisés) par nom, raccourci,
 * marque et variante. Insensible à la casse et aux accents. Pure : aucune lecture de stockage.
 *
 * Le score va de 0 à 100 ; un raccourci exact vaut 100. Un produit supprimé, ou fusionné vers
 * un autre (`redirectTo` valide), n'est jamais renvoyé : c'est sa cible qui l'est.
 */
object CatalogSearch {
    const val MIN_SCORE = 20
    private const val EXACT = 100

    fun products(all: List<Product>, query: String, limit: Int = 8, minScore: Int = MIN_SCORE): List<ProductHit> {
        val q = TextMatch.tokens(query)
        if (q.isEmpty()) return emptyList()
        val visible = visibleProducts(all)
        return visible.mapNotNull { p ->
            val (score, kind) = score(
                q,
                names = listOf(p.name) + listOfNotNull(p.brand?.let { b -> "$b ${p.name}" }, p.brand?.let { b -> "${p.name} $b" }),
                shortcuts = p.aliases,
                extras = listOfNotNull(p.brand, p.variant),
            )
            if (score >= minScore) ProductHit(p, score, kind) else null
        }
            .sortedWith(
                compareByDescending<ProductHit> { it.score }
                    .thenByDescending { it.product.kind == ProductKind.Recurring }
                    .thenByDescending { it.product.favorite }
                    .thenByDescending { it.product.defaultMatch }
                    .thenBy { TextMatch.normalize(it.product.name) },
            )
            .take(limit)
    }

    /**
     * Plats et variantes par nom, raccourci du plat et nom de variante (« salade sauce B » trouve la
     * variante B avant le plat seul), dans n'importe quel ordre et malgré les mots de liaison
     * (« la sauce B avec ma salade »). Les fiches supprimées sont écartées ; un plat en pause reste
     * trouvé (il est reconnu quand on le mange), mais signalé par `recipe.active`.
     */
    fun recipes(all: List<Recipe>, query: String, limit: Int = 5, minScore: Int = MIN_SCORE): List<RecipeHit> {
        val q = TextMatch.tokens(query)
        if (q.isEmpty()) return emptyList()
        val live = all.filter { it.isActive }
        val parents = live.associateBy { it.recipeId }
        return live.mapNotNull { r ->
            val parent = r.variantOf?.let(parents::get)
            if (r.variantOf != null && parent == null) return@mapNotNull null
            val label = Dishes.label(r)
            val (score, kind) = dishScore(
                q,
                names = listOf(label, r.name),
                shortcuts = (parent ?: r).aliases,
                extras = listOfNotNull(r.variantName),
            )
            val covered = TextMatch.tokens(label).toSet().let { words -> q.count { it in words } }
            if (score >= minScore) RecipeHit(r, score, kind, covered) else null
        }
            .sortedWith(
                compareByDescending<RecipeHit> { it.score }
                    .thenByDescending { it.covered }
                    .thenByDescending { it.recipe.active }
                    .thenByDescending { it.recipe.defaultMatch }
                    .thenBy { TextMatch.normalize(Dishes.label(it.recipe)) },
            )
            .take(limit)
    }

    /**
     * Doute entre plusieurs produits : le meilleur n'est pas nettement devant le suivant
     * (moins de 10 points). Pas de doute si lui seul correspond exactement à un nom ou à un
     * raccourci (95 et plus), ni s'il est seul à être le produit « par défaut » d'un nom donné.
     */
    fun isAmbiguous(hits: List<ProductHit>): Boolean {
        if (hits.size < 2) return false
        val (first, second) = hits
        if (first.score - second.score >= 10) return false
        if (first.score >= 95 && second.score < 95) return false
        return !(first.product.defaultMatch && !second.product.defaultMatch)
    }

    /** Produits actifs (non supprimés) dont la fiche n'est pas redirigée vers une autre fiche valide. */
    fun visibleProducts(all: List<Product>): List<Product> {
        val live = all.filter { it.isActive }
        val ids = live.map { it.productId }.toSet()
        return live.filter { it.redirectTo == null || it.redirectTo !in ids }
    }

    /** Fiche vers laquelle [product] est redirigée (un seul saut), ou lui-même. */
    fun resolve(product: Product, all: List<Product>): Product {
        val target = product.redirectTo ?: return product
        return all.firstOrNull { it.productId == target && it.isActive } ?: product
    }

    /**
     * Mots d'une phrase dite qui ne désignent rien (« j'ai mangé la salade avec la sauce César ») : la recherche
     * réessaie sans eux, et garde le meilleur des deux scores.
     */
    private val FILLERS = setOf(
        "j", "ai", "a", "as", "mange", "mangee", "manges", "pris", "prise", "bu", "eu", "c", "est", "etait",
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "avec", "et", "en", "sur", "pour",
        "ma", "mon", "mes", "ce", "cet", "cette", "midi", "soir", "matin",
    )

    private fun score(query: List<String>, names: List<String>, shortcuts: List<String>, extras: List<String>): Pair<Int, MatchKind> =
        scoreWords(query, names, shortcuts, extras)

    /**
     * Plats seulement : une phrase dite désigne un plat même entourée de mots de liaison. Un seul mot restant doit être le nom
     * ou un raccourci : « j'ai bu un café » ne désigne pas « Café gourmand ». Les produits gardent [score], car le contexte
     * cherche avec le message entier et un produit voisin serait enregistré en silence.
     */
    private fun dishScore(query: List<String>, names: List<String>, shortcuts: List<String>, extras: List<String>): Pair<Int, MatchKind> {
        val full = scoreWords(query, names, shortcuts, extras)
        val meaningful = query.filterNot { it in FILLERS }
        if (meaningful.isEmpty() || meaningful.size == query.size) return full
        val plain = scoreWords(meaningful, names, shortcuts, extras)
        if (meaningful.size == 1 && plain.first < 90) return full
        return if (plain.first > full.first) plain else full
    }

    private fun scoreWords(query: List<String>, names: List<String>, shortcuts: List<String>, extras: List<String>): Pair<Int, MatchKind> {
        val joined = query.joinToString(" ")
        val shortcutTokens = shortcuts.map { TextMatch.tokens(it) }.filter { it.isNotEmpty() }
        val nameTokens = names.map { TextMatch.tokens(it) }.filter { it.isNotEmpty() }

        if (shortcutTokens.any { it.joinToString(" ") == joined }) return EXACT to MatchKind.Shortcut
        if (nameTokens.any { it.joinToString(" ") == joined }) return 95 to MatchKind.Name
        // « snack barre protéinée » contient le raccourci « barre protéinée » : le plus long raccourci contenu l'emporte.
        if (shortcutTokens.any { containsRun(query, it) }) return 90 to MatchKind.Shortcut
        if (nameTokens.any { containsRun(query, it) }) return 85 to MatchKind.Name

        val fieldTokens = (nameTokens.flatten() + shortcutTokens.flatten() + extras.flatMap { TextMatch.tokens(it) }).toSet()
        val present = query.count { it in fieldTokens }
        if (present == query.size) {
            val extra = (fieldTokens.size - present).coerceAtMost(10)
            return (80 - extra) to MatchKind.Words
        }
        val loose = query.count { token -> token in fieldTokens || (token.length >= 3 && fieldTokens.any { it.startsWith(token) }) }
        val fraction = loose.toDouble() / query.size
        if (loose > 0 && fraction >= 0.5) return (20 + (fraction * 30).toInt()) to MatchKind.Partial
        return 0 to MatchKind.Partial
    }

    /** [run] apparaît en entier, mots contigus et dans l'ordre, dans [query]. */
    private fun containsRun(query: List<String>, run: List<String>): Boolean {
        if (run.size > query.size) return false
        for (start in 0..query.size - run.size) {
            if (run.indices.all { query[start + it] == run[it] }) return true
        }
        return false
    }
}
