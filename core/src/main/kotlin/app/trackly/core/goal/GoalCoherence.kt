package app.trackly.core.goal

import app.trackly.core.model.GoalTargets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Contrôle, par le code, qu'un objectif proposé par l'assistant tient debout : les calories
 * correspondent aux macros (kcal ≈ 4 × protéines + 4 × glucides + 9 × lipides) et les valeurs
 * restent plausibles. Le modèle ajuste les macros ; il n'est jamais cru sur parole.
 *
 * Les Réglages ne passent pas par ce contrôle : l'utilisateur y choisit ses chiffres lui-même.
 */
object GoalCoherence {
    const val KCAL_PER_PROTEIN_G = 4.0
    const val KCAL_PER_CARBS_G = 4.0
    const val KCAL_PER_FAT_G = 9.0

    /** Bornes de plausibilité : elles attrapent une faute de frappe (« 23 » pour 2 300), pas un choix de l'utilisateur. */
    const val MIN_KCAL = 500.0
    const val MAX_KCAL = 10_000.0

    /** Écart toléré entre les calories de l'objectif et celles des macros : 5 %, au moins 50 kcal. */
    fun tolerance(kcal: Double): Double = max(MIN_TOLERANCE_KCAL, TOLERANCE_RATE * kcal)

    /** Calories portées par les macros renseignées (une macro absente ne compte pas). */
    fun macroKcal(targets: GoalTargets): Double =
        (targets.proteinG ?: 0.0) * KCAL_PER_PROTEIN_G +
            (targets.carbsG ?: 0.0) * KCAL_PER_CARBS_G +
            (targets.fatG ?: 0.0) * KCAL_PER_FAT_G

    fun hasAllMacros(targets: GoalTargets?): Boolean =
        targets != null && targets.proteinG != null && targets.carbsG != null && targets.fatG != null

    /**
     * Écart, en kcal, à partir duquel les Réglages le disent à l'utilisateur qui saisit à la main : au-delà de l'arrondi
     * au gramme (jusqu'à 9 kcal), loin sous la tolérance de 5 % de l'assistant.
     */
    const val NOTICEABLE_GAP_KCAL = 25.0

    /** Les trois macros sont renseignées et leurs calories s'écartent de l'objectif de plus de [NOTICEABLE_GAP_KCAL]. */
    fun macrosOff(targets: GoalTargets): Boolean =
        hasAllMacros(targets) && abs(targets.kcal - macroKcal(targets)) > NOTICEABLE_GAP_KCAL

    /**
     * Les trois macros multipliées par un même facteur pour que leurs calories rejoignent celles de
     * l'objectif (les proportions entre protéines, glucides et lipides sont gardées), arrondies au gramme.
     * `null` si une macro manque, si les macros ne portent aucune calorie ou si elles correspondent déjà.
     * Les protéines ne descendent jamais sous le minimum de protéines : au besoin elles s'arrêtent à son plancher au
     * gramme ([floorG]) et glucides et lipides se partagent le reste des calories.
     * C'est le calcul de « Ajuster en proportion » ; il ne s'applique jamais sans que l'utilisateur le demande.
     */
    fun scaledToKcal(targets: GoalTargets): GoalTargets? {
        if (!hasAllMacros(targets)) return null
        val macros = macroKcal(targets)
        if (macros <= 0.0 || !macrosOff(targets)) return null
        val factor = targets.kcal / macros
        val protein = Math.round(targets.proteinG!! * factor).toDouble()
        val floor = targets.proteinMinG?.let(::floorG)
        if (floor == null || protein >= floor) {
            return targets.copy(
                proteinG = protein,
                carbsG = Math.round(targets.carbsG!! * factor).toDouble(),
                fatG = Math.round(targets.fatG!! * factor).toDouble(),
            )
        }
        // Les protéines restent au minimum ; glucides et lipides se partagent le reste, dans leurs proportions d'avant.
        val others = targets.carbsG!! * KCAL_PER_CARBS_G + targets.fatG!! * KCAL_PER_FAT_G
        val rest = max(0.0, targets.kcal - floor * KCAL_PER_PROTEIN_G)
        val othersFactor = if (others > 0.0) rest / others else 0.0
        return targets.copy(
            proteinG = floor,
            carbsG = Math.round(targets.carbsG * othersFactor).toDouble(),
            fatG = Math.round(targets.fatG * othersFactor).toDouble(),
        )
    }

    /**
     * Marge d'arrondi sous le minimum de protéines : l'idéal saisi au gramme (112 g) passe pour un minimum de 112,4 g.
     */
    const val MINIMUM_TOLERANCE_G = 0.5

    /**
     * Plancher au gramme d'un minimum de protéines : le plus petit nombre entier de grammes accepté (123,25 → 123).
     * Le même partout : messages, Réglages et « Ajuster en proportion ».
     */
    fun floorG(minimumG: Double): Double = Math.ceil(minimumG - MINIMUM_TOLERANCE_G)

    /**
     * Les protéines de [targets] descendent sous le minimum [minimumG] (au-delà de l'arrondi au gramme).
     * Faux sans protéines ou sans minimum.
     */
    fun belowMinimum(targets: GoalTargets, minimumG: Double? = targets.proteinMinG): Boolean {
        val protein = targets.proteinG ?: return false
        val floor = minimumG ?: return false
        return protein + MINIMUM_TOLERANCE_G < floor
    }

    /**
     * Raison du refus (message en français destiné au modèle), ou `null` si [proposed] est cohérent.
     * [current] : objectif en vigueur au moment du changement (pour ne pas perdre des macros en silence).
     */
    fun problem(proposed: GoalTargets, current: GoalTargets?): String? {
        val kcal = proposed.kcal
        if (kcal < MIN_KCAL || kcal > MAX_KCAL) {
            return "${kcal.roundToInt()} kcal est hors des limites plausibles (${MIN_KCAL.roundToInt()} à ${MAX_KCAL.roundToInt()} kcal) : vérifie la valeur avec l'utilisateur."
        }
        // Le minimum de protéines est un plancher : un changement de calories ne le franchit jamais.
        val floor = proposed.proteinMinG ?: current?.proteinMinG
        if (belowMinimum(proposed, floor)) {
            return "${proposed.proteinG!!.roundToInt()} g de protéines, c'est sous le minimum de ${floorG(floor!!).roundToInt()} g, " +
                "un plancher qui n'est jamais l'objectif. Garde les protéines à l'idéal" +
                (current?.proteinG?.let { " (${it.roundToInt()} g)" } ?: "") +
                " et ajuste glucides et lipides."
        }
        val macros = macroKcal(proposed)
        val tolerance = tolerance(kcal)
        if (hasAllMacros(proposed)) {
            val gap = kcal - macros
            if (abs(gap) > tolerance) {
                return "Macros incohérentes avec les calories : 4×protéines + 4×glucides + 9×lipides = ${macros.roundToInt()} kcal " +
                    "pour un objectif de ${kcal.roundToInt()} kcal (écart ${abs(gap).roundToInt()} kcal, tolérance ${tolerance.roundToInt()}). " +
                    "Ajuste les macros pour que le total corresponde aux calories." + carbsHint(proposed)
            }
            return null
        }
        if (hasAllMacros(current)) {
            return "L'objectif actuel porte les trois macros (protéines, glucides, lipides) : donne les trois macros ajustées " +
                "pour ${kcal.roundToInt()} kcal, de façon que 4×protéines + 4×glucides + 9×lipides corresponde aux calories."
        }
        if (macros > kcal + tolerance) {
            return "Les macros données représentent ${macros.roundToInt()} kcal, plus que l'objectif de ${kcal.roundToInt()} kcal (tolérance ${tolerance.roundToInt()})."
        }
        return null
    }

    /** Piste pour le modèle : glucides qui bouclent le total en gardant protéines et lipides. */
    private fun carbsHint(proposed: GoalTargets): String {
        val protein = proposed.proteinG ?: return ""
        val fat = proposed.fatG ?: return ""
        val carbs = (proposed.kcal - protein * KCAL_PER_PROTEIN_G - fat * KCAL_PER_FAT_G) / KCAL_PER_CARBS_G
        return if (carbs >= 0) " Piste : en gardant ${protein.roundToInt()} g de protéines et ${fat.roundToInt()} g de lipides, les glucides seraient d'environ ${carbs.roundToInt()} g." else ""
    }

    private const val TOLERANCE_RATE = 0.05
    private const val MIN_TOLERANCE_KCAL = 50.0
}
