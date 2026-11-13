package app.trackly.core.action

import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.FoodPatch
import app.trackly.core.model.Moment
import app.trackly.core.model.Origin
import app.trackly.core.model.ProductServing
import app.trackly.core.model.RecordState
import app.trackly.core.time.TrackingDay
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** Une consommation à enregistrer, telle que déclarée. */
data class NewConsumption(
    val food: Food,
    val moment: Moment,
    /** Heure réelle ; par défaut l'instant de la déclaration. */
    val consumedAt: Instant? = null,
    /** Date exprimée par l'utilisateur (« pour hier ») : prime sur la règle des 5 h. */
    val explicitDay: LocalDate? = null,
    /** Identifiant imposé (rejouer une déclaration sans doublon) ; sinon un UUID neuf. */
    val entryId: EntryId? = null,
)

/**
 * Ajoute une ou plusieurs consommations d'une même déclaration. Une seule annulation
 * retire l'ensemble.
 */
data class AddConsumptions(
    val items: List<NewConsumption>,
    val origin: Origin = Origin.App,
) : Action {
    constructor(item: NewConsumption, origin: Origin = Origin.App) : this(listOf(item), origin)

    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        if (items.isEmpty()) scope.reject(Rejection.Invalid("Aucune consommation à ajouter"))
        val ids = items.mapNotNull { it.entryId }
        if (ids.size != ids.toSet().size) scope.reject(Rejection.Invalid("Identifiant de consommation en double"))
        for ((position, item) in items.withIndex()) {
            item.explicitDay?.let { rejectFutureDay(scope, it) }
            // Sans identifiant imposé : un identifiant qui suit l'ordre de la déclaration (voir EntryId.ordered).
            val id = item.entryId ?: EntryId.ordered(scope.id, position)
            if (scope.tx.consumptions.current(id) != null) {
                scope.reject(Rejection.Invalid("La consommation $id existe déjà"))
            }
            val consumedAt = item.consumedAt ?: scope.now
            ConsumptionKind.writeNextVersion(
                scope,
                current = null,
                content = Consumption(
                    entryId = id,
                    version = 1,
                    state = RecordState.Active,
                    day = TrackingDay.of(consumedAt, item.explicitDay),
                    consumedAt = consumedAt,
                    moment = item.moment,
                    food = item.food,
                    origin = origin,
                    recordedAt = scope.now,
                    actionId = scope.id,
                ),
            )
        }
        return "Ajout : " + items.joinToString(", ") { describe(it.food) }
    }

    companion object {
        const val TYPE = "consumption.add"
    }
}

/**
 * Corrige une consommation active par une nouvelle version complète. Les champs `null`
 * restent inchangés ; l'heure réelle est toujours conservée.
 *
 * Deux façons de changer l'aliment, jamais ensemble :
 * - [food] **remplace** l'aliment en entier (« c'était l'autre barre ») : produit lié, source et
 *   hypothèses de l'ancien aliment ne suivent pas ;
 * - [patch] **ajuste** le même aliment (« c'était 200 g ») : tout ce que le correctif ne touche pas
 *   est conservé (produit lié, source, hypothèses, confiance…). Pour une consommation liée à un
 *   produit, une nouvelle quantité fait **recalculer les valeurs par le code** depuis la fiche,
 *   comme à l'ajout : les valeurs éventuelles du correctif sont alors ignorées.
 *
 * Changer de produit connu (« non, c'est l'autre barre ») : [RelinkConsumption].
 */
data class CorrectConsumption(
    val entryId: EntryId,
    val food: Food? = null,
    val moment: Moment? = null,
    /** Nouvelle date de rattachement, exprimée par l'utilisateur ; jamais dans le futur. */
    val day: LocalDate? = null,
    val patch: FoodPatch? = null,
) : Action {
    init {
        require(food == null || patch == null) { "Remplacer l'aliment ou l'ajuster, pas les deux" }
    }

    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = activeOrReject(scope, entryId)
        day?.let { rejectFutureDay(scope, it) }
        val content = current.copy(
            food = food ?: patch?.let { patched(scope, current.food, it) } ?: current.food,
            moment = moment ?: current.moment,
            day = day ?: current.day,
        )
        if (content.sameContentAs(current)) scope.reject(Rejection.NoChange)
        ConsumptionKind.writeNextVersion(scope, current, content)
        return "Correction : " + describe(content.food)
    }

    companion object {
        const val TYPE = "consumption.correct"
    }
}

/**
 * Applique [patch] à [food]. Si l'aliment est lié à un produit et que sa quantité change, les
 * valeurs viennent de la fiche du produit et de la nouvelle quantité, jamais du modèle.
 * Une unité absente de la fiche est refusée avec les unités possibles.
 */
private suspend fun patched(scope: ActionScope, food: Food, patch: FoodPatch): Food {
    val productId = food.productId ?: return patch.applyTo(food)
    val amount = patch.quantityFor(food)
    if (amount == null || amount == food.quantity) return patch.applyTo(food)
    val product = HistoryKinds.Products.versionsIn(scope).current(productId) ?: return patch.applyTo(food)
    return patch.copy(nutrition = ProductServing.nutrition(product, amount)).applyTo(food)
}

/** Supprime une consommation : nouvelle version `deleted`, rien n'est effacé. */
data class DeleteConsumption(val entryId: EntryId) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = activeOrReject(scope, entryId)
        ConsumptionKind.writeNextVersion(scope, current, current.copy(state = RecordState.Deleted))
        return "Suppression : " + current.food.label
    }

    companion object {
        const val TYPE = "consumption.delete"
    }
}

/**
 * Supprime plusieurs consommations d'un coup (« Supprimer » sur un repas de l'écran du jour) :
 * une version `deleted` chacune, rien n'est effacé, et une seule annulation les rétablit toutes.
 * Tout est refusé si l'une d'elles n'est pas (ou plus) active.
 */
data class DeleteConsumptions(val entryIds: List<EntryId>) : Action {
    init {
        require(entryIds.isNotEmpty()) { "Aucune consommation à supprimer" }
    }

    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        if (entryIds.size != entryIds.toSet().size) scope.reject(Rejection.Invalid("Identifiant de consommation en double"))
        val labels = entryIds.map { id ->
            val current = activeOrReject(scope, id)
            ConsumptionKind.writeNextVersion(scope, current, current.copy(state = RecordState.Deleted))
            current.food.label
        }
        return "Suppression : " + labels.joinToString(", ")
    }

    companion object {
        const val TYPE = "consumption.delete_many"
    }
}

/** Une date exprimée ne peut pas être dans le futur : refus net, jamais de date corrigée en silence. */
private fun rejectFutureDay(scope: ActionScope, day: LocalDate) {
    val latest = TrackingDay.latestExplicitDay(scope.now)
    if (day.isAfter(latest)) scope.reject(Rejection.Invalid("Date future refusée : $day (nous sommes le $latest)"))
}

private suspend fun activeOrReject(scope: ActionScope, entryId: EntryId): Consumption {
    val current = scope.tx.consumptions.current(entryId)
    if (current == null || !current.isActive) scope.reject(Rejection.NotFound("consommation $entryId"))
    return current
}

private fun describe(food: Food) = "${food.label} (${food.nutrition.kcal.roundToInt()} kcal)"
