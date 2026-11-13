package app.trackly.assistant.tools

import app.trackly.core.action.NewProduct
import app.trackly.core.model.Confidence
import app.trackly.core.model.FoodSource
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.Units
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * Arguments partagés des outils « produits » : fiche d'un produit à mémoriser, et sa
 * présentation au modèle. Les valeurs d'une fiche sont celles de l'étiquette ou de la source,
 * pour une base (100 g…) et/ou une portion ; les kcal d'une consommation, elles, sont
 * calculées par l'app.
 */

private val SOURCE_TYPES = listOf("label", "manufacturer", "web", "database", "estimate")

/** Schéma d'une fiche de produit à mémoriser. */
internal fun newProductSchema(description: String, nullable: Boolean = false) = Schema.obj(
    "name" to Schema.string("Nom complet du produit tel qu'on le reconnaît (« Sandwich grand thon baguette », « Barre protéinée chocolat »)."),
    "brand" to Schema.string("Marque, ou null.", nullable = true),
    "variant" to Schema.string("Variante (parfum, taille), ou null.", nullable = true),
    "per_base" to Schema.obj(
        "quantity" to Schema.number("Quantité de la base : 100 pour « pour 100 g », 1 pour « par pièce ».", minimum = 0.0),
        "unit" to Schema.string("Unité de la base : g, ml, pièce ou portion."),
        "kcal" to Schema.number("Kilocalories pour la base.", minimum = 0.0),
        "protein_g" to Schema.number("Protéines pour la base ; null si inconnu.", nullable = true, minimum = 0.0),
        "carbs_g" to Schema.number("Glucides pour la base ; null si inconnu.", nullable = true, minimum = 0.0),
        "fat_g" to Schema.number("Lipides pour la base ; null si inconnu.", nullable = true, minimum = 0.0),
        "fiber_g" to Schema.number("Fibres pour la base ; null si inconnu.", nullable = true, minimum = 0.0),
        description = "Valeurs de l'étiquette pour une base (typiquement 100 g). null si seule la portion est connue.",
        nullable = true,
    ),
    "per_portion" to Schema.obj(
        "label" to Schema.string("Nom de la portion (« 1 barre », « 1 sandwich »)."),
        "weight" to Schema.number("Poids ou volume d'une portion, ou null.", nullable = true, minimum = 0.0),
        "weight_unit" to Schema.string("Unité de `weight` (g ou ml), ou null.", nullable = true),
        "kcal" to Schema.number("Kilocalories pour une portion.", minimum = 0.0),
        "protein_g" to Schema.number("Protéines pour une portion ; null si inconnu.", nullable = true, minimum = 0.0),
        "carbs_g" to Schema.number("Glucides pour une portion ; null si inconnu.", nullable = true, minimum = 0.0),
        "fat_g" to Schema.number("Lipides pour une portion ; null si inconnu.", nullable = true, minimum = 0.0),
        "fiber_g" to Schema.number("Fibres pour une portion ; null si inconnu.", nullable = true, minimum = 0.0),
        description = "Valeurs pour une portion, si l'étiquette ou la source les donne. Sinon null.",
        nullable = true,
    ),
    "shortcuts" to Schema.array(
        Schema.string("Un raccourci que l'utilisateur emploie pour ce produit (« barre choco »)."),
        "Raccourcis donnés par l'utilisateur ; liste vide sinon.",
    ),
    "source_type" to Schema.string("Origine des valeurs.", enum = SOURCE_TYPES),
    "source_description" to Schema.string("Détail court de l'origine (« étiquette photographiée », « site du fabricant »)."),
    "confidence" to Schema.string("Confiance dans les valeurs.", nullable = true, enum = listOf("high", "medium", "low")),
    description = description,
    nullable = nullable,
)

@Serializable
internal data class BaseArgs(
    val quantity: Double,
    val unit: String,
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
)

@Serializable
internal data class PortionArgs(
    val label: String,
    val weight: Double? = null,
    @SerialName("weight_unit") val weightUnit: String? = null,
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
)

@Serializable
internal data class NewProductArgs(
    val name: String,
    val brand: String? = null,
    val variant: String? = null,
    @SerialName("per_base") val perBase: BaseArgs? = null,
    @SerialName("per_portion") val perPortion: PortionArgs? = null,
    val shortcuts: List<String> = emptyList(),
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_description") val sourceDescription: String? = null,
    val confidence: String? = null,
) {
    /** Lève [IllegalArgumentException] (message en français) si la fiche est incomplète ou invalide. */
    fun toNewProduct(kind: ProductKind = ProductKind.Occasional): NewProduct = NewProduct(
        name = name.trim(),
        brand = brand,
        variant = variant,
        nutrients = perBase?.let { Nutrients(it.kcal, it.proteinG, it.carbsG, it.fatG, it.fiberG) } ?: Nutrients(),
        basis = perBase?.let { NutritionBasis(it.quantity, Units.canonical(it.unit)) },
        portion = perPortion?.let { p ->
            ProductPortion(
                label = p.label.trim(),
                weight = p.weight?.let { w -> Quantity(w, Units.canonical(p.weightUnit ?: Units.GRAM)) },
                nutrients = Nutrients(p.kcal, p.proteinG, p.carbsG, p.fatG, p.fiberG),
            )
        },
        shortcuts = shortcuts,
        source = sourceType?.let { FoodSource(it, sourceDescription?.takeIf { d -> d.isNotBlank() } ?: it) },
        confidence = confidence?.let(Confidence::fromCode),
        kind = kind,
    )
}

/** Fiche d'un produit présentée au modèle (recherche, mémorisation). */
internal fun Product.toJson(extra: JsonObject.() -> Map<String, kotlinx.serialization.json.JsonElement> = { emptyMap() }): JsonObject {
    val product = this
    val base = buildJsonObject {
        put("product_id", product.productId)
        put("name", product.name)
        put("brand", product.brand)
        put("kind", if (product.kind == ProductKind.Recurring) "recurrent" else "ponctuel")
        put("paused", !product.active)
        putJsonArray("shortcuts") { product.aliases.forEach { add(JsonPrimitive(it)) } }
        product.basis?.takeIf { product.nutrients.kcal != null }?.let { b ->
            put("per_base", buildJsonObject {
                put("quantity", b.value)
                put("unit", b.unit)
                put("kcal", product.nutrients.kcal)
                put("protein_g", product.nutrients.proteinG)
                put("carbs_g", product.nutrients.carbsG)
                put("fat_g", product.nutrients.fatG)
            })
        }
        product.portion?.let { p ->
            put("per_portion", buildJsonObject {
                put("label", p.label)
                put("weight", p.weight?.value)
                put("weight_unit", p.weight?.unit)
                put("kcal", p.nutrients.kcal)
                put("protein_g", p.nutrients.proteinG)
                put("carbs_g", p.nutrients.carbsG)
                put("fat_g", p.nutrients.fatG)
            })
        }
        put("units", app.trackly.core.model.ProductServing.units(product))
    }
    return JsonObject(base + base.extra())
}
