package app.trackly.assistant.cards

import app.trackly.core.action.ActionResult
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.ProductUse
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Origin
import app.trackly.core.model.Product
import app.trackly.core.model.ProductServing
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.TextMatch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Un aliment d'une carte de proposition. Ses valeurs viennent de la fiche connue (produit ou plat) et de la
 * quantité, jamais du modèle. Sans fiche, ou avec une fiche sans kcal, [kcal] vaut `null` et [unknown] dit pourquoi.
 */
@Serializable
data class ProposalItem(
    val label: String,
    /** « 150 g », « 1 portion »… ou `null`. */
    val quantity: String? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    /** Fiche produit d'où viennent les valeurs, ou `null`. */
    val productId: String? = null,
    /** Version précise du plat d'où viennent les valeurs (variante retenue), ou `null`. */
    val recipeId: String? = null,
    /** Quantité à noter, dans [unit] (portions pour un plat) ; pour un aliment sans fiche, celle que l'estimation doit reprendre ; `null` sans quantité. */
    val amount: Double? = null,
    val unit: String? = null,
    /** Valeur inconnue : pourquoi (« pas de fiche », « fiche sans kcal »). `null` : valeurs connues. */
    val unknown: String? = null,
) {
    /** Les valeurs viennent d'une fiche et l'aliment peut être noté par l'app seule. */
    val known: Boolean
        get() = unknown == null && kcal != null && amount != null && unit != null && ((productId != null) != (recipeId != null))
}

/**
 * Carte de proposition : une idée de repas choisie, chiffrée par l'app depuis les fiches connues,
 * avec un bouton « Noter ». Elle ne compte pas : aucune action du moteur derrière elle ([actionId] vaut
 * [NO_ACTION]). « Noter » crée une déclaration annulable, comme un repas dicté ; la carte d'enregistrement qui
 * en naît porte [id] dans [RecordCard.proposalId].
 *
 * Une version plus ancienne de l'app ne connaît pas ce type : elle affiche le message sans ses cartes, sans planter.
 */
@Serializable
@SerialName("proposal")
data class ProposalCard(
    /** Identifiant stable de la proposition (`prop-…`), cité au modèle et repris par la carte d'enregistrement. */
    val id: String,
    /** Nom court de l'idée (« Pâtes thon sauce tomate »). */
    val title: String,
    /** Moment visé, code de [Moment]. */
    val moment: String,
    val items: List<ProposalItem>,
    override val actionId: Long = NO_ACTION,
) : ChatCard {
    val momentLabel: String get() = runCatching { Moment.fromCode(moment).label }.getOrDefault(moment)

    /** Somme des kcal connues. */
    val totalKcal: Double get() = items.sumOf { it.kcal ?: 0.0 }

    /** Toutes les kcal sont connues : le total est exact, sinon c'est une borne basse. */
    val kcalComplete: Boolean get() = items.all { it.kcal != null }

    val protein: CardMacro get() = macro { it.proteinG }
    val carbs: CardMacro get() = macro { it.carbsG }
    val fat: CardMacro get() = macro { it.fatG }

    /** Aliments dont la valeur est inconnue, à signaler sur la carte. */
    val unknownItems: List<ProposalItem> get() = items.filter { it.kcal == null || it.unknown != null }

    /** Tous les aliments viennent d'une fiche : « Noter » enregistre sur le téléphone, sans l'assistant. */
    val notableLocally: Boolean get() = items.isNotEmpty() && items.all { it.known }

    private fun macro(of: (ProposalItem) -> Double?) = CardMacro(items.sumOf { of(it) ?: 0.0 }, items.all { of(it) != null })

    /**
     * Déclaration créée par « Noter » à [now] : chaque aliment à fiche est recalculé depuis sa fiche par le moteur,
     * avec la quantité de la carte. Un aliment sans fiche prend son estimation dans [estimates] (rang dans la carte →
     * valeurs estimées par l'assistant). `null` s'il en manque une : il faut alors l'estimer.
     */
    fun declaration(now: Instant, estimates: Map<Int, Food> = emptyMap()): DeclareConsumptions? {
        if (items.isEmpty()) return null
        val moment = runCatching { Moment.fromCode(moment) }.getOrNull() ?: return null
        val declared = items.mapIndexed { i, item ->
            // Décalé d'1 ms par aliment : le journal garde l'ordre de la carte, comme une déclaration dictée.
            val at = now.plusMillis(i.toLong())
            if (!item.known) {
                val food = estimates[i] ?: return null
                return@mapIndexed DeclaredItem.Free(NewConsumption(food, moment, consumedAt = at))
            }
            val amount = Quantity(item.amount!!, item.unit!!)
            if (item.productId != null) {
                DeclaredItem.OfProduct(ProductUse(productId = item.productId, amount = amount), moment = moment, consumedAt = at)
            } else {
                DeclaredItem.OfRecipe(item.recipeId!!, amount, moment = moment, consumedAt = at, useDefault = false)
            }
        }
        return DeclareConsumptions(declared, if (estimates.isEmpty()) Origin.App else Origin.Assistant)
    }

    /**
     * La même carte, chiffrée de nouveau depuis les fiches actuelles. Un aliment dont la fiche a disparu, ou
     * ne convient plus à sa quantité, devient un aliment sans fiche (valeur inconnue). Les aliments sans fiche restent.
     */
    fun refreshed(products: List<Product>, recipes: List<Recipe>): ProposalCard = copy(items = items.map { it.refreshed(products, recipes) })

    /** Mêmes aliments, mêmes fiches, mêmes valeurs (au dixième près) : « Noter » enregistrera ce que la carte montre. Le nom seul ne compte pas. */
    fun sameValuesAs(other: ProposalCard): Boolean =
        items.size == other.items.size && items.zip(other.items).all { (a, b) ->
            a.productId == b.productId && a.recipeId == b.recipeId && a.amount == b.amount && a.unit == b.unit &&
                (a.unknown == null) == (b.unknown == null) &&
                close(a.kcal, b.kcal) && close(a.proteinG, b.proteinG) && close(a.carbsG, b.carbsG) && close(a.fatG, b.fatG)
        }

    /**
     * Consigne du tour lancé par « Noter » quand un aliment n'a pas de fiche. Le modèle reçoit la carte elle-même,
     * même sortie des messages qu'il relit : il reprend telles quelles les fiches connues et n'estime que le reste.
     */
    fun noteContext(): String {
        val known = items.filter { it.known }.joinToString("; ") { item ->
            val ref = item.productId?.let { "product_id $it" } ?: "dish recipe_id ${item.recipeId}, named_version true"
            "${item.label} : $ref, ${cardQuantity(item.amount!!, item.unit!!)}"
        }
        // Quantité brute (nombre, unité de la carte) : le texte affiché met l'unité au pluriel (« 2 pièces »).
        val guessed = items.filterNot { it.known }.joinToString("; ") { item ->
            val amount = item.amount
            val unit = item.unit
            item.label + when {
                amount != null && unit != null -> " (quantity ${if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()}, unit $unit)"
                else -> item.quantity?.let { " ($it)" }.orEmpty()
            }
        }
        return "« Noter » touché sur la carte de proposition $id : « $title », ${momentLabel.lowercase()}. " +
            "Note-la maintenant avec UN SEUL appel record_consumptions, proposal_id $id, moment $moment, sans question. " +
            (if (known.isNotEmpty()) "Reprends telles quelles ces fiches et quantités (l'app les prend de la carte) : $known. " else "") +
            "Estime seulement, chacun par un `food` qui garde exactement ce nom et cette quantité : $guessed. N'ajoute ni ne retire aucun aliment : " +
            "l'app refuse une déclaration qui ne correspond pas à la carte, et rien n'est alors noté."
    }

    override fun summaryForModel(): String {
        val list = items.joinToString("; ") { item ->
            val ref = item.productId?.let { "produit $it" } ?: item.recipeId?.let { "plat $it" } ?: "sans fiche"
            val value = item.kcal?.let { "${it.roundToInt()} kcal" } ?: "valeur inconnue"
            "${item.label}${item.quantity?.let { " ($it)" }.orEmpty()}, $value, $ref"
        }
        return "[Carte de proposition $id affichée, PAS notée (rien n'est compté) : « $title », ${momentLabel.lowercase()} ; $list. " +
            "Si l'utilisateur dit l'avoir mangée et qu'aucun enregistrement ne la cite déjà comme NOTÉE, record_consumptions avec proposal_id $id.]"
    }

    companion object {
        /** Aucune action du moteur derrière une proposition. */
        const val NO_ACTION = 0L

        /** Message envoyé à l'assistant par « Noter » quand un aliment n'a pas de fiche : il l'estime, comme un repas dicté. */
        fun noteRequest(card: ProposalCard) = "$NOTE_PREFIX${card.title}$NOTE_SUFFIX"

        /** Le message de l'utilisateur est celui de « Noter » ([noteRequest]). */
        fun isNoteRequest(text: String) = text.startsWith(NOTE_PREFIX) && text.endsWith(NOTE_SUFFIX)

        /** Titre de la carte dans un message de « Noter » ([noteRequest]), ou `null` pour un autre message. */
        fun noteRequestTitle(text: String): String? =
            if (isNoteRequest(text) && text.length > NOTE_PREFIX.length + NOTE_SUFFIX.length) {
                text.substring(NOTE_PREFIX.length, text.length - NOTE_SUFFIX.length)
            } else {
                null
            }

        private const val NOTE_PREFIX = "J’ai mangé ta proposition « "
        private const val NOTE_SUFFIX = " » : note-la."
    }
}

private fun close(a: Double?, b: Double?) = if (a == null || b == null) a == b else abs(a - b) < 0.05

private fun ProposalItem.refreshed(products: List<Product>, recipes: List<Recipe>): ProposalItem {
    val amount = amount ?: return this
    val unit = unit ?: return this
    val quantity = Quantity(amount, unit)
    // La quantité reste : l'estimation de l'assistant devra la reprendre.
    fun gone(reason: String) = copy(kcal = null, proteinG = null, carbsG = null, fatG = null, productId = null, recipeId = null, unknown = reason)
    return when {
        productId != null -> {
            val found = products.firstOrNull { it.productId == productId && it.isActive } ?: return gone("fiche supprimée")
            runCatching { ProposalPricing.product(CatalogSearch.resolve(found, products), quantity) }.getOrElse { gone("fiche modifiée") }
        }
        recipeId != null -> {
            val choice = Dishes.choose(recipes, recipeId, useDefault = false)?.takeIf { it.recipe.isActive } ?: return gone("plat supprimé")
            runCatching { ProposalPricing.dish(choice.recipe, quantity) }.getOrElse { gone("plat modifié") }
        }
        else -> this
    }
}

/** Chiffrage d'un aliment de carte de proposition depuis sa fiche : le même pour `propose_meal` et pour « Noter ». */
object ProposalPricing {
    /** [product] déjà résolu ([CatalogSearch.resolve]). Une unité qui ne convient pas à la fiche lève [IllegalArgumentException]. */
    fun product(product: Product, amount: Quantity): ProposalItem {
        val base = ProposalItem(
            label = product.name,
            quantity = cardQuantity(amount.value, amount.unit),
            productId = product.productId,
            amount = amount.value,
            unit = amount.unit,
        )
        val nutrition = try {
            ProductServing.nutrition(product, amount)
        } catch (e: IllegalArgumentException) {
            // Une fiche sans kcal : valeur inconnue, signalée. Une unité qui ne convient pas : l'appelant décide.
            if (product.nutrients.kcal == null && product.portion?.nutrients?.kcal == null) return base.copy(unknown = "fiche sans kcal")
            throw e
        }
        return base.copy(kcal = nutrition.kcal, proteinG = nutrition.proteinG, carbsG = nutrition.carbsG, fatG = nutrition.fatG)
    }

    /** [recipe] : la version précise du plat retenue, [amount] en portions. */
    fun dish(recipe: Recipe, amount: Quantity): ProposalItem {
        val base = ProposalItem(
            label = Dishes.label(recipe),
            quantity = cardQuantity(amount.value, amount.unit),
            recipeId = recipe.recipeId,
            amount = amount.value,
            unit = amount.unit,
        )
        if (RecipeServing.perPortion(recipe) == null) return base.copy(unknown = "plat sans ingrédients pesés")
        val nutrition = RecipeServing.nutrition(recipe, amount)
        return base.copy(kcal = nutrition.kcal, proteinG = nutrition.proteinG, carbsG = nutrition.carbsG, fatG = nutrition.fatG)
    }
}


/** Ce qu'un toucher sur « Noter » a fait. */
sealed interface NoteOutcome {
    /** Le repas et sa carte d'enregistrement sont écrits ensemble. */
    data class Noted(val result: ActionResult) : NoteOutcome

    /** Déjà notée, et pas annulée : rien de plus. */
    data object AlreadyNoted : NoteOutcome

    /** Une fiche a changé depuis : la carte montre sa nouvelle version, rien n'est noté. */
    data object Changed : NoteOutcome

    /** Un aliment sans fiche : il faut d'abord son estimation, par l'assistant. */
    data object NeedsEstimate : NoteOutcome

    /** L'estimation de l'assistant ne reprend pas la carte : rien n'est noté. */
    data class Mismatch(val reason: String) : NoteOutcome

    /** Le moteur refuse la déclaration. */
    data class Refused(val message: String) : NoteOutcome

    /** Écriture impossible (base en panne) : la transaction annulée, rien n'est compté. */
    data object Failed : NoteOutcome
}

/**
 * « Noter » d'une carte dont un aliment n'a pas de fiche : pendant ce tour, l'assistant n'écrit pas
 * lui-même. Son appel à `record_consumptions` donne seulement les estimations ; l'app vérifie avant toute écriture
 * qu'elles reprennent la carte, puis [commit] écrit le repas (fiches et quantités de la carte, estimations pour le
 * reste) et sa carte d'enregistrement en une transaction, en revérifiant les fiches. Un seul essai par toucher : un
 * second appel est refusé, sans boucle payante.
 */
class ProposalNoting(
    val proposal: ProposalCard,
    private val commit: suspend (estimates: Map<Int, Food>) -> NoteOutcome,
) {
    /** Résultat de l'unique essai, ou `null` si l'assistant n'a rien tenté. */
    @Volatile
    var outcome: NoteOutcome? = null
        private set

    /** Consigne du tour pour l'assistant : la carte elle-même. */
    val context: String get() = proposal.noteContext()

    /** Un aliment déclaré par l'assistant : sa fiche ([ref], produit ou plat), ou son estimation ([food]). */
    data class Offered(val ref: String?, val label: String, val food: Food?)

    /**
     * Fait correspondre la déclaration de l'assistant à la carte : un aliment à fiche de la carte est repris de la
     * carte (la quantité de l'assistant ne compte pas) ; chaque estimation va à l'aliment sans fiche de même nom. Un
     * aliment en plus, une estimation manquante ou une autre carte : [NoteOutcome.Mismatch], rien n'est écrit.
     */
    suspend fun take(offered: List<Offered>, proposalId: String?): NoteOutcome {
        outcome?.let { return it }
        val result = when (val estimates = estimates(offered, proposalId)) {
            is Matched -> try {
                commit(estimates.byRank)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                NoteOutcome.Failed
            }
            is Unmatched -> NoteOutcome.Mismatch(estimates.reason)
        }
        outcome = result
        return result
    }

    /** La déclaration de l'assistant est illisible : refusée comme une déclaration qui ne correspond pas, sans second essai. */
    fun refuse(reason: String): NoteOutcome = outcome ?: NoteOutcome.Mismatch(reason).also { outcome = it }

    private sealed interface Matching
    private data class Matched(val byRank: Map<Int, Food>) : Matching
    private data class Unmatched(val reason: String) : Matching

    private fun estimates(offered: List<Offered>, proposalId: String?): Matching {
        if (proposalId != null && proposalId.trim() != proposal.id) return Unmatched("autre carte ($proposalId)")
        val refs = proposal.items.filter { it.known }.map { it.productId ?: it.recipeId }.toSet()
        val missing = proposal.items.withIndex().filter { !it.value.known }.toMutableList()
        val byRank = mutableMapOf<Int, Food>()
        for (item in offered) {
            if (item.ref != null && item.ref in refs) continue
            val food = item.food ?: return Unmatched("« ${item.label} » n'est pas sur la carte")
            // Même nom et même quantité d'abord : deux « Pain » de quantités différentes ne se confondent pas.
            val slot = missing.indexOfFirst { sameName(it.value.label, food.label) && sameQuantity(it.value, food) }
                .takeIf { it >= 0 } ?: missing.indexOfFirst { sameName(it.value.label, food.label) }
            if (slot < 0) return Unmatched("« ${food.label} » n'est pas sur la carte")
            if (!sameQuantity(missing[slot].value, food)) {
                return Unmatched("« ${food.label} » : ${food.quantity?.let { "${it.value} ${it.unit}" } ?: "sans quantité"} au lieu de ${missing[slot].value.quantity}")
            }
            byRank[missing[slot].index] = food
            missing.removeAt(slot)
        }
        if (missing.isNotEmpty()) return Unmatched("estimation manquante pour « ${missing.first().value.label} »")
        return Matched(byRank)
    }

    /** Même nom, une fois normalisé (minuscules, sans accents ni ponctuation) : « Pain » n'est pas « Pain au chocolat ». */
    private fun sameName(expected: String, given: String): Boolean {
        val a = TextMatch.normalize(expected)
        return a.isNotEmpty() && a == TextMatch.normalize(given)
    }

    /**
     * La quantité estimée est celle de la carte (même unité, au centième près). Une carte sans quantité structurée
     * (écrite avant la 1.3, ou idée sans quantité) n'en impose pas.
     */
    private fun sameQuantity(expected: ProposalItem, food: Food): Boolean {
        val amount = expected.amount ?: return true
        val unit = expected.unit ?: return true
        val given = food.quantity ?: return false
        return canonicalUnit(given.unit) == canonicalUnit(unit) && abs(given.value - amount) <= 0.01 * maxOf(1.0, amount)
    }

    /** « pièces », « Pièce », « piece » → « piece » ; « cuillères à soupe » → « cuillere a soupe ». */
    private fun canonicalUnit(unit: String): String = TextMatch.normalize(unit).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.removeSuffix("s") }
}
