package app.trackly.core.review

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.sqrt

/** D'où vient le poids utilisé pour le coût des pas. */
enum class WeightBasis { WeekAverage, LastKnown, Default }

/** Une séance se range selon l'énergie qu'elle ajoute aux pas. */
enum class SessionKind {
    /** Musculation, renforcement : peu de pas, l'énergie s'ajoute. */
    Strength,
    /** Course, marche sportive : les pas les comptent déjà, rien ne s'ajoute. */
    OnFoot,
    /** Autre type ou type inconnu : compté comme une séance de musculation, signalé. */
    Other,
}

object SessionKinds {
    private val STRENGTH = listOf("muscu", "renfo", "force", "crossfit", "haltero", "fonte", "gym", "pompe", "circuit", "hiit", "poids")
    private val ON_FOOT = listOf("cours", "course", "running", "run", "footing", "jogging", "jog", "trail", "marche", "rando", "walk", "walking", "hiking")

    fun of(type: String): SessionKind {
        val words = Normalizer.normalize(type.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return when {
            words.any { w -> ON_FOOT.any { w == it || (it.length > 4 && w.startsWith(it)) } } -> SessionKind.OnFoot
            words.any { w -> STRENGTH.any { w.startsWith(it) } } -> SessionKind.Strength
            else -> SessionKind.Other
        }
    }
}

/**
 * Dépense par jour, ancrée sur la référence de l'utilisateur :
 *
 * ```
 * dépense = référence                                                       (3 000 kcal pour 20 000 pas et 3,5 séances)
 *         + 0,5 x poids x (pas - 20 000) / 1 000     (au-dessus de 20 000, moitié seulement)
 *         + 250 x (séances qui ajoutent de l'énergie - 3,5) / 7
 * fourchette = dépense +- racine(300^2 + (25 % des pas)^2 + (100 x |séances - 3,5| / 7)^2)
 * ```
 *
 * Sources et justification : [ReviewParams]. Un élément non fourni n'ajuste rien.
 */
internal object EnergyModel {
    /** [periodDays] : durée de la période du bilan ; les séances données pour elle sont ramenées à la semaine. */
    fun expenditure(activity: Activity, weightKg: Double, basis: WeightBasis, params: ReviewParams, periodDays: Int = 7): Expenditure {
        val stepsKnown = activity.averageSteps != null
        val steps = activity.averageSteps?.let { stepsAdjustment(it, weightKg, params) }
        val countedSessions = activity.sessions?.let { sessions ->
            sessions.filter { s -> !stepsKnown || SessionKinds.of(s.type) != SessionKind.OnFoot }.sumOf { it.count } * 7.0 / periodDays
        }
        val sessions = countedSessions?.let { (it - params.referenceSessionsPerWeek) * params.kcalPerSession / 7.0 }

        val stepsUncertainty = steps?.let { abs(it) * params.stepsUncertaintyShare } ?: 0.0
        val sessionsUncertainty = countedSessions?.let { abs(it - params.referenceSessionsPerWeek) * params.sessionUncertaintyKcal / 7.0 } ?: 0.0
        val uncertainty = sqrt(
            params.referenceUncertaintyKcal * params.referenceUncertaintyKcal +
                stepsUncertainty * stepsUncertainty + sessionsUncertainty * sessionsUncertainty,
        )
        val perDay = params.referenceKcal + (steps ?: 0.0) + (sessions ?: 0.0)
        return Expenditure(
            referenceKcal = params.referenceKcal,
            stepsAdjustmentKcal = steps,
            sessionsAdjustmentKcal = sessions,
            kcalPerDay = perDay,
            uncertaintyKcal = uncertainty,
            weightKg = weightKg,
            weightBasis = basis,
            assumptions = assumptions(activity, weightKg, basis, stepsKnown, params),
        )
    }

    /** kcal par jour : 0,5 kcal par kg et par tranche de 1 000 pas ; la moitié seulement au-dessus de la référence. */
    fun stepsAdjustment(averageSteps: Double, weightKg: Double, params: ReviewParams): Double {
        val thousands = (averageSteps - params.referenceSteps) / 1000.0
        val counted = if (thousands > 0) thousands * params.stepsAboveReferenceShare else thousands
        return counted * weightKg * params.kcalPerKgPer1000Steps
    }

    private fun assumptions(activity: Activity, weightKg: Double, basis: WeightBasis, stepsKnown: Boolean, p: ReviewParams): List<String> = buildList {
        add(
            "Référence donnée par toi : ${fmt(p.referenceKcal)} kcal par jour pour ${fmt(p.referenceSteps)} pas par jour et 3 à 4 séances par semaine. " +
                "Elle n'a jamais été mesurée : compte environ ${PLUS_MINUS} ${fmt(p.referenceUncertaintyKcal)} kcal par jour.",
        )
        val weightText = when (basis) {
            WeightBasis.WeekAverage -> "ton poids moyen de la semaine, ${fmtOne(weightKg)} kg"
            WeightBasis.LastKnown -> "ton dernier poids connu, ${fmtOne(weightKg)} kg (aucune pesée cette semaine)"
            WeightBasis.Default -> "une valeur de travail de ${fmtOne(weightKg)} kg, qui n'est pas ton poids (aucune pesée connue)"
        }
        add(
            "Pas (hypothèse de l'application, pas une mesure) : environ ${fmtOne(p.kcalPerKgPer1000Steps)} kcal par kg et par tranche de 1 000 pas d'écart à ${fmt(p.referenceSteps)}, " +
                "soit environ ${fmt(Math.round(p.kcalPerKgPer1000Steps * weightKg).toDouble())} kcal par 1 000 pas avec $weightText (${PLUS_MINUS} ${(p.stepsUncertaintyShare * 100).toInt()} %). " +
                "Au-dessus de ${fmt(p.referenceSteps)} pas, seule la moitié est comptée, car la dépense plafonne quand l'activité est très haute.",
        )
        val stepsNote = if (stepsKnown) "Une course ou une marche sportive n'ajoute rien : ses pas sont déjà comptés. " else "Pas non fournis : une course ou une marche est comptée comme une autre séance. "
        add(
            "Séances : environ ${fmt(p.kcalPerSession)} kcal (${PLUS_MINUS} ${fmt(p.sessionUncertaintyKcal)}) par séance de musculation, écart mesuré à ${fmtOne(p.referenceSessionsPerWeek)} séances par semaine, répartis sur 7 jours. " +
                stepsNote + "Un type de séance non reconnu est compté comme de la musculation.",
        )
        add("La dépense reste une estimation : seuls les pas sont comptés, car les montres ne mesurent pas bien l'énergie ; l'intensité réelle des séances et le poids du jour ne sont pas pris en compte.")
    }

    private const val PLUS_MINUS = "±"

    private fun fmt(value: Double): String {
        if (value != Math.floor(value)) return fmtOne(value)
        return value.toLong().toString().reversed().chunked(3).joinToString(" ").reversed()
    }

    /** Une décimale, virgule : `72.05000000000001` → « 72,1 » (jamais l'écriture brute d'un `Double`). */
    private fun fmtOne(value: Double): String =
        BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
