package app.trackly.core.review

import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.sqrt

/** Moyenne des pesées d'une semaine civile, avec le nombre de pesées. */
data class WeekMean(val range: WeekRange, val averageKg: Double, val count: Int)

/**
 * Tendance du poids sur les dernières semaines pesées, en kg par semaine (négatif : perte), avec sa
 * fourchette [lowKgPerWeek, highKgPerWeek] (environ 95 %).
 */
data class WeightTrend(
    val slopeKgPerWeek: Double,
    val lowKgPerWeek: Double,
    val highKgPerWeek: Double,
    /** Les semaines pesées utilisées, la plus ancienne d'abord. */
    val weeks: List<WeekMean>,
) {
    val halfWidthKgPerWeek: Double get() = (highKgPerWeek - lowKgPerWeek) / 2
}

/**
 * Pente du poids par **régression linéaire pondérée** sur les moyennes hebdomadaires :
 *
 * - une moyenne de `n` pesées a un bruit `max(bruit minimal, bruit d'une pesée / racine(n))` (0,8 kg pour une
 *   seule pesée, 0,3 kg à partir de 7) ; le poids de la semaine dans la régression est l'inverse de ce bruit au carré ;
 * - la pente est celle de la droite des moindres carrés pondérés, avec `x` en semaines ;
 * - son erreur type est `1 / racine(somme des poids x (x - x moyen)^2)` : elle vient du bruit supposé des
 *   pesées, pas des résidus, car 3 ou 4 points ne permettent pas de les estimer ;
 * - la fourchette est la pente +- [ReviewParams.trendRangeFactor] erreurs types.
 *
 * Méthode (pente sur 21 à 28 jours avec fourchette ; lissage de la balance plutôt que
 * pesée du jour : https://mhealth.jmir.org/2020/9/e17977). Moins de [ReviewParams.trendMinWeeks] semaines pesées :
 * aucune tendance (`null`), rien n'est deviné.
 */
object WeightTrends {
    fun weekNoiseKg(count: Int, params: ReviewParams): Double =
        max(params.weekMeanMinNoiseKg, params.weighInNoiseKg / sqrt(count.toDouble()))

    fun fit(weeks: List<WeekMean>, params: ReviewParams = ReviewParams.Default): WeightTrend? {
        if (weeks.size < params.trendMinWeeks) return null
        val latest = weeks.maxOf { it.range.start }
        val xs = weeks.map { ChronoUnit.WEEKS.between(latest, it.range.start).toDouble() }
        val ws = weeks.map { 1.0 / weekNoiseKg(it.count, params).let { n -> n * n } }
        val sumW = ws.sum()
        val meanX = xs.indices.sumOf { ws[it] * xs[it] } / sumW
        val meanY = weeks.indices.sumOf { ws[it] * weeks[it].averageKg } / sumW
        val sxx = xs.indices.sumOf { ws[it] * (xs[it] - meanX) * (xs[it] - meanX) }
        if (sxx <= 0.0) return null
        val slope = xs.indices.sumOf { ws[it] * (xs[it] - meanX) * (weeks[it].averageKg - meanY) } / sxx
        val half = params.trendRangeFactor / sqrt(sxx)
        return WeightTrend(slope, slope - half, slope + half, weeks.sortedBy { it.range.start })
    }
}
