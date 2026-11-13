package app.trackly.review.page

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.SetTemporaryGoal
import app.trackly.core.goal.SetWeightGoal
import app.trackly.core.goal.TemporaryGoalStatus
import app.trackly.core.model.ActionId
import java.time.LocalDate

/** Où en est le bouton d'un conseil, lu en direct dans les objectifs (jamais dans la page figée). */
sealed interface AdviceState {
    /** Pas encore appliqué : bouton « Appliquer » (ou « Changer la date »). */
    data object Open : AdviceState

    /** Objectif temporaire jamais appliqué et dont le premier jour est passé : plus de bouton. */
    data object Expired : AdviceState

    /**
     * Objectif temporaire appliqué, annulé, arrêté ou terminé. [matches] faux : ce n'est pas celui de la carte (même premier jour,
     * autre durée ou autre baisse, appliqué depuis le chat) ; la carte le dit au lieu d'afficher « Appliqué » sous d'autres chiffres.
     */
    data class Temporary(val status: TemporaryGoalStatus, val matches: Boolean = true) : AdviceState

    /** Nouvelle date appliquée par l'action [actionId] : « Annuler ». */
    data class DateSet(val actionId: Long) : AdviceState
}

/**
 * Les boutons des conseils, pour la page du bilan et la carte du chat du bilan. Rien ne change sans un toucher :
 * « Appliquer » écrit l'action du moteur, « Annuler » passe par `Undo`, qui ne réécrit jamais un jour passé.
 */
class AdviceActions(private val engine: TracklyEngine) {
    /** État de chaque conseil à bouton, rangé par [key] ; la phrase des pas n'en a pas. */
    suspend fun states(advice: List<Advice>): Map<String, AdviceState> =
        advice.mapNotNull { a -> state(a)?.let { key(a) to it } }.toMap()

    suspend fun state(a: Advice): AdviceState? = when (a.kind) {
        AdviceKind.Steps -> null
        AdviceKind.Temporary -> {
            val start = a.start?.let(LocalDate::parse)
            when {
                start == null -> null
                else -> engine.temporaryGoal(start)?.let { AdviceState.Temporary(it, it.days == a.days && a.baseKcal != null && a.dropKcal != null && it.temporaryKcal == a.baseKcal - a.dropKcal) }
                    ?: if (engine.today().isAfter(start)) AdviceState.Expired else AdviceState.Open
            }
        }
        AdviceKind.Date -> {
            val goal = engine.weightGoal()
            val id = goal?.actionId
            val set = goal != null && goal.isActive && id != null && goal.target.kg == a.targetKg && goal.target.date.toString() == a.newDate &&
                engine.action(id)?.undoneBy == null
            if (set) AdviceState.DateSet(id!!.value) else AdviceState.Open
        }
    }

    /** « Appliquer » ou « Changer la date » ; renvoie le refus du moteur à afficher, ou `null`. */
    suspend fun apply(a: Advice): String? {
        val action = when (a.kind) {
            // L'objectif d'avant lu sur la carte : refusé s'il a changé depuis, le bouton applique exactement ce qui est affiché.
            AdviceKind.Temporary -> SetTemporaryGoal(LocalDate.parse(a.start ?: return null), a.days ?: return null, a.dropKcal ?: return null, expectedBaseKcal = a.baseKcal)
            AdviceKind.Date -> SetWeightGoal(a.targetKg ?: return null, LocalDate.parse(a.newDate ?: return null))
            AdviceKind.Steps -> return null
        }
        return message(engine.execute(action))
    }

    /** « Annuler » (ou « Rétablir ») d'un conseil appliqué ; renvoie le refus du moteur à afficher, ou `null`. */
    suspend fun toggle(state: AdviceState): String? {
        val target = when (state) {
            is AdviceState.Temporary -> state.status.head
            is AdviceState.DateSet -> ActionId(state.actionId)
            AdviceState.Open, AdviceState.Expired -> return null
        }
        return message(engine.execute(Undo(target)))
    }

    private fun message(outcome: ActionOutcome): String? = (outcome as? ActionOutcome.Rejected)?.rejection?.message

    companion object {
        /** Clé stable d'un conseil : sa sorte et sa date (premier jour, ou nouvelle date visée). */
        fun key(a: Advice): String = "${a.kind}:${a.start ?: a.newDate.orEmpty()}"
    }
}
