package app.trackly.core.goal

import app.trackly.core.action.Action
import app.trackly.core.action.ActionScope
import app.trackly.core.action.GoalKind
import app.trackly.core.action.Rejection
import app.trackly.core.action.Undo
import app.trackly.core.model.ActionId
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.store.ActionRecord
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Objectif temporaire : un objectif calorique plus bas pendant quelques jours, puis le **retour** à
 * l'objectif d'avant. Ce n'est pas une nouvelle sorte de donnée : deux versions datées de l'objectif nutritionnel, écrites par
 * une seule action [SetTemporaryGoal] :
 *
 * - au premier jour, l'objectif d'avant avec moins de calories (les glucides portent la baisse, voir [lowered]) ;
 * - au jour du retour, l'objectif d'avant, tel quel.
 *
 * Le retour est donc **déduit de la date**, comme tout objectif daté : aucune tâche de fond, rien à programmer, et une
 * sauvegarde restaurée le garde, puisqu'il est dans la table des objectifs.
 *
 * L'annulation ne réécrit jamais un jour passé (voir [TemporaryGoalUndo]) : avant le premier jour, tout disparaît ;
 * pendant, l'objectif d'avant revient dès aujourd'hui et les jours écoulés gardent l'objectif temporaire ; après le retour,
 * il n'y a plus rien à annuler.
 *
 * **Poids de référence** : une mise à jour des macros au jour D réécrit l'objectif en vigueur à D et chaque objectif daté
 * après D ([GoalTimeline.rewriteFrom]), chacun avec **ses** calories : l'objectif temporaire garde sa baisse, son retour
 * garde les calories d'avant, et les deux prennent les nouvelles macros. L'annulation et le statut le tolèrent.
 */
object TemporaryGoals {
    /** Baisse maximale par jour, en kcal (sécurité) ; voir aussi [MAX_DROP_SHARE]. */
    const val MAX_DROP_KCAL = 200.0

    /** Baisse maximale par jour, en part de l'objectif d'avant (sécurité) : 10 %. */
    const val MAX_DROP_SHARE = 0.10

    /** Baisse minimale par jour : en dessous, la différence ne se sent pas et ne vaut pas un changement d'objectif. */
    const val MIN_DROP_KCAL = 20.0

    /** Durée, en jours (sécurité : au plus une semaine). */
    const val MIN_DAYS = 1
    const val MAX_DAYS = 7

    /** Plancher : l'objectif temporaire ne descend jamais sous ce nombre de kcal. */
    const val MIN_KCAL = 1_500.0

    /** Le premier jour : aujourd'hui au plus tôt (jamais un jour passé), au plus tard dans une semaine. */
    const val MAX_START_AHEAD_DAYS = 7L

    /** Baisse maximale permise pour cet objectif d'avant : la plus petite des deux limites, arrondie à 10 kcal en dessous. */
    fun maxDrop(baseKcal: Double): Double = floor(min(MAX_DROP_KCAL, MAX_DROP_SHARE * baseKcal) / 10.0) * 10.0

    /**
     * L'objectif d'avant avec [dropKcal] de moins : protéines, minimum et lipides ne bougent pas, les glucides portent la
     * baisse (ils remplissent le reste des calories), sans descendre sous 0.
     */
    fun lowered(base: GoalTargets, dropKcal: Double): GoalTargets = base.copy(
        kcal = base.kcal - dropKcal,
        carbsG = base.carbsG?.let { max(0.0, (it - dropKcal / KCAL_PER_CARBS_G).roundToInt().toDouble()) },
    )

    private const val KCAL_PER_CARBS_G = 4.0

    /**
     * Où en est l'objectif temporaire qui commence le [start] (lu dans la transaction, aucune écriture), ou `null` si aucun
     * objectif temporaire n'a été appliqué à cette date. Le plus récent l'emporte quand il y en a eu plusieurs.
     */
    suspend fun status(scope: ActionScope, start: LocalDate): TemporaryGoalStatus? =
        rootAt(scope, start) { it.start == start }?.let { (root, plan) -> statusOf(scope, root, plan) }

    /**
     * L'objectif temporaire encore en vigueur, prévu ou en cours (son retour est encore à venir), ou `null`. Il n'y en a jamais
     * qu'un : [SetTemporaryGoal] refuse d'en empiler un second, et `SetGoal` de changer les calories pendant qu'il court.
     */
    suspend fun inEffect(scope: ActionScope): TemporaryGoalStatus? {
        val today = scope.today
        val horizon = today.plusDays(MAX_START_AHEAD_DAYS + MAX_DAYS)
        for (goal in GoalTimeline.effective(scope.tx.goals.allCurrent())) {
            val day = goal.effectiveFrom
            if (!day.isAfter(today) || day.isAfter(horizon)) continue
            val (root, plan) = rootAt(scope, day) { it.returnOn == day } ?: continue
            val status = statusOf(scope, root, plan)
            if (status.phase == TemporaryPhase.Scheduled || status.phase == TemporaryPhase.Running) return status
        }
        return null
    }

    /**
     * L'action [SetTemporaryGoal] qui a écrit l'objectif daté du [day] et dont le plan passe [accept]. La version courante peut
     * venir d'une réécriture des macros (poids de référence) ou d'une annulation : on remonte les versions.
     */
    private suspend fun rootAt(scope: ActionScope, day: LocalDate, accept: (Plan) -> Boolean): Pair<ActionRecord, Plan>? {
        val current = scope.tx.goals.current(day) ?: return null
        for (version in current.version downTo 1) {
            val goal = scope.tx.goals.version(day, version) ?: continue
            val writer = scope.tx.actions.get(goal.actionId ?: continue) ?: continue
            val root = rootOf(scope, writer)
            if (root.type != SetTemporaryGoal.TYPE) continue
            val plan = Plan.of(scope, root) ?: continue
            if (accept(plan)) return root to plan
        }
        return null
    }

    internal suspend fun statusOf(scope: ActionScope, root: ActionRecord, plan: Plan): TemporaryGoalStatus {
        val today = scope.today
        val head = headOf(scope, root)
        val inEffect = chainDepth(scope, head) % 2 == 0
        val stoppedOn = if (inEffect) null else stopDay(head, plan)
        val phase = when {
            !inEffect && (stoppedOn == null || !stoppedOn.isAfter(plan.start)) -> TemporaryPhase.Cancelled
            !inEffect -> TemporaryPhase.Stopped
            today.isBefore(plan.start) -> TemporaryPhase.Scheduled
            today.isBefore(plan.returnOn) -> TemporaryPhase.Running
            else -> TemporaryPhase.Finished
        }
        // Le bouton n'est proposé que si l'annulation (ou le rétablissement) passera : mêmes contrôles qu'à l'action.
        val canToggle = when (phase) {
            TemporaryPhase.Scheduled, TemporaryPhase.Running -> TemporaryGoalUndo.cancelBlocker(scope, head, plan) == null
            TemporaryPhase.Finished -> false
            TemporaryPhase.Cancelled, TemporaryPhase.Stopped -> TemporaryGoalUndo.restoreBlocker(scope, head, plan) == null
        }
        return TemporaryGoalStatus(
            action = root.id,
            head = head.id,
            phase = phase,
            start = plan.start,
            days = plan.days,
            returnOn = plan.returnOn,
            temporaryKcal = plan.temporaryKcal,
            baseKcal = plan.baseKcal,
            stoppedOn = if (phase == TemporaryPhase.Stopped) stoppedOn else null,
            canToggle = canToggle,
        )
    }

    /** Ce qu'a écrit une action [SetTemporaryGoal] : premier jour et retour, d'après ses effets et les versions qu'elle a écrites. */
    internal class Plan(val start: LocalDate, val returnOn: LocalDate, val temporaryKcal: Double, val baseKcal: Double) {
        val days: Int get() = java.time.temporal.ChronoUnit.DAYS.between(start, returnOn).toInt()

        companion object {
            suspend fun of(scope: ActionScope, record: ActionRecord): Plan? {
                val effects = record.effects.filter { it.kind == GoalKind.NAME }
                if (effects.size != 2) return null
                val start = LocalDate.parse(effects[0].key)
                val back = LocalDate.parse(effects[1].key)
                val temporary = scope.tx.goals.version(start, effects[0].afterVersion) ?: return null
                val base = scope.tx.goals.version(back, effects[1].afterVersion) ?: return null
                return Plan(start, back, temporary.targets.kcal, base.targets.kcal)
            }
        }
    }

    internal suspend fun rootOf(scope: ActionScope, record: ActionRecord): ActionRecord {
        var current = record
        while (true) current = scope.tx.actions.get(current.undoes ?: return current) ?: return current
    }

    /** Dernier maillon de la chaîne « action ← annulation ← rétablissement… » partie de [root]. */
    internal suspend fun headOf(scope: ActionScope, root: ActionRecord): ActionRecord {
        var current = root
        while (true) current = scope.tx.actions.get(current.undoneBy ?: return current) ?: return current
    }

    internal suspend fun chainDepth(scope: ActionScope, record: ActionRecord): Int {
        var depth = 0
        var current = record
        while (true) {
            current = scope.tx.actions.get(current.undoes ?: return depth) ?: return depth
            depth++
        }
    }

    internal fun goalDays(record: ActionRecord): List<LocalDate> =
        record.effects.filter { it.kind == GoalKind.NAME }.map { LocalDate.parse(it.key) }

    /** Jour d'un arrêt en cours de route : le jour qu'a écrit l'annulation, autre que le retour ; `null` pour un retrait complet. */
    internal fun stopDay(head: ActionRecord, plan: Plan): LocalDate? =
        goalDays(head).firstOrNull { it != plan.start && it != plan.returnOn }
}

/** Étape d'un objectif temporaire, déduite de la date du jour et des annulations. */
enum class TemporaryPhase {
    /** Appliqué, le premier jour n'est pas encore arrivé. */
    Scheduled,

    /** En cours : du premier jour à la veille du retour. */
    Running,

    /** Le retour a eu lieu : les jours de l'objectif temporaire sont passés, il n'y a plus rien à annuler. */
    Finished,

    /** Annulé avant son premier jour (ou le jour même) : il n'a jamais compté. */
    Cancelled,

    /** Arrêté en cours de route : les jours écoulés l'ont gardé, l'objectif d'avant est revenu à [TemporaryGoalStatus.stoppedOn]. */
    Stopped,
}

data class TemporaryGoalStatus(
    /** L'action [SetTemporaryGoal]. */
    val action: ActionId,
    /** Dernier maillon de sa chaîne d'annulations : `Undo(head)` annule ou rétablit (quand [canToggle]). */
    val head: ActionId,
    val phase: TemporaryPhase,
    val start: LocalDate,
    val days: Int,
    val returnOn: LocalDate,
    val temporaryKcal: Double,
    val baseKcal: Double,
    /** Premier jour revenu à l'objectif d'avant, pour un arrêt en cours de route. */
    val stoppedOn: LocalDate?,
    /** « Annuler » (ou « Rétablir ») peut servir sans réécrire un jour passé. */
    val canToggle: Boolean,
) {
    val end: LocalDate get() = returnOn.minusDays(1)
    val inEffect: Boolean get() = phase == TemporaryPhase.Scheduled || phase == TemporaryPhase.Running || phase == TemporaryPhase.Finished
}

/**
 * Applique un objectif temporaire : [dropKcal] de moins par jour, du [start] pendant [days] jours, puis retour à l'objectif
 * d'avant, tout seul. Les chiffres viennent de l'application (`CatchUpAdvices`) ; l'action les contrôle quand même :
 *
 * - le premier jour n'est jamais passé, et au plus dans [TemporaryGoals.MAX_START_AHEAD_DAYS] jours ;
 * - [days] entre [TemporaryGoals.MIN_DAYS] et [TemporaryGoals.MAX_DAYS] ;
 * - baisse entre [TemporaryGoals.MIN_DROP_KCAL] et [TemporaryGoals.maxDrop], jamais sous [TemporaryGoals.MIN_KCAL] ;
 * - aucun autre objectif temporaire prévu ou en cours (un seul à la fois : la base est toujours l'objectif permanent) ;
 * - aucun autre objectif daté entre le premier jour (exclu) et le retour (inclus), que le retour écraserait ;
 * - [expectedBaseKcal] : l'objectif d'avant est toujours celui de la carte.
 */
data class SetTemporaryGoal(
    val start: LocalDate,
    val days: Int,
    val dropKcal: Double,
    /** Objectif d'avant affiché sur la carte : refusé s'il a changé depuis (le bouton applique ce qui a été lu). */
    val expectedBaseKcal: Double? = null,
) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val today = scope.today
        if (start.isBefore(today)) scope.reject(Rejection.Invalid("Un objectif temporaire ne commence jamais dans le passé : au plus tôt aujourd'hui ($today)."))
        if (start.isAfter(today.plusDays(TemporaryGoals.MAX_START_AHEAD_DAYS))) {
            scope.reject(Rejection.Invalid("Premier jour trop lointain : $start (au plus ${TemporaryGoals.MAX_START_AHEAD_DAYS} jours après aujourd'hui)."))
        }
        if (days !in TemporaryGoals.MIN_DAYS..TemporaryGoals.MAX_DAYS) {
            scope.reject(Rejection.Invalid("Un objectif temporaire dure de ${TemporaryGoals.MIN_DAYS} à ${TemporaryGoals.MAX_DAYS} jours ($days demandés)."))
        }
        val returnOn = start.plusDays(days.toLong())
        TemporaryGoals.inEffect(scope)?.let {
            scope.reject(Rejection.Invalid("Un objectif temporaire est déjà prévu ou en cours (retour le ${it.returnOn}) : un seul à la fois. Rien n'a changé."))
        }
        val all = scope.tx.goals.allCurrent()
        val base = GoalTimeline.inForce(start, all)?.targets
            ?: scope.reject(Rejection.Invalid("Aucun objectif calorique en vigueur le $start : rien à baisser."))
        if (expectedBaseKcal != null && expectedBaseKcal != base.kcal) {
            scope.reject(Rejection.Invalid("Ton objectif a changé depuis cette proposition (${base.kcal.roundToInt()} kcal au lieu de ${expectedBaseKcal.roundToInt()}) : redemande un conseil dans le bilan. Rien n'a changé."))
        }
        val max = TemporaryGoals.maxDrop(base.kcal)
        if (!dropKcal.isFinite() || dropKcal < TemporaryGoals.MIN_DROP_KCAL || dropKcal > max) {
            scope.reject(Rejection.Invalid("Baisse refusée : ${dropKcal.roundToInt()} kcal par jour (entre ${TemporaryGoals.MIN_DROP_KCAL.roundToInt()} et ${max.roundToInt()} pour un objectif de ${base.kcal.roundToInt()} kcal)."))
        }
        if (base.kcal - dropKcal < TemporaryGoals.MIN_KCAL) {
            scope.reject(Rejection.Invalid("Un objectif temporaire ne descend jamais sous ${TemporaryGoals.MIN_KCAL.roundToInt()} kcal."))
        }
        val clash = GoalTimeline.effective(all).firstOrNull { it.effectiveFrom.isAfter(start) && !it.effectiveFrom.isAfter(returnOn) }
        if (clash != null) {
            scope.reject(Rejection.Invalid("Un objectif est déjà prévu le ${clash.effectiveFrom} : l'objectif temporaire le remplacerait. Rien n'a changé."))
        }
        val lowered = TemporaryGoals.lowered(base, dropKcal)
        GoalKind.writeNextVersion(scope, scope.tx.goals.current(start), goal(start, lowered, scope), inForceBefore = base)
        GoalKind.writeNextVersion(scope, scope.tx.goals.current(returnOn), goal(returnOn, base, scope), inForceBefore = lowered)
        return "Objectif temporaire : ${lowered.kcal.roundToInt()} kcal du $start au ${returnOn.minusDays(1)}, retour à ${base.kcal.roundToInt()} le $returnOn"
    }

    private fun goal(from: LocalDate, targets: GoalTargets, scope: ActionScope) =
        Goal(effectiveFrom = from, version = 1, state = RecordState.Active, targets = targets, recordedAt = scope.now, actionId = scope.id)

    companion object {
        const val TYPE = "goal.temporary"
    }
}

/**
 * Annulation d'une chaîne « objectif temporaire ← annulation ← rétablissement… », appelée par `Undo` : **jamais un jour passé
 * ne change**.
 *
 * - En vigueur, le retour est passé (ou c'est aujourd'hui) : refusé, il n'y a plus rien à annuler.
 * - En vigueur, tous les jours touchés sont aujourd'hui ou après : annulation ordinaire (tout est retiré).
 * - En vigueur, le premier jour est passé : arrêt en cours de route. L'objectif d'avant revient dès aujourd'hui (nouvelle
 *   version datée d'aujourd'hui), le retour prévu est retiré, les jours écoulés gardent l'objectif temporaire.
 * - Annulée : le rétablissement est ordinaire si tous les jours touchés sont aujourd'hui ou après, refusé sinon.
 */
internal object TemporaryGoalUndo {
    /**
     * `null` : rétablissement ordinaire, à faire par `Undo` (après contrôle). Sinon le titre de l'annulation, déjà écrite.
     *
     * Une réécriture des macros à calories constantes (poids de référence) ne bloque pas l'annulation : seule une version
     * aux **calories** changées compte comme un conflit (un autre objectif choisi entre-temps n'est jamais écrasé).
     */
    suspend fun apply(scope: ActionScope, record: ActionRecord, root: ActionRecord, depth: Int): String? {
        val plan = TemporaryGoals.Plan.of(scope, root) ?: scope.reject(Rejection.NotFound("objectif temporaire de l'action ${root.id.value}"))
        if (depth % 2 == 1) {
            restoreBlocker(scope, record, plan)?.let { scope.reject(it) }
            return null
        }
        cancelBlocker(scope, record, plan)?.let { scope.reject(it) }
        val today = scope.today
        val (startEffect, backEffect) = root.effects.filter { it.kind == GoalKind.NAME }.let { it.first() to it.last() }
        val back = scope.tx.goals.current(plan.returnOn)!!
        if (!plan.start.isBefore(today)) {
            // Rien n'a encore compté (le premier jour est aujourd'hui au plus tôt) : tout revient comme avant.
            val before = startEffect.beforeVersion?.let { scope.tx.goals.version(plan.start, it) }
            val current = scope.tx.goals.current(plan.start)
            // Sans objectif propre au premier jour, c'est l'objectif d'avant qui y reviendrait. Seul cas à corriger : une mise
            // à jour du poids de référence datée du premier jour a réécrit l'objectif temporaire et son retour, mais pas cet objectif
            // d'avant, écrit plus tôt et resté aux macros de l'ancien poids. Un rétablissement recopie ces versions : on remonte
            // jusqu'à leur auteur, pour que chaque annulation garde les macros du retour.
            val rewrite = author(scope, plan.start)?.takeIf { it.type == UpdateReferenceWeight.TYPE && author(scope, plan.returnOn)?.id == it.id }
            val previous = if (before?.isActive == true || rewrite == null) null else {
                GoalTimeline.inForce(plan.start.minusDays(1), scope.tx.goals.allCurrent())
                    ?.takeIf { (it.actionId?.value ?: Long.MIN_VALUE) < rewrite.id.value }
            }
            if (before != null && before.isActive && before.targets.kcal == back.targets.kcal && before.targets != back.targets) {
                // Le premier jour portait déjà un objectif, et ses macros ont été réécrites depuis à calories constantes
                // (poids de référence) : son retour porte ces macros. L'objectif revient avec elles, pas avec celles d'avant.
                GoalKind.writeNextVersion(scope, current, before.copy(targets = back.targets), inForceBefore = current?.targets)
            } else if (previous != null && previous.targets.kcal == back.targets.kcal && previous.targets != back.targets) {
                // Aucun objectif ne précédait le premier jour, et un poids de référence mis à jour ce jour-là a réécrit l'objectif
                // temporaire et son retour : l'objectif d'avant reviendrait avec les macros de l'ancien poids. Le premier
                // jour reçoit donc le retour, aux macros du poids de référence en vigueur.
                GoalKind.writeNextVersion(scope, current, back.copy(effectiveFrom = plan.start), inForceBefore = current?.targets)
            } else {
                GoalKind.restore(scope, startEffect.key, startEffect.beforeVersion)
            }
            GoalKind.restore(scope, backEffect.key, backEffect.beforeVersion)
            return "Annulation : ${root.title}"
        }
        // Arrêt en cours de route : aujourd'hui revient à l'objectif d'avant (macros du retour comprises), le retour prévu est
        // retiré, les jours écoulés gardent l'objectif temporaire.
        val todayGoal = scope.tx.goals.current(today)
        val running = GoalTimeline.inForce(today, scope.tx.goals.allCurrent())?.targets
        GoalKind.writeNextVersion(scope, todayGoal, back.copy(effectiveFrom = today), inForceBefore = running)
        GoalKind.restore(scope, backEffect.key, backEffect.beforeVersion)
        return "Annulation : objectif temporaire arrêté, retour à ${back.targets.kcal.roundToInt()} kcal dès le $today"
    }

    /**
     * L'action qui a écrit le contenu actuel de l'objectif du [day], en remontant les copies à l'identique faites par une
     * annulation ou un rétablissement ; `null` sans objectif ou sans auteur connu.
     */
    private suspend fun author(scope: ActionScope, day: LocalDate): ActionRecord? {
        val current = scope.tx.goals.current(day) ?: return null
        for (version in current.version downTo 1) {
            val goal = scope.tx.goals.version(day, version) ?: continue
            if (goal.state != current.state || goal.targets != current.targets) continue
            val writer = scope.tx.actions.get(goal.actionId ?: continue) ?: continue
            if (writer.type != Undo.TYPE) return writer
        }
        return null
    }

    /**
     * Pourquoi « Annuler » serait refusé maintenant ([head] en vigueur), ou `null`. Refusé quand le retour est passé, ou quand
     * un objectif aux calories différentes a été choisi entre le premier jour et aujourd'hui, ou au retour : il n'est jamais écrasé.
     */
    suspend fun cancelBlocker(scope: ActionScope, head: ActionRecord, plan: TemporaryGoals.Plan): Rejection? {
        val today = scope.today
        if (!today.isBefore(plan.returnOn)) {
            return Rejection.Invalid("Cet objectif temporaire est terminé (retour le ${plan.returnOn}) : les jours passés gardent leur objectif, il n'y a plus rien à annuler.")
        }
        val back = scope.tx.goals.current(plan.returnOn)
        if (back == null || !back.isActive || back.targets.kcal != plan.baseKcal) return Rejection.UndoConflict(head.id)
        val last = if (plan.start.isBefore(today)) today else plan.start
        val changed = GoalTimeline.effective(scope.tx.goals.allCurrent()).any { g ->
            !g.effectiveFrom.isBefore(plan.start) && !g.effectiveFrom.isAfter(last) && g.targets.kcal != plan.temporaryKcal
        }
        val first = scope.tx.goals.current(plan.start)
        if (changed || first == null || !first.isActive) return Rejection.UndoConflict(head.id)
        return null
    }

    /**
     * Pourquoi « Rétablir » serait refusé maintenant ([head] est une annulation), ou `null`. Le rétablissement refait les
     * contrôles de [SetTemporaryGoal] : aucun jour passé ne change, aucun autre objectif temporaire en vigueur, l'objectif d'avant
     * a toujours les mêmes calories, rien n'est prévu jusqu'au retour, et rien n'a été réécrit depuis l'annulation.
     */
    suspend fun restoreBlocker(scope: ActionScope, head: ActionRecord, plan: TemporaryGoals.Plan): Rejection? {
        val today = scope.today
        if (TemporaryGoals.goalDays(head).any { it.isBefore(today) }) {
            return Rejection.Invalid("Les jours passés gardent leur objectif : cet objectif temporaire ne peut plus être rétabli. Demande-en un nouveau dans le bilan.")
        }
        if (TemporaryGoals.inEffect(scope) != null) return Rejection.Invalid("Un autre objectif temporaire est déjà prévu ou en cours : un seul à la fois.")
        val from = TemporaryGoals.stopDay(head, plan) ?: plan.start
        val all = scope.tx.goals.allCurrent()
        if (GoalTimeline.inForce(from, all)?.targets?.kcal != plan.baseKcal) return Rejection.UndoConflict(head.id)
        if (GoalTimeline.effective(all).any { it.effectiveFrom.isAfter(from) && !it.effectiveFrom.isAfter(plan.returnOn) }) return Rejection.UndoConflict(head.id)
        for (effect in head.effects.filter { it.kind == GoalKind.NAME }) {
            if (scope.tx.goals.current(LocalDate.parse(effect.key))?.version != effect.afterVersion) return Rejection.UndoConflict(head.id)
        }
        return null
    }
}
