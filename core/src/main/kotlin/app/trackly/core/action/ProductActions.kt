package app.trackly.core.action

import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Confidence
import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.FoodSource
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Origin
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.ProductServing
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.core.model.TextMatch
import app.trackly.core.model.Units
import app.trackly.core.time.TrackingDay
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Produits. Un produit consommé est mémorisé avec ses valeurs ; ponctuel au départ, il
 * devient récurrent quand l'utilisateur le nomme. Chaque action écrit des versions de la fiche
 * (`HistoryKinds.Products`) : l'annulation et l'historique fonctionnent sans code propre.
 */

/** Produit à mémoriser, tel que connu au moment de la consommation ou de la demande. */
data class NewProduct(
    val name: String,
    val brand: String? = null,
    val variant: String? = null,
    /** Valeurs pour la base [basis] (« pour 100 g »). */
    val nutrients: Nutrients = Nutrients(),
    val basis: NutritionBasis? = null,
    val portion: ProductPortion? = null,
    /** Raccourcis connus dès la création (« barre choco »). */
    val shortcuts: List<String> = emptyList(),
    val source: FoodSource? = null,
    val confidence: Confidence? = null,
    /** Identifiant imposé (tests, reprise) ; sinon un identifiant neuf. */
    val productId: String? = null,
    val kind: ProductKind = ProductKind.Occasional,
) {
    init {
        require(name.isNotBlank()) { "Nom de produit vide" }
        require(name.length <= MAX_NAME) { "Nom de produit trop long" }
        require(nutrients.kcal == null || basis != null) { "Les valeurs d'un produit exigent leur base (100 g, 100 ml, une pièce…)" }
        require((basis != null && nutrients.kcal != null) || portion?.nutrients?.kcal != null) {
            "Il faut au moins les kcal pour une base (100 g…) ou pour une portion"
        }
    }

    fun toProduct(id: String) = Product(
        productId = id,
        version = 1,
        state = RecordState.Active,
        name = name.trim(),
        brand = brand?.trim()?.ifEmpty { null },
        variant = variant?.trim()?.ifEmpty { null },
        aliases = shortcuts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(TextMatch::normalize),
        nutrients = nutrients,
        basis = basis,
        source = source,
        confidence = confidence,
        kind = kind,
        portion = portion,
        recordedAt = Instant.EPOCH,
        actionId = null,
    )

    companion object {
        const val MAX_NAME = 120
    }
}

/** Mémorise un produit (ponctuel par défaut) sans le consommer. */
data class MemorizeProduct(val product: NewProduct) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val created = ProductWrites.create(scope, product, reuseExisting = false)
        return "Produit mémorisé : " + created.name
    }

    companion object {
        const val TYPE = "product.memorize"
    }
}

/**
 * Corrige les valeurs d'un produit connu (« la barre c'est 12 g de protéines ») : nouvelle version de la même
 * fiche avec la base, la portion, la source et la confiance de [values]. Nom, raccourcis et nature restent.
 * Les consommations déjà enregistrées gardent leurs valeurs.
 */
data class CorrectProduct(val productId: String, val values: NewProduct) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = ProductWrites.active(scope, productId)
        val next = current.copy(
            nutrients = values.nutrients,
            basis = values.basis,
            portion = values.portion,
            source = values.source ?: current.source,
            confidence = values.confidence ?: current.confidence,
        )
        if (next == current) scope.reject(Rejection.NoChange)
        ProductWrites.write(scope, current, next)
        return "Produit modifié : ${current.name}"
    }

    companion object {
        const val TYPE = "product.correct"
    }
}

/**
 * Rend un produit ponctuel récurrent, sous le nom choisi par l'utilisateur. Le produit garde son
 * identifiant (ses consommations restent liées) et son ancien nom devient un raccourci.
 */
data class MakeProductRecurring(val productId: String, val name: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = ProductWrites.active(scope, productId)
        if (current.kind == ProductKind.Recurring) {
            scope.reject(Rejection.Invalid("« ${current.name} » est déjà un produit récurrent : utilise le renommage."))
        }
        val next = ProductWrites.renamed(scope, current, name).copy(kind = ProductKind.Recurring)
        ProductWrites.write(scope, current, next)
        return "Produit récurrent : ${next.name}"
    }

    companion object {
        const val TYPE = "product.make_recurring"
    }
}

/** Renomme un produit ; l'ancien nom reste un raccourci (rien n'est perdu, la reconnaissance continue). */
data class RenameProduct(val productId: String, val name: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = ProductWrites.active(scope, productId)
        val next = ProductWrites.renamed(scope, current, name)
        if (next == current) scope.reject(Rejection.NoChange)
        ProductWrites.write(scope, current, next)
        return "Produit renommé : ${current.name} → ${next.name}"
    }

    companion object {
        const val TYPE = "product.rename"
    }
}

/** Ajoute un raccourci (« barre choco ») qui désigne directement ce produit. */
data class AddProductShortcut(val productId: String, val shortcut: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = ProductWrites.active(scope, productId)
        val clean = shortcut.trim()
        val plain = TextMatch.normalize(clean)
        if (plain.isEmpty()) scope.reject(Rejection.Invalid("Raccourci vide"))
        if (current.aliases.any { TextMatch.normalize(it) == plain }) scope.reject(Rejection.NoChange)
        ProductWrites.otherHolder(scope, current, plain)?.let {
            scope.reject(Rejection.Invalid("Le raccourci « $clean » désigne déjà « ${it.name} » (${it.productId})."))
        }
        ProductWrites.write(scope, current, current.copy(aliases = current.aliases + clean))
        return "Raccourci « $clean » pour ${current.name}"
    }

    companion object {
        const val TYPE = "product.add_shortcut"
    }
}

/** Produit consommé, tel que déclaré : connu par son identifiant, ou à mémoriser. */
data class ProductUse(
    val productId: String? = null,
    val newProduct: NewProduct? = null,
    /** Quantité mangée : les kcal et macros en sont calculés par le code. */
    val amount: Quantity,
    val assumptions: List<String> = emptyList(),
) {
    init {
        require((productId == null) != (newProduct == null)) { "Un produit connu OU un produit à mémoriser" }
    }
}

/** Élément d'une déclaration : un aliment estimé librement, un produit ou un plat avec sa quantité. */
sealed interface DeclaredItem {
    data class Free(val consumption: NewConsumption) : DeclaredItem

    data class OfProduct(
        val use: ProductUse,
        val moment: Moment,
        val consumedAt: Instant? = null,
        val explicitDay: LocalDate? = null,
        val entryId: EntryId? = null,
        /** Plat récurrent reconnu (le produit alimente `Food.recipeId` quand il s'agit d'un plat). */
        val recipeId: String? = null,
    ) : DeclaredItem

    /**
     * Plat récurrent mangé : kcal et macros **calculées par le code** depuis les ingrédients et
     * la quantité (portions, ou grammes de plat). [recipeId] peut être le plat (sa variante par défaut
     * est alors retenue, et dite) ou une variante précise.
     */
    data class OfRecipe(
        val recipeId: String,
        val amount: Quantity = Quantity(1.0, Units.PORTION),
        val moment: Moment,
        val assumptions: List<String> = emptyList(),
        val consumedAt: Instant? = null,
        val explicitDay: LocalDate? = null,
        val entryId: EntryId? = null,
        /** Faux : l'utilisateur a désigné cette version précise (même la version de base) ; vrai : il a dit le nom du plat seul. */
        val useDefault: Boolean = true,
    ) : DeclaredItem
}

/**
 * Déclaration de consommation avec produits : comme [AddConsumptions] (une seule annulation pour
 * l'ensemble), mais un élément peut être un produit. Un produit absent de la mémoire y est
 * mémorisé comme **produit ponctuel**, sauf s'il est déjà connu (même nom, même marque, même
 * variante : la fiche existante est alors réutilisée). Les valeurs viennent de la fiche et de la
 * quantité, jamais du modèle.
 */
data class DeclareConsumptions(
    val items: List<DeclaredItem>,
    val origin: Origin = Origin.Assistant,
) : Action {
    override val type get() = AddConsumptions.TYPE

    override suspend fun apply(scope: ActionScope): String {
        if (items.isEmpty()) scope.reject(Rejection.Invalid("Aucune consommation à ajouter"))
        val ids = items.mapNotNull {
            when (it) {
                is DeclaredItem.Free -> it.consumption.entryId
                is DeclaredItem.OfProduct -> it.entryId
                is DeclaredItem.OfRecipe -> it.entryId
            }
        }
        if (ids.size != ids.toSet().size) scope.reject(Rejection.Invalid("Identifiant de consommation en double"))
        val labels = mutableListOf<String>()
        for ((position, item) in items.withIndex()) {
            val declared = when (item) {
                is DeclaredItem.Free -> Declared(item.consumption.food, item.consumption.moment, item.consumption.consumedAt, item.consumption.explicitDay, item.consumption.entryId)
                is DeclaredItem.OfProduct -> {
                    val product = when {
                        item.use.productId != null -> ProductWrites.known(scope, item.use.productId)
                        else -> ProductWrites.create(scope, checkNotNull(item.use.newProduct), reuseExisting = true)
                    }
                    val food = try {
                        ProductServing.food(product, item.use.amount, item.use.assumptions).copy(recipeId = item.recipeId)
                    } catch (e: IllegalArgumentException) {
                        scope.reject(Rejection.Invalid(e.message ?: "Quantité invalide"))
                    }
                    Declared(food, item.moment, item.consumedAt, item.explicitDay, item.entryId)
                }
                is DeclaredItem.OfRecipe -> {
                    val choice = Dishes.choose(scope.tx.recipes.allCurrent(), item.recipeId, item.useDefault)
                        ?: scope.reject(Rejection.NotFound("plat ${item.recipeId}"))
                    // Le défaut est dit à l'utilisateur : la carte affiche la variante retenue.
                    val notes = (if (choice.usedDefault) listOf(DEFAULT_NOTE + (choice.recipe.variantName ?: "version de base")) else emptyList()) + item.assumptions
                    val food = try {
                        RecipeServing.food(choice.recipe, item.amount, notes)
                    } catch (e: IllegalArgumentException) {
                        scope.reject(Rejection.Invalid(e.message ?: "Quantité invalide"))
                    }
                    Declared(food, item.moment, item.consumedAt, item.explicitDay, item.entryId)
                }
            }
            val id = declared.entryId ?: EntryId.ordered(scope.id, position)
            if (scope.tx.consumptions.current(id) != null) scope.reject(Rejection.Invalid("La consommation $id existe déjà"))
            val consumedAt = declared.consumedAt ?: scope.now
            ConsumptionKind.writeNextVersion(
                scope,
                current = null,
                content = Consumption(
                    entryId = id,
                    version = 1,
                    state = RecordState.Active,
                    day = TrackingDay.of(consumedAt, declared.explicitDay),
                    consumedAt = consumedAt,
                    moment = declared.moment,
                    food = declared.food,
                    origin = origin,
                    recordedAt = scope.now,
                    actionId = scope.id,
                ),
            )
            labels += "${declared.food.label} (${Math.round(declared.food.nutrition.kcal)} kcal)"
        }
        return "Ajout : " + labels.joinToString(", ")
    }

    private class Declared(val food: Food, val moment: Moment, val consumedAt: Instant?, val explicitDay: LocalDate?, val entryId: EntryId?)
}

/**
 * « Non, c'est l'autre » : relie une consommation à un autre produit, par une nouvelle version
 * complète. Les valeurs sont recalculées depuis le nouveau produit ; la quantité reste celle de
 * la consommation sauf si [amount] en donne une autre (le nouveau produit peut compter en portions).
 */
data class RelinkConsumption(
    val entryId: EntryId,
    val productId: String,
    val amount: Quantity? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = scope.tx.consumptions.current(entryId)
        if (current == null || !current.isActive) scope.reject(Rejection.NotFound("consommation $entryId"))
        val product = ProductWrites.known(scope, productId)
        val quantity = amount ?: current.food.quantity
            ?: scope.reject(Rejection.Invalid("Cette consommation n'a pas de quantité : indique-la."))
        val food = try {
            ProductServing.food(product, quantity, current.food.assumptions).copy(recipeId = null)
        } catch (e: IllegalArgumentException) {
            scope.reject(Rejection.Invalid(e.message ?: "Quantité invalide"))
        }
        val content = current.copy(food = food)
        if (content.sameContentAs(current)) scope.reject(Rejection.NoChange)
        ConsumptionKind.writeNextVersion(scope, current, content)
        return "Produit changé : ${product.name} (${Math.round(food.nutrition.kcal)} kcal)"
    }

    companion object {
        const val TYPE = "consumption.relink"
    }
}

/**
 * « Non, celle avec la sauce B » : relie une consommation de plat à une autre variante (ou à un
 * autre plat), par une nouvelle version complète. Les valeurs sont recalculées depuis les ingrédients
 * de la nouvelle version ; la quantité reste celle de la consommation sauf si [amount] en donne une autre.
 */
data class RelinkConsumptionToDish(
    val entryId: EntryId,
    val recipeId: String,
    val amount: Quantity? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = scope.tx.consumptions.current(entryId)
        if (current == null || !current.isActive) scope.reject(Rejection.NotFound("consommation $entryId"))
        // « Non, celle-ci » désigne une version précise, pas le défaut.
        val choice = Dishes.choose(scope.tx.recipes.allCurrent(), recipeId, useDefault = false)
            ?: scope.reject(Rejection.NotFound("plat $recipeId"))
        // Un plat qui garde sa quantité en portions ou en grammes de plat ; sinon une portion.
        val quantity = amount ?: current.food.quantity?.takeIf { Units.canonical(it.unit) in listOf(Units.PORTION, Units.GRAM) }
            ?: Quantity(1.0, Units.PORTION)
        val food = try {
            RecipeServing.food(choice.recipe, quantity, current.food.assumptions.filterNot { it.startsWith(DEFAULT_NOTE) })
        } catch (e: IllegalArgumentException) {
            scope.reject(Rejection.Invalid(e.message ?: "Quantité invalide"))
        }
        val content = current.copy(food = food)
        if (content.sameContentAs(current)) scope.reject(Rejection.NoChange)
        ConsumptionKind.writeNextVersion(scope, current, content)
        return "Plat changé : ${food.label} (${Math.round(food.nutrition.kcal)} kcal)"
    }

    companion object {
        const val TYPE = "consumption.relink_dish"
    }
}

private const val DEFAULT_NOTE = "Variante par défaut : "

/** Fiches de produits écrites par ces actions dans la transaction d'une action. */
val ActionResult.productChanges: List<RecordChange<Product>>
    get() = changes.filterIsInstance<RecordChange<*>>().filter { it.kind == "product" }.map {
        @Suppress("UNCHECKED_CAST")
        it as RecordChange<Product>
    }

/** Produits nouvellement mémorisés par l'action (première version). */
val ActionResult.memorizedProducts: List<Product> get() = productChanges.filter { it.before == null }.map { it.after }

internal object ProductWrites {
    fun newId() = "prd-" + UUID.randomUUID()

    suspend fun active(scope: ActionScope, productId: String): Product {
        val current = HistoryKinds.Products.versionsIn(scope).current(productId)
        if (current == null || !current.isActive) scope.reject(Rejection.NotFound("produit $productId"))
        return current
    }

    /** Produit connu par son identifiant, redirection comprise (doublon fusionné). */
    suspend fun known(scope: ActionScope, productId: String): Product {
        val product = active(scope, productId)
        return CatalogSearch.resolve(product, scope.tx.products.allCurrent())
    }

    /** Fiche active de même nom, marque et variante, ou `null`. */
    private suspend fun existing(scope: ActionScope, product: NewProduct): Product? {
        val key = identity(product.name, product.brand, product.variant)
        return CatalogSearch.visibleProducts(scope.tx.products.allCurrent())
            .firstOrNull { identity(it.name, it.brand, it.variant) == key }
    }

    suspend fun create(scope: ActionScope, product: NewProduct, reuseExisting: Boolean): Product {
        existing(scope, product)?.let {
            if (reuseExisting) return it
            scope.reject(Rejection.Invalid("« ${it.name} » est déjà mémorisé (${it.productId})."))
        }
        val id = product.productId ?: newId()
        if (HistoryKinds.Products.versionsIn(scope).current(id) != null) scope.reject(Rejection.Invalid("Le produit $id existe déjà"))
        return HistoryKinds.Products.writeNextVersion(scope, null, product.toProduct(id)).after
    }

    suspend fun write(scope: ActionScope, current: Product, next: Product) {
        HistoryKinds.Products.writeNextVersion(scope, current, next)
    }

    /** Autre produit visible dont le nom ou un raccourci est exactement [plain] (déjà normalisé). */
    suspend fun otherHolder(scope: ActionScope, current: Product, plain: String): Product? =
        CatalogSearch.visibleProducts(scope.tx.products.allCurrent()).firstOrNull { other ->
            other.productId != current.productId &&
                (TextMatch.normalize(other.name) == plain || other.aliases.any { TextMatch.normalize(it) == plain })
        }

    /** [current] sous un nouveau nom ; l'ancien devient un raccourci. Refuse un nom déjà pris. */
    suspend fun renamed(scope: ActionScope, current: Product, name: String): Product {
        val clean = name.trim()
        if (clean.isEmpty()) scope.reject(Rejection.Invalid("Nom vide"))
        if (clean.length > NewProduct.MAX_NAME) scope.reject(Rejection.Invalid("Nom trop long"))
        val plain = TextMatch.normalize(clean)
        otherHolder(scope, current, plain)?.let { scope.reject(Rejection.Invalid("Le nom « $clean » désigne déjà « ${it.name} » (${it.productId}).")) }
        if (TextMatch.normalize(current.name) == plain) return current.copy(name = clean)
        val keepOld = current.aliases.none { TextMatch.normalize(it) == TextMatch.normalize(current.name) }
        val aliases = (if (keepOld) current.aliases + current.name else current.aliases).filterNot { TextMatch.normalize(it) == plain }
        return current.copy(name = clean, aliases = aliases)
    }

    private fun identity(name: String, brand: String?, variant: String?) =
        listOf(name, brand.orEmpty(), variant.orEmpty()).joinToString("|") { TextMatch.normalize(it) }
}
