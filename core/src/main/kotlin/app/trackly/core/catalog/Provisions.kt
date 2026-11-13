package app.trackly.core.catalog

import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import app.trackly.core.model.TextMatch

/**
 * Provisions : ce que l'utilisateur dit avoir. Pure lecture et appariement, sans écriture.
 * Une provision n'est jamais une consommation.
 */
object Provisions {
    /** Provisions « j'ai » (versions courantes actives), triées par nom. */
    fun held(all: Collection<Provision>): List<Provision> =
        all.filter { it.isActive }.sortedBy { TextMatch.normalize(it.name) }

    /** Clé de comparaison d'un nom : sans casse ni accents, au singulier (« pommes » = « pomme »). */
    fun identity(name: String): String = tokens(name).joinToString(" ")

    private fun tokens(name: String): List<String> =
        TextMatch.tokens(name).filter { it !in STOP_WORDS }.map { t -> if (t.length > 3 && (t.endsWith("s") || t.endsWith("x"))) t.dropLast(1) else t }

    /** Provision déjà connue (« j'ai » ou « plus ») pour ce nom ou ce produit, ou `null`. */
    fun find(all: Collection<Provision>, name: String, productId: String?): Provision? {
        val id = identity(name)
        return all.firstOrNull { productId != null && it.productId == productId }
            ?: all.firstOrNull { identity(it.name) == id }
    }

    /** L'ingrédient [label] (ou son produit) est couvert par cette provision (« thon » couvre « Thon en boîte »). */
    fun covers(provision: Provision, label: String, productId: String?): Boolean {
        if (productId != null && provision.productId == productId) return true
        val a = tokens(provision.name).toSet()
        val b = tokens(label).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.all { it in b } || b.all { it in a }
    }

    /** Ingrédients de [recipe] que [held] ne couvre pas (noms). */
    fun missing(recipe: Recipe, held: Collection<Provision>): List<String> =
        recipe.ingredients.filter { i -> held.none { covers(it, i.label, i.productId) } }.map { it.label }

    /** Plats actifs (non en pause) qui utilisent l'aliment de [provision] : à signaler quand on n'en a plus. */
    fun dishesUsing(provision: Provision, dishes: Collection<Dish>): List<Dish> =
        dishes.filter { !it.isPaused && it.versions.any { r -> r.ingredients.any { i -> covers(provision, i.label, i.productId) } } }

    private val STOP_WORDS = setOf("de", "du", "des", "la", "le", "les", "un", "une", "en", "au", "aux", "d", "l")
}
