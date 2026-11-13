package app.trackly.core.review

import app.trackly.core.action.Action
import app.trackly.core.action.ActionScope
import app.trackly.core.action.RecordKind
import app.trackly.core.action.Rejection
import app.trackly.core.action.VersionedKind
import app.trackly.core.model.RecordState
import app.trackly.core.time.TrackingDay
import java.time.Instant
import java.time.LocalDate

/** Type de donnée versionnée « informations déclarées de la semaine » (annulable comme les autres). */
object ReviewKinds {
    val WeekInfos = RecordKind<WeekInfo>("week_info", { it.weekInfos })

    val all: List<VersionedKind> = listOf(WeekInfos)
}

/**
 * Enregistre les informations **déclarées** de la période du bilan qui commence le [weekStart] (conversation de bilan) :
 * pesées datées, pas par jour ou moyenne, séances par type. Fusionne avec ce qui est déjà noté
 * (une pesée du même jour remplace l'ancienne, un type de séance aussi) et écrit une nouvelle
 * version complète. Rien n'est jamais calculé ni complété ici : seules les valeurs données existent.
 */
data class DeclareWeekInfo(
    val weekStart: LocalDate,
    val weighIns: List<WeighIn> = emptyList(),
    val steps: List<DaySteps> = emptyList(),
    val stepsAverage: Int? = null,
    /** Types donnés, fusionnés par type (le reste est conservé). Liste vide : « aucune séance », remet les séances à zéro. `null` : inchangé. */
    val sessions: List<SessionCount>? = null,
    /** Pesées à retirer (erreur de lecture), par jour. */
    val removeWeighInDays: List<LocalDate> = emptyList(),
    val removeStepDays: List<LocalDate> = emptyList(),
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val latestDay = TrackingDay.latestExplicitDay(scope.now)
        if (weekStart.isAfter(latestDay)) scope.reject(Rejection.Invalid("La période du $weekStart n'a pas commencé."))
        if ((weighIns.map { it.day } + steps.map { it.day }).any { it.isAfter(latestDay) }) {
            scope.reject(Rejection.Invalid("Une date est dans le futur : seules des valeurs déjà mesurées peuvent être notées."))
        }
        // Dernière version (une annulation peut l'avoir marquée supprimée : la suivante reprend la numérotation).
        val latest = ReviewKinds.WeekInfos.versionsIn(scope).current(weekStart.toString())
        val current = latest?.takeIf { it.isActive }
        val base = current ?: WeekInfo(weekStart, 1, RecordState.Active, recordedAt = Instant.EPOCH, actionId = null)

        val mergedWeighIns = (base.weighIns.filter { it.day !in removeWeighInDays && weighIns.none { n -> n.day == it.day } } + weighIns)
            .sortedBy { it.day }
        val mergedSteps = (base.steps.filter { it.day !in removeStepDays && steps.none { n -> n.day == it.day } } + steps)
            .sortedBy { it.day }
        val mergedSessions = when {
            sessions == null -> base.sessions
            // Liste vide = « aucune séance » : remise à zéro (nouvelle version annulable), pas une fusion qui ne change rien.
            sessions.isEmpty() -> emptyList()
            else -> {
                val given = sessions.map { it.copy(type = it.type.trim().lowercase()) }
                ((base.sessions ?: emptyList()).filter { old -> given.none { it.type == old.type } } + given)
                    .filter { it.count > 0 }
                    .sortedBy { it.type }
            }
        }
        val next = base.copy(
            weighIns = mergedWeighIns,
            steps = mergedSteps,
            stepsAverage = stepsAverage ?: base.stepsAverage,
            sessions = mergedSessions,
        )
        if (current != null && next.sameContentAs(current)) scope.reject(Rejection.NoChange)
        if (current == null && next.weighIns.isEmpty() && next.steps.isEmpty() && next.stepsAverage == null && next.sessions == null) {
            scope.reject(Rejection.Invalid("Aucune information à noter."))
        }
        ReviewKinds.WeekInfos.writeNextVersion(scope, latest, next)
        return "Bilan du $weekStart : ${summary(next)}"
    }

    private fun summary(info: WeekInfo): String = listOfNotNull(
        info.weighIns.takeIf { it.isNotEmpty() }?.let { "${it.size} pesée(s)" },
        if (info.steps.isNotEmpty()) "pas de ${info.steps.size} jour(s)" else info.stepsAverage?.let { "pas : moyenne" },
        info.sessions?.let { s -> if (s.isEmpty()) "aucune séance" else "${s.sumOf { it.count }} séance(s)" },
    ).joinToString(", ").ifEmpty { "rien" }

    companion object {
        const val TYPE = "week_info.declare"
    }
}
