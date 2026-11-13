package app.trackly.core.action

import app.trackly.core.catalog.Provisions
import app.trackly.core.model.Provision
import app.trackly.core.model.RecordState
import java.time.Instant
import java.util.UUID

/*
 * Provisions : « j'ai » / « je n'ai plus ». Liste simple, sans quantités. Une provision n'est
 * jamais une consommation : aucune de ces actions ne touche au journal des repas.
 */

/** Aliment dit « j'ai » : un nom, et le produit de la mémoire qu'il désigne si on le connaît. */
data class ProvisionInput(val name: String, val productId: String? = null) {
    init {
        require(name.isNotBlank()) { "Nom de provision vide" }
        require(name.trim().length <= NewProduct.MAX_NAME) { "Nom de provision trop long" }
    }
}

/**
 * Ce qu'une mise à jour des provisions change, calculé sans rien écrire. Sert à l'action (qui
 * l'applique) et à l'assistant (qui le raconte : déjà là, introuvable…).
 */
data class ProvisionPlan(
    /** Provisions nouvelles. */
    val created: List<ProvisionInput>,
    /** Provisions « plus » qui reviennent en « j'ai ». */
    val reactivated: List<Pair<Provision, ProvisionInput>>,
    /** Déjà dans la liste « j'ai » (rien à écrire, sauf un produit relié en plus). */
    val alreadyHeld: List<Provision>,
    /** Provisions « j'ai » qui passent en « je n'ai plus ». */
    val removed: List<Provision>,
    /** Noms à retirer qui n'étaient pas dans la liste. */
    val notFound: List<String>,
    /** Provisions « j'ai » dont on relie le produit. */
    val relinked: List<Pair<Provision, ProvisionInput>> = emptyList(),
) {
    val isEmpty: Boolean get() = created.isEmpty() && reactivated.isEmpty() && removed.isEmpty() && relinked.isEmpty()

    companion object {
        /** Plan pour [have] (« j'ai ») et [out] (« je n'ai plus »), d'après les versions courantes [existing]. */
        fun of(existing: Collection<Provision>, have: List<ProvisionInput>, out: List<String>): ProvisionPlan {
            val created = mutableListOf<ProvisionInput>()
            val reactivated = mutableListOf<Pair<Provision, ProvisionInput>>()
            val already = mutableListOf<Provision>()
            val relinked = mutableListOf<Pair<Provision, ProvisionInput>>()
            val seen = mutableSetOf<String>()
            for (input in have) {
                if (!seen.add(Provisions.identity(input.name))) continue
                val known = Provisions.find(existing, input.name, input.productId)
                when {
                    known == null -> created += input
                    !known.isActive -> reactivated += known to input
                    input.productId != null && known.productId != input.productId -> relinked += known to input
                    else -> already += known
                }
            }
            val removed = mutableListOf<Provision>()
            val notFound = mutableListOf<String>()
            for (name in out) {
                val known = existing.firstOrNull { it.isActive && (it.provisionId == name || Provisions.identity(it.name) == Provisions.identity(name)) }
                if (known == null) notFound += name else if (known !in removed) removed += known
            }
            return ProvisionPlan(created, reactivated, already, removed, notFound, relinked)
        }
    }
}

/**
 * Met à jour la liste des provisions : [have] ce que l'utilisateur dit avoir (« retiens que j'ai des
 * pommes, des bananes »), [out] ce qu'il n'a plus (« je n'ai plus de thon »). Une seule annulation.
 */
data class UpdateProvisions(val have: List<ProvisionInput> = emptyList(), val out: List<String> = emptyList()) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        if (have.isEmpty() && out.isEmpty()) scope.reject(Rejection.Invalid("Rien à retenir"))
        if (have.size + out.size > MAX_ITEMS) scope.reject(Rejection.Invalid("Trop d'aliments d'un coup (au plus $MAX_ITEMS)"))
        val clash = have.map { Provisions.identity(it.name) }.toSet() intersect out.map { Provisions.identity(it) }.toSet()
        if (clash.isNotEmpty()) scope.reject(Rejection.Invalid("« ${clash.first()} » est à la fois « j'ai » et « je n'ai plus »."))
        val plan = ProvisionPlan.of(scope.tx.provisions.allCurrent(), have, out)
        if (plan.isEmpty) scope.reject(Rejection.NoChange)
        val kind = HistoryKinds.Provisions
        for (input in plan.created) {
            val fresh = Provision("prv-" + UUID.randomUUID(), 1, RecordState.Active, input.name.trim(), input.productId, Instant.EPOCH, null)
            kind.writeNextVersion(scope, null, fresh)
        }
        for ((old, input) in plan.reactivated) {
            kind.writeNextVersion(scope, old, old.copy(state = RecordState.Active, name = input.name.trim(), productId = input.productId ?: old.productId))
        }
        for ((old, input) in plan.relinked) kind.writeNextVersion(scope, old, old.copy(productId = input.productId))
        for (old in plan.removed) kind.writeNextVersion(scope, old, old.withState(RecordState.Deleted))
        val parts = buildList {
            val added = plan.created.map { it.name.trim() } + plan.reactivated.map { it.second.name.trim() }
            if (added.isNotEmpty()) add("J'ai : " + added.joinToString(", "))
            if (plan.removed.isNotEmpty()) add("Plus de : " + plan.removed.joinToString(", ") { it.name })
        }
        return parts.joinToString(" · ").ifEmpty { "Provisions mises à jour" }
    }

    companion object {
        const val TYPE = "provisions.update"
        const val MAX_ITEMS = 60
    }
}

/** Provisions écrites par l'action (nouvelles versions). */
val ActionResult.provisionChanges: List<RecordChange<Provision>>
    get() = changes.filterIsInstance<RecordChange<*>>().filter { it.kind == "provision" }.map {
        @Suppress("UNCHECKED_CAST")
        it as RecordChange<Provision>
    }
