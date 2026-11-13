package app.trackly.assistant.context

import app.trackly.core.catalog.Dishes
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.Consumption
import app.trackly.core.suggestion.FoodHabits
import app.trackly.core.suggestion.FoodMatcher
import app.trackly.core.suggestion.MomentHabit
import app.trackly.core.time.TrackingDay
import app.trackly.dishes.ProductsBook
import app.trackly.dishes.RecurringBook
import java.time.LocalDate

/** Ce que l'agent a retenu : notes durables, à appliquer sans les réciter. */
object MemoryContext : ContextProvider {
    override val id = "memory"
    override val title = "Mémoire"

    override suspend fun render(env: ContextEnv): String? {
        val notes = AgentMemory.notes(env.engine.activePreferences())
        if (notes.isEmpty()) return null
        return buildString {
            append("Ce que tu as retenu (identifiant · sujet : note) :")
            for (n in notes) append("\n- ").append(n.preferenceId).append(" · ").append(n.trigger).append(" : ").append(n.rule.trim())
        }
    }
}

/** Habitudes calculées sur les repas enregistrés : ce qui est mangé à chaque moment, et ce qui ne l'est presque jamais. */
object HabitsContext : ContextProvider {
    override val id = "habits"
    override val title = "Habitudes"

    override suspend fun render(env: ContextEnv): String? {
        val habits = FoodHabitsReader.read(env.engine, TrackingDay.of(env.now))
        if (habits.isEmpty()) return null
        return buildString {
            append("Calculées par l'application sur ${FoodHabits.WINDOW_DAYS} jours de repas enregistrés :")
            for (h in habits) append("\n").append(line(h))
        }
    }

    internal fun line(h: MomentHabit): String = buildString {
        append("- ").append(h.moment.label).append(" (").append(h.days).append(" jours, ").append(h.averageKcal).append(" kcal en moyenne) : ")
        append(h.usual.joinToString(", ") { "${it.name} ${it.days} j" }.ifEmpty { "rien de régulier" })
        if (h.rare.isNotEmpty()) append(". Presque jamais : ").append(h.rare.joinToString(", "))
        append('.')
    }
}

/** Lit les repas récents et en tire les habitudes, avec les noms des fiches. */
object FoodHabitsReader {
    suspend fun eaten(engine: TracklyEngine, day: LocalDate): List<Consumption> =
        engine.daySummaries(day.minusDays(FoodHabits.WINDOW_DAYS - 1), day).flatMap { it.consumptions }

    suspend fun read(engine: TracklyEngine, day: LocalDate): List<MomentHabit> {
        val products = engine.allProducts()
        val dishes = Dishes.group(engine.allRecipes())
        val matcher = FoodMatcher(products, dishes, RecurringBook.replacements(products), ProductsBook::title)
        return FoodHabits.of(eaten(engine, day), matcher)
    }
}
