package app.trackly.core.forecast

import app.trackly.core.goal.WeightTarget
import app.trackly.core.review.ReviewParams
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeightHistory
import app.trackly.core.review.WeightTrends
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DaySummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sqrt

/** Ce que la prévision peut dire, selon les pesées connues. */
enum class ForecastStatus {
    /** Aucune pesée dans la fenêtre : on ne sait pas où en est le poids, aucune date. */
    NoWeighIn,

    /**
     * Moins de semaines pesées que le minimum : estimation théorique (référence et repas), **jamais une date
     * ferme** ; aucun verdict d'avance ou de retard.
     */
    Provisional,

    /** Le poids visé est atteint (ou dépassé dans le bon sens). */
    Reached,

    /** Date calculée depuis les pesées, ralentissement attendu compris ; voir le verdict. */
    Measured,

    /** Poids stable, mesuré avec assez de précision pour l'affirmer : à ce rythme, pas d'arrivée. */
    Stalled,

    /** Le poids s'éloigne de l'objectif, de façon sûre (au-delà du bruit des pesées). */
    MovingAway,
}

/** Avance ou retard sur la date visée, avec la marge de la prévision. */
enum class ArrivalVerdict {
    /** Même la date la plus tardive de la marge tombe au plus tard à la date visée. */
    Early,

    /** La date estimée tombe à temps, mais la marge déborde après la date visée. */
    LikelyOnTime,

    /** La date estimée tombe après la date visée, mais la marge permet encore d'arriver à temps. */
    LikelyLate,

    /** Même la date la plus précoce de la marge tombe après la date visée (ou il n'y a pas d'arrivée). */
    Late,
}

/** D'où vient le rythme utilisé. */
enum class PaceSource {
    /** Pesées d'au moins [ReviewParams.trendMinWeeks] semaines : rythme mesuré. */
    WeighIns,

    /** Dépense de référence moins la moyenne des repas réels comptés. */
    MealsAndReference,

    /** Dépense de référence moins l'objectif calorique en vigueur (trop peu de jours comptés). */
    CalorieGoalAndReference,

    /** Ni pesées suffisantes, ni repas, ni objectif calorique : aucun rythme. */
    None,
}

/** Un point d'une courbe, avec sa marge (poids aux deux bouts de la fourchette du rythme). */
data class CurvePoint(val day: LocalDate, val kg: Double, val lowKg: Double, val highKg: Double)

/** Dépense déduite des pesées et des repas réels, à comparer à celle du modèle de référence. */
data class MeasuredExpenditure(
    val kcalPerDay: Double,
    /** Demi-largeur de la fourchette, kcal par jour (vient de la fourchette du rythme). */
    val uncertaintyKcal: Double,
    /** Ce que donne le modèle de référence au même poids. */
    val modelKcalPerDay: Double,
    val countedDays: Int,
)

/**
 * Prévision d'arrivée : date estimée à laquelle le poids visé sera atteint, avec sa marge, la courbe attendue
 * et le chemin qui arriverait pile à la date visée. **Calculée par l'application, jamais par l'IA.** Voir
 * [ArrivalForecasts] pour le calcul et ses hypothèses.
 *
 * Rythmes en kg par semaine, négatifs pour une perte.
 */
data class ArrivalForecast(
    val asOf: LocalDate,
    val target: WeightTarget,
    val status: ForecastStatus,
    val paceSource: PaceSource,
    /** `null` sauf pour [ForecastStatus.Measured], [ForecastStatus.Stalled] et [ForecastStatus.MovingAway]. */
    val verdict: ArrivalVerdict?,
    /** Poids estimé : courbe ajustée sur les pesées (au [asOf]), ou moyenne de la dernière semaine pesée. */
    val currentKg: Double?,
    /** Jour auquel vaut [currentKg] : le [asOf] pour une courbe ajustée, la dernière pesée sinon. */
    val currentKgDay: LocalDate?,
    /** Date estimée ; `null` si le poids visé n'est pas atteint à ce rythme. */
    val arrival: LocalDate?,
    /** Marge : date au rythme le plus favorable de la fourchette. */
    val earliest: LocalDate?,
    /** Marge : date au rythme le moins favorable ; `null` si, à ce rythme-là, le poids visé n'est jamais atteint. */
    val latest: LocalDate?,
    val paceKgPerWeek: Double?,
    val paceLowKgPerWeek: Double?,
    val paceHighKgPerWeek: Double?,
    /** Rythme attendu au moment de l'arrivée : plus lent que [paceKgPerWeek], c'est le ralentissement normal. */
    val paceAtArrivalKgPerWeek: Double?,
    /** Rythme qu'il faudrait aujourd'hui pour arriver pile à la date visée, ralentissement compris. */
    val requiredKgPerWeek: Double?,
    /** Poids où la perte s'arrêterait à ces repas (la dépense a rejoint les apports) ; `null` si sans objet. */
    val plateauKg: Double?,
    /** Semaines pesées dans la fenêtre. */
    val weighedWeeks: Int,
    val minWeighedWeeks: Int,
    /** Courbe attendue à ce rythme, une semaine par point, du [asOf] à l'arrivée ou à la date visée. */
    val expectedCurve: List<CurvePoint>,
    /** Chemin qui arriverait pile à la date visée, ralentissement compris. */
    val onTimeCurve: List<CurvePoint>,
    /** Dépense déduite des pesées et des repas réels ; `null` sans assez de jours comptés ou de pesées. */
    val measuredExpenditure: MeasuredExpenditure?,
    /** Hypothèses du calcul, en phrases simples, à afficher telles quelles. */
    val assumptions: List<String>,
) {
    /** La date visée est passée sans que le poids soit atteint. */
    val targetDatePassed: Boolean get() = status != ForecastStatus.Reached && !target.date.isAfter(asOf)

    /** Une date ferme (au sens d'estimation mesurée) peut être annoncée. */
    val hasMeasuredDate: Boolean get() = status == ForecastStatus.Measured && arrival != null
}

/**
 * Calcul de la prévision d'arrivée. Pur et déterministe.
 *
 * **Modèle.** La dépense baisse avec le poids : `dépense(poids) = référence + k x (poids - poids de référence)`,
 * soit 3 000 kcal à 70 kg et `k` = [ReviewParams.expenditureKcalPerKg] (30 kcal par jour et par kilo). À repas
 * constants, l'écart entre apports et dépense se réduit donc de `k` kcal par jour à chaque kilo perdu, et le poids
 * suit une courbe qui ralentit :
 *
 * ```
 * poids(t) = poids(0) + rythme(0) x τ x (1 - e^(-t/τ)),   τ = 7 700 / k ≈ 257 jours
 * ```
 *
 * Les premiers kilos partent plus vite que les derniers : ce ralentissement est **attendu** et fait partie de
 * la courbe, il ne déplace pas la date estimée et n'est jamais une alerte.
 *
 * **Avec assez de pesées** (au moins [ReviewParams.trendMinWeeks] semaines pesées dans les
 * [ReviewParams.forecastWindowWeeks] dernières), cette courbe est ajustée sur les moyennes hebdomadaires des pesées
 * (moindres carrés pondérés, même bruit que [WeightTrends]) : elle donne le poids actuel et le rythme actuel, avec
 * une fourchette d'environ 95 %. La date et sa marge en découlent. Ajuster le rythme sur les pesées revient à
 * corriger la dépense de référence : la [MeasuredExpenditure] le montre quand les repas sont connus.
 *
 * **Avec moins de pesées**, le rythme est théorique : référence moins les repas réels (au moins
 * [ReviewParams.forecastMinIntakeDays] jours comptés sur les 4 dernières semaines), sinon moins l'objectif
 * calorique. La marge vient de l'incertitude de la référence (± 300 kcal). Ce n'est jamais une date ferme.
 */
object ArrivalForecasts {
    fun compute(
        asOf: LocalDate,
        target: WeightTarget,
        weights: WeightHistory,
        days: List<DaySummary>,
        params: ReviewParams = ReviewParams.Default,
    ): ArrivalForecast {
        val tau = params.kcalPerKg / params.expenditureKcalPerKg
        val known = WeightHistory.of(emptyList(), weights.all.filter { !it.day.isAfter(asOf) })
        val window = known.weeklyMeans(WeekRange.containing(asOf), params.forecastWindowWeeks)
        val base = Base(asOf, target, params, tau, window.size)
        if (window.isEmpty()) return base.empty(ForecastStatus.NoWeighIn)
        // Le sens vient de l'objectif enregistré, jamais de la position du poids : un objectif dépassé reste atteint.
        val direction = if (target.losing) -1.0 else 1.0
        // Une date mesurée demande aussi une pesée récente : une semaine pesée parmi les deux dernières.
        val recent = !window.last().range.start.isBefore(WeekRange.containing(asOf).start.minusWeeks(1))

        if (window.size >= params.trendMinWeeks && recent) {
            val points = window.map { week ->
                val inWeek = known.within(week.range)
                val meanDay = inWeek.sumOf { ChronoUnit.DAYS.between(asOf, it.day).toDouble() } / inWeek.size
                Point(meanDay, week.averageKg, 1.0 / WeightTrends.weekNoiseKg(week.count, params).let { it * it })
            }
            val fit = fit(points, tau) ?: return provisional(base, known, days, direction)
            val firstDay = window.first().range.start
            val counted = countedIntake(days, firstDay, asOf)
            val measured = counted.takeIf { it.size >= params.forecastMinMeasuredIntakeDays }?.let { list ->
                val averageIntake = list.sum() / list.size
                // Rythme moyen sur la fenêtre (la courbe ralentit : plus rapide au début qu'au jour du bilan).
                val span = points.minOf { it.x }
                val averageRate = if (span < 0) fit.ratePerDay * h(span, tau) / span else fit.ratePerDay
                MeasuredExpenditure(
                    kcalPerDay = averageIntake - params.kcalPerKg * averageRate,
                    uncertaintyKcal = params.kcalPerKg * params.trendRangeFactor * fit.rateSe * abs(if (span < 0) h(span, tau) / span else 1.0),
                    // Au poids moyen de la fenêtre, comme la dépense déduite (moyenne sur la fenêtre).
                    modelKcalPerDay = model(fit.meanKg, params),
                    countedDays = list.size,
                )
            }
            return base.project(
                current = fit.current,
                rate = fit.ratePerDay,
                low = fit.ratePerDay - params.trendRangeFactor * fit.rateSe,
                high = fit.ratePerDay + params.trendRangeFactor * fit.rateSe,
                direction = direction,
                source = PaceSource.WeighIns,
                measured = measured,
            )
        }
        return provisional(base, known, days, direction)
    }

    // ───── Calcul ─────

    /** Dépense du modèle de référence au poids donné, kcal par jour. */
    fun model(weightKg: Double, params: ReviewParams): Double =
        params.referenceKcal + params.expenditureKcalPerKg * (weightKg - params.referenceWeightKg)

    /** Forme de la courbe : `τ x (1 - e^(-t/τ))`, en jours ; vaut à peu près `t` sur quelques semaines. */
    internal fun h(t: Double, tau: Double): Double = tau * (1 - exp(-t / tau))

    /**
     * Jours pour aller de [current] à [targetKg] au rythme [ratePerDay] (kg par jour aujourd'hui), ralentissement
     * compris ; `null` si le poids visé n'est jamais atteint à ce rythme (mauvais sens, ou palier avant la cible).
     */
    internal fun daysToReach(current: Double, targetKg: Double, ratePerDay: Double, tau: Double): Double? {
        val delta = targetKg - current
        if (abs(delta) < REACHED_TOLERANCE_KG) return 0.0
        if (ratePerDay == 0.0 || sign(delta) != sign(ratePerDay)) return null
        val ratio = delta / (ratePerDay * tau)
        if (ratio >= 1.0) return null
        return -tau * ln(1 - ratio)
    }

    private data class Point(val x: Double, val y: Double, val w: Double)

    private data class Fit(val current: Double, val ratePerDay: Double, val rateSe: Double, val meanKg: Double)

    /** Moindres carrés pondérés de `y = a + r x h(x)` : `a` est le poids au jour du bilan, `r` le rythme de ce jour. */
    private fun fit(points: List<Point>, tau: Double): Fit? {
        val hs = points.map { h(it.x, tau) }
        val sumW = points.sumOf { it.w }
        val meanH = points.indices.sumOf { points[it].w * hs[it] } / sumW
        val meanY = points.sumOf { it.w * it.y } / sumW
        val sxx = points.indices.sumOf { points[it].w * (hs[it] - meanH) * (hs[it] - meanH) }
        if (sxx <= 0.0) return null
        val rate = points.indices.sumOf { points[it].w * (hs[it] - meanH) * (points[it].y - meanY) } / sxx
        return Fit(current = meanY - rate * meanH, ratePerDay = rate, rateSe = 1.0 / sqrt(sxx), meanKg = meanY)
    }

    /** Calories des jours comptés (complets ou estimés validés) de [from] à [to] : jamais un jour partiel ni une borne. */
    private fun countedIntake(days: List<DaySummary>, from: LocalDate, to: LocalDate): List<Double> =
        days.filter {
            !it.day.isBefore(from) && !it.day.isAfter(to) &&
                (it.coverage == DayCoverage.Complete || it.coverage == DayCoverage.Estimated) && it.energy.kcal != null
        }.map { it.energy.kcal!! }

    private fun provisional(base: Base, known: WeightHistory, days: List<DaySummary>, direction: Double): ArrivalForecast {
        val p = base.params
        // Poids de départ : moyenne de la dernière semaine pesée, datée de ses pesées.
        val lastWeek = WeekRange.containing(known.all.last().day)
        val weighIns = known.within(lastWeek)
        val currentKg = weighIns.sumOf { it.kg } / weighIns.size
        val weighedOn = weighIns.last().day
        if (ChronoUnit.DAYS.between(weighedOn, base.asOf) > p.forecastMaxWeighInAgeDays) {
            // Pesée trop ancienne pour servir de point de départ : on dit où on en était, sans date.
            return base.empty(ForecastStatus.Provisional, currentKg, direction, weighedOn = weighedOn, stale = true)
        }
        val intake = countedIntake(days, base.asOf.minusDays(INTAKE_WINDOW_DAYS - 1), base.asOf)
        val goalKcal = days.filter { !it.day.isAfter(base.asOf) }.maxByOrNull { it.day }?.goal?.kcal
        val (kcal, source) = when {
            intake.size >= p.forecastMinIntakeDays -> intake.sum() / intake.size to PaceSource.MealsAndReference
            goalKcal != null -> goalKcal to PaceSource.CalorieGoalAndReference
            else -> null to PaceSource.None
        }
        if (kcal == null) return base.empty(ForecastStatus.Provisional, currentKg, direction, weighedOn = weighedOn)
        val rate = (kcal - model(currentKg, p)) / p.kcalPerKg
        val spread = p.referenceUncertaintyKcal / p.kcalPerKg
        return base.project(currentKg, rate, rate - spread, rate + spread, direction, source, measured = null, provisional = true)
            .copy(currentKgDay = weighedOn)
    }

    /** Ce qui ne dépend pas du rythme. */
    private class Base(val asOf: LocalDate, val target: WeightTarget, val params: ReviewParams, val tau: Double, val weighedWeeks: Int) {
        fun empty(
            status: ForecastStatus,
            currentKg: Double? = null,
            direction: Double = 0.0,
            source: PaceSource = PaceSource.None,
            weighedOn: LocalDate? = null,
            stale: Boolean = false,
        ): ArrivalForecast {
            val reached = currentKg != null && isReached(currentKg, direction)
            val open = currentKg?.takeIf { !reached }
            return ArrivalForecast(
                asOf = asOf, target = target, status = if (reached) ForecastStatus.Reached else status,
                paceSource = source, verdict = null, currentKg = currentKg, currentKgDay = currentKg?.let { weighedOn ?: asOf },
                arrival = null, earliest = null, latest = null,
                paceKgPerWeek = null, paceLowKgPerWeek = null, paceHighKgPerWeek = null, paceAtArrivalKgPerWeek = null,
                requiredKgPerWeek = open?.takeIf { !stale }?.let { required(it) }, plateauKg = null,
                weighedWeeks = weighedWeeks, minWeighedWeeks = params.trendMinWeeks,
                expectedCurve = emptyList(), onTimeCurve = open?.takeIf { !stale }?.let { onTime(it) }.orEmpty(),
                measuredExpenditure = null,
                assumptions = assumptions(status, source, stale),
            )
        }

        /** Atteint : à la tolérance près, ou au-delà du poids visé dans le sens de l'objectif. */
        fun isReached(currentKg: Double, direction: Double): Boolean =
            abs(target.kg - currentKg) < REACHED_TOLERANCE_KG || (direction != 0.0 && (target.kg - currentKg) * direction < 0)

        private val daysToTarget: Long get() = ChronoUnit.DAYS.between(asOf, target.date)

        /** Rythme qu'il faudrait aujourd'hui pour arriver pile à la date visée (kg par semaine), ralentissement compris. */
        fun required(currentKg: Double): Double? {
            val t = daysToTarget.toDouble()
            if (t <= 0 || abs(target.kg - currentKg) < REACHED_TOLERANCE_KG) return null
            return (target.kg - currentKg) / h(t, tau) * 7
        }

        fun onTime(currentKg: Double): List<CurvePoint> {
            val perDay = required(currentKg)?.div(7) ?: return emptyList()
            return weeks(daysToTarget).map { t ->
                val kg = currentKg + perDay * h(t.toDouble(), tau)
                CurvePoint(asOf.plusDays(t), kg, kg, kg)
            }
        }

        private fun weeks(lastDay: Long): List<Long> =
            (0L..lastDay step 7).toList().let { if (it.last() != lastDay) it + lastDay else it }

        private fun dateAfter(days: Double?): LocalDate? =
            days?.takeIf { it <= params.forecastHorizonDays }?.let { asOf.plusDays(it.roundToLong()) }

        fun project(
            current: Double,
            rate: Double,
            low: Double,
            high: Double,
            direction: Double,
            source: PaceSource,
            measured: MeasuredExpenditure?,
            provisional: Boolean = false,
        ): ArrivalForecast {
            if (isReached(current, direction)) {
                return empty(ForecastStatus.Reached, current, direction, source).copy(
                    paceKgPerWeek = rate * 7, paceLowKgPerWeek = low * 7, paceHighKgPerWeek = high * 7,
                    measuredExpenditure = measured,
                )
            }
            // Le plus favorable des deux bouts de la fourchette est celui qui avance le plus vers la cible.
            val (fast, slow) = if ((low - high) * direction > 0) low to high else high to low
            val central = daysToReach(current, target.kg, rate, tau)
            val earliestDays = daysToReach(current, target.kg, fast, tau)
            val latestDays = daysToReach(current, target.kg, slow, tau)
            val arrival = dateAfter(central)
            val earliest = dateAfter(earliestDays)
            val latest = dateAfter(latestDays)

            val progress = rate * direction * 7 // kg par semaine vers la cible
            val halfWidth = abs(high - low) / 2 * 7
            val status = when {
                provisional -> ForecastStatus.Provisional
                // Même le bout le plus favorable de la fourchette s'éloigne de la cible.
                fast * direction < 0.0 -> ForecastStatus.MovingAway
                // Stable, mesuré avec précision, et aucune arrivée à ce rythme. Une lente arrivée tout près du but n'est pas une
                // stagnation, même après la date visée : la date calculée reste affichée, avec son retard.
                abs(progress) <= params.stallMaxKgPerWeek && halfWidth <= params.trendPrecisionKgPerWeek && arrival == null -> ForecastStatus.Stalled
                else -> ForecastStatus.Measured
            }
            val verdict = if (provisional) null else verdict(arrival, earliest, latest)
            // Palier seulement s'il tombe avant le poids visé (sinon la cible est atteinte avant, ou jamais dans ce sens).
            val plateau = (current + rate * tau).takeIf { rate * direction > 0 && (target.kg - it) * direction > 0 }
            val horizon = maxOf(daysToTarget, central?.roundToLong() ?: 0L).coerceIn(0L, EXPECTED_CURVE_MAX_DAYS)
            val expected = weeks(horizon).map { t ->
                val shape = h(t.toDouble(), tau)
                val a = current + low * shape
                val b = current + high * shape
                CurvePoint(asOf.plusDays(t), current + rate * shape, minOf(a, b), maxOf(a, b))
            }
            return ArrivalForecast(
                asOf = asOf, target = target, status = status, paceSource = source, verdict = verdict,
                currentKg = current, currentKgDay = asOf, arrival = arrival, earliest = earliest, latest = latest,
                paceKgPerWeek = rate * 7, paceLowKgPerWeek = low * 7, paceHighKgPerWeek = high * 7,
                paceAtArrivalKgPerWeek = central?.let { rate * exp(-it / tau) * 7 },
                requiredKgPerWeek = required(current), plateauKg = plateau,
                weighedWeeks = weighedWeeks, minWeighedWeeks = params.trendMinWeeks,
                expectedCurve = expected, onTimeCurve = onTime(current),
                measuredExpenditure = measured,
                assumptions = assumptions(status, source),
            )
        }

        private fun verdict(arrival: LocalDate?, earliest: LocalDate?, latest: LocalDate?): ArrivalVerdict = when {
            earliest == null || earliest.isAfter(target.date) -> ArrivalVerdict.Late
            latest != null && !latest.isAfter(target.date) -> ArrivalVerdict.Early
            arrival != null && !arrival.isAfter(target.date) -> ArrivalVerdict.LikelyOnTime
            else -> ArrivalVerdict.LikelyLate
        }

        private fun assumptions(status: ForecastStatus, source: PaceSource, stale: Boolean = false): List<String> = buildList {
            val p = params
            add(
                "Dépense de départ : ${int(p.referenceKcal)} kcal par jour à ${one(p.referenceWeightKg)} kg (ta référence, avec environ ${int(p.referenceSteps)} pas par jour " +
                    "et 3 séances par semaine), jamais mesurée : environ ± ${int(p.referenceUncertaintyKcal)} kcal.",
            )
            add(
                "Elle baisse d'environ ${int(p.expenditureKcalPerKg)} kcal par jour pour chaque kilo perdu (métabolisme et coût des pas plus bas). " +
                    "À repas égaux, la perte ralentit donc d'environ ${two(p.expenditureKcalPerKg * 7 / p.kcalPerKg)} kg par semaine pour chaque kilo perdu : " +
                    "les premiers kilos partent plus vite que les derniers. Ce ralentissement est prévu dans la date : ce n'est pas une alerte.",
            )
            add("Un kilo de poids correspond à environ ${int(p.kcalPerKg)} kcal. Le taux de masse grasse n'entre pas dans le calcul.")
            when (source) {
                PaceSource.WeighIns -> add(
                    "Rythme mesuré sur tes pesées des ${p.forecastWindowWeeks} dernières semaines ($weighedWeeks semaine(s) pesée(s)) : il corrige la référence. " +
                        "La marge vient du bruit des pesées (environ 95 %) ; elle se resserre avec plus de semaines pesées.",
                )
                PaceSource.MealsAndReference -> add(
                    "Moins de ${p.trendMinWeeks} semaines pesées : rythme théorique, dépense de départ moins la moyenne de tes repas comptés. Ce n'est pas une date ferme.",
                )
                PaceSource.CalorieGoalAndReference -> add(
                    "Moins de ${p.trendMinWeeks} semaines pesées et trop peu de repas comptés : rythme théorique, en supposant que tu manges ton objectif calorique. Ce n'est pas une date ferme.",
                )
                PaceSource.None -> add(
                    when {
                        status == ForecastStatus.NoWeighIn -> "Aucune pesée récente : pas de prévision."
                        stale -> "Dernière pesée trop ancienne (plus de ${p.forecastMaxWeighInAgeDays} jours) pour servir de départ : pas de date."
                        else -> "Pas assez de pesées ni de repas comptés pour estimer un rythme : pas de date."
                    },
                )
            }
            add("La date suppose que tu continues comme ces dernières semaines (mêmes repas, même activité).")
        }
    }

    private fun int(value: Double): String = value.roundToLong().toString().reversed().chunked(3).joinToString(" ").reversed()
    private fun one(value: Double): String = BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    private fun two(value: Double): String = BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    private const val REACHED_TOLERANCE_KG = 0.05
    private const val INTAKE_WINDOW_DAYS = 28L
    private const val EXPECTED_CURVE_MAX_DAYS = 366L
}
