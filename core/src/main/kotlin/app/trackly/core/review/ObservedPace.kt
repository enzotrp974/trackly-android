package app.trackly.core.review

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * Perte (ou prise) **observée** par semaine, au début de la fenêtre de pesées puis récemment, à partir des moyennes hebdomadaires,
 * avec le ralentissement que le modèle de dépense de la prévision d'arrivée attendrait entre ces deux moments.
 *
 * Rythmes en kg par semaine, **positifs vers l'objectif** (une perte pour qui veut perdre, une prise pour qui veut prendre).
 * Calcul pur, sans lien avec [app.trackly.core.forecast.ArrivalForecasts] : il lit seulement ses paramètres.
 */
data class ObservedPace(
    val earlyKgPerWeek: Double,
    val recentKgPerWeek: Double,
    /** Premier jour de la première semaine pesée de la fenêtre (le moment du rythme « au début »). */
    val earlyFrom: LocalDate,
    /** Part du rythme du début que le modèle conserverait au moment récent : `e^(-écart / τ)`, 1 = aucun ralentissement attendu. */
    val expectedRatio: Double,
) {
    /** Part du rythme du début encore observée récemment (0,6 : on perd 40 % de moins qu'au début). */
    val observedRatio: Double get() = recentKgPerWeek / earlyKgPerWeek

    /** La perte observée a vraiment baissé : d'au moins [ObservedPaces.MIN_DROP_SHARE] et [ObservedPaces.MIN_DROP_KG_PER_WEEK]. */
    val slowed: Boolean
        get() = earlyKgPerWeek - recentKgPerWeek >= ObservedPaces.MIN_DROP_KG_PER_WEEK &&
            observedRatio <= 1 - ObservedPaces.MIN_DROP_SHARE

    /**
     * Ce ralentissement est celui du modèle, à la tolérance près : il n'est pas bien plus fort que prévu. Un rythme qui s'effondre
     * au-delà ([ObservedPaces.EXPECTED_TOLERANCE]) n'est pas « attendu » : c'est une stagnation, que la prévision traite à part.
     */
    val slowdownExpected: Boolean get() = slowed && observedRatio >= expectedRatio - ObservedPaces.EXPECTED_TOLERANCE
}

object ObservedPaces {
    /** Moyennes hebdomadaires minimum pour comparer le début à la fin : trois de chaque côté. */
    const val MIN_WEEKLY_MEANS = 6

    /** Baisse minimale du rythme pour parler de ralentissement : au moins 10 % et 0,05 kg par semaine. */
    const val MIN_DROP_SHARE = 0.10
    const val MIN_DROP_KG_PER_WEEK = 0.05

    /**
     * Écart toléré entre le ralentissement observé et celui du modèle (en part du rythme du début). Les moyennes hebdomadaires
     * bougent de quelques centaines de grammes : en dessous de cette marge, l'écart n'est pas une information.
     */
    const val EXPECTED_TOLERANCE = 0.25

    /**
     * @param weights pesées connues, jusqu'à [asOf] (les plus anciennes comptent seulement dans la fenêtre)
     * @param asOf fin de la période : les blocs de sept jours finissent avec elle
     * @param losing le sens de l'objectif (perte ou prise)
     * @return `null` sans [MIN_WEEKLY_MEANS] moyennes hebdomadaires, ou si le rythme du début n'allait pas vers l'objectif
     */
    fun compute(weights: List<WeighIn>, asOf: LocalDate, losing: Boolean, params: ReviewParams = ReviewParams.Default): ObservedPace? {
        val means = WeightHistory.of(emptyList(), weights.filter { !it.day.isAfter(asOf) })
            .weeklyMeans(WeekRange(asOf.minusDays(6), asOf), params.forecastWindowWeeks + 2)
        if (means.size < MIN_WEEKLY_MEANS) return null
        val half = means.size / 2
        val early = means.take(half)
        val recent = means.takeLast(half)
        val sign = if (losing) -1.0 else 1.0
        val earlyRate = slopePerWeek(early, asOf) * sign
        val recentRate = slopePerWeek(recent, asOf) * sign
        if (earlyRate <= 0.0) return null
        val gap = ChronoUnit.DAYS.between(midpoint(early), midpoint(recent)).toDouble()
        val tau = params.kcalPerKg / params.expenditureKcalPerKg
        return ObservedPace(earlyRate, recentRate, early.first().range.start, exp(-gap / tau))
    }

    private fun midpoint(means: List<WeekMean>): LocalDate {
        val days = means.map { it.range.start.plusDays(3).toEpochDay() }
        return LocalDate.ofEpochDay(Math.round(days.average()))
    }

    /** Pente des moyennes (moindres carrés, une moyenne par semaine), en kg par semaine ; négative pour une perte. */
    private fun slopePerWeek(means: List<WeekMean>, asOf: LocalDate): Double {
        val x = means.map { ChronoUnit.DAYS.between(asOf, it.range.start.plusDays(3)) / 7.0 }
        val y = means.map { it.averageKg }
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return 0.0
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
    }
}
