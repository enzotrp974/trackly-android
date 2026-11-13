package app.trackly.core.review

import app.trackly.core.forecast.ArrivalForecast
import app.trackly.core.forecast.ArrivalForecasts
import app.trackly.core.goal.WeightTarget
import app.trackly.core.summary.DayCoverage
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.EnergyStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Information qui manque pour que le bilan soit complet : signalée dans le rapport, jamais comblée. */
enum class MissingCode { NoIntakeDays, UntrackedDays, BoundedDays, MacrosUnknown, NoGoal, NoWeighIn, FewWeighIns, StepsUnknown, StepsPartial, SessionsUnknown }

data class MissingInfo(val code: MissingCode, val message: String)

data class BoundedDay(val day: LocalDate, val kcalMin: Double)

/**
 * Couverture de la semaine, comme `energy_balance_summary` du moteur Python : seuls les jours
 * complets ou estimés ([counted]) entrent dans les moyennes ; une borne minimale, un jour partiel
 * ou manquant n'est jamais extrapolé.
 */
data class WeekCoverage(
    val counted: List<LocalDate>,
    /** Parmi [counted], jours d'estimation globale validée (macros inconnues). */
    val estimated: List<LocalDate>,
    val bounded: List<BoundedDay>,
    val partial: List<LocalDate>,
    val missing: List<LocalDate>,
) {
    val countedDays: Int get() = counted.size
}

/** Calories et macros moyennes par jour suivi. Macros : jours au détail complet seulement ([macroDays]). */
data class Intake(
    val averageKcal: Double?,
    val averageProteinG: Double?,
    val averageCarbsG: Double?,
    val averageFatG: Double?,
    val macroDays: Int,
)

data class MacroGoal(val averageTargetG: Double?, val averageDifferenceG: Double?)

/**
 * Écart à l'objectif **en vigueur chaque jour** (un objectif changé en cours de semaine est pris
 * tel qu'il valait ce jour-là). Positif : au-dessus de l'objectif.
 */
data class GoalComparison(
    val daysWithGoal: Int,
    val averageTargetKcal: Double?,
    val averageDifferenceKcal: Double?,
    /** L'objectif n'est pas le même sur tous les jours comparés. */
    val goalChanged: Boolean,
    val protein: MacroGoal,
    val carbs: MacroGoal,
    val fat: MacroGoal,
    /**
     * Objectif calorique moyen des jours **de la semaine** qui en avaient un, qu'ils soient comptés ou non.
     * Sert à dire « un objectif existait, mais aucun jour complet n'est comparable » plutôt que « aucun objectif ».
     */
    val weekAverageTargetKcal: Double? = null,
)

/** Cumul depuis le premier jour suivi jusqu'au dimanche de la semaine (dépense de référence non ajustée, comme le moteur Python). */
data class Cumulative(
    val from: LocalDate?,
    val to: LocalDate,
    val countedDays: Int,
    val partialDays: Int,
    val boundedDays: Int,
    val missingDays: Int,
    val averageKcal: Double?,
    /** Somme, sur les jours comptés ayant un objectif, de (calories − objectif). */
    val differenceToGoalKcal: Double?,
    val theoreticalDeficitKcal: Double?,
    val theoreticalLossKg: Double?,
)

enum class StepsSource { PerDay, Average, Unknown }

/** Pas et séances déclarés pour la semaine. */
data class Activity(
    val averageSteps: Double?,
    val stepsSource: StepsSource,
    val stepsDays: Int,
    /** `null` : séances non fournies. */
    val sessions: List<SessionCount>?,
    /** Pas jour par jour quand l'utilisateur les a donnés ([StepsSource.PerDay]), sinon vide : de quoi dessiner, jamais complété. */
    val stepsByDay: List<DaySteps> = emptyList(),
) {
    val sessionsTotal: Int? get() = sessions?.sumOf { it.count }
}

/**
 * Dépense estimée par jour : la référence de l'utilisateur, ajustée selon les pas et séances
 * **fournis** (un élément manquant n'ajuste rien), avec sa fourchette d'incertitude. [assumptions] est
 * affiché tel quel dans le rapport. Formule et sources : [EnergyModel] et [ReviewParams].
 */
data class Expenditure(
    val referenceKcal: Double,
    val stepsAdjustmentKcal: Double?,
    val sessionsAdjustmentKcal: Double?,
    val kcalPerDay: Double,
    /** Demi-largeur de la fourchette, kcal par jour. */
    val uncertaintyKcal: Double,
    /** Poids utilisé pour le coût des pas (voir [weightBasis] : jamais présenté comme un poids mesuré s'il vient d'une valeur par défaut). */
    val weightKg: Double,
    val weightBasis: WeightBasis,
    val assumptions: List<String>,
) {
    val adjusted: Boolean get() = stepsAdjustmentKcal != null || sessionsAdjustmentKcal != null
    val lowKcal: Double get() = kcalPerDay - uncertaintyKcal
    val highKcal: Double get() = kcalPerDay + uncertaintyKcal
}

/** Bilan énergétique théorique de la semaine (jours comptés seulement) ; `null` si une borne minimale empêche le calcul. */
data class EnergyBalance(
    /** Dépense estimée moins calories consommées, sur les jours comptés. Positif : déficit. */
    val deficitKcal: Double,
    /** Variation de poids théorique, en kg (négatif : perte). Ce n'est pas une mesure. */
    val theoreticalChangeKg: Double,
    /** Demi-largeur de la fourchette du déficit, kcal (celle de la dépense x nombre de jours). */
    val deficitUncertaintyKcal: Double = 0.0,
    /** Demi-largeur de la fourchette de la variation théorique, kg. */
    val changeUncertaintyKg: Double = 0.0,
)

/**
 * Un jour de la période, tel qu'il est : de quoi dessiner les petits graphiques du bilan sans rien recalculer. Les valeurs
 * inconnues restent `null` (jamais 0) ; seuls les jours comptés ([WeekCoverage.counted]) ont des calories.
 */
data class DayFigure(
    val day: LocalDate,
    val counted: Boolean,
    val kcal: Double?,
    val goalKcal: Double?,
    /** Macros du jour : `null` si le jour n'est pas au détail complet. */
    val proteinG: Double?,
    val fatG: Double?,
    val proteinGoalG: Double?,
    val proteinMinG: Double?,
    val fatGoalG: Double?,
)

enum class TrajectoryStatus {
    /** Aucune pesée cette semaine : trajectoire inconnue. */
    Unknown,
    Reached,
    PassedDate,
    /** Pas assez de semaines pesées, ou fourchette trop large : l'écart au rythme nécessaire ne dépasse pas le bruit. */
    TooEarly,
    /** L'écart au rythme nécessaire est dans le bruit et la fourchette est assez étroite pour le dire. */
    OnTrack,
    /** Le rythme nécessaire est hors de la fourchette de la tendance, du côté d'un rythme plus lent. */
    Slower,
    /** Idem, du côté d'un rythme plus rapide. */
    Faster,
}

/** Fiabilité du poids de la semaine, selon le nombre de pesées. */
enum class WeighInReliability { None, Single, Few, Enough }

/** Trajectoire vers le poids visé. Rythmes en kg par semaine (négatif : perte). */
data class Trajectory(
    val targetKg: Double,
    val targetDate: LocalDate,
    val weighInCount: Int,
    /** Poids de la semaine : moyenne des pesées déclarées, jamais une pesée isolée prise pour le poids. */
    val weekAverageKg: Double?,
    val reliability: WeighInReliability,
    val weeksRemaining: Double,
    val requiredKgPerWeek: Double?,
    /** Tendance sur les dernières semaines pesées, avec fourchette ; `null` si trop peu de semaines pesées. */
    val trend: WeightTrend?,
    /** Semaines pesées parmi la fenêtre de tendance (la semaine du bilan comprise). */
    val weighedWeeks: Int,
    val trendMinWeeks: Int,
    /** Rythme déduit du bilan énergétique théorique : indicatif, jamais utilisé pour le statut. */
    val theoreticalKgPerWeek: Double?,
    val status: TrajectoryStatus,
)

/**
 * Chiffres de la semaine, **tous calculés ici** : le modèle les reçoit et les commente, il ne les
 * recalcule jamais. Pur et déterministe (voir [WeeklyReview.compute]).
 */
data class WeeklyFigures(
    val range: WeekRange,
    val coverage: WeekCoverage,
    val intake: Intake,
    val goal: GoalComparison,
    val cumulative: Cumulative,
    val activity: Activity,
    val expenditure: Expenditure,
    val balance: EnergyBalance?,
    val trajectory: Trajectory,
    val weighIns: List<WeighIn>,
    val missing: List<MissingInfo>,
    val params: ReviewParams,
    /** Prévision d'arrivée au dimanche de la semaine : date estimée, marge, courbe attendue. */
    val forecast: ArrivalForecast,
    /** Un élément par jour de la période, dans l'ordre. */
    val daily: List<DayFigure> = emptyList(),
    /**
     * Pesées connues, des dernières semaines jusqu'à la fin de la période (le plus ancien d'abord) : le fil de la courbe du poids.
     * Seules les pesées dites ou reprises, jamais complétées.
     */
    val weightHistory: List<WeighIn> = emptyList(),
    /** La veille du premier jour : la première pesée de la période se lit avec elle. `null` sans journée ce jour-là. */
    val eve: DayFigure? = null,
)

object WeeklyReview {
    /**
     * @param days bilans des jours depuis le premier jour suivi jusqu'au dimanche de [range] (au moins la semaine)
     * @param info informations déclarées pour la semaine (version courante active), ou `null`
     * @param weights toutes les pesées connues (reprises et déclarées)
     * @param target objectif de poids en vigueur (enregistré, sinon celui par défaut)
     */
    fun compute(
        range: WeekRange,
        days: List<DaySummary>,
        info: WeekInfo?,
        weights: WeightHistory,
        params: ReviewParams = ReviewParams.Default,
        target: WeightTarget = WeightTarget.Default,
    ): WeeklyFigures {
        val byDay = days.associateBy { it.day }
        val week = range.days.map { byDay[it] }
        val coverage = coverage(range, week)
        val counted = week.filterNotNull().filter { it.day in coverage.counted && it.energy.kcal != null }

        val intake = intake(counted)
        val goal = goalComparison(counted, week.filterNotNull())
        val cumulative = cumulative(range, days, params)
        val activity = activity(info)
        val (weightKg, weightBasis) = workingWeight(range, weights, params)
        val expenditure = EnergyModel.expenditure(activity, weightKg, weightBasis, params, range.days.size)

        val noBound = coverage.bounded.isEmpty()
        val balance = if (counted.isNotEmpty() && noBound && intake.averageKcal != null) {
            val deficit = (expenditure.kcalPerDay - intake.averageKcal) * counted.size
            val uncertainty = expenditure.uncertaintyKcal * counted.size
            EnergyBalance(deficit, -deficit / params.kcalPerKg, uncertainty, uncertainty / params.kcalPerKg)
        } else {
            null
        }
        val trajectory = trajectory(range, weights, balance, counted.size, params, target)
        val missing = missing(coverage, intake, goal, trajectory, activity, counted.size, range.days.size)
        val forecast = ArrivalForecasts.compute(range.end, target, weights, days, params)
        val since = range.end.minusWeeks((params.forecastWindowWeeks + 2).toLong())
        return WeeklyFigures(
            range, coverage, intake, goal, cumulative, activity, expenditure, balance, trajectory, weights.within(range), missing, params, forecast,
            daily = range.days.mapIndexed { index, day -> dayFigure(day, week[index], day in coverage.counted) },
            weightHistory = weights.all.filter { !it.day.isAfter(range.end) && !it.day.isBefore(since) },
            eve = range.start.minusDays(1).let { day ->
                byDay[day]?.let { dayFigure(day, it, it.coverage == DayCoverage.Complete || it.coverage == DayCoverage.Estimated) }
            },
        )
    }

    /** [countable] : le jour entre dans les moyennes (complet ou estimé). */
    private fun dayFigure(day: LocalDate, summary: DaySummary?, countable: Boolean): DayFigure {
        val counted = countable && summary?.energy?.kcal != null
        val detailed = counted && summary!!.energy.status == EnergyStatus.Detailed &&
            summary.totals.protein.value != null && summary.totals.carbs.value != null && summary.totals.fat.value != null
        return DayFigure(
            day = day,
            counted = counted,
            kcal = if (counted) summary!!.energy.kcal else null,
            goalKcal = summary?.goal?.kcal,
            proteinG = if (detailed) summary!!.totals.protein.known else null,
            fatG = if (detailed) summary!!.totals.fat.known else null,
            proteinGoalG = summary?.goal?.proteinG,
            proteinMinG = summary?.goal?.proteinMinG,
            fatGoalG = summary?.goal?.fatG,
        )
    }

    private fun coverage(range: WeekRange, week: List<DaySummary?>): WeekCoverage {
        val counted = mutableListOf<LocalDate>()
        val estimated = mutableListOf<LocalDate>()
        val bounded = mutableListOf<BoundedDay>()
        val partial = mutableListOf<LocalDate>()
        val missing = mutableListOf<LocalDate>()
        for ((index, summary) in week.withIndex()) {
            val day = range.start.plusDays(index.toLong())
            when (summary?.coverage ?: DayCoverage.Missing) {
                DayCoverage.Complete -> counted += day
                DayCoverage.Estimated -> {
                    counted += day
                    estimated += day
                }
                DayCoverage.Bounded -> bounded += BoundedDay(day, summary!!.energy.kcalLow)
                DayCoverage.Partial -> partial += day
                DayCoverage.Missing -> missing += day
            }
        }
        return WeekCoverage(counted, estimated, bounded, partial, missing)
    }

    private fun intake(counted: List<DaySummary>): Intake {
        if (counted.isEmpty()) return Intake(null, null, null, null, 0)
        val withMacros = counted.filter {
            it.energy.status == EnergyStatus.Detailed &&
                it.totals.protein.value != null && it.totals.carbs.value != null && it.totals.fat.value != null
        }
        fun average(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
        return Intake(
            averageKcal = average(counted.map { it.energy.kcal!! }),
            averageProteinG = average(withMacros.map { it.totals.protein.known }),
            averageCarbsG = average(withMacros.map { it.totals.carbs.known }),
            averageFatG = average(withMacros.map { it.totals.fat.known }),
            macroDays = withMacros.size,
        )
    }

    private fun goalComparison(counted: List<DaySummary>, weekDays: List<DaySummary>): GoalComparison {
        val withGoal = counted.filter { it.goal != null }
        fun average(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }

        fun macro(target: (DaySummary) -> Double?, actual: (DaySummary) -> Double): MacroGoal {
            val days = counted.filter { d ->
                d.energy.status == EnergyStatus.Detailed && target(d) != null &&
                    d.totals.protein.value != null && d.totals.carbs.value != null && d.totals.fat.value != null
            }
            return MacroGoal(average(days.map { target(it)!! }), average(days.map { actual(it) - target(it)!! }))
        }
        return GoalComparison(
            daysWithGoal = withGoal.size,
            averageTargetKcal = average(withGoal.map { it.goal!!.kcal }),
            averageDifferenceKcal = average(withGoal.map { it.energy.kcal!! - it.goal!!.kcal }),
            goalChanged = withGoal.map { it.goal }.distinct().size > 1,
            protein = macro({ it.goal?.proteinG }, { it.totals.protein.known }),
            carbs = macro({ it.goal?.carbsG }, { it.totals.carbs.known }),
            fat = macro({ it.goal?.fatG }, { it.totals.fat.known }),
            weekAverageTargetKcal = average(weekDays.mapNotNull { it.goal?.kcal }),
        )
    }

    private fun cumulative(range: WeekRange, days: List<DaySummary>, params: ReviewParams): Cumulative {
        val upToEnd = days.filter { !it.day.isAfter(range.end) }.sortedBy { it.day }
        val first = upToEnd.firstOrNull { it.coverage != DayCoverage.Missing }?.day
        val window = if (first == null) emptyList() else upToEnd.filter { !it.day.isBefore(first) }
        val counted = window.filter { (it.coverage == DayCoverage.Complete || it.coverage == DayCoverage.Estimated) && it.energy.kcal != null }
        val bounded = window.count { it.coverage == DayCoverage.Bounded }
        val consumed = counted.sumOf { it.energy.kcal!! }
        val withGoal = counted.filter { it.goal != null }
        val deficit = if (counted.isNotEmpty() && bounded == 0) params.referenceKcal * counted.size - consumed else null
        val windowLength = if (first == null) 0 else (ChronoUnit.DAYS.between(first, range.end) + 1).toInt()
        val partial = window.count { it.coverage == DayCoverage.Partial }
        return Cumulative(
            from = first,
            to = range.end,
            countedDays = counted.size,
            partialDays = partial,
            boundedDays = bounded,
            // Comme Python : jours de la fenêtre ni comptés, ni partiels, ni à borne minimale.
            missingDays = windowLength - counted.size - partial - bounded,
            averageKcal = counted.takeIf { it.isNotEmpty() }?.let { consumed / it.size },
            differenceToGoalKcal = withGoal.takeIf { it.isNotEmpty() }?.sumOf { it.energy.kcal!! - it.goal!!.kcal },
            theoreticalDeficitKcal = deficit,
            theoreticalLossKg = deficit?.let { it / params.kcalPerKg },
        )
    }

    private fun activity(info: WeekInfo?): Activity {
        if (info == null) return Activity(null, StepsSource.Unknown, 0, null)
        return when {
            info.steps.isNotEmpty() ->
                Activity(info.steps.sumOf { it.steps }.toDouble() / info.steps.size, StepsSource.PerDay, info.steps.size, info.sessions, info.steps.sortedBy { it.day })
            info.stepsAverage != null -> Activity(info.stepsAverage.toDouble(), StepsSource.Average, 0, info.sessions)
            else -> Activity(null, StepsSource.Unknown, 0, info.sessions)
        }
    }

    /** Poids pour le coût des pas : moyenne de la semaine, sinon dernière pesée connue, sinon valeur de travail. */
    private fun workingWeight(range: WeekRange, weights: WeightHistory, params: ReviewParams): Pair<Double, WeightBasis> {
        weights.average(range)?.let { return it to WeightBasis.WeekAverage }
        weights.lastUpTo(range.end)?.let { return it.kg to WeightBasis.LastKnown }
        return params.defaultWeightKg to WeightBasis.Default
    }

    private fun trajectory(range: WeekRange, weights: WeightHistory, balance: EnergyBalance?, counted: Int, params: ReviewParams, target: WeightTarget): Trajectory {
        val average = weights.average(range)
        val count = weights.within(range).size
        val weeksRemaining = ChronoUnit.DAYS.between(range.end, target.date) / 7.0
        val weeks = weights.weeklyMeans(range, params.trendWindowWeeks)
        val trend = if (average == null) null else WeightTrends.fit(weeks, params)
        val theoretical = balance?.let { it.theoreticalChangeKg / counted * 7 }
        val difference = average?.let { target.kg - it }
        val required = if (difference != null && weeksRemaining > 0) difference / weeksRemaining else null
        val status = when {
            average == null -> TrajectoryStatus.Unknown
            abs(difference!!) < REACHED_TOLERANCE_KG -> TrajectoryStatus.Reached
            weeksRemaining <= 0 -> TrajectoryStatus.PassedDate
            trend == null -> TrajectoryStatus.TooEarly
            else -> compare(trend, required!!, params)
        }
        return Trajectory(
            targetKg = target.kg,
            targetDate = target.date,
            weighInCount = count,
            weekAverageKg = average,
            reliability = when {
                count == 0 -> WeighInReliability.None
                count == 1 -> WeighInReliability.Single
                count < params.reliableWeighIns -> WeighInReliability.Few
                else -> WeighInReliability.Enough
            },
            weeksRemaining = weeksRemaining,
            requiredKgPerWeek = required,
            trend = trend,
            weighedWeeks = weeks.size,
            trendMinWeeks = params.trendMinWeeks,
            theoreticalKgPerWeek = theoretical,
            status = status,
        )
    }

    /**
     * Compare la tendance au rythme nécessaire, dans le sens du chemin à parcourir. L'écart n'est une
     * conclusion que s'il dépasse le bruit (la demi-largeur de la fourchette) ; sinon « dans le rythme » si la
     * fourchette est assez étroite pour l'affirmer, et « trop tôt pour conclure » dans le cas contraire.
     */
    private fun compare(trend: WeightTrend, required: Double, params: ReviewParams): TrajectoryStatus {
        val gap = (trend.slopeKgPerWeek - required) * Math.signum(required) // positif : plus rapide que nécessaire
        val noise = trend.halfWidthKgPerWeek
        return when {
            gap > noise -> TrajectoryStatus.Faster
            gap < -noise -> TrajectoryStatus.Slower
            noise <= params.trendPrecisionKgPerWeek -> TrajectoryStatus.OnTrack
            else -> TrajectoryStatus.TooEarly
        }
    }

    private fun missing(
        coverage: WeekCoverage,
        intake: Intake,
        goal: GoalComparison,
        trajectory: Trajectory,
        activity: Activity,
        counted: Int,
        periodDays: Int,
    ): List<MissingInfo> = buildList {
        if (counted == 0) add(MissingInfo(MissingCode.NoIntakeDays, "Aucun jour complet cette semaine : pas de moyenne de calories ni de bilan énergétique."))
        val untracked = coverage.partial.size + coverage.missing.size
        if (untracked > 0 && counted > 0) {
            add(MissingInfo(MissingCode.UntrackedDays, "$untracked jour(s) sans suivi complet (partiels ou manquants) : exclus des moyennes, sans extrapolation."))
        }
        if (coverage.bounded.isNotEmpty()) {
            add(MissingInfo(MissingCode.BoundedDays, "${coverage.bounded.size} jour(s) connus seulement par un minimum : le bilan énergétique n'est pas calculé."))
        }
        if (counted > 0 && intake.macroDays < counted) {
            add(MissingInfo(MissingCode.MacrosUnknown, "Macros connues sur ${intake.macroDays} jour(s) sur $counted (les estimations globales n'ont pas de macros)."))
        }
        if (counted > 0 && goal.daysWithGoal == 0) add(MissingInfo(MissingCode.NoGoal, "Aucun objectif calorique en vigueur : écart à l'objectif indisponible."))
        if (trajectory.weighInCount == 0) add(MissingInfo(MissingCode.NoWeighIn, "Aucune pesée cette semaine : trajectoire inconnue cette semaine."))
        when (trajectory.reliability) {
            WeighInReliability.Single -> add(MissingInfo(MissingCode.FewWeighIns, "Une seule pesée cette semaine : le poids de la semaine est peu fiable (une pesée isolée varie de 0,5 à 1 kg d'un jour à l'autre)."))
            WeighInReliability.Few -> add(MissingInfo(MissingCode.FewWeighIns, "${trajectory.weighInCount} pesées cette semaine : la moyenne est indicative, au moins 3 pesées du matin la rendent plus fiable."))
            else -> Unit
        }
        when (activity.stepsSource) {
            StepsSource.Unknown -> add(MissingInfo(MissingCode.StepsUnknown, "Pas non fournis : la dépense n'est pas ajustée selon les pas."))
            StepsSource.PerDay -> if (activity.stepsDays < periodDays) {
                add(MissingInfo(MissingCode.StepsPartial, "Pas fournis pour ${activity.stepsDays} jour(s) sur $periodDays : moyenne sur ces jours seulement."))
            }
            StepsSource.Average -> Unit
        }
        if (activity.sessions == null) add(MissingInfo(MissingCode.SessionsUnknown, "Séances non fournies : la dépense n'est pas ajustée selon les séances."))
    }

    private const val REACHED_TOLERANCE_KG = 0.05
}
