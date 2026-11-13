package app.trackly.core.summary

import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import java.time.LocalDate

/**
 * Total d'une macro. [known] additionne les seules valeurs connues ; [value] vaut `null`
 * dès qu'une consommation active a cette macro inconnue (contrat v2 : `known_totals`).
 */
data class MacroTotal(val known: Double, val complete: Boolean) {
    val value: Double? get() = if (complete) known else null

    companion object {
        fun of(values: List<Double?>) = MacroTotal(values.sumOf { it ?: 0.0 }, values.all { it != null })
    }
}

/** Totaux des consommations détaillées. [kcalLow]/[kcalHigh] tiennent compte des fourchettes d'estimation. */
data class Totals(
    val kcal: Double,
    val protein: MacroTotal,
    val carbs: MacroTotal,
    val fat: MacroTotal,
    val fiber: MacroTotal,
    val kcalLow: Double = kcal,
    val kcalHigh: Double = kcal,
    /** Au moins une consommation porte une fourchette d'estimation. */
    val containsEstimates: Boolean = false,
) {
    companion object {
        fun of(consumptions: List<Consumption>) = Totals(
            kcal = consumptions.sumOf { it.food.nutrition.kcal },
            protein = MacroTotal.of(consumptions.map { it.food.nutrition.proteinG }),
            carbs = MacroTotal.of(consumptions.map { it.food.nutrition.carbsG }),
            fat = MacroTotal.of(consumptions.map { it.food.nutrition.fatG }),
            fiber = MacroTotal.of(consumptions.map { it.food.nutrition.fiberG }),
            kcalLow = consumptions.sumOf { it.food.kcalRange?.low ?: it.food.nutrition.kcal },
            kcalHigh = consumptions.sumOf { it.food.kcalRange?.high ?: it.food.nutrition.kcal },
            containsEstimates = consumptions.any { it.food.kcalRange != null },
        )
    }
}

data class MomentGroup(val moment: Moment, val consumptions: List<Consumption>) {
    val kcal: Double get() = consumptions.sumOf { it.food.nutrition.kcal }
}

/**
 * Bilan du jour : toujours recalculé depuis les versions courantes actives de ce jour,
 * jamais repris d'un total stocké ou fourni par le modèle.
 */
data class DaySummary(
    val day: LocalDate,
    /** Objectif en vigueur ce jour-là, ou `null` si aucun objectif n'est encore fixé. */
    val goal: GoalTargets?,
    /**
     * Consommations actives, dans l'ordre d'affichage (moment, puis heure réelle). Les anciennes
     * entrées agrégées remplacées par une estimation journalière n'y figurent pas.
     */
    val consumptions: List<Consumption>,
    /** Totaux des [consumptions] (détail connu). */
    val totals: Totals,
    /** Énergie de la journée : détail, estimation journalière validée, borne minimale ou inconnue. */
    val energy: DayEnergy = DayEnergy.detailed(totals),
    /** Couverture de la journée, pour les moyennes et bilans. */
    val coverage: DayCoverage = DayCoverage.Missing,
    /**
     * L'utilisateur a déclaré cette journée incomplète (« je n'ai pas tout noté »). Plus étroit que [DayCoverage.Partial],
     * qui inclut aussi une journée sans soir et sans déclaration : la vue du jour n'écarte que les journées déclarées.
     */
    val declaredIncomplete: Boolean = false,
) {
    /** Un groupe par moment, dans l'ordre de la journée, même vide. */
    val moments: List<MomentGroup>
        get() = Moment.entries.map { m -> MomentGroup(m, consumptions.filter { it.moment == m }) }

    /**
     * Calories restantes (négatives en cas de dépassement), ou `null` sans objectif. Avec une
     * borne minimale ou une énergie inconnue, c'est un maximum (calculé sur le minimum connu).
     */
    val remainingKcal: Double? get() = goal?.let { it.kcal - energy.shownKcal }

    companion object {
        /**
         * Calcule le bilan de [day]. [consumptions] peut contenir d'autres jours, des versions
         * anciennes ou supprimées : seules les versions courantes actives de ce jour comptent.
         */
        fun of(
            day: LocalDate,
            consumptions: Collection<Consumption>,
            goals: Collection<Goal>,
            /** Versions des estimations journalières (toutes, y compris anciennes et supprimées). */
            estimates: Collection<DailyEstimate> = emptyList(),
            /** Versions du suivi déclaré des journées. */
            tracking: Collection<DayTracking> = emptyList(),
        ): DaySummary {
            val dayEstimates = estimates.filter { it.day == day }
            // Une entrée remplacée par une estimation reste dans l'historique, sans redevenir un repas.
            val replaced = dayEstimates.flatMapTo(HashSet()) { it.replacesEntryIds }
            val active = currentVersions(consumptions)
                .filter { it.isActive && it.day == day && it.entryId !in replaced }
                .sortedWith(DISPLAY_ORDER)
            val totals = Totals.of(active)
            val flags = tracking.filter { it.day == day }.maxByOrNull { it.version }?.takeIf { it.isActive }
            val estimate = currentEstimate(dayEstimates)
            val energy = DayEnergy.of(totals, estimate, hasEstimates = dayEstimates.isNotEmpty(), flags)
            val coverage = DayCoverage.of(active, estimate, hasEstimates = dayEstimates.isNotEmpty(), flags)
            val declaredIncomplete = flags != null && (flags.detailedTrackingComplete ?: flags.trackingComplete) == false
            return DaySummary(day, goalInForce(day, goals), active, totals, energy, coverage, declaredIncomplete)
        }

        /** Estimation journalière active (version courante), au plus une par jour. */
        fun currentEstimate(estimates: Collection<DailyEstimate>): DailyEstimate? =
            estimates.groupBy { it.assessmentId }
                .values
                .map { versions -> versions.maxBy { it.version } }
                .filter { it.isActive }
                // Deux estimations actives sont refusées à l'écriture ; par sécurité, la plus récente.
                .maxByOrNull { it.recordedAt }

        /** Objectif actif dont la date de départ est la plus récente, sans dépasser [day]. */
        fun goalInForce(day: LocalDate, goals: Collection<Goal>): GoalTargets? =
            goals.groupBy { it.effectiveFrom }
                .values
                .map { versions -> versions.maxBy { it.version } }
                .filter { it.isActive && !it.effectiveFrom.isAfter(day) }
                .maxByOrNull { it.effectiveFrom }
                ?.targets

        /** Version la plus haute de chaque consommation. */
        fun currentVersions(consumptions: Collection<Consumption>): List<Consumption> =
            consumptions.groupBy { it.entryId }.values.map { versions -> versions.maxBy { it.version } }

        /**
         * Ordre d'affichage : moment de la journée, puis heure réelle, puis identifiant. Les identifiants créés par le moteur
         * suivent l'ordre de la déclaration ([EntryId.ordered]) : des aliments déclarés ensemble gardent leur ordre.
         */
        val DISPLAY_ORDER: Comparator<Consumption> =
            compareBy<Consumption> { it.moment.ordinal }.thenBy { it.consumedAt }.thenBy { it.entryId.value }
    }
}
