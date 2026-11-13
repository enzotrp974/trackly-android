package app.trackly.core.goal

import app.trackly.core.model.Goal
import java.time.LocalDate

/**
 * Lecture de la suite des objectifs datés. Chaque date de départ a une suite de versions ;
 * seule la plus haute compte, et seulement si elle est active (une version `deleted` retire
 * l'objectif de cette date sans rien effacer).
 */
object GoalTimeline {
    /** Objectifs en vigueur à travers le temps : version courante active de chaque date, de la plus ancienne à la plus récente. */
    fun effective(goals: Collection<Goal>): List<Goal> =
        goals.groupBy { it.effectiveFrom }
            .values
            .map { versions -> versions.maxBy { it.version } }
            .filter { it.isActive }
            .sortedBy { it.effectiveFrom }

    /** Objectif qui vaut ce [day] : le plus récent dont la date de départ ne dépasse pas le jour. */
    fun inForce(day: LocalDate, goals: Collection<Goal>): Goal? =
        effective(goals).lastOrNull { !it.effectiveFrom.isAfter(day) }

    /** Objectifs qui démarrent après [day]. */
    fun upcoming(day: LocalDate, goals: Collection<Goal>): List<Goal> =
        effective(goals).filter { it.effectiveFrom.isAfter(day) }

    /**
     * Ce qu'une mise à jour des macros datée de [day] (poids de référence) doit réécrire, **avec la date à écrire** : l'objectif
     * en vigueur ce jour-là (écrit à [day], jamais à sa date passée), puis chaque objectif qui démarre après (à sa propre date).
     * Chacun garde ses calories : un objectif temporaire garde sa baisse, son retour les calories d'avant.
     */
    fun rewriteFrom(day: LocalDate, goals: Collection<Goal>): List<Pair<LocalDate, Goal>> =
        listOfNotNull(inForce(day, goals)?.let { day to it }) + upcoming(day, goals).map { it.effectiveFrom to it }
}
