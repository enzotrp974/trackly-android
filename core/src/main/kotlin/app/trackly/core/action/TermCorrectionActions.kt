package app.trackly.core.action

import app.trackly.core.catalog.Dishes
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecordState
import app.trackly.core.model.TermCorrection
import app.trackly.core.model.TermCorrections
import app.trackly.core.model.TextMatch
import java.time.Instant
import java.util.UUID

/*
 * Mots corrigés : « quand j'écris ou dicte nutri, c'est Nutrio ». Une correction est une préférence
 * durable (`term_correction`, versionnée) ; la même action corrige les noms et marques des produits et des
 * plats concernés. Tout est écrit dans une seule transaction : une seule annulation défait le tout.
 */

private const val MAX_WORD = 60

/**
 * Enregistre la correction [heard] → [correct] et l'applique aux noms et marques des produits et aux noms des
 * plats (mot entier, sans tenir compte de la casse). L'ancien nom reste un raccourci. Un produit ou un plat
 * dont le nouveau nom désignerait déjà une autre fiche est laissé tel quel : il n'est pas fusionné en silence.
 * Refusée (`NoChange`) si rien ne change.
 */
data class CorrectTerm(val heard: String, val correct: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val from = TermWrites.clean(scope, heard, "Mot entendu")
        val to = TermWrites.clean(scope, correct, "Vrai mot")
        if (from == to) scope.reject(Rejection.Invalid("Le mot entendu et le vrai mot sont identiques."))
        val correction = TermCorrection("", from, to)

        val saved = writePreference(scope, from, to)
        val renamed = renameProducts(scope, correction) + renameDishes(scope, correction)
        if (!saved && renamed == 0) scope.reject(Rejection.NoChange)
        return "Mot corrigé : $from → $to"
    }

    /** Écrit la préférence ; faux si elle existait déjà, active et identique. */
    private suspend fun writePreference(scope: ActionScope, from: String, to: String): Boolean {
        val existing = TermWrites.find(scope, from)
        if (existing != null && existing.isActive && existing.active && existing.rule == to) return false
        val next = existing?.copy(state = RecordState.Active, trigger = from, rule = to, active = true)
            ?: Preference(
                preferenceId = "tc-" + UUID.randomUUID(), version = 1, state = RecordState.Active, kind = TermCorrections.KIND,
                trigger = from, rule = to, recordedAt = Instant.EPOCH, actionId = null,
            )
        HistoryKinds.Preferences.writeNextVersion(scope, existing, next)
        return true
    }

    private suspend fun renameProducts(scope: ActionScope, c: TermCorrection): Int {
        var count = 0
        val active = scope.tx.products.allCurrent().filter { it.isActive }
        for (product in active) {
            val name = TermCorrections.apply(product.name, listOf(c))
            val brand = product.brand?.let { TermCorrections.apply(it, listOf(c)) }
            if (name == product.name && brand == product.brand) continue
            // Une autre fiche a déjà ce nom et cette marque (« Lait nutri » et « Lait Nutrio ») : deux fiches identiques seraient pires.
            val twin = active.any { other ->
                other.productId != product.productId && other.redirectTo == null && other.variant == product.variant &&
                    TextMatch.normalize(other.name) == TextMatch.normalize(name) &&
                    TextMatch.normalize(other.brand.orEmpty()) == TextMatch.normalize(brand.orEmpty())
            }
            if (twin) continue
            val base = if (name == product.name) product else try {
                ProductWrites.renamed(scope, product, name)
            } catch (e: ActionRejected) {
                continue // nom déjà pris par une autre fiche : on ne fusionne pas en silence
            }
            // Le nom d'origine reste un raccourci, sauf s'il désigne déjà une autre fiche.
            val old = TextMatch.normalize(product.name)
            val next = base.copy(brand = brand).let { fixed ->
                if (fixed.aliases.any { TextMatch.normalize(it) == old } && ProductWrites.otherHolder(scope, fixed, old) != null) {
                    fixed.copy(aliases = fixed.aliases.filterNot { TextMatch.normalize(it) == old })
                } else {
                    fixed
                }
            }
            if (next == product) continue
            ProductWrites.write(scope, product, next)
            count++
        }
        return count
    }

    private suspend fun renameDishes(scope: ActionScope, c: TermCorrection): Int {
        var count = 0
        for (dish in Dishes.group(DishWrites.all(scope))) {
            val name = TermCorrections.apply(dish.name, listOf(c))
            if (name == dish.name) continue
            val all = DishWrites.all(scope)
            try {
                DishWrites.requireFreeDishName(scope, all, name, ignore = dish.base.recipeId)
            } catch (e: ActionRejected) {
                continue
            }
            val oldFree = runCatching { DishWrites.requireFreeDishName(scope, all, dish.name, ignore = dish.base.recipeId) }.isSuccess
            val keepOld = oldFree && dish.base.aliases.none { TextMatch.normalize(it) == TextMatch.normalize(dish.name) }
            val aliases = dish.base.aliases.filterNot { TextMatch.normalize(it) == TextMatch.normalize(name) }
            HistoryKinds.Recipes.writeNextVersion(
                scope, dish.base, dish.base.copy(name = name, aliases = if (keepOld) aliases + dish.name else aliases),
            )
            // Les variantes portent le nom du plat : elles le suivent dans la même action.
            for (variant in dish.variants) HistoryKinds.Recipes.writeNextVersion(scope, variant, variant.copy(name = name))
            count++
        }
        return count
    }

    companion object {
        const val TYPE = "term.correct"
    }
}

/** Retire une correction de mot : la règle est désactivée (jamais supprimée), les noms déjà corrigés restent. */
data class RemoveTermCorrection(val heard: String) : Action {
    override val type get() = TYPE

    override suspend fun apply(scope: ActionScope): String {
        val from = TermWrites.clean(scope, heard, "Mot entendu")
        val current = TermWrites.find(scope, from)
        if (current == null || !current.isActive || !current.active) scope.reject(Rejection.NotFound("correction de « $from »"))
        HistoryKinds.Preferences.writeNextVersion(scope, current, current.copy(active = false))
        return "Correction retirée : ${current.trigger} → ${current.rule}"
    }

    companion object {
        const val TYPE = "term.remove"
    }
}

private object TermWrites {
    fun clean(scope: ActionScope, word: String, label: String): String {
        val clean = word.trim().replace(Regex("\\s+"), " ")
        if (TextMatch.normalize(clean).isEmpty()) scope.reject(Rejection.Invalid("$label vide"))
        if (clean.length > MAX_WORD) scope.reject(Rejection.Invalid("$label trop long"))
        return clean
    }

    /** Préférence de correction (toutes versions courantes, même désactivée) dont le déclencheur est [heard]. */
    suspend fun find(scope: ActionScope, heard: String): Preference? {
        val plain = TextMatch.normalize(heard)
        return scope.tx.preferences.allCurrent().firstOrNull { it.kind == TermCorrections.KIND && TextMatch.normalize(it.trigger) == plain }
    }
}

/** Fiches de préférences écrites par l'action. */
val ActionResult.preferenceChanges: List<RecordChange<Preference>>
    get() = changes.filterIsInstance<RecordChange<*>>().filter { it.kind == "preference" }.map {
        @Suppress("UNCHECKED_CAST")
        it as RecordChange<Preference>
    }
