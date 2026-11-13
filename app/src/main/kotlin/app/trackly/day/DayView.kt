package app.trackly.day

import app.trackly.core.model.EstimateKind
import app.trackly.core.model.Moment
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarState
import app.trackly.core.overview.DayOverview
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.EnergyStatus
import app.trackly.core.summary.MacroTotal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bilan du jour tel qu'affiché par l'écran d'accueil. Présentation seulement : tous les
 * chiffres viennent du bilan du moteur ([DaySummary]), arrondis pour l'affichage.
 */
data class DayView(
    val date: LocalDate,
    val label: String,
    val isToday: Boolean,
    /** Objectif en vigueur ce jour-là ; `null` tant qu'aucun objectif n'est fixé. */
    val goals: Goals?,
    val meals: List<Meal>,
    val consumedKcal: Int,
    val protein: MacroAmount,
    val carbs: MacroAmount,
    val fat: MacroAmount,
    /** Énergie estimée ou seulement minimale (journée reprise de l'ancien suivi) ; `null` = détail. */
    val energyNote: EnergyNote? = null,
    /** Estimation journalière validée en vigueur, affichée à part des repas. */
    val estimate: EstimateLine? = null,
    /** « Aujourd’hui » ou « Hier » devant la date ; `null` pour les autres jours. */
    val relative: String? = null,
    /** Jour déjà passé : le bilan parle à l'imparfait (« Il restait ») et ne dit jamais « pour l'instant ». */
    val isPast: Boolean = false,
    /** Vue du jour : ce que dit le module de `:core` de chaque barre (trait de moyenne, état). */
    val overview: DayOverview = DayOverview.None,
) {
    /**
     * Position du trait de moyenne sur [bar], de 0 (début) à 1 (fin de la barre), comme le remplissage : la moyenne
     * ramenée à la cible affichée. `null` : pas de trait (rien noté, trop peu d'historique, barre sans cible).
     */
    fun averageMark(bar: Bar): Float? {
        val target = when (bar) {
            Bar.Kcal -> goals?.kcal
            Bar.Protein -> goals?.protein
            Bar.Carbs -> goals?.carbs
            Bar.Fat -> goals?.fat
        }?.takeIf { it > 0 } ?: return null
        return overview[bar].average?.let { (it / target).toFloat().coerceIn(0f, 1f) }
    }

    /** État de [bar] : seule la couleur de son remplissage en dépend. */
    fun barState(bar: Bar): BarState = overview[bar].state

    /** Date compacte de l'en-tête (« Dim. 1er nov. »). */
    val shortLabel: String get() = DayLabels.compact(date)

    /** Description complète pour les lecteurs d'écran (« Aujourd’hui, lundi 2 novembre »). */
    val spokenLabel: String get() = relative?.let { "$it, ${DayLabels.inSentence(date)}" } ?: label

    /** Jour passé sans rien de noté : ni repas ni estimation. */
    val untracked: Boolean get() = isPast && !hasData

    /** Le total affiché n'est qu'un minimum (borne minimale ou énergie inconnue). */
    private val atLeast: Boolean get() = energyNote == EnergyNote.AtLeast || energyNote == EnergyNote.Incomplete

    /** Jour avec des données (repas ou estimation), marqué dans le calendrier. */
    val hasData: Boolean get() = meals.isNotEmpty() || estimate != null

    val headline: Headline
        get() {
            if (untracked) return Headline(kicker = "Journée non suivie", kcal = null, unit = "Rien n’a été noté ce jour-là.")
            val goal = goals
                ?: return Headline(kicker = if (atLeast) "Consommé au moins" else "Consommé", kcal = consumedKcal, unit = "kcal ce jour")
            val remaining = goal.kcal - consumedKcal
            return if (remaining >= 0) {
                val kicker = when {
                    isPast -> if (atLeast) "Il restait au plus" else "Il restait"
                    else -> if (atLeast) "Il te reste au plus" else "Il te reste"
                }
                Headline(kicker = kicker, kcal = remaining, unit = if (isPast) "kcal" else "kcal restantes")
            } else {
                val kicker = when {
                    isPast -> if (atLeast) "Tu avais dépassé d’au moins" else "Tu avais dépassé de"
                    else -> if (atLeast) "Tu as dépassé d’au moins" else "Tu as dépassé de"
                }
                Headline(kicker = kicker, kcal = -remaining, unit = "kcal en plus")
            }
        }

    /** Le total consommé n'est qu'une valeur basse : la jauge le dit (« au moins 1 550 »). */
    val consumedAtLeast: Boolean get() = atLeast

    /** Un créneau par moment de la journée, dans l'ordre chronologique, même vide. */
    val slots: List<MomentSlot>
        get() = Moment.entries.map { moment -> MomentSlot(moment, meals.filter { it.moment == moment }) }

    companion object {
        fun from(summary: DaySummary, today: LocalDate, overview: DayOverview = DayOverview.None) = DayView(
            date = summary.day,
            label = DayLabels.format(summary.day),
            isToday = summary.day == today,
            relative = DayLabels.relative(summary.day, today),
            isPast = summary.day.isBefore(today),
            overview = overview,
            goals = summary.goal?.let { g ->
                Goals(
                    kcal = g.kcal.roundToInt(),
                    protein = g.proteinG?.roundToInt(),
                    carbs = g.carbsG?.roundToInt(),
                    fat = g.fatG?.roundToInt(),
                )
            },
            meals = summary.consumptions.map { c ->
                Meal(
                    entryId = c.entryId.value,
                    moment = c.moment,
                    description = c.food.label,
                    kcal = c.food.nutrition.kcal,
                    protein = c.food.nutrition.proteinG,
                    carbs = c.food.nutrition.carbsG,
                    fat = c.food.nutrition.fatG,
                    quantity = c.food.quantity?.let { formatQuantity(it.value, it.unit) },
                    assumptions = c.food.assumptions,
                    productLinked = c.food.productId != null,
                )
            },
            consumedKcal = summary.energy.shownKcal.roundToInt(),
            // Estimation journalière ou énergie inconnue : les macros détaillées ne sont qu'un minimum.
            protein = summary.totals.protein.toAmount(summary.energy.macrosKnown),
            carbs = summary.totals.carbs.toAmount(summary.energy.macrosKnown),
            fat = summary.totals.fat.toAmount(summary.energy.macrosKnown),
            energyNote = when (summary.energy.status) {
                EnergyStatus.Detailed -> null
                EnergyStatus.Estimate -> EnergyNote.Estimate
                EnergyStatus.LowerBound -> EnergyNote.AtLeast
                EnergyStatus.Unknown -> EnergyNote.Incomplete
            },
            estimate = summary.energy.estimate?.let { e ->
                EstimateLine(
                    description = e.description,
                    kcal = (e.kcal ?: e.kcalLow).roundToInt(),
                    range = when (e.kind) {
                        EstimateKind.Estimate -> "entre ${formatKcal(e.kcalLow.roundToInt())} et ${formatKcal(e.kcalHigh!!.roundToInt())} kcal"
                        EstimateKind.LowerBound -> "au moins ${formatKcal(e.kcalLow.roundToInt())} kcal"
                    },
                    atLeast = e.kind == EstimateKind.LowerBound,
                )
            },
        )

        private fun MacroTotal.toAmount(known: Boolean) = MacroAmount(this.known.roundToInt(), complete && known)
    }
}

/** Nature de l'énergie d'une journée, signalée par une étiquette. */
enum class EnergyNote(val tag: String) {
    /** Estimation globale validée. */
    Estimate("ESTIMATION"),

    /** Borne minimale validée. */
    AtLeast("AU MOINS"),

    /** Estimation supprimée sans détail déclaré complet : seul un minimum est connu. */
    Incomplete("INCOMPLET"),
}

/** Estimation journalière affichée au-dessus des repas. */
data class EstimateLine(val description: String, val kcal: Int, val range: String, val atLeast: Boolean)

/** Objectif arrondi ; une macro sans cible vaut `null`. */
data class Goals(val kcal: Int, val protein: Int?, val carbs: Int?, val fat: Int?)

/** Grammes connus d'une macro ; [complete] faux si une consommation a cette macro inconnue. */
data class MacroAmount(val grams: Int, val complete: Boolean = true)

data class Meal(
    val entryId: String,
    val moment: Moment,
    val description: String,
    /** Valeurs exactes : le total d'un moment est arrondi une seule fois. */
    val kcal: Double,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    /** Quantité déclarée (« 250 g »), si elle l'est. */
    val quantity: String? = null,
    /** Hypothèses d'estimation, une par ligne dans la feuille du repas. */
    val assumptions: List<String> = emptyList(),
    /** L'aliment vient d'un produit mémorisé. */
    val productLinked: Boolean = false,
)

data class MomentSlot(val moment: Moment, val meals: List<Meal>) {
    val description: String? get() = meals.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.description }

    /** Identifiants des consommations de ce moment, pour « Corriger » (chat) ou « Supprimer ». */
    val consumptionIds: List<String> get() = meals.map { it.entryId }
    val kcal: Int? get() = meals.takeIf { it.isNotEmpty() }?.sumOf { it.kcal }?.roundToInt()
}

/** [kcal] est `null` pour une journée passée non suivie : [unit] porte alors la phrase. */
data class Headline(val kicker: String, val kcal: Int?, val unit: String)

/** 250.0 + « g » → « 250 g » ; 1.5 + « pièce » → « 1,5 pièce » ; 2 + « pièce » → « 2 pièces » (voir [QuantityText]). */
fun formatQuantity(value: Double, unit: String): String {
    val n = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.FRENCH, "%.1f", value)
    return QuantityText.format(value, unit, n)
}

/** « Lundi 2 novembre », « Dimanche 1er novembre ». */
object DayLabels {
    private val DAYS = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    fun format(date: LocalDate): String {
        val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
        return "${DAYS[date.dayOfWeek.value - 1]} $day ${MONTHS[date.monthValue - 1]}"
    }

    private val SHORT_DAYS = listOf("Lun.", "Mar.", "Mer.", "Jeu.", "Ven.", "Sam.", "Dim.")
    private val SHORT_MONTHS = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin",
        "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    )

    /** « Dim. 1er nov. » : date compacte de l'en-tête. */
    fun compact(date: LocalDate): String {
        val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
        return "${SHORT_DAYS[date.dayOfWeek.value - 1]} $day ${SHORT_MONTHS[date.monthValue - 1]}"
    }

    /** « Aujourd’hui » ou « Hier » ; `null` pour tout autre jour. */
    fun relative(date: LocalDate, today: LocalDate): String? = when (date) {
        today -> "Aujourd’hui"
        today.minusDays(1) -> "Hier"
        else -> null
    }

    /** « dimanche 1er novembre » en milieu de phrase. */
    fun inSentence(date: LocalDate): String = format(date).replaceFirstChar { it.lowercase(Locale.FRENCH) }

    /** « 1er septembre 2026 ». */
    fun short(date: LocalDate): String {
        val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
        return "$day ${MONTHS[date.monthValue - 1]} ${date.year}"
    }

    /** « Octobre 2026 ». */
    fun month(month: YearMonth): String =
        "${MONTHS[month.monthValue - 1].replaceFirstChar { it.titlecase(Locale.FRENCH) }} ${month.year}"
}
