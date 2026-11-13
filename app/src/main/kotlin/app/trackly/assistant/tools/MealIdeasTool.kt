package app.trackly.assistant.tools

import app.trackly.core.catalog.Dish
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Instant
import app.trackly.dishes.RecurringBook
import app.trackly.dishes.ProductsBook
import app.trackly.core.suggestion.FoodMatcher
import app.trackly.core.suggestion.FoodHabits
import app.trackly.core.model.TextMatch
import app.trackly.core.model.Provision
import app.trackly.core.model.Moment
import app.trackly.core.model.Consumption
import app.trackly.assistant.context.HabitsContext
import app.trackly.assistant.context.FoodHabitsReader
import app.trackly.core.catalog.Provisions
import app.trackly.core.suggestion.MealSuggestions
import app.trackly.core.time.TrackingDay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Données pour suggérer un repas : ce qu'il reste à manger aujourd'hui (calculé par l'app), les
 * provisions et les plats ACTIFS avec leurs valeurs pour une portion. Lecture seule : rien n'est
 * enregistré, et un plat en pause n'y figure jamais.
 */
object MealIdeasTool : AssistantTool {
    override val name = "get_meal_ideas"

    override val description = """
        Donne de quoi SUGGÉRER un repas : les calories et macros restantes du jour de suivi (calculées par l'app), les provisions de l'utilisateur et ses plats actifs (jamais ceux en pause) avec leurs valeurs pour une portion et les ingrédients qui manquent.
        À appeler quand l'utilisateur demande une idée de repas (« qu'est-ce que je mange ce soir ? », « il me reste quoi comme macros ? »). Ne modifie rien. Réponds ensuite comme le dit la section « Stock et conseils de repas ». Une suggestion n'est JAMAIS une consommation : n'appelle pas record_consumptions tant que l'utilisateur ne dit pas l'avoir mangée.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "moment" to Schema.string(
            "Moment du repas à conseiller, s'il est dit ou évident ; null : d'après l'heure.",
            nullable = true,
            enum = Moment.entries.map { it.code },
        ),
    )

    @Serializable
    internal data class Args(val moment: String? = null)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val moment = args.moment?.let { code -> Moment.entries.firstOrNull { it.code == code } ?: return ToolResult.error("Moment inconnu : $code") }
            ?: momentAt(context.messageAt)
        val day = TrackingDay.of(context.messageAt)
        val summary = context.engine.daySummary(day)
        val remaining = MealSuggestions.remaining(summary)
        val book = context.engine.dishBook()
        val candidates = MealSuggestions.candidates(book.dishes, book.provisions, remaining)
        val eaten = FoodHabitsReader.eaten(context.engine, day)
        val products = context.engine.allProducts()
        val matcher = FoodMatcher(products, book.dishes, RecurringBook.replacements(products), ProductsBook::title)
        return ToolResult(
            buildJsonObject {
                put("status", "ok")
                put("day", day.toString())
                put("remaining", buildJsonObject {
                    put("kcal", remaining.kcal?.round1())
                    put("protein_g", remaining.proteinG?.round1())
                    // Écart au minimum de protéines, le plancher : l'idéal (`protein_g`) reste l'objectif.
                    summary.goal?.proteinMinG?.let { min -> put("protein_to_minimum_g", (min - summary.totals.protein.known).coerceAtLeast(0.0).round1()) }
                    put("carbs_g", remaining.carbsG?.round1())
                    put("fat_g", remaining.fatG?.round1())
                    put("at_most", !remaining.macrosComplete)
                    put("energy_status", summary.energy.status.code)
                })
                putJsonArray("provisions") { Provisions.held(book.provisions).forEach { add(it.toJson()) } }
                putJsonArray("dishes") {
                    for (c in candidates) add(
                        buildJsonObject {
                            put("recipe_id", c.recipeId)
                            put("label", c.label)
                            put("kcal", c.perPortion.kcal.round1())
                            put("protein_g", c.perPortion.proteinG?.round1())
                            put("carbs_g", c.perPortion.carbsG?.round1())
                            put("fat_g", c.perPortion.fatG?.round1())
                            put("fits_remaining_kcal", c.fitsKcal)
                            putJsonArray("missing_ingredients") { c.missingIngredients.forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                }
                put("moment", moment.code)
                FoodHabits.of(eaten, matcher).firstOrNull { it.moment == moment }?.let { put("habits_for_moment", HabitsContext.line(it)) }
                putJsonArray("likely_at_home") { likelyAtHome(eaten, matcher, day, book.provisions).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("usual_meals_for_moment") { usualMeals(eaten, moment, book.dishes).forEach { add(it.toMealJson()) } }
                putJsonArray("last_days") { eaten.filter { it.isActive && it.day >= day.minusDays(LAST_DAYS) }.sortedByDescending { it.consumedAt }.take(MAX_LAST).forEach { add(it.toMealJson()) } }
                put("note", "Suggestions seulement : rien n'est compté. Les plats en pause ne sont pas listés.")
            },
        )
    }

    private const val LAST_DAYS = 2L
    private const val MAX_LAST = 12
    private const val USUAL_MEALS = 10
    private const val AT_HOME_DAYS = 30L
    private const val AT_HOME_MIN = 3

    private fun momentAt(at: Instant): Moment {
        val hour = at.atZone(TrackingDay.ZONE).hour
        return when {
            hour in 5..10 -> Moment.Matin
            hour in 11..14 -> Moment.Midi
            hour in 15..17 -> Moment.Snack
            else -> Moment.Soir
        }
    }

    /** Ce qu'il a mangé au moins [AT_HOME_MIN] jours sur [AT_HOME_DAYS], hors stock : sans doute chez lui, sans certitude. */
    private fun likelyAtHome(eaten: List<Consumption>, matcher: FoodMatcher, day: LocalDate, stock: List<Provision>): List<String> {
        val recent = eaten.filter { it.isActive && it.day >= day.minusDays(AT_HOME_DAYS) }
        val days = HashMap<String, MutableSet<LocalDate>>()
        for (c in recent) for (id in matcher.ids(c)) days.getOrPut(id) { HashSet() } += c.day
        return days.filter { it.value.size >= AT_HOME_MIN }.keys.mapNotNull(matcher::nameOf)
            .filter { name -> stock.none { Provisions.covers(it, name, null) } }
            .sorted()
    }

    /** Ses vrais repas à ce moment, les plus récents d'abord, sans les doublons. */
    private fun usualMeals(eaten: List<Consumption>, moment: Moment, dishes: List<Dish>): List<Consumption> {
        val paused = dishes.filter { it.isPaused }.flatMap { d -> d.versions.map { it.recipeId } }.toSet()
        return eaten.filter { it.isActive && it.moment == moment && it.food.recipeId !in paused }
            .sortedByDescending { it.consumedAt }
            .distinctBy { TextMatch.normalize(ProductsBook.cleanName(it.food.label)) }
            .take(USUAL_MEALS)
    }

    private fun Consumption.toMealJson() = buildJsonObject {
        put("day", day.toString())
        put("moment", moment.code)
        put("label", ProductsBook.cleanName(food.label))
        put("kcal", food.nutrition.kcal.round1())
        put("protein_g", food.nutrition.proteinG?.round1())
        put("carbs_g", food.nutrition.carbsG?.round1())
        put("fat_g", food.nutrition.fatG?.round1())
    }
}
