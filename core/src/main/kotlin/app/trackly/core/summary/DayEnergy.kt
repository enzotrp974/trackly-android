package app.trackly.core.summary

import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.EstimateKind
import app.trackly.core.model.Moment

/** Origine de l'énergie d'une journée (code identique au moteur Python : `energy_status`). */
enum class EnergyStatus(val code: String) {
    /** Somme des consommations détaillées. */
    Detailed("detailed"),

    /** Estimation journalière validée, avec centre et fourchette. */
    Estimate("estimate"),

    /** Borne minimale validée : « au moins ». */
    LowerBound("lower_bound"),

    /** Estimation supprimée sans que le détail ait été déclaré complet : seul un minimum est connu. */
    Unknown("unknown"),
}

/**
 * Énergie d'une journée, selon les règles du moteur Python (`calculate_day_totals`).
 * Une estimation journalière remplace l'énergie détaillée sans s'y additionner ; les macros
 * deviennent alors inconnues. Une borne minimale n'a ni centre ni borne haute.
 */
data class DayEnergy(
    val status: EnergyStatus,
    /** Total ; `null` pour une borne minimale ou une énergie inconnue. */
    val kcal: Double?,
    val kcalLow: Double,
    /** `null` pour une borne minimale ou une énergie inconnue. */
    val kcalHigh: Double?,
    val containsEstimates: Boolean,
    /** Estimation journalière en vigueur, le cas échéant. */
    val estimate: DailyEstimate? = null,
) {
    /** Les macros du détail ne valent que pour une énergie détaillée. */
    val macrosKnown: Boolean get() = status == EnergyStatus.Detailed

    /** Valeur à afficher : le total, ou à défaut le minimum connu. */
    val shownKcal: Double get() = kcal ?: kcalLow

    /** Vrai si [shownKcal] n'est qu'un minimum. */
    val isMinimum: Boolean get() = kcal == null

    companion object {
        fun detailed(totals: Totals) =
            DayEnergy(EnergyStatus.Detailed, totals.kcal, totals.kcalLow, totals.kcalHigh, totals.containsEstimates)

        fun of(totals: Totals, estimate: DailyEstimate?, hasEstimates: Boolean, tracking: DayTracking?): DayEnergy {
            if (estimate == null) {
                return if (hasEstimates && tracking?.detailedTrackingComplete != true) {
                    // Supprimer une estimation ne prouve pas que le détail est complet.
                    DayEnergy(EnergyStatus.Unknown, null, totals.kcalLow, null, totals.containsEstimates)
                } else {
                    detailed(totals)
                }
            }
            return when (estimate.kind) {
                EstimateKind.Estimate ->
                    DayEnergy(EnergyStatus.Estimate, estimate.kcal, estimate.kcalLow, estimate.kcalHigh, true, estimate)
                EstimateKind.LowerBound ->
                    DayEnergy(EnergyStatus.LowerBound, null, maxOf(estimate.kcalLow, totals.kcalLow), null, true, estimate)
            }
        }
    }
}

/** Couverture d'une journée pour les moyennes (code identique au moteur Python : `day_energy_status`). */
enum class DayCoverage(val code: String) {
    Complete("complete"),
    Partial("partial"),
    Estimated("estimated"),
    Bounded("bounded"),
    Missing("missing"),
    ;

    companion object {
        fun of(detailed: List<Consumption>, estimate: DailyEstimate?, hasEstimates: Boolean, tracking: DayTracking?): DayCoverage {
            if (estimate != null) return if (estimate.kind == EstimateKind.LowerBound) Bounded else Estimated
            tracking?.detailedTrackingComplete?.let { return if (it) Complete else Partial }
            if (hasEstimates) return Partial
            if (tracking?.trackingComplete == false) return Partial
            if (tracking?.trackingComplete == true || tracking?.closedExplicitly == true || detailed.any { it.moment == Moment.Soir }) {
                return Complete
            }
            return if (detailed.isEmpty()) Missing else Partial
        }
    }
}
