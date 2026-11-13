package app.trackly.core.suggestion

import app.trackly.core.catalog.Dish
import app.trackly.core.model.Consumption
import app.trackly.core.model.Moment
import app.trackly.core.model.Product
import app.trackly.core.model.TextMatch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Retrouve les aliments connus dans un repas : par son lien direct à une fiche et par le texte libre
 * (« salade habituelle, filet de poulet 170 g, oignons crispy » cite trois aliments). Pure lecture.
 */
class FoodMatcher(
    products: Collection<Product>,
    dishes: Collection<Dish>,
    /** Fiche inactive → fiche qui la remplace. */
    private val replaced: Map<String, String> = emptyMap(),
    title: (Product) -> String = { it.name },
) {
    private class Known(val id: String, val name: String, val phrases: List<Set<String>>)

    private val known: List<Known> = buildList {
        for (p in products) {
            if (!p.isActive || !p.active || p.redirectTo != null) continue
            add(Known(p.productId, title(p), (p.aliases + p.name).map(::words).filter { it.isNotEmpty() }))
        }
        for (d in dishes) {
            val names = d.versions.flatMap { it.aliases } + d.base.aliases + d.name
            add(Known(d.base.recipeId, d.name, names.map(::words).filter { it.isNotEmpty() }))
        }
    }
    private val names = known.associate { it.id to it.name }
    private val dishOf = dishes.flatMap { d -> d.versions.map { it.recipeId to d.base.recipeId } }.toMap()

    fun nameOf(id: String): String? = names[id]

    /**
     * Identifiants des fiches présentes dans [consumption]. Un repas relié à une fiche ne compte que pour elle. Sinon
     * on lit son texte, morceau par morceau : « poulet 150 g, riz 200 g » ne cite pas un plat « Riz au poulet ». Une
     * expression contenue dans une plus longue ne compte pas : « sandwich thon œuf » cite le sandwich, pas la boîte de
     * thon ni l'œuf. Ce qui suit « sans » n'est pas mangé.
     */
    fun ids(consumption: Consumption): Set<String> {
        val food = consumption.food
        val direct = listOfNotNull(
            food.recipeId?.let { dishOf[it] ?: it },
            food.productId?.let { replaced[it] ?: it },
        )
        if (direct.isNotEmpty()) return direct.toSet()
        val segments = food.label.split(SEGMENT).map(::eatenWords).filter { it.isNotEmpty() }
        val hits = known.mapNotNull { k ->
            k.phrases.filter { phrase -> segments.any { it.containsAll(phrase) } }.maxByOrNull { it.size }?.let { k.id to it }
        }
        return hits.filter { (_, phrase) -> hits.none { (_, other) -> other.size > phrase.size && other.containsAll(phrase) } }
            .map { it.first }.toSet()
    }

    private companion object {
        val SEGMENT = Regex("[,;+]")

        /** Mots d'un morceau de repas, sans celui qui suit « sans ». */
        fun eatenWords(text: String): Set<String> {
            val tokens = TextMatch.tokens(text.replace("œ", "oe").replace("Œ", "oe"))
            val eaten = tokens.filterIndexed { i, token -> token != "sans" && (i == 0 || tokens[i - 1] != "sans") }
            return words(eaten.joinToString(" "))
        }

        val STOP_WORDS = setOf("de", "du", "des", "la", "le", "les", "un", "une", "en", "au", "aux", "d", "l", "et", "avec", "a")

        /** Mots utiles : sans casse ni accents, au singulier. « œufs » et « oeufs » se valent. */
        fun words(text: String): Set<String> = TextMatch.tokens(text.replace("œ", "oe").replace("Œ", "oe"))
            .filter { it !in STOP_WORDS }
            .map { if (it.length > 3 && (it.endsWith("s") || it.endsWith("x"))) it.dropLast(1) else it }
            .toSet()
    }
}

/** Un aliment et le nombre de jours où il a été mangé à ce moment. */
data class FoodCount(val name: String, val days: Int)

/** Ce qui est mangé d'habitude à un moment de la journée. */
data class MomentHabit(
    val moment: Moment,
    /** Jours où quelque chose a été mangé à ce moment. */
    val days: Int,
    /** Calories moyennes de ce moment, ces jours-là. */
    val averageKcal: Int,
    val usual: List<FoodCount>,
    /** Aliments souvent mangés, mais presque jamais à ce moment. */
    val rare: List<String>,
)

/** Habitudes calculées sur les repas enregistrés : rien n'est stocké, elles suivent le journal. */
object FoodHabits {
    const val WINDOW_DAYS = 90L
    private const val USUAL = 8
    private const val RARE = 5

    /** Jours minimum pour qu'un aliment absent d'un moment vaille la peine d'être signalé. */
    private const val MIN_DAYS_FOR_RARE = 6
    private const val RARE_SHARE = 0.1

    fun of(consumptions: Collection<Consumption>, matcher: FoodMatcher): List<MomentHabit> {
        val eaten = consumptions.filter { it.isActive }
        val seen = HashMap<Pair<Moment, String>, MutableSet<LocalDate>>()
        for (c in eaten) {
            for (id in matcher.ids(c)) seen.getOrPut(c.moment to id) { HashSet() } += c.day
        }
        val totals = seen.entries.groupBy({ it.key.second }, { it.value.size }).mapValues { it.value.sum() }
        return Moment.entries.mapNotNull { moment ->
            val here = eaten.filter { it.moment == moment }
            if (here.isEmpty()) return@mapNotNull null
            val days = here.map { it.day }.toSet().size
            val usual = totals.keys.mapNotNull { id ->
                val count = seen[moment to id]?.size ?: return@mapNotNull null
                matcher.nameOf(id)?.let { FoodCount(it, count) }
            }.sortedWith(compareByDescending<FoodCount> { it.days }.thenBy { it.name }).take(USUAL)
            val rare = totals.filter { (id, total) ->
                total >= MIN_DAYS_FOR_RARE && (seen[moment to id]?.size ?: 0) <= total * RARE_SHARE
            }.entries.sortedByDescending { it.value }.mapNotNull { matcher.nameOf(it.key) }.take(RARE)
            MomentHabit(moment, days, (here.sumOf { it.food.nutrition.kcal } / days).roundToInt(), usual, rare)
        }
    }
}
