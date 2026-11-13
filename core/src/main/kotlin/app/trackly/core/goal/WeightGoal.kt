package app.trackly.core.goal

import app.trackly.core.action.Action
import app.trackly.core.action.ActionScope
import app.trackly.core.action.RecordChange
import app.trackly.core.action.RecordKind
import app.trackly.core.action.Rejection
import app.trackly.core.model.ActionId
import app.trackly.core.model.RecordState
import app.trackly.core.model.VersionedRecord
import app.trackly.core.review.WeightHistory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * Poids visé à une date : la cible de la prévision d'arrivée. [losing] fixe le sens (perte ou prise), décidé quand
 * l'objectif est fixé : un poids passé sous la cible d'une perte reste « atteint », jamais une prise à faire.
 */
data class WeightTarget(val kg: Double, val date: LocalDate, val losing: Boolean = true) {
    init {
        require(kg.isFinite() && kg in MIN_KG..MAX_KG) { "Poids visé invalide : $kg kg (entre $MIN_KG et $MAX_KG)" }
    }

    companion object {
        const val MIN_KG = 30.0
        const val MAX_KG = 250.0

        /** Objectif par défaut tant que l'utilisateur n'en a pas fixé d'autre. */
        val Default = WeightTarget(60.0, LocalDate.of(2027, 3, 1))
    }
}

/**
 * Objectif de poids enregistré : une seule donnée ([KEY]), en versions. Sans version active (jamais fixé, ou
 * fixation annulée), l'objectif est [WeightTarget.Default]. Il ne touche ni aux calories ni au poids de référence.
 */
data class WeightGoal(
    val target: WeightTarget,
    override val version: Int,
    override val state: RecordState,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<WeightGoal> {
    override val key get() = KEY
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)

    companion object {
        const val KEY = "weight_goal"

        /** Objectif en vigueur : la version courante si elle est active, sinon l'objectif par défaut. */
        fun effective(current: WeightGoal?): WeightTarget = current?.takeIf { it.isActive }?.target ?: WeightTarget.Default
    }
}

/** Type de donnée versionnée « objectif de poids » (annulable comme les autres). */
object WeightGoalKinds {
    val WeightGoals = RecordKind<WeightGoal>("weight_goal", { it.weightGoals })
}

/**
 * Change l'objectif de poids (outil du chat du bilan). Nouvelle version complète, annulable : l'annulation
 * rétablit l'objectif d'avant (l'objectif par défaut s'il n'y en avait pas). Une date passée ou trop lointaine
 * est refusée ; le même objectif ne crée rien.
 */
data class SetWeightGoal(val targetKg: Double, val targetDate: LocalDate) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val wanted = try {
            WeightTarget(targetKg, targetDate)
        } catch (e: IllegalArgumentException) {
            scope.reject(Rejection.Invalid(e.message ?: "Objectif invalide"))
        }
        val today = scope.today
        if (!targetDate.isAfter(today)) scope.reject(Rejection.Invalid("La date visée doit être après aujourd'hui ($today)."))
        if (targetDate.isAfter(today.plusYears(MAX_YEARS_AHEAD))) {
            scope.reject(Rejection.Invalid("Date visée trop lointaine : $targetDate (au plus $MAX_YEARS_AHEAD ans)."))
        }
        val current = WeightGoalKinds.WeightGoals.versionsIn(scope).current(WeightGoal.KEY)
        val before = WeightGoal.effective(current)
        // Sens : perte si le poids visé est sous la dernière pesée connue ; sans pesée (ou à égalité), celui d'avant.
        val imported = WeightHistory.parseImported(scope.tx.imports.latest("weights", "weights")?.json)
        val declared = scope.tx.weekInfos.allCurrent().filter { it.isActive }.flatMap { it.weighIns }
        val lastKg = WeightHistory.of(imported, declared).lastUpTo(today)?.kg
        val oriented = wanted.copy(losing = if (lastKg == null || lastKg == targetKg) before.losing else targetKg < lastKg)
        if (before == oriented) scope.reject(Rejection.NoChange)
        WeightGoalKinds.WeightGoals.writeNextVersion(
            scope,
            current,
            WeightGoal(oriented, 1, RecordState.Active, scope.now, scope.id),
        )
        return "Objectif de poids : ${label(oriented)}"
    }

    companion object {
        const val TYPE = "weight_goal.set"
        const val MAX_YEARS_AHEAD = 5L

        fun label(target: WeightTarget): String =
            "${BigDecimal(target.kg.toString()).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')} kg au ${target.date}" +
                if (target.losing) "" else " (prise de poids)"

        /** Objectif avant et après une action [SetWeightGoal] appliquée (ou son annulation), d'après ses changements. */
        fun change(changes: List<app.trackly.core.action.Change>): Pair<WeightTarget, WeightTarget>? {
            @Suppress("UNCHECKED_CAST")
            val change = changes.lastOrNull { it.kind == WeightGoalKinds.WeightGoals.name } as? RecordChange<WeightGoal> ?: return null
            return WeightGoal.effective(change.before) to WeightGoal.effective(change.after)
        }
    }
}
