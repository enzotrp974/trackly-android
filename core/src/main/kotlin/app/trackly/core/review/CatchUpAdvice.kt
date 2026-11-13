package app.trackly.core.review

import app.trackly.core.forecast.ArrivalVerdict
import app.trackly.core.forecast.ForecastStatus
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.goal.TemporaryGoals
import app.trackly.core.model.Goal
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Objectif temporaire proposé : [dropKcal] de moins par jour du [start] pendant [days] jours, puis retour à [baseKcal].
 * Ce que le bouton « Appliquer » applique, tel quel (`SetTemporaryGoal`).
 */
data class TemporaryPlan(
    val start: LocalDate,
    val days: Int,
    val dropKcal: Double,
    val baseKcal: Double,
) {
    val temporaryKcal: Double get() = baseKcal - dropKcal
    val end: LocalDate get() = start.plusDays(days - 1L)
    val returnOn: LocalDate get() = start.plusDays(days.toLong())

    /** Calories rattrapées sur toute la durée. */
    val totalKcal: Double get() = dropKcal * days
}

/** Le même effort en pas : [extraSteps] de plus par jour pendant [days] jours, soit environ [kcalPerDay], au poids [weightKg]. */
data class StepsOption(
    val extraSteps: Int,
    val kcalPerDay: Double,
    val days: Int,
    val weightKg: Double,
    /** Pas par jour d'où l'on part : la moyenne donnée pour la période, sinon la référence. */
    val fromSteps: Double,
    val fromKnown: Boolean,
)

/** Un mélange : la moitié de la baisse en calories ([plan]), l'autre moitié en pas ([steps]). */
data class MixOption(val plan: TemporaryPlan, val steps: StepsOption)

/** Pourquoi il n'y a aucun conseil : le modèle le reçoit pour ne rien proposer de lui-même. */
enum class NoAdviceReason {
    /** Arrivée à temps ou en avance, objectif atteint : tout va bien. */
    OnTrack,

    /** Pas de date ferme (pas assez de pesées, aucune pesée) : pas de quoi dire qu'il faut rattraper. */
    NoFirmDate,

    /** La date visée est passée : rattraper quelques centaines de kcal n'a plus de sens, c'est la date qu'il faut revoir. */
    TargetDatePassed,

    /** Objectif de prise de poids : ces conseils ne visent que la perte. */
    GainGoal,
}

sealed interface CatchUpResult {
    data class None(val reason: NoAdviceReason) : CatchUpResult

    /**
     * En retard sur la date visée. [overshootKcal] : calories au-dessus des objectifs de la période, jours comptés seulement
     * (`null` ou trop petit : pas de baisse ni de pas à proposer, seulement la date). [plan] manque quand l'objectif ne peut
     * pas baisser (pas d'objectif, un objectif déjà prévu sur ces jours, un objectif temporaire en cours).
     */
    data class Advice(
        val overshootKcal: Double?,
        val countedDays: Int,
        val plan: TemporaryPlan?,
        val steps: StepsOption?,
        val mix: MixOption?,
        /** Garder son rythme et viser la date d'arrivée prévue (« Changer la date ») ; `null` sans date d'arrivée. */
        val newDate: LocalDate?,
        val targetKg: Double,
        /** Pourquoi [plan] manque, en une phrase, ou `null`. */
        val planBlocked: String? = null,
        /** Un objectif temporaire est déjà prévu ou en cours : rien d'autre à rattraper (ni baisse, ni pas), seulement la date. */
        val inProgress: Boolean = false,
    ) : CatchUpResult
}

/**
 * Conseils chiffrés du bilan, **calculés ici et jamais par l'IA** : elle choisit et commente, elle n'invente aucun chiffre.
 *
 * Un conseil n'existe que si la prévision d'arrivée (ralentissement attendu compris) dit que la date visée sera
 * manquée : mesurée et en retard (« plutôt en retard » ou « en retard »), ou poids stable, ou poids qui s'éloigne. Un
 * ralentissement normal ne donne donc **jamais** de conseil : il est déjà dans la date. Et une baisse ou des pas ne sont
 * proposés que pour rattraper un **dépassement** réel des objectifs sur la période (« tu as dépassé de 400 kcal »), jamais
 * pour compenser un rythme : sans dépassement, il reste seulement « Changer la date ».
 */
object CatchUpAdvices {
    /** En dessous, le dépassement de la période ne vaut pas un objectif temporaire. */
    const val MIN_OVERSHOOT_KCAL = 200.0

    /** Baisse visée par jour quand l'IA ne choisit pas la durée : 100 kcal par jour, un effort qui reste confortable. */
    const val COMFORT_DROP_KCAL = 100.0

    /** Durée minimale proposée d'office : un seul jour à −200 se sent plus que deux à −100. */
    const val DEFAULT_MIN_DAYS = 2

    /** Pas : arrondis à cette tranche ; au-delà de [MAX_EXTRA_STEPS] par jour, le conseil en pas n'est pas réaliste. */
    const val STEPS_ROUNDING = 500
    const val MAX_EXTRA_STEPS = 8_000

    /**
     * @param goals version courante de chaque objectif daté (comme `GoalVersions.allCurrent`)
     * @param today jour de suivi en cours : l'objectif temporaire commence le lendemain
     * @param days durée choisie (chat du bilan), bornée par [TemporaryGoals.MIN_DAYS]..[TemporaryGoals.MAX_DAYS] ; `null` : d'office
     * @param inProgress un objectif temporaire est déjà prévu ou en cours (`TemporaryGoals.inEffect`)
     */
    fun compute(figures: WeeklyFigures, goals: Collection<Goal>, today: LocalDate, days: Int? = null, inProgress: Boolean = false): CatchUpResult {
        val p = figures.forecast
        if (!p.target.losing) return CatchUpResult.None(NoAdviceReason.GainGoal)
        if (p.targetDatePassed) return CatchUpResult.None(NoAdviceReason.TargetDatePassed)
        val late = when (p.status) {
            ForecastStatus.Measured -> p.verdict == ArrivalVerdict.LikelyLate || p.verdict == ArrivalVerdict.Late
            ForecastStatus.Stalled, ForecastStatus.MovingAway -> true
            ForecastStatus.Reached -> return CatchUpResult.None(NoAdviceReason.OnTrack)
            ForecastStatus.NoWeighIn, ForecastStatus.Provisional -> return CatchUpResult.None(NoAdviceReason.NoFirmDate)
        }
        if (!late) return CatchUpResult.None(NoAdviceReason.OnTrack)

        val newDate = if (p.status == ForecastStatus.Measured) p.arrival?.takeIf { it.isAfter(p.target.date) } else null
        val overshoot = overshoot(figures)
        val countedDays = figures.daily.count { it.counted && it.kcal != null && it.goalKcal != null }
        if (inProgress) {
            // Déjà appliqué : le dépassement de la période reste le même, le proposer encore ferait rattraper deux fois.
            return CatchUpResult.Advice(overshoot, countedDays, null, null, null, newDate, p.target.kg, "un objectif temporaire est déjà prévu ou en cours", inProgress = true)
        }
        if (overshoot == null || overshoot < MIN_OVERSHOOT_KCAL) {
            return CatchUpResult.Advice(overshoot, countedDays, null, null, null, newDate, p.target.kg)
        }
        val start = today.plusDays(1)
        val base = GoalTimeline.inForce(start, goals)?.targets
        val (plan, blocked) = when {
            base == null -> null to "aucun objectif calorique en vigueur"
            else -> {
                val clash = GoalTimeline.effective(goals).firstOrNull { it.effectiveFrom.isAfter(start) }
                val built = plan(overshoot, base.kcal, start, days)
                when {
                    built == null -> null to "l'objectif ne peut pas baisser davantage sans passer sous ${TemporaryGoals.MIN_KCAL.roundToInt()} kcal"
                    clash != null && !clash.effectiveFrom.isAfter(built.returnOn) -> null to "un objectif est déjà prévu le ${clash.effectiveFrom}"
                    else -> built to null
                }
            }
        }
        val weight = p.currentKg ?: figures.expenditure.weightKg.takeIf { figures.expenditure.weightBasis != WeightBasis.Default }
        val stepsDays = plan?.days ?: defaultDays(overshoot, base?.kcal)
        val stepsKcal = plan?.dropKcal ?: (overshoot / stepsDays)
        val steps = weight?.let { steps(stepsKcal, stepsDays, it, figures.activity, figures.params) }
        val mix = if (plan != null && weight != null) mix(plan, weight, figures.activity, figures.params) else null
        return CatchUpResult.Advice(overshoot, countedDays, plan, steps, mix, newDate, p.target.kg, blocked)
    }

    /** Calories au-dessus des objectifs de la période, sur les jours comptés qui ont un objectif ; `null` sans aucun tel jour. */
    fun overshoot(figures: WeeklyFigures): Double? {
        val days = figures.daily.filter { it.counted && it.kcal != null && it.goalKcal != null }
        if (days.isEmpty()) return null
        return days.sumOf { it.kcal!! - it.goalKcal!! }
    }

    /**
     * Le plan qui rattrape [overshoot] : par défaut autant de jours qu'il faut pour rester vers [COMFORT_DROP_KCAL] par jour
     * (de [DEFAULT_MIN_DAYS] à [TemporaryGoals.MAX_DAYS]), baisse arrondie à 10 kcal et bornée par [TemporaryGoals.maxDrop]
     * (un gros dépassement n'est alors rattrapé qu'en partie). `null` si l'objectif ne peut pas baisser.
     */
    fun plan(overshoot: Double, baseKcal: Double, start: LocalDate, days: Int? = null): TemporaryPlan? {
        val max = min(TemporaryGoals.maxDrop(baseKcal), baseKcal - TemporaryGoals.MIN_KCAL)
        if (max < TemporaryGoals.MIN_DROP_KCAL) return null
        val n = days?.coerceIn(TemporaryGoals.MIN_DAYS, TemporaryGoals.MAX_DAYS) ?: defaultDays(overshoot, baseKcal)
        val drop = (Math.round(overshoot / n / 10.0) * 10.0).coerceIn(TemporaryGoals.MIN_DROP_KCAL, floorTen(max))
        return TemporaryPlan(start, n, drop, baseKcal)
    }

    private fun defaultDays(overshoot: Double, baseKcal: Double?): Int {
        val perDay = baseKcal?.let { min(COMFORT_DROP_KCAL, TemporaryGoals.maxDrop(it)) } ?: COMFORT_DROP_KCAL
        return ceil(overshoot / perDay).toInt().coerceIn(DEFAULT_MIN_DAYS, TemporaryGoals.MAX_DAYS)
    }

    private fun floorTen(value: Double) = kotlin.math.floor(value / 10.0) * 10.0

    /**
     * Pas en plus par jour pour dépenser environ [kcalPerDay] de plus, **au poids [weightKg]**, avec le coût des pas du bilan
     * (`EnergyModel` : 0,5 kcal par kg et par 1 000 pas, la moitié seulement au-dessus de la référence). `null` au-delà de
     * [MAX_EXTRA_STEPS] par jour.
     */
    fun steps(kcalPerDay: Double, days: Int, weightKg: Double, activity: Activity, params: ReviewParams): StepsOption? {
        val from = activity.averageSteps ?: params.referenceSteps
        val needed = StepsCost.extraStepsFor(kcalPerDay, from, weightKg, params)
        val rounded = max(STEPS_ROUNDING, (Math.round(needed / STEPS_ROUNDING) * STEPS_ROUNDING).toInt())
        if (rounded > MAX_EXTRA_STEPS) return null
        return StepsOption(rounded, StepsCost.kcalFor(rounded.toDouble(), from, weightKg, params), days, weightKg, from, activity.averageSteps != null)
    }

    /** La moitié de la baisse en calories (arrondie à 10), le reste en pas. */
    private fun mix(plan: TemporaryPlan, weightKg: Double, activity: Activity, params: ReviewParams): MixOption? {
        val half = Math.round(plan.dropKcal / 2.0 / 10.0) * 10.0
        if (half < TemporaryGoals.MIN_DROP_KCAL) return null
        val steps = steps(plan.dropKcal - half, plan.days, weightKg, activity, params) ?: return null
        return MixOption(plan.copy(dropKcal = half), steps)
    }
}

/** Coût des pas du bilan ([EnergyModel.stepsAdjustment]), lu dans les deux sens : kcal pour des pas, pas pour des kcal. */
object StepsCost {
    /** kcal par jour dépensées en plus en passant de [fromSteps] à [fromSteps] + [extraSteps] pas par jour, au poids [weightKg]. */
    fun kcalFor(extraSteps: Double, fromSteps: Double, weightKg: Double, params: ReviewParams): Double =
        EnergyModel.stepsAdjustment(fromSteps + extraSteps, weightKg, params) - EnergyModel.stepsAdjustment(fromSteps, weightKg, params)

    /** Pas en plus par jour pour dépenser [kcal] de plus : l'inverse de [kcalFor], en deux morceaux autour de la référence. */
    fun extraStepsFor(kcal: Double, fromSteps: Double, weightKg: Double, params: ReviewParams): Double {
        val perStepBelow = params.kcalPerKgPer1000Steps * weightKg / 1000.0
        val perStepAbove = perStepBelow * params.stepsAboveReferenceShare
        val belowRoom = max(0.0, params.referenceSteps - fromSteps)
        val belowKcal = belowRoom * perStepBelow
        return if (kcal <= belowKcal) kcal / perStepBelow else belowRoom + (kcal - belowKcal) / perStepAbove
    }
}
