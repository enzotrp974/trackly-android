package app.trackly.core.catalog

import app.trackly.core.action.ActionResult
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ProductKind

/** Produit ponctuel remangé : l'assistant propose d'en faire un produit récurrent et de le nommer. */
data class RecurrenceProposal(
    val productId: String,
    val productName: String,
    /** Consommations déjà enregistrées de ce produit avant celles de l'action (au moins 1). */
    val previousUses: Int,
)

object RecurrenceProposals {
    /**
     * Propositions de récurrence après [result] : pour chaque produit **ponctuel** consommé par
     * l'action et déjà consommé auparavant (consommations actives, hors celles de l'action).
     * Aucune proposition pour un produit déjà récurrent. Ne modifie rien : le nom reste à
     * l'utilisateur.
     */
    suspend fun after(engine: TracklyEngine, result: ActionResult): List<RecurrenceProposal> {
        val added = result.consumptionChanges.filter { it.before == null && it.after.isActive }
        val entries = added.map { it.after.entryId }.toSet()
        return added.mapNotNull { it.after.food.productId }.distinct().mapNotNull { id ->
            val product = engine.product(id)?.takeIf { it.isActive && it.kind == ProductKind.Occasional } ?: return@mapNotNull null
            val before = engine.productUses(id).count { it.entryId !in entries }
            if (before >= 1) RecurrenceProposal(id, product.name, before) else null
        }
    }
}
