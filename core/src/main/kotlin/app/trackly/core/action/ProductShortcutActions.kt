package app.trackly.core.action

import app.trackly.core.model.TextMatch

/**
 * Retire un raccourci d'un produit (« barre choco » ne le désigne plus). Nouvelle version de la fiche,
 * annulable. Le nom du produit n'est pas un raccourci : on ne le retire pas par cette action.
 */
data class RemoveProductShortcut(val productId: String, val shortcut: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val current = ProductWrites.active(scope, productId)
        val plain = TextMatch.normalize(shortcut)
        if (plain.isEmpty()) scope.reject(Rejection.Invalid("Raccourci vide"))
        val kept = current.aliases.filterNot { TextMatch.normalize(it) == plain }
        if (kept.size == current.aliases.size) scope.reject(Rejection.NotFound("raccourci « ${shortcut.trim()} » de ${current.name}"))
        ProductWrites.write(scope, current, current.copy(aliases = kept))
        return "Raccourci « ${shortcut.trim()} » retiré de ${current.name}"
    }

    companion object {
        const val TYPE = "product.remove_shortcut"
    }
}
