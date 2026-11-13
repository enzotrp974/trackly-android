package app.trackly.core.engine

import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ActionRejected
import app.trackly.core.action.ActionResult
import app.trackly.core.action.ActionScope
import app.trackly.core.action.CoreKinds
import app.trackly.core.action.Rejection
import app.trackly.core.action.UndoStatus
import app.trackly.core.action.UndoStatuses
import app.trackly.core.action.VersionedKind
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.ActionId
import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Goal
import app.trackly.core.model.RecordState
import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.Dishes
import app.trackly.core.model.Preference
import app.trackly.core.model.TermCorrection
import app.trackly.core.model.TermCorrections
import app.trackly.core.model.Product
import app.trackly.core.model.Provision
import app.trackly.core.model.Recipe
import app.trackly.core.goal.ReferenceWeight
import app.trackly.core.goal.ReferenceWeightOutcome
import app.trackly.core.goal.ReferenceWeightPlan
import app.trackly.core.goal.ReferenceWeights
import app.trackly.core.goal.UpdateReferenceWeight
import app.trackly.core.goal.WeightGoal
import app.trackly.core.goal.WeightTarget
import app.trackly.core.review.ReviewParams
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeeklyFigures
import app.trackly.core.review.WeeklyReview
import app.trackly.core.review.WeightHistory
import app.trackly.core.store.ActionRecord
import app.trackly.core.store.TracklyStore
import app.trackly.core.summary.DaySummary
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.trackly.core.goal.TemporaryGoalStatus
import app.trackly.core.goal.TemporaryGoals
import app.trackly.core.review.CatchUpAdvices
import app.trackly.core.review.CatchUpResult
import java.time.Clock
import java.time.LocalDate

/**
 * Moteur métier : applique les actions de façon atomique et calcule les bilans.
 * Point d'entrée unique des écrans, du panneau de développement et de l'assistant.
 */
class TracklyEngine(
    private val store: TracklyStore,
    private val clock: Clock,
    kinds: List<VersionedKind> = CoreKinds.all,
) {
    private val kinds: Map<String, VersionedKind> = kinds.associateBy { it.name }.also {
        require(it.size == kinds.size) { "Deux types de données portent le même nom" }
    }

    private val _revision = MutableStateFlow(0L)

    /** Augmente après chaque action appliquée (écran ou assistant) : de quoi recharger le catalogue. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Instant présent selon l'horloge du moteur. */
    fun now(): java.time.Instant = clock.instant()

    /** Jour de suivi en cours (avant 5 h : la veille). */
    fun today(): LocalDate = TrackingDay.today(clock)

    /**
     * Applique [action] dans une seule transaction. Un refus, comme une donnée invalide,
     * n'écrit rien et revient sous forme de [ActionOutcome.Rejected].
     */
    suspend fun execute(action: Action): ActionOutcome = try {
        val outcome = store.transaction {
            val now = clock.instant()
            val id = actions.begin(action.type, now)
            val scope = ActionScope(id, now, this, kinds)
            val title = action.apply(scope)
            if (scope.changes.isEmpty() && !action.recordedWhenUnchanged) scope.reject(Rejection.NoChange)
            actions.complete(id, title, if (action.undoable) scope.changes.map { it.effect } else emptyList(), scope.undoes)
            ActionOutcome.Applied(ActionResult(id, action.type, title, now, scope.changes.toList(), scope.undoes))
        }
        // Les écrans qui montrent le catalogue (Mes plats) se rechargent après toute action appliquée.
        _revision.value += 1
        outcome
    } catch (e: ActionRejected) {
        ActionOutcome.Rejected(e.rejection)
    } catch (e: IllegalArgumentException) {
        ActionOutcome.Rejected(Rejection.Invalid(e.message ?: "Donnée invalide"))
    }

    suspend fun daySummary(day: LocalDate): DaySummary = store.transaction {
        DaySummary.of(
            day,
            consumptions.activeBetween(day, day),
            goals.allCurrent(),
            dailyEstimates.allVersionsBetween(day, day),
            dayTracking.currentBetween(day, day),
        )
    }

    /** Bilans des jours de [from] à [to] inclus, recalculés à chaque changement. */
    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> {
        require(!from.isAfter(to)) { "Période inversée : $from > $to" }
        return combine(
            store.observeActiveConsumptions(from, to),
            store.observeGoals(),
            store.observeDailyEstimates(from, to),
            store.observeDayTracking(from, to),
        ) { consumptions, goals, estimates, tracking ->
            val consumptionsByDay = consumptions.groupBy { it.day }
            val estimatesByDay = estimates.groupBy { it.day }
            val trackingByDay = tracking.groupBy { it.day }
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { day ->
                    DaySummary.of(
                        day,
                        consumptionsByDay[day].orEmpty(),
                        goals,
                        estimatesByDay[day].orEmpty(),
                        trackingByDay[day].orEmpty(),
                    )
                }
                .toList()
        }.distinctUntilChanged()
    }

    fun observeFirstDay(): Flow<LocalDate?> = store.observeFirstDay().distinctUntilChanged()

    /** Nombre de documents repris de l'ancien système (0 : aucune reprise faite). */
    suspend fun importedSourceCount(): Int = store.transaction { imports.count() }

    fun observeRecentActions(limit: Int = 20): Flow<List<ActionRecord>> = store.observeRecentActions(limit)

    /** Action du journal (titre, effets, annulée ou non), ou `null` si inconnue. */
    suspend fun action(id: ActionId): ActionRecord? = store.transaction { actions.get(id) }

    /**
     * Statut d'annulation de chaque action de [ids] (en vigueur ou annulée, maillon à annuler pour basculer,
     * données modifiées depuis), lu en une transaction. Les actions inconnues sont absentes du résultat.
     */
    suspend fun undoStatuses(ids: Collection<ActionId>): Map<ActionId, UndoStatus> = store.transaction {
        // Lecture seule : la portée ne sert qu'à lire les versions courantes, rien n'est écrit ni journalisé.
        val scope = ActionScope(ActionId(0), clock.instant(), this, kinds)
        ids.distinct().mapNotNull { id -> UndoStatuses.of(scope, id)?.let { id to it } }.toMap()
    }

    /** Fiches courantes des produits (ponctuels et récurrents), supprimées comprises. */
    suspend fun allProducts(): List<Product> = store.transaction { products.allCurrent() }

    /** Fiches courantes des plats récurrents (recettes reprises), supprimées comprises. */
    suspend fun allRecipes(): List<Recipe> = store.transaction { recipes.allCurrent() }

    /** Provisions (versions courantes, « j'ai » et « plus »). */
    suspend fun allProvisions(): List<Provision> = store.transaction { provisions.allCurrent() }

    /**
     * Préférences durables courantes et actives (règles explicites de l'utilisateur, reprises ou notées).
     * Une règle désactivée (`active: false`) ou supprimée n'y figure pas ; elle reste dans la base. Ordre stable
     * (type, déclencheur, identifiant) pour que le contexte de l'assistant ne varie pas sans changement.
     */
    suspend fun activePreferences(): List<Preference> = store.transaction { preferences.allCurrent() }
        .filter { it.isActive && it.active }
        .sortedWith(compareBy<Preference>({ it.kind }, { it.trigger.lowercase() }, { it.preferenceId }))

    /** Mots corrigés actifs (« yaourd » → « yaourt »), triés par mot entendu. */
    suspend fun activeTermCorrections(): List<TermCorrection> = TermCorrections.active(store.transaction { preferences.allCurrent() })

    /** [activeTermCorrections] à jour après chaque action appliquée. */
    fun observeTermCorrections(): Flow<List<TermCorrection>> = revision.map { activeTermCorrections() }.distinctUntilChanged()

    /** Plats et variantes, et provisions « j'ai » : ce que montre « Mes plats », lu en une fois. */
    suspend fun dishBook(): DishBook = store.transaction {
        DishBook(Dishes.group(recipes.allCurrent()), provisions.allCurrent().filter { it.isActive }.sortedBy { it.name.lowercase() })
    }

    /** [dishBook] à jour après chaque action appliquée. */
    fun observeDishBook(): Flow<DishBook> = revision.map { dishBook() }.distinctUntilChanged()

    /** Fiche courante d'un produit, ou `null`. */
    suspend fun product(productId: String): Product? = store.transaction { products.current(productId) }

    /** Consommations actives liées à un produit. */
    suspend fun productUses(productId: String): List<Consumption> = store.transaction { consumptions.activeForProduct(productId) }

    /** Toutes les versions d'une consommation, de la plus ancienne à la plus récente. */
    suspend fun history(entryId: EntryId): List<Consumption> = store.transaction { consumptions.history(entryId) }

    // ───── Objectifs ─────

    /** Objectifs en vigueur à travers le temps (version courante active de chaque date), du plus ancien au plus récent. */
    fun observeGoals(): Flow<List<Goal>> = store.observeGoals().map(GoalTimeline::effective).distinctUntilChanged()

    suspend fun goals(): List<Goal> = store.transaction { GoalTimeline.effective(goals.allCurrent()) }

    /** Document d'origine repris de l'ancien suivi (`profile`/`profile` : profil complet), tel quel, ou `null`. */
    suspend fun importedDocument(kind: String, key: String): String? = store.transaction { imports.latest(kind, key)?.json }

    // ───── Bilan du lundi ─────

    /** Bilans de chaque jour de [from] à [to] inclus, calculés en une seule lecture. */
    suspend fun daySummaries(from: LocalDate, to: LocalDate): List<DaySummary> = daySummaries(from, to, emptyList())

    /** [extraGoals] : versions d'objectifs lues en plus des versions enregistrées, sans être écrites (prévisualisation). */
    private suspend fun daySummaries(from: LocalDate, to: LocalDate, extraGoals: List<Goal>): List<DaySummary> {
        require(!from.isAfter(to)) { "Période inversée : $from > $to" }
        return store.transaction {
            val consumptionsByDay = consumptions.activeBetween(from, to).groupBy { it.day }
            val estimatesByDay = dailyEstimates.allVersionsBetween(from, to).groupBy { it.day }
            val trackingByDay = dayTracking.currentBetween(from, to).groupBy { it.day }
            val allGoals = goals.allCurrent() + extraGoals
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { day ->
                    DaySummary.of(day, consumptionsByDay[day].orEmpty(), allGoals, estimatesByDay[day].orEmpty(), trackingByDay[day].orEmpty())
                }
                .toList()
        }
    }

    /** Informations déclarées pour la semaine commençant le lundi [weekStart] (version courante active), ou `null`. */
    suspend fun weekInfo(weekStart: LocalDate): WeekInfo? =
        store.transaction { weekInfos.current(weekStart.toString()) }?.takeIf { it.isActive }

    /**
     * Chiffres de la semaine [range], recalculés depuis les consommations actives, les estimations
     * validées, les objectifs en vigueur, les pesées reprises ou déclarées et les informations de la semaine.
     */
    suspend fun weeklyFigures(range: WeekRange, params: ReviewParams = ReviewParams.Default): WeeklyFigures = weeklyFigures(range, params, emptyList())

    /**
     * Chiffres de la période [range] tels que les verra le rapport produit le [day] : avec les objectifs qu'écrirait la
     * mise à jour du poids de référence de ce jour ([updateReferenceWeight]), **sans rien écrire**. Le modèle écrit son mot sur
     * ces chiffres, et le rapport, lu après la mise à jour, retrouve les mêmes. Sans mise à jour à faire (déjà faite ce jour-là,
     * pas de pesée, même poids, refus), ce sont les chiffres d'aujourd'hui.
     */
    suspend fun reportFigures(range: WeekRange, day: LocalDate = today(), params: ReviewParams = ReviewParams.Default): ReportFigures {
        val change = store.transaction {
            if (ReferenceWeights.doneOn(this, day) != null) null else ReferenceWeights.plan(this, day) as? ReferenceWeightPlan.Change
        }
        val extra = change?.let { plan ->
            store.transaction {
                plan.rewrites.map { r -> Goal(r.from, (goals.current(r.from)?.version ?: 0) + 1, RecordState.Active, r.after, clock.instant(), null) }
            }
        }.orEmpty()
        return ReportFigures(weeklyFigures(range, params, extra), change)
    }

    private suspend fun weeklyFigures(range: WeekRange, params: ReviewParams, extraGoals: List<Goal>): WeeklyFigures {
        val firstDay = store.observeFirstDay().first()
        val from = minOf(firstDay ?: range.start, range.start)
        val days = daySummaries(from, range.end, extraGoals)
        return store.transaction {
            val declared = weekInfos.allCurrent().filter { it.isActive }
            val info = declared.firstOrNull { it.weekStart == range.start }
            val imported = WeightHistory.parseImported(imports.latest("weights", "weights")?.json)
            val weights = WeightHistory.of(imported, declared.flatMap { it.weighIns })
            val target = WeightGoal.effective(weightGoals.current(WeightGoal.KEY))
            WeeklyReview.compute(range, days, info, weights, params, target)
        }
    }

    /**
     * Conseils chiffrés du bilan pour [figures], calculés par l'application : objectif temporaire qui commence le
     * lendemain de [today], équivalent en pas, mélange, nouvelle date. [days] : durée choisie, `null` d'office.
     */
    suspend fun catchUp(figures: WeeklyFigures, today: LocalDate = today(), days: Int? = null): CatchUpResult {
        val (all, running) = store.transaction {
            goals.allCurrent() to (TemporaryGoals.inEffect(ActionScope(ActionId(0), clock.instant(), this, kinds)) != null)
        }
        return CatchUpAdvices.compute(figures, all, today, days, running)
    }

    /** Où en est l'objectif temporaire qui commence le [start], ou `null` s'il n'y en a pas eu. Lecture seule. */
    suspend fun temporaryGoal(start: LocalDate): TemporaryGoalStatus? = store.transaction {
        TemporaryGoals.status(ActionScope(ActionId(0), clock.instant(), this, kinds), start)
    }

    /** Version courante de l'objectif de poids (active ou annulée), avec l'action qui l'a écrite ; `null` s'il n'a jamais été fixé. */
    suspend fun weightGoal(): WeightGoal? = store.transaction { weightGoals.current(WeightGoal.KEY) }

    /** Objectif de poids en vigueur : celui enregistré, sinon l'objectif par défaut ([WeightTarget.Default]). */
    suspend fun weightTarget(): WeightTarget = WeightGoal.effective(store.transaction { weightGoals.current(WeightGoal.KEY) })

    // ───── Poids de référence ─────

    /**
     * Met à jour le poids de référence au [day] d'après les pesées (à la production d'un bilan, voir [UpdateReferenceWeight]).
     * Renvoie ce qui a été calculé, et l'action quand la mise à jour est faite ; rien n'est écrit sinon.
     */
    suspend fun updateReferenceWeight(day: LocalDate): ReferenceWeightOutcome {
        // Déjà mis à jour ce jour-là et toujours en vigueur (un rapport qui ne s'est pas enregistré, un second bilan le même
        // jour) : on montre cette mise à jour, jamais une seconde.
        store.transaction { ReferenceWeights.doneOn(this, day) }?.let { return it }
        val plan = store.transaction { ReferenceWeights.plan(this, day) }
        if (plan !is ReferenceWeightPlan.Change) return ReferenceWeightOutcome(plan)
        return when (val outcome = execute(UpdateReferenceWeight(day))) {
            is ActionOutcome.Applied -> ReferenceWeightOutcome(plan, outcome.result.id)
            is ActionOutcome.Rejected -> ReferenceWeightOutcome(plan, refusal = outcome.rejection.message)
        }
    }

    /**
     * Poids de référence en vigueur : la dernière version enregistrée active, sinon celui du profil repris (règle en vigueur au
     * [day]) ; `null` s'il est inconnu. Ce n'est pas un historique : pour un jour passé, l'objectif daté de ce jour fait foi.
     */
    suspend fun referenceWeight(day: LocalDate = today()): Double? = store.transaction {
        ReferenceWeights.current(referenceWeights.current(ReferenceWeight.KEY), ReferenceWeights.profileOf(this), day)
    }
}

/** Plats (avec variantes) et provisions « j'ai ». */
data class DishBook(val dishes: List<Dish>, val provisions: List<Provision>)

/**
 * Chiffres d'un bilan avant sa production ([TracklyEngine.reportFigures]) : [figures] comptent déjà la mise à jour du poids de
 * référence [referenceChange] que fera le rapport, qui n'est pas encore écrite (`null` : aucune à faire).
 */
data class ReportFigures(val figures: WeeklyFigures, val referenceChange: ReferenceWeightPlan.Change?)
