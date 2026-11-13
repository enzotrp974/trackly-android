package app.trackly.core.importing

import app.trackly.core.summary.DaySummary
import java.time.LocalDate
import kotlin.math.abs

/**
 * Contrôle de parité : les totaux de chaque journée, recalculés par le moteur Kotlin à partir
 * des seules données de l'export, doivent égaler ceux du moteur Python (`checks`).
 */
object ParityCheck {
    /** Le moteur Python arrondit à 2 décimales ; le moteur Kotlin garde la somme exacte. */
    private const val TOLERANCE = 0.006

    /** Bilans des journées de l'export, calculés uniquement depuis ses données. */
    fun summaries(bundle: HistoryBundle): Map<LocalDate, DaySummary> {
        val consumptions = bundle.days.flatMap { d -> d.consumptions.map { it.record } }
        val estimates = bundle.days.flatMap { d -> d.estimates.map { it.record } }
        val tracking = bundle.days.map { it.tracking.record }
        val goals = bundle.goals.map { it.record }
        return bundle.days.associate { d -> d.day to DaySummary.of(d.day, consumptions, goals, estimates, tracking) }
    }

    /** Écarts constatés, en français ; liste vide si tout concorde. */
    fun differences(bundle: HistoryBundle): List<String> {
        val checks = bundle.checks ?: return emptyList()
        val summaries = summaries(bundle)
        val problems = mutableListOf<String>()
        for (day in (checks.keys + summaries.keys).sorted()) {
            val expected = checks[day]
            val summary = summaries[day]
            if (expected == null || summary == null) {
                problems += "$day : journée absente d'un côté du contrôle"
                continue
            }
            problems += compare(expected, summary).map { "$day : $it" }
        }
        return problems
    }

    /** Champs du bilan Kotlin, présentés comme le moteur Python (`calculate_day_totals`). */
    fun compare(expected: ExpectedDay, summary: DaySummary): List<String> {
        val energy = summary.energy
        val totals = summary.totals
        val macros = energy.macrosKnown
        val diffs = mutableListOf<String>()
        fun check(label: String, python: Double?, kotlin: Double?) {
            val same = if (python == null || kotlin == null) python == kotlin else abs(python - kotlin) <= TOLERANCE
            if (!same) diffs += "$label attendu ${python ?: "inconnu"}, calculé ${kotlin ?: "inconnu"}"
        }
        check("kcal", expected.kcal, energy.kcal)
        check("protéines", expected.proteinG, totals.protein.value.takeIf { macros })
        check("glucides", expected.carbsG, totals.carbs.value.takeIf { macros })
        check("lipides", expected.fatG, totals.fat.value.takeIf { macros })
        check("fibres", expected.fiberG, totals.fiber.value.takeIf { macros })
        check("kcal basses", expected.kcalLow, energy.kcalLow)
        check("kcal hautes", expected.kcalHigh, energy.kcalHigh)
        val known = listOf(totals.kcal, totals.protein.known, totals.carbs.known, totals.fat.known, totals.fiber.known)
        listOf("kcal connues", "protéines connues", "glucides connus", "lipides connus", "fibres connues")
            .forEachIndexed { i, label -> check(label, expected.known[i], known[i]) }
        if (expected.energyStatus != energy.status.code) diffs += "énergie ${expected.energyStatus} attendue, ${energy.status.code} calculée"
        if (expected.containsEstimates != energy.containsEstimates) diffs += "présence d'estimations différente"
        if (expected.coverage != summary.coverage.code) diffs += "couverture ${expected.coverage} attendue, ${summary.coverage.code} calculée"
        val goal = summary.goal
        val targets = expected.targets
        if ((targets == null) != (goal == null)) {
            diffs += "objectif ${if (targets == null) "absent" else "présent"} attendu"
        } else if (targets != null && goal != null) {
            check("objectif kcal", targets[0], goal.kcal)
            check("objectif protéines", targets[1], goal.proteinG)
            check("objectif glucides", targets[2], goal.carbsG)
            check("objectif lipides", targets[3], goal.fatG)
        }
        return diffs
    }
}
