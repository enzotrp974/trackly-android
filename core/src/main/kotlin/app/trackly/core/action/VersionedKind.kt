package app.trackly.core.action

import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.goal.ReferenceWeightKinds
import app.trackly.core.goal.WeightGoalKinds
import app.trackly.core.review.ReviewKinds
import java.time.LocalDate

/**
 * Type de donnée versionnée que l'annulation sait rétablir. Un nouveau type
 * (produit, plat, provision…) fournit le sien et l'ajoute à la liste passée au moteur.
 */
interface VersionedKind {
    val name: String

    suspend fun currentVersion(scope: ActionScope, key: String): Int?

    /**
     * Écrit une nouvelle version qui reprend le contenu de la version [toVersion],
     * ou une version `deleted` si [toVersion] vaut `null` (la donnée n'existait pas).
     */
    suspend fun restore(scope: ActionScope, key: String, toVersion: Int?): Change

    /**
     * La version courante [current] a le même contenu que [written] (seule la trace diffère) : une action plus récente l'a
     * réécrite à l'identique, par exemple l'annulation d'une mise à jour du poids de référence. Annuler l'action qui a
     * écrit [written] n'écrase alors rien. Les objectifs, le poids de référence et les consommations le font ; pour les autres
     * données, seule la comparaison des versions compte (une provision remise à l'identique reste un conflit).
     */
    suspend fun sameContent(scope: ActionScope, key: String, current: Int?, written: Int): Boolean = false

    /** La donnée a encore ce qu'a écrit la version [written] : même version, ou même contenu. */
    suspend fun unchangedSince(scope: ActionScope, key: String, written: Int): Boolean {
        val current = currentVersion(scope, key)
        return current == written || sameContent(scope, key, current, written)
    }
}

object CoreKinds {
    val all: List<VersionedKind> = listOf(ConsumptionKind, GoalKind) + HistoryKinds.all + ReviewKinds.all + WeightGoalKinds.WeightGoals +
        ReferenceWeightKinds.ReferenceWeights
}

object ConsumptionKind : VersionedKind {
    const val NAME = "consumption"
    override val name = NAME

    override suspend fun currentVersion(scope: ActionScope, key: String) =
        scope.tx.consumptions.current(EntryId(key))?.version

    override suspend fun restore(scope: ActionScope, key: String, toVersion: Int?): Change {
        val id = EntryId(key)
        val current = scope.tx.consumptions.current(id) ?: scope.reject(Rejection.NotFound("consommation $key"))
        val content = if (toVersion == null) {
            current.copy(state = RecordState.Deleted)
        } else {
            scope.tx.consumptions.version(id, toVersion) ?: scope.reject(Rejection.NotFound("version $toVersion de $key"))
        }
        return writeNextVersion(scope, current, content)
    }

    /**
     * Une correction annulée remet la consommation à l'identique (même contenu, autre version) : l'ajout qu'elle
     * corrigeait n'est plus « corrigé depuis » et s'annule sans conflit. Seule la trace diffère.
     */
    override suspend fun sameContent(scope: ActionScope, key: String, current: Int?, written: Int): Boolean {
        current ?: return false
        val id = EntryId(key)
        val now = scope.tx.consumptions.version(id, current) ?: return false
        val then = scope.tx.consumptions.version(id, written) ?: return false
        return now.sameContentAs(then)
    }

    /** Écrit [content] comme version suivante de [current] et enregistre le changement. */
    suspend fun writeNextVersion(scope: ActionScope, current: Consumption?, content: Consumption): ConsumptionChange {
        val next = content.copy(
            version = (current?.version ?: 0) + 1,
            recordedAt = scope.now,
            actionId = scope.id,
        )
        scope.tx.consumptions.insert(next)
        return ConsumptionChange(current, next).also(scope::record)
    }
}

object GoalKind : VersionedKind {
    const val NAME = "goal"
    override val name = NAME

    override suspend fun currentVersion(scope: ActionScope, key: String) =
        scope.tx.goals.current(LocalDate.parse(key))?.version

    override suspend fun sameContent(scope: ActionScope, key: String, current: Int?, written: Int): Boolean {
        current ?: return false
        val from = LocalDate.parse(key)
        val now = scope.tx.goals.version(from, current) ?: return false
        val then = scope.tx.goals.version(from, written) ?: return false
        return now.state == then.state && now.targets == then.targets
    }

    override suspend fun restore(scope: ActionScope, key: String, toVersion: Int?): Change {
        val from = LocalDate.parse(key)
        val current = scope.tx.goals.current(from) ?: scope.reject(Rejection.NotFound("objectif du $key"))
        val content = if (toVersion == null) {
            current.copy(state = RecordState.Deleted)
        } else {
            scope.tx.goals.version(from, toVersion) ?: scope.reject(Rejection.NotFound("version $toVersion de l'objectif du $key"))
        }
        return writeNextVersion(scope, current, content)
    }

    suspend fun writeNextVersion(scope: ActionScope, current: Goal?, content: Goal, inForceBefore: GoalTargets? = null): GoalChange {
        val next = content.copy(
            version = (current?.version ?: 0) + 1,
            recordedAt = scope.now,
            actionId = scope.id,
        )
        scope.tx.goals.insert(next)
        return GoalChange(current, next, inForceBefore).also(scope::record)
    }
}
