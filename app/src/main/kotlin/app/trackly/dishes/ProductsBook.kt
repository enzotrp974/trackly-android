package app.trackly.dishes

import app.trackly.core.catalog.CatalogSearch
import app.trackly.day.QuantityText
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.TextMatch
import kotlin.math.roundToInt

/** « Mes produits » : lecture seule des fiches ; toute modification passe par les actions du moteur. */
object ProductsBook {
    /** Combien de produits ponctuels récents sont montrés. */
    const val RECENT_LIMIT = 10

    /** Produits récurrents (nommés par l'utilisateur) visibles, par ordre alphabétique. */
    fun recurring(all: Collection<Product>): List<Product> =
        CatalogSearch.visibleProducts(all.toList()).filter { it.kind == ProductKind.Recurring }.sortedBy { TextMatch.normalize(it.name) }

    /** Produits ponctuels visibles, les plus récents d'abord. */
    fun recentOccasional(all: Collection<Product>, limit: Int = RECENT_LIMIT): List<Product> =
        CatalogSearch.visibleProducts(all.toList()).filter { it.kind == ProductKind.Occasional }.sortedByDescending { it.recordedAt }.take(limit)

    /** Une ligne de valeurs : « 220 kcal par 1 barre », sinon « 400 kcal pour 100 g », sinon rien d'inventé. */
    fun summary(product: Product): String {
        product.portion?.nutrients?.kcal?.let { return "${it.roundToInt()} kcal par ${QuantityText.agree(product.portion!!.label)}" }
        val kcal = product.nutrients.kcal ?: return "Valeurs non renseignées"
        val basis = product.basis ?: return "${kcal.roundToInt()} kcal"
        val value = if (basis.value == basis.value.roundToInt().toDouble()) basis.value.roundToInt().toString() else basis.value.toString().replace('.', ',')
        return "${kcal.roundToInt()} kcal pour ${QuantityText.format(basis.value, basis.unit, value)}"
    }

    /** Protéines sur la même base que [summary] : « P 20 g », `null` si inconnues. */
    fun protein(product: Product): String? = base(product).proteinG?.let { "P\u00A0${it.roundToInt()}\u00A0g" }

    private fun base(product: Product) = product.portion?.nutrients?.takeIf { it.kcal != null } ?: product.nutrients

    /** Macros connues sur la même base que [summary] : « P 20 g · G 12 g · L 8 g », vide si aucune. */
    fun macros(product: Product): String {
        val n = base(product)
        return listOfNotNull(
            protein(product),
            n.carbsG?.let { "G\u00A0${it.roundToInt()}\u00A0g" },
            n.fatG?.let { "L\u00A0${it.roundToInt()}\u00A0g" },
        ).joinToString(" · ")
    }

    /**
     * Nom propre suivi de la marque, comme on le lit dans une liste : « Boisson chocolat (Marque) », mais pas
     * « Marque Boisson chocolat (Marque) ». Le vrai nom ne change pas (voir [cleanName]).
     */
    fun title(product: Product): String {
        val name = cleanName(product.name)
        val brand = product.brand?.let(::cleanName)?.takeIf { it.isNotBlank() && !named(name, it) }
        return name + (brand?.let { " ($it)" } ?: "")
    }

    /** La marque (ou chacune de ses parties « A / B ») est déjà dans le nom. */
    private fun named(name: String, brand: String): Boolean {
        val plain = TextMatch.normalize(name)
        return brand.split('/').map(TextMatch::normalize).filter { it.isNotBlank() }.all { plain.contains(it) }
    }

    private const val DASH = """\s*[—–]\s*"""

    /** Traces de l'ancien suivi, retirées à l'affichage : chaque motif une seule fois, dans cet ordre. */
    private val noise = listOf(
        Regex("""$DASH(?:nom|garniture) partiellement masquée?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*,\s*identité partielle\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*(?:[—–]|,|\()\s*identifiée? par correspondance secondaire FDDB\s*\)?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""$DASH\d+(?:[.,]\d+)?\s*kcal\s*/\s*100\s*(?:g|ml)\b[^—–]*?(?=\s+[—–]|\s*$)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(EAN\s*\d{8,14}\)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:Petit-déjeuner|Déjeuner|Dîner|Snack|Boisson)$DASH""", RegexOption.IGNORE_CASE),
    )

    /**
     * Nom d'un produit sans les traces de l'ancien suivi (« Déjeuner — », « (EAN …) », « — nom partiellement
     * masqué »…), pour l'affichage seulement. Les tailles utiles (« — 245 g ») restent. Rien de lisible : nom d'origine.
     */
    fun cleanName(name: String): String {
        val cleaned = noise.fold(name) { text, rule -> rule.replaceFirst(text, "") }.trim()
        return cleaned.ifBlank { name.trim() }
    }
}
