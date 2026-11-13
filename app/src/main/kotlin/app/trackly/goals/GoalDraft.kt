package app.trackly.goals

import app.trackly.core.action.SetGoal
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.model.GoalTargets
import app.trackly.day.formatKcal
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Date de départ choisie dans le formulaire. */
enum class GoalStart { Today, Tomorrow, Other }

/** Les macros saisies font [macroKcal] kcal alors que l'objectif est de [goalKcal]. */
data class MacroMismatch(val macroKcal: Int, val goalKcal: Int)

/** Résultat de la lecture du formulaire : l'action à exécuter, ou ce qu'il faut corriger. */
sealed interface DraftResult {
    data class Ready(val action: SetGoal, val start: LocalDate) : DraftResult
    data class Invalid(val message: String) : DraftResult
}

/**
 * Formulaire de modification de l'objectif, en texte tel que saisi. [read] le transforme en
 * action [SetGoal] (la même que celle de l'assistant) ou en message à corriger.
 */
data class GoalDraft(
    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val start: GoalStart = GoalStart.Today,
    /** « JJ/MM/AAAA » (ou « AAAA-MM-JJ ») quand [start] vaut [GoalStart.Other]. */
    val otherDate: String = "",
    /**
     * Minimum de protéines de l'objectif en vigueur : jamais affiché ni saisi ici, il sert seulement de plancher
     * à la saisie et à « Ajuster en proportion ». L'action [SetGoal] le reprend elle-même de l'objectif en vigueur.
     */
    val proteinMin: Double? = null,
) {
    fun read(today: LocalDate): DraftResult {
        val date = when (start) {
            GoalStart.Today -> today
            GoalStart.Tomorrow -> today.plusDays(1)
            GoalStart.Other -> parseDate(otherDate) ?: return DraftResult.Invalid("Date de départ invalide : écris-la sous la forme 25/12/2026.")
        }
        if (date.isBefore(today)) {
            return DraftResult.Invalid("Les jours passés gardent leur objectif : choisis aujourd’hui ou un jour suivant.")
        }
        val kcalValue = parseAmount(kcal) ?: return DraftResult.Invalid("Indique l’objectif en calories.")
        val macros = listOf(protein, carbs, fat).map { text ->
            if (text.isBlank()) null else parseAmount(text) ?: return DraftResult.Invalid("« $text » n’est pas un nombre valide pour une macro.")
        }
        val targets = try {
            GoalTargets(kcalValue, macros[0], macros[1], macros[2])
        } catch (e: IllegalArgumentException) {
            return DraftResult.Invalid(e.message ?: "Valeur invalide.")
        }
        // Dans les Réglages, l'utilisateur choisit ses chiffres : on lui signale les bornes, on ne les impose pas.
        if (kcalValue < GoalCoherence.MIN_KCAL || kcalValue > GoalCoherence.MAX_KCAL) {
            return DraftResult.Invalid("Les calories doivent être entre ${GoalCoherence.MIN_KCAL.toInt()} et ${GoalCoherence.MAX_KCAL.toInt()}.")
        }
        // Le minimum de protéines est un plancher : l'idéal ne passe pas dessous.
        if (GoalCoherence.belowMinimum(targets, proteinMin)) {
            return DraftResult.Invalid("Les protéines ne descendent pas sous ton minimum de ${GoalCoherence.floorG(proteinMin!!).toInt()} g.")
        }
        return DraftResult.Ready(SetGoal(targets, date, allowPast = false), date)
    }

    private fun typedTargets(): GoalTargets? = runCatching {
        GoalTargets(parseAmount(kcal) ?: return null, parseAmount(protein), parseAmount(carbs), parseAmount(fat), proteinMin)
    }.getOrNull()

    /**
     * Les macros saisies ne font plus les calories : les trois sont renseignées et leurs calories (4 × protéines +
     * 4 × glucides + 9 × lipides) s'écartent de l'objectif de plus de l'arrondi au gramme. `null` si tout va bien.
     */
    fun macroMismatch(): MacroMismatch? {
        val targets = typedTargets() ?: return null
        if (!GoalCoherence.macrosOff(targets)) return null
        return MacroMismatch(macroKcal = GoalCoherence.macroKcal(targets).roundToInt(), goalKcal = targets.kcal.roundToInt())
    }

    /** Phrase du rappel (« Tes macros font 2 350 kcal… »), sans bloquer l'enregistrement. */
    fun coherenceHint(): String? = macroMismatch()?.let {
        "Tes macros font ${formatKcal(it.macroKcal)} kcal, pour un objectif de ${formatKcal(it.goalKcal)} kcal. Tu peux enregistrer quand même."
    }

    /**
     * « Ajuster en proportion » : le formulaire avec les trois macros mises à l'échelle des calories (même calcul que le
     * chat), protéines jamais sous le minimum. `null` s'il n'y a rien à ajuster.
     */
    fun adjustedInProportion(): GoalDraft? {
        val scaled = GoalCoherence.scaledToKcal(typedTargets() ?: return null) ?: return null
        return copy(protein = plain(scaled.proteinG!!), carbs = plain(scaled.carbsG!!), fat = plain(scaled.fatG!!))
    }

    companion object {
        /** Préremplit le formulaire avec l'objectif [targets] en vigueur ; les grammes sont arrondis à l'unité. */
        fun from(targets: GoalTargets?) = GoalDraft(
            kcal = targets?.kcal?.let(::plain).orEmpty(),
            protein = targets?.proteinG?.let { plain(Math.rint(it)) }.orEmpty(),
            carbs = targets?.carbsG?.let { plain(Math.rint(it)) }.orEmpty(),
            fat = targets?.fatG?.let { plain(Math.rint(it)) }.orEmpty(),
            proteinMin = targets?.proteinMinG,
        )

        /** « 2 300 », « 2300 » ou « 2300,5 » → nombre ; `null` si ce n’est pas un nombre. */
        fun parseAmount(text: String): Double? =
            text.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

        fun parseDate(text: String): LocalDate? {
            val clean = text.trim()
            return try {
                val parts = clean.split('/', '.', '-')
                when {
                    parts.size == 3 && parts[0].length == 4 -> LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    parts.size == 3 && parts[2].length == 4 -> LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                    else -> null
                }
            } catch (e: DateTimeParseException) {
                null
            } catch (e: NumberFormatException) {
                null
            } catch (e: java.time.DateTimeException) {
                null
            }
        }

        private fun plain(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString() else value.toString().replace('.', ',')
    }
}
