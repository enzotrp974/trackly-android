package app.trackly.core.goal

import app.trackly.core.action.Action
import app.trackly.core.action.ActionScope
import app.trackly.core.action.GoalKind
import app.trackly.core.action.RecordKind
import app.trackly.core.action.Rejection
import app.trackly.core.importing.pythonRound2
import app.trackly.core.model.ActionId
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.model.VersionedRecord
import app.trackly.core.review.WeightHistory
import app.trackly.core.store.ActionRecord
import app.trackly.core.store.Effect
import app.trackly.core.store.StoreTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max

/**
 * Poids de référence enregistré : le poids sur lequel se calculent les grammes de l'objectif nutritionnel
 * qui en dépendent (idéal et minimum de protéines, lipides). Une seule donnée ([KEY]), en versions, comme l'objectif de poids.
 * Chaque version porte le jour [from] où elle a pris effet. Sans version active, le poids de référence est celui du profil
 * repris de l'ancien suivi ([ReferenceWeights.fromProfile]).
 *
 * Rangée dans la table `weight_goal_versions` sous sa propre clé (voir `WeightGoalTables.kt`), sans migration : la prévision
 * et l'objectif de poids ne lisent que la clé `weight_goal`, ceci ne lit que la clé `reference_weight`.
 */
data class ReferenceWeight(
    val kg: Double,
    val from: LocalDate,
    override val version: Int,
    override val state: RecordState,
    override val recordedAt: Instant,
    override val actionId: ActionId?,
) : VersionedRecord<ReferenceWeight> {
    init {
        require(kg.isFinite() && kg in WeightTarget.MIN_KG..WeightTarget.MAX_KG) { "Poids de référence invalide : $kg kg" }
    }

    override val key get() = KEY
    override fun traced(version: Int, recordedAt: Instant, actionId: ActionId?) =
        copy(version = version, recordedAt = recordedAt, actionId = actionId)
    override fun withState(state: RecordState) = copy(state = state)

    companion object {
        const val KEY = "reference_weight"
    }
}

/** Type de donnée versionnée « poids de référence » (annulable comme les autres). */
object ReferenceWeightKinds {
    val ReferenceWeights = RecordKind<ReferenceWeight>(ReferenceWeight.KEY, { it.referenceWeights }, contentAware = true)
}

/** Le poids qui servira de référence, tiré des pesées : moyenne des 7 derniers jours, sinon dernière pesée. */
data class MeasuredWeight(
    /** Arrondi à 0,1 kg. */
    val kg: Double,
    /** Nombre de pesées moyennées (1 pour la dernière pesée seule). */
    val count: Int,
    /** Jour de la pesée la plus récente prise en compte. */
    val lastDay: LocalDate,
    /** Vrai : moyenne des pesées des 7 derniers jours ; faux : dernière pesée seule, plus ancienne. */
    val averaged: Boolean,
)

/** Un objectif daté réécrit par la mise à jour : grammes recalculés, calories identiques. */
data class GoalRewrite(
    /** Date de départ écrite : le jour de la mise à jour pour l'objectif en vigueur, sinon la date de l'objectif prévu. */
    val from: LocalDate,
    val before: GoalTargets,
    val after: GoalTargets,
)

/** Ce que ferait la mise à jour du poids de référence au jour donné (lecture seule). */
sealed interface ReferenceWeightPlan {
    /** Aucun poids de référence connu (ni enregistré, ni dans le profil repris) : rien à mettre à l'échelle. */
    data object NoReference : ReferenceWeightPlan

    /** Aucune pesée utilisable : rien ne change. */
    data class NoWeighIn(val currentKg: Double) : ReferenceWeightPlan

    /** Les pesées donnent le poids de référence actuel, à 0,1 kg près : rien ne change. */
    data class Unchanged(val currentKg: Double, val measured: MeasuredWeight) : ReferenceWeightPlan

    /** Refusé par un garde-fou (protéines sous le minimum) : rien ne change. */
    data class Refused(val currentKg: Double, val measured: MeasuredWeight, val message: String) : ReferenceWeightPlan

    data class Change(
        val beforeKg: Double,
        val measured: MeasuredWeight,
        val day: LocalDate,
        /** Objectif en vigueur au jour de la mise à jour, avant et après (le récapitulatif de la page), ou `null` sans objectif. */
        val inForce: GoalRewrite?,
        /** Les versions à écrire : objectif en vigueur au jour de la mise à jour, puis chaque objectif déjà prévu après. */
        val rewrites: List<GoalRewrite>,
    ) : ReferenceWeightPlan {
        val afterKg: Double get() = measured.kg
    }
}

/**
 * Résultat de `TracklyEngine.updateReferenceWeight` : le calcul ([plan]), l'action écrite quand le poids a changé ([action]),
 * ou la raison d'un refus au moment d'écrire ([refusal]).
 */
data class ReferenceWeightOutcome(val plan: ReferenceWeightPlan, val action: ActionId? = null, val refusal: String? = null)

/**
 * Calcul du poids de référence, sans écriture.
 *
 * **Règle** (le dernier poids donné, ou la moyenne récente) : à la production d'un bilan, la moyenne des pesées des
 * 7 derniers jours ([AVERAGE_DAYS]), sinon la dernière pesée si elle a au plus [MAX_AGE_DAYS] jours (le seuil de fraîcheur
 * de la prévision d'arrivée), arrondie à 0,1 kg.
 *
 * **Grammes** ([rescaled]) : chaque objectif garde ses g/kg. L'idéal de protéines, le minimum de protéines et les lipides sont
 * multipliés par nouveau ÷ ancien poids de référence ; les glucides prennent la différence, pour que les calories des macros
 * restent les mêmes. Les calories de l'objectif ne changent jamais. Pour un objectif tiré des règles du profil
 * (`protein_ideal_g_per_kg`, `protein_min_g_per_kg`, `fat_g_per_kg`), c'est exactement le calcul de la
 * reprise ; un choix fait à la main dans les Réglages garde ses proportions au lieu d'être écrasé.
 */
object ReferenceWeights {
    const val AVERAGE_DAYS = 7L
    const val MAX_AGE_DAYS = 14L

    fun measured(history: WeightHistory, day: LocalDate): MeasuredWeight? {
        val known = history.all.filter { !it.day.isAfter(day) }
        val recent = known.filter { !it.day.isBefore(day.minusDays(AVERAGE_DAYS - 1)) }
        if (recent.isNotEmpty()) return MeasuredWeight(round1(recent.sumOf { it.kg } / recent.size), recent.size, recent.last().day, true)
        val last = known.lastOrNull() ?: return null
        if (last.day.isBefore(day.minusDays(MAX_AGE_DAYS))) return null
        return MeasuredWeight(round1(last.kg), 1, last.day, false)
    }

    /**
     * Poids de référence du profil repris pour le [day] : celui de la règle en vigueur (`reference_weight_kg`), sinon celui du
     * profil (`current_reference_weight_kg`). Même lecture que la reprise et le minimum de protéines.
     */
    fun fromProfile(profile: JsonObject?, day: LocalDate): Double? {
        profile ?: return null
        val rule = (profile["goals"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { r -> (r["effective_from"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { it to r } }
            ?.filter { (from, _) -> !from.isAfter(day) }
            ?.maxByOrNull { (from, _) -> from }?.second
        val kg = rule?.number("reference_weight_kg") ?: profile.number("current_reference_weight_kg") ?: return null
        return kg.takeIf { it.isFinite() && it in WeightTarget.MIN_KG..WeightTarget.MAX_KG }
    }

    /** Poids de référence en vigueur : la version enregistrée active, sinon celui du profil repris, sinon `null`. */
    fun current(record: ReferenceWeight?, profile: JsonObject?, day: LocalDate): Double? =
        record?.takeIf { it.isActive }?.kg ?: fromProfile(profile, day)

    /**
     * [targets] pour un poids de référence passé de [fromKg] à [toKg] : mêmes g/kg, mêmes calories (voir l'en-tête). Arrondi
     * au centième de gramme, comme la reprise : l'écart ne s'accumule pas d'un bilan à l'autre (test de dérive).
     */
    fun rescaled(targets: GoalTargets, fromKg: Double, toKg: Double): GoalTargets {
        require(fromKg > 0 && toKg > 0) { "Poids de référence invalide" }
        val ratio = toKg / fromKg
        val protein = targets.proteinG?.let { pythonRound2(it * ratio) }
        val fat = targets.fatG?.let { pythonRound2(it * ratio) }
        val freedKcal = ((targets.proteinG ?: 0.0) - (protein ?: 0.0)) * GoalCoherence.KCAL_PER_PROTEIN_G +
            ((targets.fatG ?: 0.0) - (fat ?: 0.0)) * GoalCoherence.KCAL_PER_FAT_G
        return targets.copy(
            proteinG = protein,
            proteinMinG = targets.proteinMinG?.let { pythonRound2(it * ratio) },
            fatG = fat,
            carbsG = targets.carbsG?.let { max(0.0, pythonRound2(it + freedKcal / GoalCoherence.KCAL_PER_CARBS_G)) },
        )
    }

    /** Ce que ferait la mise à jour au [day], lu dans [tx]. */
    suspend fun plan(tx: StoreTransaction, day: LocalDate): ReferenceWeightPlan {
        val profile = profileOf(tx)
        val currentKg = current(tx.referenceWeights.current(ReferenceWeight.KEY), profile, day) ?: return ReferenceWeightPlan.NoReference
        val imported = WeightHistory.parseImported(tx.imports.latest("weights", "weights")?.json)
        val declared = tx.weekInfos.allCurrent().filter { it.isActive }.flatMap { it.weighIns }
        val measured = measured(WeightHistory.of(imported, declared), day) ?: return ReferenceWeightPlan.NoWeighIn(currentKg)
        if (round1(currentKg) == measured.kg) return ReferenceWeightPlan.Unchanged(currentKg, measured)
        // L'objectif en vigueur (écrit au jour même) puis chaque objectif déjà prévu, chacun avec ses calories.
        val all = GoalTimeline.rewriteFrom(day, tx.goals.allCurrent()).map { (from, goal) -> GoalRewrite(from, goal.targets, rescaled(goal.targets, currentKg, measured.kg)) }
        val inForce = all.firstOrNull { it.from == day }
        val rewrites = all.filter { it.before != it.after }
        rewrites.firstOrNull { GoalCoherence.belowMinimum(it.after) }?.let { bad ->
            return ReferenceWeightPlan.Refused(
                currentKg, measured,
                "les protéines du ${bad.from} passeraient sous leur minimum (${bad.after.proteinG!!.toInt()} g pour ${bad.after.proteinMinG!!.toInt()} g)",
            )
        }
        return ReferenceWeightPlan.Change(currentKg, measured, day, inForce, rewrites)
    }

    /**
     * La mise à jour faite le [day] si elle est encore en vigueur (version active datée de ce jour, écrite par
     * [UpdateReferenceWeight], non annulée), refaite en résultat pour la page ; `null` sinon.
     */
    suspend fun doneOn(tx: StoreTransaction, day: LocalDate): ReferenceWeightOutcome? {
        val record = tx.referenceWeights.current(ReferenceWeight.KEY)?.takeIf { it.isActive && it.from == day } ?: return null
        val action = record.actionId?.let { tx.actions.get(it) }?.takeIf { it.type == UpdateReferenceWeight.TYPE && it.undoneBy == null } ?: return null
        val refEffect = action.effects.lastOrNull { it.kind == ReferenceWeight.KEY } ?: return null
        val beforeKg = refEffect.beforeVersion?.let { tx.referenceWeights.version(ReferenceWeight.KEY, it) }?.takeIf { it.isActive }?.kg
            ?: fromProfile(profileOf(tx), day) ?: return null
        val all = tx.goals.allCurrent()
        val rewrites = action.effects.filter { it.kind == GoalKind.NAME }.mapNotNull { e ->
            val from = LocalDate.parse(e.key)
            val after = tx.goals.version(from, e.afterVersion)?.targets ?: return@mapNotNull null
            val before = e.beforeVersion?.let { tx.goals.version(from, it) }?.takeIf { it.isActive }?.targets
                ?: GoalTimeline.inForce(from.minusDays(1), all.filter { it.effectiveFrom.isBefore(from) })?.targets
                ?: return@mapNotNull null
            GoalRewrite(from, before, after)
        }
        val imported = WeightHistory.parseImported(tx.imports.latest("weights", "weights")?.json)
        val declared = tx.weekInfos.allCurrent().filter { it.isActive }.flatMap { it.weighIns }
        val measured = measured(WeightHistory.of(imported, declared), day)?.copy(kg = record.kg) ?: MeasuredWeight(record.kg, 1, day, true)
        val plan = ReferenceWeightPlan.Change(beforeKg, measured, day, rewrites.firstOrNull { it.from == day }, rewrites)
        return ReferenceWeightOutcome(plan, action.id)
    }

    /** Le profil repris de l'ancien suivi (`profile`/`profile`), ou `null` s'il manque ou ne se lit pas. */
    suspend fun profileOf(tx: StoreTransaction): JsonObject? = tx.imports.latest("profile", "profile")?.json?.let { text ->
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    /** Les grammes de [targets] dépendent-ils du poids de référence ? (Un objectif sans protéines ni lipides n'en dépend pas.) */
    fun dependsOnWeight(targets: GoalTargets): Boolean = rescaled(targets, 1.0, 2.0) != targets

    fun round1(kg: Double): Double = BigDecimal(kg.toString()).setScale(1, RoundingMode.HALF_UP).toDouble()

    /** « 72,4 » : un chiffre après la virgule, sans « ,0 ». */
    fun label(kg: Double): String = BigDecimal(kg.toString()).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}

/**
 * Met à jour le poids de référence au [day] (le jour de suivi du bilan produit), d'après les pesées : une nouvelle version
 * datée du poids de référence, et une nouvelle version de l'objectif en vigueur ce jour-là (datée du [day]) et de chaque
 * objectif déjà prévu après (objectif temporaire et son retour compris), chacun avec **ses** calories. Les jours d'avant
 * ne changent jamais. Annulable : voir [ReferenceWeightUndo].
 *
 * Appelée par l'application à la production du rapport, jamais par l'IA. Refusée (rien n'est écrit) sans pesée utilisable,
 * sans poids de référence connu, avec le même poids, ou si des protéines passaient sous leur minimum.
 */
data class UpdateReferenceWeight(val day: LocalDate) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        if (day.isBefore(scope.today)) scope.reject(Rejection.Invalid("Les jours passés gardent leur objectif : le poids de référence se met à jour au jour de suivi en cours (${scope.today})."))
        if (day.isAfter(scope.today.plusDays(1))) scope.reject(Rejection.Invalid("Date trop lointaine : $day."))
        val plan = when (val p = ReferenceWeights.plan(scope.tx, day)) {
            is ReferenceWeightPlan.Change -> p
            is ReferenceWeightPlan.Unchanged -> scope.reject(Rejection.NoChange)
            is ReferenceWeightPlan.NoWeighIn -> scope.reject(Rejection.Invalid(NO_WEIGH_IN))
            ReferenceWeightPlan.NoReference -> scope.reject(Rejection.Invalid(NO_REFERENCE))
            is ReferenceWeightPlan.Refused -> scope.reject(Rejection.Invalid("Poids de référence non mis à jour : ${p.message}."))
        }
        val kind = ReferenceWeightKinds.ReferenceWeights
        kind.writeNextVersion(
            scope,
            kind.versionsIn(scope).current(ReferenceWeight.KEY),
            ReferenceWeight(plan.afterKg, day, 1, RecordState.Active, scope.now, scope.id),
        )
        for (rewrite in plan.rewrites) {
            GoalKind.writeNextVersion(
                scope,
                scope.tx.goals.current(rewrite.from),
                Goal(effectiveFrom = rewrite.from, version = 1, state = RecordState.Active, targets = rewrite.after, recordedAt = scope.now, actionId = scope.id),
                inForceBefore = rewrite.before,
            )
        }
        return "Poids de référence : ${ReferenceWeights.label(plan.beforeKg)} → ${ReferenceWeights.label(plan.afterKg)} kg, calories inchangées"
    }

    companion object {
        const val TYPE = "reference_weight.update"
        const val NO_WEIGH_IN = "Aucune pesée utilisable (ni ces 7 derniers jours, ni depuis 14 jours) : le poids de référence ne change pas."
        const val NO_REFERENCE = "Aucun poids de référence connu : rien à mettre à jour."
    }
}

/**
 * Annulation (et rétablissement) d'une mise à jour du poids de référence, appelée par `Undo`. Elle remet exactement l'état
 * d'avant, ou elle est refusée avec ce qu'il faut faire : **jamais un objectif daté dans un état faux, jamais un jour passé
 * réécrit**.
 *
 * - Chaque donnée touchée doit avoir encore le contenu écrit par la mise à jour. Une version plus récente au même contenu
 *   compte comme intacte (un objectif temporaire posé puis annulé remet la version de la mise à jour).
 * - Aucun objectif actif daté du jour de la mise à jour ou après ne doit avoir été écrit depuis (objectif temporaire, Réglages) :
 *   ses grammes viennent du nouveau poids, ils resteraient faux si la référence revenait à l'ancien. Un objectif posé puis
 *   annulé est supprimé : il ne bloque plus.
 * - Le jour même, tout revient comme avant. Après, les jours écoulés gardent leurs objectifs : l'objectif d'avant revient
 *   **dès aujourd'hui** (nouvelle version datée d'aujourd'hui), les objectifs prévus après retrouvent leur version d'avant.
 * - Rétablir est refusé s'il réécrivait un jour passé, ou si un objectif touché a changé depuis l'annulation (objectif
 *   temporaire annulé entre-temps, par exemple) : le prochain bilan remettra le poids de référence à jour.
 *
 * Refus : « Annule d'abord « … », puis le poids de référence. »
 */
internal object ReferenceWeightUndo {
    const val NO_REDO = "Les jours passés gardent leur objectif : ce poids de référence ne peut plus être rétabli. Le prochain bilan le remettra à jour."
    const val CHANGED_SINCE = "Un objectif ou le poids de référence a changé depuis l'annulation : ce poids de référence ne peut plus être rétabli. Le prochain bilan le remettra à jour."

    suspend fun apply(scope: ActionScope, record: ActionRecord, root: ActionRecord, depth: Int): String {
        val today = scope.today
        val cancel = depth % 2 == 0
        val effects = record.effects
            .groupBy { it.kind to it.key }
            .values
            .map { touches -> touches.first().copy(afterVersion = touches.last().afterVersion) }
        if (effects.isEmpty()) scope.reject(Rejection.NothingToUndo)
        if (!cancel && effects.any { it.kind == GoalKind.NAME && LocalDate.parse(it.key).isBefore(today) }) scope.reject(Rejection.Invalid(NO_REDO))
        val restore = mutableListOf<Effect>()
        // Objectifs remis par l'annulation d'un objectif temporaire au premier jour, aux macros du nouveau poids : sans
        // l'objectif temporaire, ce jour n'avait pas d'objectif propre. Ils sont retirés avec le poids de référence.
        val carried = mutableListOf<Effect>()
        for (effect in effects) {
            val kind = scope.kinds[effect.kind] ?: scope.reject(Rejection.Invalid("Type de donnée inconnu : ${effect.kind}"))
            if (kind.unchangedSince(scope, effect.key, effect.afterVersion)) {
                restore += effect
                continue
            }
            // Rétablir après une modification intermédiaire remettrait le poids sans les objectifs qui en dépendent : refusé.
            if (!cancel) scope.reject(Rejection.Invalid(CHANGED_SINCE))
            if (effect.kind == GoalKind.NAME) {
                // Un objectif réécrit ici puis retiré depuis (objectif temporaire annulé) reste retiré : le remettre le ferait revenir.
                if (scope.tx.goals.current(LocalDate.parse(effect.key))?.isActive == false) continue
                if (carriedByTemporaryCancel(scope, effect.key)) {
                    carried += effect
                    continue
                }
            }
            scope.reject(Rejection.Invalid(blocked(scope, writer(scope, effect))))
        }
        val day = day(scope, root)
        if (day != null) {
            val touched = effects.filter { it.kind == GoalKind.NAME }.map { it.key }.toSet()
            val later = scope.tx.goals.allCurrent()
                .filter { it.isActive && !it.effectiveFrom.isBefore(day) && it.effectiveFrom.toString() !in touched }
                .filter { (it.actionId?.value ?: Long.MIN_VALUE) > record.id.value }
                .filter { ReferenceWeights.dependsOnWeight(it.targets) }
                .minByOrNull { it.effectiveFrom }
            if (later != null) scope.reject(Rejection.Invalid(blocked(scope, later.actionId)))
        }
        val past = { e: Effect -> e.kind == GoalKind.NAME && LocalDate.parse(e.key).isBefore(today) }
        val fromToday = cancel && (restore + carried).any(past)
        // L'objectif qui valait aujourd'hui avant la mise à jour : versions d'avant pour ce qui est remis, rien pour ce qui est retiré.
        val wanted = if (!fromToday) null else {
            val replaced = (restore + carried).filter { it.kind == GoalKind.NAME }.map { LocalDate.parse(it.key) }.toSet()
            val previous = restore.filter { it.kind == GoalKind.NAME }.mapNotNull { e -> e.beforeVersion?.let { scope.tx.goals.version(LocalDate.parse(e.key), it) } }
            GoalTimeline.inForce(today, scope.tx.goals.allCurrent().filter { it.effectiveFrom !in replaced } + previous)
        }
        for (effect in restore) if (!fromToday || !past(effect)) scope.kinds.getValue(effect.kind).restore(scope, effect.key, effect.beforeVersion)
        for (effect in carried) if (!fromToday || !past(effect)) GoalKind.restore(scope, effect.key, null)
        scope.undoes = record.id
        scope.tx.actions.markUndone(record.id, scope.id)
        if (!fromToday) return (if (depth % 2 == 1) "Rétablissement : " else "Annulation : ") + root.title
        // Après le jour du bilan : les jours écoulés gardent leurs objectifs, l'objectif d'avant revient dès aujourd'hui.
        val now = GoalTimeline.inForce(today, scope.tx.goals.allCurrent())
        if (wanted != null && now?.targets != wanted.targets) {
            GoalKind.writeNextVersion(scope, scope.tx.goals.current(today), wanted.copy(effectiveFrom = today), inForceBefore = now?.targets)
        }
        val kg = ReferenceWeights.current(scope.tx.referenceWeights.current(ReferenceWeight.KEY), ReferenceWeights.profileOf(scope.tx), today)
        val back = kg?.let { "revenu à ${ReferenceWeights.label(it)} kg" } ?: "revenu à l'ancien poids"
        return "Annulation : poids de référence $back dès aujourd'hui, les jours passés gardent leur objectif"
    }

    /**
     * L'objectif du [key] a été écrit par l'annulation d'un objectif temporaire qui commençait ce jour-là sans objectif propre
     * avant lui : c'est le retour, posé au premier jour pour garder les macros du nouveau poids (`TemporaryGoalUndo`).
     */
    private suspend fun carriedByTemporaryCancel(scope: ActionScope, key: String): Boolean {
        val from = LocalDate.parse(key)
        val goal = scope.tx.goals.current(from)?.takeIf { it.isActive } ?: return false
        val writer = goal.actionId?.let { scope.tx.actions.get(it) }?.takeIf { it.undoes != null } ?: return false
        if (TemporaryGoals.chainDepth(scope, writer) % 2 == 0) return false
        val temporary = TemporaryGoals.rootOf(scope, writer).takeIf { it.type == SetTemporaryGoal.TYPE } ?: return false
        val start = temporary.effects.firstOrNull { it.kind == GoalKind.NAME }?.takeIf { it.key == key } ?: return false
        return start.beforeVersion?.let { scope.tx.goals.version(from, it) }?.isActive != true
    }

    /** Message de refus : quelle action défaire d'abord. */
    private suspend fun blocked(scope: ActionScope, writer: ActionId?): String {
        val action = writer?.let { scope.tx.actions.get(it) }
        val title = action?.title?.takeIf { it.isNotBlank() }
        return if (title != null && action.undoes != null) {
            // Une annulation ne se défait pas toujours (jours passés) : on ne promet pas un chemin qui n'existe peut-être pas.
            "« $title » a changé un objectif calculé sur le nouveau poids : le poids de référence ne peut plus être annulé."
        } else if (title != null) {
            "Annule d'abord « $title », puis le poids de référence : cet objectif a été calculé sur le nouveau poids."
        } else {
            "Un objectif a changé depuis la mise à jour : le poids de référence ne peut plus être annulé sans le défaire d'abord."
        }
    }

    /** Jour de la mise à jour d'origine : celui de la version du poids de référence qu'elle a écrite. */
    private suspend fun day(scope: ActionScope, root: ActionRecord): LocalDate? {
        val effect = root.effects.lastOrNull { it.kind == ReferenceWeight.KEY } ?: return null
        return scope.tx.referenceWeights.version(ReferenceWeight.KEY, effect.afterVersion)?.from
    }

    private suspend fun writer(scope: ActionScope, effect: Effect): ActionId? = when (effect.kind) {
        GoalKind.NAME -> scope.tx.goals.current(LocalDate.parse(effect.key))?.actionId
        ReferenceWeight.KEY -> scope.tx.referenceWeights.current(effect.key)?.actionId
        else -> null
    }
}
