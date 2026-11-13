package app.trackly.assistant.context

import app.trackly.assistant.cards.formatNumber
import app.trackly.core.action.ForgetFormattingNotes
import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.summary.DaySummary
import app.trackly.core.summary.EnergyStatus
import app.trackly.core.summary.MacroTotal
import app.trackly.core.time.TrackingDay
import app.trackly.day.DayLabels
import kotlinx.coroutines.flow.first
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Profil : réglages stables de l'utilisateur. Enrichi par la reprise quand le profil existera. */
object ProfileContext : ContextProvider {
    override val id = "profile"
    override val title = "Profil"

    override suspend fun render(env: ContextEnv) =
        "Langue : français. Fuseau horaire : ${TrackingDay.ZONE.id}. Unités métriques (g, ml, kcal)."
}

/** Heure du message et jour de suivi (règle des 5 h déjà appliquée). */
object NowContext : ContextProvider {
    override val id = "now"
    override val title = "Maintenant"
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    override suspend fun render(env: ContextEnv): String {
        val local = env.now.atZone(TrackingDay.ZONE)
        val day = TrackingDay.of(env.now)
        return buildString {
            append("Message envoyé le ${local.toLocalDate()} à ${TIME.format(local)} (heure locale). ")
            append("Jour de suivi en cours : ${DayLabels.inSentence(day)} ($day).")
            if (day != local.toLocalDate()) append(" Avant 5 h : sans date précisée, une consommation compte pour ce jour de suivi.")
            append("\nJours récents d'après la date civile du message (pour convertir « hier », « samedi »… en date) : ")
            append(
                (1L..7L).joinToString("; ") { back ->
                    val d = local.toLocalDate().minusDays(back)
                    val name = when (back) {
                        1L -> "hier = "
                        2L -> "avant-hier = "
                        else -> ""
                    }
                    "$name${DayLabels.inSentence(d)} ($d)"
                },
            )
            append('.')
        }
    }
}

/** Journée de suivi en cours : consommations actives (avec leurs identifiants) et totaux. */
object TrackingDayContext : ContextProvider {
    override val id = "day"
    override val title = "Journée de suivi en cours"

    override suspend fun render(env: ContextEnv): String {
        val summary = env.engine.daySummary(TrackingDay.of(env.now))
        return buildString {
            if (summary.consumptions.isEmpty()) {
                append("Rien d'enregistré pour l'instant.")
            } else {
                for (group in summary.moments.filter { it.consumptions.isNotEmpty() }) {
                    append(group.moment.label).append(" :\n")
                    for (c in group.consumptions) append("- ").append(line(c)).append('\n')
                }
            }
            append('\n').append(totalLine(summary))
            proteinLine(summary)?.let { append('\n').append(it) }
        }
    }

    /**
     * Ce qui manque en protéines, calculé ici : pour le minimum (plancher) et pour l'idéal (l'objectif). Rien sans
     * idéal fixé, ni quand les macros de la journée sont inconnues (estimation journalière, borne minimale).
     */
    internal fun proteinLine(summary: DaySummary): String? {
        val goal = summary.goal ?: return null
        val ideal = goal.proteinG ?: return null
        if (summary.energy.status != EnergyStatus.Detailed) return null
        val eaten = summary.totals.protein
        val atLeast = if (eaten.complete) "" else "au moins "
        val got = eaten.known
        fun gap(target: Double) = (target - got).roundToInt()
        val min = goal.proteinMinG?.takeIf { it < ideal }
        val missing = when {
            min != null && gap(min) > 0 ->
                "il manque ${if (eaten.complete) "" else "au plus "}${gap(min)} g pour le minimum (${min.roundToInt()} g, plancher) " +
                    "et ${gap(ideal)} g pour l'idéal (${ideal.roundToInt()} g, objectif)"
            gap(ideal) > 0 ->
                (if (min != null) "minimum atteint ; " else "") + "il manque ${if (eaten.complete) "" else "au plus "}${gap(ideal)} g pour l'idéal (${ideal.roundToInt()} g, objectif)"
            else -> "idéal atteint (${ideal.roundToInt()} g)"
        }
        return "Protéines : $atLeast${got.roundToInt()} g mangés ; $missing."
    }

    private fun totalLine(summary: DaySummary): String {
        val t = summary.totals
        val energy = summary.energy
        val remaining = summary.remainingKcal?.let { " Reste : ${it.roundToInt()} kcal." }.orEmpty()
        return when (energy.status) {
            EnergyStatus.Detailed ->
                "Total : ${t.kcal.roundToInt()} kcal, P ${macro(t.protein)}, G ${macro(t.carbs)}, L ${macro(t.fat)}.$remaining"
            EnergyStatus.Estimate ->
                "Total : estimation de la journée validée par l'utilisateur, ${energy.kcal!!.roundToInt()} kcal " +
                    "(entre ${energy.kcalLow.roundToInt()} et ${energy.kcalHigh!!.roundToInt()} kcal) ; macros inconnues. " +
                    "Le détail ci-dessus (${t.kcal.roundToInt()} kcal) est partiel et ne s'y ajoute pas.$remaining"
            EnergyStatus.LowerBound ->
                "Total : au moins ${energy.kcalLow.roundToInt()} kcal (borne minimale validée) ; macros inconnues. " +
                    "Le détail ci-dessus (${t.kcal.roundToInt()} kcal) est partiel.$remaining"
            EnergyStatus.Unknown ->
                "Total : au moins ${energy.kcalLow.roundToInt()} kcal (journée incomplète, énergie inconnue) ; macros inconnues.$remaining"
        }
    }

    private fun line(c: Consumption) = ConsumptionLine.of(c)

    private fun macro(total: MacroTotal) =
        (if (total.complete) "" else "≥ ") + "${formatNumber(total.known.roundToOne())} g"
}

/** Une consommation en une ligne pour le modèle, avec l'identifiant à utiliser pour la corriger. */
internal object ConsumptionLine {
    fun of(c: Consumption, withDayAndMoment: Boolean = false): String {
        val n = c.food.nutrition
        val quantity = c.food.quantity?.let { " (${formatNumber(it.value)} ${it.unit})" }.orEmpty()
        val where = if (withDayAndMoment) ", ${c.moment.label} du ${c.day}" else ""
        val product = c.food.productId?.let { ", produit $it" }.orEmpty() + c.food.recipeId?.let { ", plat $it" }.orEmpty()
        return "${c.food.label}$quantity : ${n.kcal.roundToInt()} kcal, P ${grams(n.proteinG)}, G ${grams(n.carbsG)}, L ${grams(n.fatG)} " +
            "[entry_id ${c.entryId.value}, action ${c.actionId?.value ?: "-"}$product$where]"
    }

    private fun grams(value: Double?) = value?.let { "${formatNumber(it.roundToOne())} g" } ?: "?"
}

private fun Double.roundToOne() = Math.round(this * 10) / 10.0

/** Dernières actions, pour annuler ou corriger « ça ». */
object RecentActionsContext : ContextProvider {
    override val id = "recent-actions"
    override val title = "Dernières actions"

    override suspend fun render(env: ContextEnv): String? {
        // Un nettoyage de la mémoire qui n'a rien retiré n'a rien à annuler : il n'encombre pas la liste.
        val actions = env.engine.observeRecentActions(LIMIT).first().filterNot { it.type == ForgetFormattingNotes.TYPE && it.effects.isEmpty() }
        if (actions.isEmpty()) return null
        return actions.joinToString("\n") { a ->
            val state = if (a.undoneBy != null) " (annulée)" else ""
            "- action ${a.id.value} : ${a.title}$state"
        }
    }

    private const val LIMIT = 5
}

/**
 * Correction demandée par le bouton « Corriger » d'une carte : l'enregistrement visé, dans son état
 * actuel. Sans cela, le modèle devinerait la cible parmi toutes les cartes de la conversation.
 */
object CorrectionFocusContext : ContextProvider {
    override val id = "correction-focus"
    override val title = "Correction demandée"

    override suspend fun render(env: ContextEnv): String? {
        val focus = env.focus ?: return null
        val lines = focus.entryIds.map { entryId ->
            val current = env.engine.history(EntryId(entryId)).lastOrNull()
            when {
                current == null -> "- consommation $entryId : introuvable"
                current.isActive -> "- " + ConsumptionLine.of(current, withDayAndMoment = true)
                else -> "- ${current.food.label} [entry_id $entryId] : déjà retirée du journal (supprimée ou annulée)"
            }
        }
        return buildString {
            val where = focus.actionId?.let { "la carte de l'action $it" } ?: "un repas de l'écran du jour"
            append("L'utilisateur a touché « Corriger » sur $where. ")
            append("Son message corrige cet enregistrement (pas un autre), sauf s'il désigne clairement une autre consommation :\n")
            append(lines.joinToString("\n"))
        }
    }
}
