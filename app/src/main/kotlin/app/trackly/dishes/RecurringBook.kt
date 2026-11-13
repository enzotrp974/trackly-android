package app.trackly.dishes

import app.trackly.core.suggestion.FoodMatcher
import app.trackly.core.model.AgentMemory
import app.trackly.core.catalog.CatalogSearch
import app.trackly.assistant.context.PreferencesContext
import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Consumption
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.TextMatch
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Combien de fois une fiche a été mangée récemment, et quand pour la dernière fois. */
data class Usage(val count: Int, val last: Instant, val lastDay: LocalDate)

/** Les ordres de la liste « Mes aliments ». */
enum class FoodSort(val label: String) {
    Frequent("Les plus mangés"),
    Recent("Récents"),
    Alphabetical("A-Z"),
}

/** Un groupe de la liste ; sans titre quand la liste n'est pas découpée. */
data class FoodSection(val key: String, val title: String?, val items: List<RecurringItem>)

/** Une ligne de la liste « Mes aliments » : un plat (avec ses variantes) ou un aliment récurrent. */
sealed interface RecurringItem {
    val id: String
    val name: String
    val paused: Boolean

    data class OfDish(val dish: Dish) : RecurringItem {
        override val id get() = dish.base.recipeId
        override val name get() = dish.name
        override val paused get() = dish.isPaused
    }

    data class OfProduct(val product: Product) : RecurringItem {
        override val id get() = product.productId
        override val name get() = ProductsBook.title(product)
        override val paused get() = false
    }
}

/** « Mes aliments » : lecture seule des plats, aliments et préférences ; toute modification passe par le moteur. */
object RecurringBook {
    /** Jours de consommations lus pour classer la liste. */
    const val USAGE_DAYS = 120L

    /** Type de préférence des mots corrigés à la dictée (déclencheur = mot entendu, règle = vrai mot). */
    const val TERM_CORRECTION = "term_correction"

    /** Produits mémorisés depuis moins de ce nombre de jours : « Nouveaux » tant qu'ils n'ont pas été mangés. */
    const val FRESH_DAYS = 30L

    /** À partir de ce nombre de repas sur [USAGE_DAYS] jours, un aliment est mangé « souvent ». */
    const val OFTEN_USES = 5

    /**
     * Utilisation par plat (identifiant du plat, variantes comprises) et par produit. Une consommation d'une fiche
     * inactive compte pour la fiche active qui la remplace ([Product.redirectTo], sinon même nom et même marque).
     */
    fun usage(dishes: List<Dish>, consumptions: List<Consumption>, products: List<Product> = emptyList()): Map<String, Usage> {
        val matcher = FoodMatcher(products, dishes, replacements(products))
        val keys = consumptions.filter { it.isActive }.flatMap { c -> matcher.ids(c).map { it to c } }
        return keys.groupBy({ it.first }, { it.second }).mapValues { (_, eaten) ->
            Usage(eaten.size, eaten.maxOf { it.consumedAt }, eaten.maxOf { it.day })
        }
    }

    /** Fiche inactive → fiche active qui la remplace (redirection, sinon un seul homonyme actif de même marque). */
    fun replacements(products: List<Product>): Map<String, String> {
        fun live(p: Product) = p.isActive && p.active && p.redirectTo == null
        fun same(p: Product) = TextMatch.normalize(p.name) to p.brand?.let(TextMatch::normalize)
        val alive = products.filter(::live)
        val ids = alive.map { it.productId }.toSet()
        val byName = alive.groupBy(::same)
        return products.filterNot(::live).mapNotNull { p ->
            val target = p.redirectTo?.takeIf { it in ids } ?: byName[same(p)]?.singleOrNull()?.productId
            target?.takeIf { it != p.productId }?.let { p.productId to it }
        }.toMap()
    }

    /** Tout ce que l'assistant connaît : les plats et les fiches visibles, sans les anciennes remplacées par une autre. */
    fun all(dishes: List<Dish>, products: List<Product>): List<RecurringItem> {
        val replaced = replacements(products).keys
        return dishes.map { RecurringItem.OfDish(it) } +
            CatalogSearch.visibleProducts(products).filter { it.productId !in replaced }.map { RecurringItem.OfProduct(it) }
    }

    /** [items] dans l'ordre demandé. Rien n'est écrit : c'est un classement d'affichage. */
    fun sorted(items: List<RecurringItem>, usage: Map<String, Usage>, sort: FoodSort): List<RecurringItem> {
        val byName = compareBy<RecurringItem> { TextMatch.normalize(it.name) }
        val paused = compareBy<RecurringItem> { it.paused }
        val byLast = compareByDescending<RecurringItem> { usage[it.id]?.lastDay ?: LocalDate.MIN }
            .thenByDescending { usage[it.id]?.last ?: Instant.MIN }
        val byCount = compareByDescending<RecurringItem> { usage[it.id]?.count ?: 0 }
        return items.sortedWith(
            when (sort) {
                FoodSort.Frequent -> paused.then(byCount).then(byLast).then(byName)
                FoodSort.Recent -> paused.then(byLast).then(byName)
                FoodSort.Alphabetical -> byName
            },
        )
    }

    /**
     * La liste découpée selon l'ordre choisi. Un aliment tout juste mémorisé et pas encore mangé va dans « Nouveaux »,
     * et les plats en pause ferment la liste. En A-Z, tout reste dans une seule liste.
     */
    fun sections(items: List<RecurringItem>, usage: Map<String, Usage>, fresh: Set<String>, sort: FoodSort, today: LocalDate): List<FoodSection> {
        val ordered = sorted(items, usage, sort)
        if (sort == FoodSort.Alphabetical) return listOf(FoodSection("tout", null, ordered)).filter { it.items.isNotEmpty() }
        val groups = ordered.groupBy { item ->
            val eaten = usage[item.id]
            when {
                item.paused -> PAUSED
                eaten == null -> if (item.id in fresh) NEW else NOT_RECENT
                sort == FoodSort.Frequent -> when {
                    eaten.count >= OFTEN_USES -> "SOUVENT"
                    eaten.count >= 2 -> "PARFOIS"
                    else -> "UNE FOIS"
                }
                else -> when (ChronoUnit.DAYS.between(eaten.lastDay, today)) {
                    in Long.MIN_VALUE..6L -> "CES 7 DERNIERS JOURS"
                    in 7L..29L -> "CES 30 DERNIERS JOURS"
                    else -> "PLUS ANCIEN"
                }
            }
        }
        val order = listOf(NEW, "SOUVENT", "PARFOIS", "UNE FOIS", "CES 7 DERNIERS JOURS", "CES 30 DERNIERS JOURS", "PLUS ANCIEN", NOT_RECENT, PAUSED)
        return order.mapNotNull { title -> groups[title]?.let { FoodSection(title, title, it) } }
    }

    /** Dernier repas, en peu de mots : « aujourd'hui », « hier », « il y a 3 j », « il y a 2 sem. », « il y a 3 mois ». */
    fun lastEaten(day: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0 -> "aujourd’hui"
            days == 1L -> "hier"
            days < 7 -> "il y a $days\u00A0j"
            days < 60 -> "il y a ${days / 7}\u00A0sem."
            else -> "il y a ${days / 30}\u00A0mois"
        }
    }

    private const val NEW = "NOUVEAUX"
    private const val NOT_RECENT = "PAS MANGÉ RÉCEMMENT"
    private const val PAUSED = "EN PAUSE"

    /** Recherche simple : tous les mots se retrouvent dans le nom, une variante ou un raccourci. */
    fun filter(items: List<RecurringItem>, query: String): List<RecurringItem> {
        val words = TextMatch.tokens(query)
        if (words.isEmpty()) return items
        return items.filter { item ->
            val text = when (item) {
                is RecurringItem.OfDish -> listOf(item.dish.name) + item.dish.versions.mapNotNull { it.variantName } + item.dish.base.aliases
                is RecurringItem.OfProduct -> listOf(item.name, item.product.name) + item.product.aliases
            }.joinToString(" ").let(TextMatch::normalize)
            words.all { text.contains(it) }
        }
    }

    /** Ligne sous le nom, courte pour rester sur une ligne : ses valeurs et ses protéines ; le reste est dans sa fiche. */
    fun rowDetail(item: RecurringItem): String {
        val protein = (item as? RecurringItem.OfProduct)?.let { ProductsBook.protein(it.product) }
        return listOfNotNull(detail(item), protein).joinToString(" · ")
    }

    /** Ligne utile sous le nom : « sauce B · 193 kcal la portion · 2 variantes », « 220 kcal par 1 barre ». */
    fun detail(item: RecurringItem): String = when (item) {
        is RecurringItem.OfProduct -> ProductsBook.summary(item.product)
        is RecurringItem.OfDish -> {
            val dish = item.dish
            val chosen = dish.defaultVersion
            listOfNotNull(
                chosen.variantName?.takeIf { dish.hasVariants },
                RecipeServing.perPortion(chosen)?.let { "${it.kcal.roundToInt()} kcal la portion" } ?: "à peser",
                if (dish.hasVariants) "${dish.versions.size} variantes" else null,
            ).joinToString(" · ")
        }
    }

    /** Préférences actives hors mots corrigés, groupées par type lisible (« cuisson », « portion »…). */
    fun preferenceGroups(preferences: List<Preference>): List<Pair<String, List<Preference>>> =
        preferences.filter { it.kind != TERM_CORRECTION && it.kind != AgentMemory.KIND }
            .groupBy { PreferencesContext.kindLabel(it.kind) }
            .toList()
            .sortedBy { TextMatch.normalize(it.first) }

    /** Mots corrigés actifs, par mot entendu. */
    fun termCorrections(preferences: List<Preference>): List<Preference> =
        preferences.filter { it.kind == TERM_CORRECTION }.sortedBy { TextMatch.normalize(it.trigger) }
}
