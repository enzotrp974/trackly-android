package app.trackly.core.overview

import app.trackly.core.model.Consumption
import app.trackly.core.model.Moment
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.EnergyStatus
import java.time.LocalDate

/** Les quatre barres de l'accueil : la jauge des calories et les trois macros. */
enum class Bar { Kcal, Protein, Carbs, Fat }

/** État d'une barre de l'accueil : seule la couleur de son remplissage en dépend (jamais un chiffre ni une étiquette). */
enum class BarState {
    /** Rien à signaler : couleur d'origine. */
    Normal,

    /** Attention (jaune) : les repas restants habituels feraient dépasser la limite, ou ne suffiraient pas pour le minimum. */
    Warn,

    /** Dépassé (rouge) : la limite l'est déjà, ou le soir est noté sans que le minimum de protéines soit atteint. */
    Bad,
}

/**
 * Ce que la vue du jour dit d'une barre.
 *
 * [average] est la position du trait de moyenne, dans l'unité de la barre (kcal ou grammes) : ce que mangent d'habitude
 * les 7 jours précédents au même stade de la journée. `null` : pas de trait. L'écran la ramène à la cible de la barre.
 *
 * [state] est l'état de la barre. Le trait et l'état sont indépendants : l'un n'implique jamais l'autre.
 */
data class BarOverview(val average: Double?, val state: BarState = BarState.Normal) {
    companion object {
        val None = BarOverview(null)
    }
}

/** Vue du jour : l'état de chaque barre de l'accueil, calculé par l'application, jamais par l'écran ni par l'IA. */
data class DayOverview(private val bars: Map<Bar, BarOverview>) {
    operator fun get(bar: Bar): BarOverview = bars[bar] ?: BarOverview.None

    companion object {
        /** Rien à dire : aucun trait, toutes les barres normales. */
        val None = DayOverview(emptyMap())
    }
}

/**
 * Module pur de la vue du jour : à partir du bilan d'un jour et des bilans des jours précédents, il calcule pour chaque
 * barre la position du trait de moyenne et son état. Il ne lit ni ne modifie rien ; l'assistant ne l'appelle pas.
 *
 * **Jours de comparaison.** Les 7 jours calendaires qui précèdent le jour affiché, parmi lesquels on garde ceux qui ont des
 * repas détaillés et qui ne sont pas déclarés incomplets : un soir oublié tirerait la moyenne vers le bas. Il en faut
 * [MIN_DAYS] au moins, sinon ni trait ni jaune.
 *
 * **Trait de moyenne.** Aujourd'hui, le dernier moment noté fixe le stade de la journée (matin, midi, snack ou soir).
 * Pour chacun des jours de comparaison, on additionne ce qui a été mangé jusqu'à ce moment inclus ; un moment
 * sans repas ce jour-là compte pour zéro. Le trait est la moyenne de ces totaux, sauf si elle est nulle.
 * Aucun trait tant que rien n'est noté aujourd'hui.
 *
 * **États** (aujourd'hui) :
 * - calories et lipides : *dépassé* si le total, arrondi comme à l'écran, dépasse la limite ; *attention* si le total plus les
 *   repas restants habituels (la moyenne, sur les jours de comparaison, de ce qui se mange aux moments qui suivent le dernier
 *   noté) dépasse la limite de plus de [WARN_MARGIN] ;
 * - protéines, jamais sur l'idéal mais sur le **minimum** enregistré : *dépassé* si le soir est noté et que le minimum n'est pas
 *   atteint ; *attention* si le total plus les repas restants habituels reste sous le minimum de plus de [WARN_MARGIN] ;
 * - glucides : toujours normal (ils remplissent le reste des calories).
 *
 * **Jour passé** : pas de trait, pas de jaune (rien ne reste à manger), seulement le rouge. **Journée estimée ou bornée** :
 * aucun trait, aucun état (pas de détail par moment, macros inconnues).
 */
object DayOverviews {
    /** Fenêtre d'historique : les jours qui précèdent le jour affiché. */
    const val WINDOW_DAYS = 7

    /** Nombre minimal de jours de données pour qu'un trait ou un jaune ait un sens. */
    const val MIN_DAYS = 3

    /**
     * Seuil du jaune (à régler à l'usage, pour qu'il ne s'allume pas trop souvent) :
     * part de la limite dont la prévision doit s'écarter pour passer en jaune. À 0,05, 70 g de lipides ne jaunissent qu'à
     * partir de 73,5 g prévus ; un minimum de 112 g de protéines qu'en dessous de 106,4 g prévus. À 0, le jaune suit la lettre
     * (« feraient dépasser »).
     */
    const val WARN_MARGIN = 0.05

    /**
     * [history] peut contenir d'autres jours qu'attendu, dans n'importe quel ordre, et même [day] : seuls les
     * [WINDOW_DAYS] jours précédents comptent. [today] vaut [day] par défaut (le jour affiché est aujourd'hui).
     */
    fun of(day: DaySummary, history: Collection<DaySummary>, today: LocalDate = day.day): DayOverview {
        if (day.day.isAfter(today)) return DayOverview.None
        val goal = day.goal ?: return DayOverview.None
        // Une journée estimée ou bornée n'a pas de détail par moment : elle ne se compare pas, et ses macros sont inconnues.
        if (day.energy.status != EnergyStatus.Detailed) return DayOverview.None
        val bad = exceeded(day)
        if (day.day != today) {
            // Jour passé : rien à prévoir, ni trait. Le rouge dit un fait.
            return DayOverview(bad.associateWith { BarOverview(null, BarState.Bad) })
        }
        val stage = day.consumptions.maxOfOrNull { it.moment.ordinal } ?: return DayOverview.None
        val moments = Moment.entries.filter { it.ordinal <= stage }
        val remaining = Moment.entries.filter { it.ordinal > stage }
        val days = previousDays(day.day, history)

        fun average(target: Double?, amount: (Consumption) -> Double?): Double? {
            if (target == null || target <= 0) return null
            // Un jour où une valeur de la barre est inconnue avant ce stade n'a pas de total sûr : il est écarté de cette barre.
            val totals = days.mapNotNull { d ->
                val values = d.consumptions.filter { it.moment in moments }.map(amount)
                if (values.any { it == null }) null else values.sumOf { it ?: 0.0 }
            }
            if (totals.size < MIN_DAYS) return null
            // Une moyenne nulle (jamais de petit-déjeuner, par exemple) collerait le trait au bord : il ne dirait rien.
            return totals.average().takeIf { it > 0 }
        }

        /** Ce qui se mange d'habitude aux moments restants ; `null` sans assez de jours sûrs pour cette barre. */
        fun expectedRest(amount: (Consumption) -> Double?): Double? {
            val totals = days.mapNotNull { d ->
                val values = d.consumptions.filter { it.moment in remaining }.map(amount)
                if (values.any { it == null }) null else values.sumOf { it ?: 0.0 }
            }
            return if (totals.size < MIN_DAYS) null else totals.average()
        }

        fun limitState(bar: Bar, limit: Double?, consumed: Double, amount: (Consumption) -> Double?): BarState {
            if (bar in bad) return BarState.Bad
            // Rien ne reste à manger : la prévision est le total, qui ne dépasse pas (sinon c'est déjà rouge).
            if (limit == null || limit <= 0 || remaining.isEmpty()) return BarState.Normal
            val rest = expectedRest(amount) ?: return BarState.Normal
            return if (consumed + rest > limit * (1 + WARN_MARGIN)) BarState.Warn else BarState.Normal
        }

        fun proteinState(): BarState {
            if (Bar.Protein in bad) return BarState.Bad
            val min = goal.proteinMinG?.takeIf { it > 0 } ?: return BarState.Normal
            // Une protéine inconnue ou une journée déclarée incomplète : le total n'est qu'un minimum, on ne dit pas qu'il manque.
            if (!day.totals.protein.complete || day.declaredIncomplete || remaining.isEmpty()) return BarState.Normal
            val rest = expectedRest { it.food.nutrition.proteinG } ?: return BarState.Normal
            return if (day.totals.protein.known + rest < min * (1 - WARN_MARGIN)) BarState.Warn else BarState.Normal
        }

        return DayOverview(
            mapOf(
                Bar.Kcal to BarOverview(average(goal.kcal) { it.food.nutrition.kcal }, limitState(Bar.Kcal, goal.kcal, day.totals.kcal) { it.food.nutrition.kcal }),
                Bar.Protein to BarOverview(average(goal.proteinG) { it.food.nutrition.proteinG }, proteinState()),
                Bar.Carbs to BarOverview(average(goal.carbsG) { it.food.nutrition.carbsG }),
                Bar.Fat to BarOverview(average(goal.fatG) { it.food.nutrition.fatG }, limitState(Bar.Fat, goal.fatG, day.totals.fat.known) { it.food.nutrition.fatG }),
            ),
        )
    }

    /**
     * Les barres *dépassées* d'un jour (rouge), sans historique : un fait du jour, vrai pour un jour passé comme pour
     * aujourd'hui. La carte du chat le fige à l'enregistrement. Vide pour une journée estimée ou bornée, ou sans objectif.
     * - calories et lipides : le total, arrondi comme à l'écran, dépasse la limite (une valeur inconnue ne fait que le minorer :
     *   un minimum qui dépasse la limite la dépasse vraiment) ;
     * - protéines : le soir est noté, le total est complet et la journée non déclarée incomplète, et le minimum n'est pas atteint.
     */
    fun exceeded(day: DaySummary): Set<Bar> {
        val goal = day.goal ?: return emptySet()
        if (day.energy.status != EnergyStatus.Detailed) return emptySet()
        return buildSet {
            if (over(day.totals.kcal, goal.kcal)) add(Bar.Kcal)
            if (goal.fatG != null && goal.fatG > 0 && over(day.totals.fat.known, goal.fatG)) add(Bar.Fat)
            val min = goal.proteinMinG
            if (min != null && min > 0 && day.totals.protein.complete && !day.declaredIncomplete &&
                day.consumptions.any { it.moment == Moment.Soir } && Math.round(day.totals.protein.known) < Math.round(min)
            ) {
                add(Bar.Protein)
            }
        }
    }

    /** Comme à l'écran, où les totaux et les limites sont arrondis au gramme (« +N g ») : 70,4 sur 70 ne dépasse pas. */
    private fun over(value: Double, limit: Double) = Math.round(value) > Math.round(limit)

    /** Les jours de la fenêtre qui ont des repas détaillés et ne sont pas déclarés incomplets, un bilan par jour. */
    private fun previousDays(today: LocalDate, history: Collection<DaySummary>): List<DaySummary> {
        val first = today.minusDays(WINDOW_DAYS.toLong())
        return history
            .filter { !it.day.isBefore(first) && it.day.isBefore(today) }
            .filter { it.energy.status == EnergyStatus.Detailed && it.consumptions.isNotEmpty() && !it.declaredIncomplete }
            .distinctBy { it.day }
    }
}
