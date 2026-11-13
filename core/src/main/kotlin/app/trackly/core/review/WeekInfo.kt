package app.trackly.core.review

import app.trackly.core.model.ActionId
import app.trackly.core.model.RecordState
import app.trackly.core.model.VersionedRecord
import java.time.Instant
import java.time.LocalDate

/** Une pesée datée, donnée par l'utilisateur (dite ou lue par lui sur sa montre). */
data class WeighIn(val day: LocalDate, val kg: Double) {
    init {
        require(kg.isFinite() && kg in MIN_KG..MAX_KG) { "Poids invalide : $kg kg (entre $MIN_KG et $MAX_KG)" }
    }

    companion object {
        const val MIN_KG = 20.0
        const val MAX_KG = 400.0
    }
}

/** Nombre de pas d'un jour. */
data class DaySteps(val day: LocalDate, val steps: Int) {
    init {
        require(steps in 0..MAX_STEPS) { "Nombre de pas invalide : $steps" }
    }

    companion object {
        const val MAX_STEPS = 100_000
    }
}

/**
 * Séances d'un type (musculation, course…) sur la période. La limite haute dépend de la durée de la période
 * ([maxFor]) : elle est vérifiée là où la période est connue, à la déclaration.
 */
data class SessionCount(val type: String, val count: Int) {
    init {
        require(type.isNotBlank()) { "Type de séance vide" }
        require(count >= 0) { "Nombre de séances invalide : $count" }
    }

    companion object {
        const val MAX_PER_DAY = 3

        /** Plus grand nombre de séances d'un type sur une période de [days] jours : 3 par jour, 21 au moins (une semaine). */
        fun maxFor(days: Int): Int = MAX_PER_DAY * maxOf(days, 7)
    }
}

/**
 * Informations **déclarées** par l'utilisateur pour la période d'un bilan, dans la conversation de
 * bilan : pesées datées, pas (par jour ou moyenne) et séances par type. Clé : le premier jour de la
 * période ([weekStart] ; un lundi pour les bilans les plus anciens, n'importe quel jour ensuite). Les jours
 * donnés vont de ce premier jour jusqu'à aujourd'hui, sans fin fixée d'avance. Chaque version est la liste complète à ce moment-là ; rien n'est effacé.
 *
 * Seules les valeurs données ou lues par l'utilisateur y figurent : jamais de valeur estimée ni
 * inventée. `null` signifie « non fourni » (différent de 0).
 */
data class WeekInfo(
    val weekStart: LocalDate,
    override val version: Int,
    override val state: RecordState,
    val weighIns: List<WeighIn> = emptyList(),
    /** Pas par jour (au plus un par jour). */
    val steps: List<DaySteps> = emptyList(),
    /** Moyenne de pas par jour, quand l'utilisateur ne donne que celle-ci. */
    val stepsAverage: Int? = null,
    /** `null` : séances non fournies ; liste vide : aucune séance cette semaine. */
    val sessions: List<SessionCount>? = null,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<WeekInfo> {
    init {
        require(version >= 1) { "Version invalide : $version" }
        require(weighIns.map { it.day }.toSet().size == weighIns.size) { "Deux pesées le même jour" }
        require(steps.map { it.day }.toSet().size == steps.size) { "Deux nombres de pas le même jour" }
        require(weighIns.all { !it.day.isBefore(weekStart) }) { "Pesée avant la période commencée le $weekStart" }
        require(steps.all { !it.day.isBefore(weekStart) }) { "Pas avant la période commencée le $weekStart" }
        require(stepsAverage == null || stepsAverage in 0..DaySteps.MAX_STEPS) { "Moyenne de pas invalide : $stepsAverage" }
        require(sessions == null || sessions.map { it.type.trim().lowercase() }.toSet().size == sessions.size) { "Deux fois le même type de séance" }
    }

    override val key get() = weekStart.toString()
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)
}
