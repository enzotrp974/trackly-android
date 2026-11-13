package app.trackly.review.page

import app.trackly.core.goal.WeightTarget
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.review.DaySteps
import app.trackly.core.review.ReviewParams
import app.trackly.core.review.SessionCount
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeeklyFigures
import app.trackly.core.review.WeeklyReview
import app.trackly.core.review.WeighIn
import app.trackly.core.review.WeightHistory
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DayEnergy
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.MacroTotal
import app.trackly.core.summary.Totals
import java.time.Instant
import java.time.LocalDate

/**
 * Données **fictives** des scénarios de démonstration : départ à 68 kg le 14 septembre 2026, objectif 62 kg au 1er mars 2027,
 * 2 300 kcal, minimum de protéines 120 g, limite de lipides 70 g, bilan du 31 octobre au 6 novembre. Aucune donnée réelle.
 * Sert aux tests du contenu et aux captures : mêmes chiffres des deux côtés.
 */
object ReportPageFixtures {
    val start: LocalDate = LocalDate.parse("2026-09-14")
    val range = WeekRange(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-06"))
    val target = WeightTarget(62.0, LocalDate.parse("2027-03-01"))
    private const val GOAL_KCAL = 2300.0
    private const val TODAY = 53

    enum class Scenario(val label: String) {
        Avance("En avance"),
        Temps("Dans les temps"),
        Retard("En retard"),
        Ralenti("Ralentissement normal"),
    }

    /** Jour d'arrivée visé (en jours depuis le début) de chaque scénario : 1er mars = 168. */
    private fun arrivalDay(scenario: Scenario) = when (scenario) {
        Scenario.Avance -> 148
        Scenario.Temps -> 168
        Scenario.Retard -> 196
        Scenario.Ralenti -> 165
    }

    /**
     * Poids jour par jour d'une personne qui mange la même chose chaque jour, avec la dépense du modèle de l'app (3 000 kcal à 70 kg,
     * 30 kcal de moins par kilo, 7 700 kcal par kilo) : la perte ralentit d'elle-même. L'apport est choisi pour atteindre 62 kg au jour voulu.
     */
    private fun simulate(intake: Double, days: Int): DoubleArray {
        val out = DoubleArray(days + 1)
        var w = 68.0
        for (t in 0..days) {
            out[t] = w
            w -= (3000.0 + 30.0 * (w - 70.0) - intake) / 7700.0
        }
        return out
    }

    private val curves = Scenario.values().associateWith { s ->
        val day = arrivalDay(s)
        var low = 1500.0
        var high = 3200.0
        repeat(60) {
            val mid = (low + high) / 2
            if (simulate(mid, day)[day] > 62.0) high = mid else low = mid
        }
        simulate((low + high) / 2, 200)
    }

    private fun real(scenario: Scenario, t: Double): Double = curves.getValue(scenario)[t.toInt()]

    private fun noise(i: Int) = (((i * 7919 + 13) % 101) / 101.0 - 0.5) * 0.1

    fun weights(scenario: Scenario): List<WeighIn> {
        val offsets = (0..6).flatMap { w -> listOf(w * 7 + 1, w * 7 + 3, w * 7 + 6) } + listOf(47, 49, 51, 53)
        return offsets.filter { it <= TODAY }.distinct().mapIndexed { i, t ->
            WeighIn(start.plusDays(t.toLong()), Math.round((real(scenario, t.toDouble()) + noise(i)) * 10) / 10.0)
        }
    }

    /** Les sept jours du bilan : calories, protéines, lipides et pas selon le scénario. */
    private data class Day(val kcal: Double, val protein: Double, val fat: Double, val steps: Int)

    private fun periodDays(scenario: Scenario): List<Day> = when (scenario) {
        Scenario.Avance -> List(7) { Day(2240.0, 138.0, 63.0, 21_400) }
        Scenario.Temps -> listOf(112.0, 131.0, 104.0, 138.0, 127.0, 141.0, 109.0).map { Day(2290.0, it, 66.0, 19_800) }
        Scenario.Retard -> listOf(290.0, 220.0, -40.0, -30.0, 10.0, -40.0, -10.0).zip(listOf(14_200, 16_800, 12_500, 15_900, 17_300, 14_600, 15_800))
            .map { (gap, steps) -> Day(GOAL_KCAL + gap, 131.0, 68.0, steps) }
        Scenario.Ralenti -> List(7) { Day(2270.0, 137.0, 61.0, 20_300) }
    }

    private fun summary(day: LocalDate, kcal: Double, protein: Double, fat: Double): DaySummary {
        val carbs = ((kcal - 4 * protein - 9 * fat) / 4).coerceAtLeast(0.0)
        val totals = Totals(kcal, MacroTotal(protein, true), MacroTotal(carbs, true), MacroTotal(fat, true), MacroTotal(25.0, true))
        return DaySummary(day, GoalTargets(GOAL_KCAL, 135.0, 250.0, 70.0, 120.0), emptyList(), totals, DayEnergy.detailed(totals), DayCoverage.Complete)
    }

    /** Les jours depuis le début du suivi jusqu'à la fin de la période, comme les reçoit le moteur du bilan. */
    private fun days(period: List<Day>): List<DaySummary> =
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(range.end) }.map { d ->
            val i = range.days.indexOf(d)
            if (i >= 0) summary(d, period[i].kcal, period[i].protein, period[i].fat) else summary(d, GOAL_KCAL, 135.0, 66.0)
        }.toList()

    private fun info(period: List<Day>, sessions: List<SessionCount>?) = WeekInfo(
        weekStart = range.start, version = 1, state = RecordState.Active,
        steps = range.days.mapIndexed { i, d -> DaySteps(d, period[i].steps) },
        sessions = sessions, recordedAt = Instant.EPOCH, actionId = null,
    )

    fun figures(scenario: Scenario, params: ReviewParams = ReviewParams.Default): WeeklyFigures {
        val period = periodDays(scenario)
        val sessions = if (scenario == Scenario.Retard) listOf(SessionCount("musculation", 2)) else listOf(SessionCount("musculation", 3))
        return WeeklyReview.compute(
            range, days(period), info(period, sessions), WeightHistory.of(emptyList(), weights(scenario)), params, target,
        )
    }

    /** Mêmes jours, autres pesées : pour les statuts de la prévision (aucune pesée, estimation, atteint, stable, s'éloigne). */
    fun figures(weights: List<WeighIn>, scenario: Scenario = Scenario.Temps, target: WeightTarget = this.target): WeeklyFigures {
        val period = periodDays(scenario)
        return WeeklyReview.compute(
            range, days(period), info(period, listOf(SessionCount("musculation", 3))), WeightHistory.of(emptyList(), weights), ReviewParams.Default, target,
        )
    }

    /**
     * Sept semaines de pesées (trois par semaine, les blocs de sept jours finissent avec la période) où la perte passe de 0,5 à environ
     * 0,38 kg par semaine : un ralentissement du même ordre que celui du modèle de dépense, donc « attendu ».
     */
    fun slowingWeights(): List<WeighIn> {
        val first = range.end.minusDays(7L * 7 - 1)
        return (0 until 7).flatMap { w -> listOf(1, 3, 5).map { d -> w * 7 + d } }.map { t ->
            val kg = if (t <= 24) 68.0 - 0.5 * t / 7 else 68.0 - 0.5 * 24 / 7 - 0.38 * (t - 24) / 7
            WeighIn(first.plusDays(t.toLong()), Math.round(kg * 100) / 100.0)
        }
    }

    const val ANALYSIS = "Franchement, belle semaine.\n\nTu as de l’avance et tout est au vert : rien à corriger."
}
