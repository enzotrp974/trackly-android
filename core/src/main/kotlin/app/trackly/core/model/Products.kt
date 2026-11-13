package app.trackly.core.model

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Cycle de vie d'un produit. Le passage de ponctuel à récurrent est une nouvelle
 * version de la fiche : le produit garde son identifiant, donc ses consommations restent liées.
 */
enum class ProductKind(val code: String) {
    /** Consommé une fois : fiche gardée à part, sans nom choisi par l'utilisateur. */
    Occasional("occasional"),

    /** Nommé par l'utilisateur : reconnu directement lors des déclarations suivantes. */
    Recurring("recurring"),
    ;

    companion object {
        fun fromCode(code: String): ProductKind =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Type de produit inconnu : $code")
    }
}

/**
 * Valeurs d'une portion usuelle (« 1 barre »). [weight] est le poids ou volume de la portion
 * s'il est connu ; il permet de passer d'une quantité en grammes à un nombre de portions,
 * seulement lorsque les deux unités sont les mêmes.
 */
data class ProductPortion(
    val label: String,
    val weight: Quantity? = null,
    val nutrients: Nutrients,
) {
    init {
        require(label.isNotBlank()) { "Nom de portion vide" }
    }
}

/** Unités reconnues pour une quantité mangée. Aucune conversion entre unités n'est implicite. */
object Units {
    const val GRAM = "g"
    const val MILLILITRE = "ml"
    const val PIECE = "pièce"
    const val PORTION = "portion"

    private val synonyms = mapOf(
        "g" to GRAM, "gr" to GRAM, "gramme" to GRAM, "grammes" to GRAM,
        "ml" to MILLILITRE, "millilitre" to MILLILITRE, "millilitres" to MILLILITRE,
        "piece" to PIECE, "pieces" to PIECE, "unite" to PIECE, "unites" to PIECE,
        "portion" to PORTION, "portions" to PORTION,
    )

    /** Forme canonique d'une unité, pour comparer « portions » et « portion », « pièces » et « pièce ». */
    fun canonical(unit: String): String {
        val plain = TextMatch.normalize(unit)
        return synonyms[plain] ?: plain
    }

    fun same(a: String, b: String) = canonical(a) == canonical(b)
}

/** Normalisation des textes pour la recherche : minuscules, sans accents, mots séparés par une espace. */
object TextMatch {
    private val combining = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(combining, "")
            .replace('œ', 'o')
            .replace(separators, " ")
            .trim()

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }
}

/**
 * Valeurs d'une consommation **calculées par le code** à partir de la fiche du produit et de
 * la quantité mangée : le modèle ne fournit jamais les kcal ni les macros d'un produit connu.
 */
object ProductServing {
    /** Nombre de décimales conservées (0,1 g / 0,1 kcal suffit pour un journal). */
    private fun round(value: Double) = (value * 10).roundToLong() / 10.0

    /**
     * Valeurs pour [amount]. Lève [IllegalArgumentException] (message en français, destiné
     * au modèle) si la quantité ne s'exprime pas dans une unité de la fiche, ou si les kcal
     * de la fiche sont inconnues.
     */
    fun nutrition(product: Product, amount: Quantity): Nutrition {
        val unit = Units.canonical(amount.unit)
        val basis = product.basis
        val portion = product.portion
        val scaled: Nutrients = when {
            portion != null && unit == Units.PORTION -> portion.nutrients.times(amount.value)
            basis != null && unit == Units.canonical(basis.unit) -> product.nutrients.times(amount.value / basis.value)
            portion?.weight != null && unit == Units.canonical(portion.weight.unit) ->
                portion.nutrients.times(amount.value / portion.weight.value)
            else -> throw IllegalArgumentException(
                "« ${product.name} » n'a pas de valeurs pour une quantité en ${amount.unit}. Unités possibles : ${units(product)}.",
            )
        }
        val kcal = scaled.kcal ?: throw IllegalArgumentException("Les kcal de « ${product.name} » sont inconnues : la fiche est incomplète.")
        return Nutrition(round(kcal), scaled.proteinG?.let(::round), scaled.carbsG?.let(::round), scaled.fatG?.let(::round), scaled.fiberG?.let(::round))
    }

    /** Aliment d'une consommation liée à [product] (le produit est mémorisé dans `productId`). */
    fun food(product: Product, amount: Quantity, assumptions: List<String> = emptyList()): Food = Food(
        label = product.name,
        nutrition = nutrition(product, amount),
        quantity = amount,
        assumptions = assumptions,
        productId = product.productId,
        source = product.source,
        confidence = product.confidence,
    )

    /** Unités acceptées pour ce produit, en texte (« g, portion »). */
    fun units(product: Product): String = listOfNotNull(
        product.basis?.takeIf { product.nutrients.kcal != null }?.unit,
        Units.PORTION.takeIf { product.portion != null },
        product.portion?.weight?.unit,
    ).map(Units::canonical).distinct().joinToString(", ").ifEmpty { "aucune (base inconnue)" }

    private fun Nutrients.times(factor: Double) = Nutrients(
        kcal?.times(factor), proteinG?.times(factor), carbsG?.times(factor), fatG?.times(factor), fiberG?.times(factor),
    )
}
