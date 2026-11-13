package app.trackly.assistant.context

import app.trackly.assistant.cards.formatNumber
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.TextMatch
import kotlin.math.roundToInt

/**
 * Produits récurrents connus (identifiant, nom, raccourcis, unités). Les plats ont leur propre élément (`DishListContext`). Liste **stable** :
 * triée par nom, elle ne change que si le catalogue change, donc elle ne casse pas le cache
 * d'entrée d'un message à l'autre. Les valeurs nutritionnelles n'y sont pas : l'app les calcule.
 */
object ProductListContext : ContextProvider {
    override val id = "products"
    override val title = "Produits récurrents"

    private const val MAX_PRODUCTS = 120

    override suspend fun render(env: ContextEnv): String? {
        val products = CatalogSearch.visibleProducts(env.engine.allProducts())
            .filter { it.kind == ProductKind.Recurring }
            .sortedBy { TextMatch.normalize(it.name) }
        if (products.isEmpty()) return null
        return buildString {
            append("Déclare un produit par son product_id : l'app calcule ses valeurs depuis la fiche et la quantité.")
            if (products.isNotEmpty()) {
                append("\nProduits récurrents (").append(products.size).append(") :")
                for (p in products.take(MAX_PRODUCTS)) append("\n- ").append(line(p))
                if (products.size > MAX_PRODUCTS) append("\n- … et ${products.size - MAX_PRODUCTS} autres : utilise search_products.")
            }
        }
    }

    internal fun line(p: Product): String = buildString {
        append(p.productId).append(" · ").append(p.name)
        p.brand?.let { append(" (").append(it).append(')') }
        if (p.aliases.isNotEmpty()) append(" · raccourcis : ").append(p.aliases.joinToString(", "))
        append(" · unités : ").append(app.trackly.core.model.ProductServing.units(p))
        p.portion?.let { portion ->
            append(" · portion : ").append(portion.label)
            portion.weight?.let { append(" (").append(formatNumber(it.value)).append(' ').append(it.unit).append(')') }
        }
        if (!p.active) append(" · en pause")
    }
}

/**
 * Produits qui correspondent au message (avec leurs valeurs, pour répondre à une question) et
 * produits ponctuels récents, jamais nommés (« le sandwich d'hier »). Variable : placé après
 * les éléments stables.
 */
object RelevantProductsContext : ContextProvider {
    override val id = "relevant-products"
    override val title = "Produits liés à ce message"

    private const val MATCHES = 5
    private const val RECENT = 5
    private const val MIN_SCORE = 50

    override suspend fun render(env: ContextEnv): String? {
        val all = env.engine.allProducts()
        val matches = if (env.text.isBlank()) emptyList() else CatalogSearch.products(all, env.text, MATCHES, MIN_SCORE)
        val recent = CatalogSearch.visibleProducts(all)
            .filter { it.kind == ProductKind.Occasional }
            .sortedByDescending { it.recordedAt }
            .take(RECENT)
        if (matches.isEmpty() && recent.isEmpty()) return null
        return buildString {
            if (matches.isNotEmpty()) {
                append("Correspondances avec le message")
                if (CatalogSearch.isAmbiguous(matches)) append(" (plusieurs se valent : doute possible)")
                append(" :")
                for (hit in matches) append("\n- ").append(ProductListContext.line(hit.product)).append(" · ").append(values(hit.product))
            }
            if (recent.isNotEmpty()) {
                if (matches.isNotEmpty()) append("\n")
                append("Produits ponctuels récents (jamais nommés par l'utilisateur) :")
                for (p in recent) {
                    val uses = env.engine.productUses(p.productId).size
                    append("\n- ").append(ProductListContext.line(p)).append(" · mangé ").append(uses).append(" fois").append(" au total")
                }
            }
        }
    }

    private fun values(p: Product): String {
        val parts = listOfNotNull(
            p.basis?.takeIf { p.nutrients.kcal != null }?.let { "pour ${formatNumber(it.value)} ${it.unit} : ${nutrients(p.nutrients)}" },
            p.portion?.let { "pour ${it.label} : ${nutrients(it.nutrients)}" },
        )
        return parts.joinToString(" ; ").ifEmpty { "valeurs inconnues" }
    }

    private fun nutrients(n: Nutrients): String {
        fun g(v: Double?) = v?.let { formatNumber((it * 10).roundToInt() / 10.0) } ?: "?"
        return "${n.kcal?.roundToInt() ?: "?"} kcal, P ${g(n.proteinG)}, G ${g(n.carbsG)}, L ${g(n.fatG)}"
    }
}
