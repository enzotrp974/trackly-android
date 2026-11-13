package app.trackly.assistant.cards

import app.trackly.assistant.openai.Citation
import app.trackly.day.QuantityText
import app.trackly.core.action.ActionResult
import app.trackly.core.model.Consumption
import app.trackly.core.model.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Carte affichée dans le chat après une action de l'assistant. Toujours construite par le
 * code à partir du résultat de l'action (jamais à partir du texte du modèle) et conservée
 * dans l'historique du chat (données structurées seulement, aucune photo).
 *
 * Nouvelle carte : une sous-classe `@Serializable` avec son
 * `@SerialName`, puis son rendu dans `ui/chat/Cards.kt`.
 */
@Serializable
sealed interface ChatCard {
    /** Action du moteur représentée par la carte (base du bouton « Annuler »). */
    val actionId: Long

    /** Résumé court destiné au modèle, dans l'historique des tours suivants. */
    fun summaryForModel(): String
}

@Serializable
enum class RecordKind {
    Added,
    Corrected,
    Deleted,

    /** Enregistrement rétabli après une annulation (« remets-le ») : de nouveau compté. */
    Restored,
}

/** Une consommation telle qu'affichée sur la carte. */
@Serializable
data class CardItem(
    val entryId: String,
    val label: String,
    /** « 150 g », « 1 pièce »… ou `null`. */
    val quantity: String? = null,
    val moment: String,
    val day: String,
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    /** Produit mémorisé auquel la consommation est liée, ou `null`. */
    val productId: String? = null,
    /** Plat récurrent (ou sa variante) auquel la consommation est liée, ou `null`. */
    val recipeId: String? = null,
    /** Hypothèses de cette consommation (variante par défaut retenue…). */
    val assumptions: List<String> = emptyList(),
    /** Correction : quantité avant la correction (« 200 g »), ou `null` si inchangée ou inconnue. */
    val beforeQuantity: String? = null,
    /** Correction : kcal avant la correction, ou `null` (carte d'ajout, ou carte enregistrée avant la v1.1). */
    val beforeKcal: Double? = null,
) {
    val momentLabel: String get() = runCatching { Moment.fromCode(moment).label }.getOrDefault(moment)

    /** La correction a changé les kcal (la carte montre « avant → après »). */
    val kcalChanged: Boolean get() = beforeKcal != null && Math.round(beforeKcal) != Math.round(kcal)
}

/** Produit cité sur une carte. */
@Serializable
data class ProductRef(val productId: String, val name: String)

/** Produit ponctuel remangé : l'assistant propose de le rendre récurrent, l'utilisateur choisit le nom. */
@Serializable
data class RecurrenceOffer(val productId: String, val productName: String, val previousUses: Int)

/**
 * Doute entre plusieurs produits (« est-ce celui-ci ? ») : la meilleure estimation est déjà
 * enregistrée ([chosen]), les [others] sont les autres choix possibles.
 */
@Serializable
data class ProductDoubt(val entryId: String, val chosen: ProductRef, val others: List<ProductRef>)

/** Total d'une macro sur la carte : somme des valeurs connues, incomplet si une manque. */
data class CardMacro(val grams: Double, val complete: Boolean)

/**
 * Carte d'enregistrement : aliments, kcal, macros, hypothèses, Annuler / Corriger.
 */
@Serializable
@SerialName("record")
data class RecordCard(
    override val actionId: Long,
    val kind: RecordKind,
    val items: List<CardItem>,
    val assumptions: List<String> = emptyList(),
    /** Question en attente : l'estimation est déjà comptée. */
    val question: String? = null,
    /** Produits mémorisés par cet enregistrement (ponctuels). */
    val memorized: List<ProductRef> = emptyList(),
    /** Propositions de récurrence en attente d'un nom. */
    val recurrence: List<RecurrenceOffer> = emptyList(),
    /** Questions « est-ce celui-ci ? » en attente. */
    val doubts: List<ProductDoubt> = emptyList(),
    /** Carte de proposition notée par cet enregistrement ([ProposalCard.id]), ou `null`. */
    val proposalId: String? = null,
    /**
     * Macros hors objectif au moment de l'enregistrement ([RecordAlerts.PROTEIN], [RecordAlerts.FAT]) : la carte les montre en
     * rouge. Figé à l'enregistrement ; vide pour une carte enregistrée avant la 1.3, qui reste comme avant. Réservé à
     * l'affichage : jamais dans [summaryForModel] ni dans un résultat d'outil.
     */
    val overLimit: List<String> = emptyList(),
) : ChatCard {
    val totalKcal: Double get() = items.sumOf { it.kcal }

    /** Correction : total avant (kcal), ou `null` si la carte ne le connaît pas. */
    val beforeTotalKcal: Double?
        get() = if (kind == RecordKind.Corrected && items.any { it.beforeKcal != null }) items.sumOf { it.beforeKcal ?: it.kcal } else null

    /** Hypothèses à afficher, une par ligne, sans doublon ni ligne vide. */
    val assumptionLines: List<String>
        get() = assumptions.map { it.trim().trimEnd(';').trim() }.filter { it.isNotEmpty() }.distinct()
    val protein: CardMacro get() = macro { it.proteinG }
    val carbs: CardMacro get() = macro { it.carbsG }
    val fat: CardMacro get() = macro { it.fatG }

    /** Moments touchés, dans l'ordre de la journée (« Snack », « Matin · Midi »). */
    val momentsLabel: String
        get() = items.map { it.moment }.distinct()
            .sortedBy { code -> Moment.entries.indexOfFirst { it.code == code }.let { if (it < 0) Int.MAX_VALUE else it } }
            .joinToString(" · ") { code -> runCatching { Moment.fromCode(code).label }.getOrDefault(code) }

    private fun macro(of: (CardItem) -> Double?) =
        CardMacro(items.sumOf { of(it) ?: 0.0 }, items.all { of(it) != null })

    override fun summaryForModel(): String {
        val verb = when (kind) {
            RecordKind.Added -> "Enregistré"
            RecordKind.Corrected -> "Corrigé"
            RecordKind.Deleted -> "Supprimé"
            RecordKind.Restored -> "Rétabli"
        }
        val list = items.joinToString("; ") {
            "${it.label}${it.quantity?.let { q -> " ($q)" }.orEmpty()}, ${it.kcal.roundToInt()} kcal, ${it.momentLabel} du ${it.day}, entry_id ${it.entryId}"
        }
        val products = buildList {
            items.filter { it.productId != null }.forEach { add("${it.label} = produit ${it.productId}") }
            items.filter { it.recipeId != null }.forEach { add("${it.label} = plat ${it.recipeId}") }
            memorized.forEach { add("fiche ponctuelle mémorisée : ${it.name} (${it.productId})") }
            recurrence.forEach {
                add("PROPOSITION EN ATTENTE : rendre « ${it.productName} » (${it.productId}) récurrent ; il manque le nom choisi par l'utilisateur")
            }
            doubts.forEach { d ->
                add("QUESTION EN ATTENTE : est-ce « ${d.chosen.name} » (${d.chosen.productId}, enregistré) ? autres choix : " +
                    d.others.joinToString(", ") { "${it.name} (${it.productId})" } + " ; entry_id ${d.entryId}")
            }
        }
        // Question posée après l'enregistrement : la réponse de l'utilisateur affine ces consommations.
        val pending = question?.let { "; QUESTION EN ATTENTE (estimation déjà enregistrée, la réponse l'affine par correct_consumption) : « $it »" }.orEmpty()
        // Proposition notée : le modèle ne doit pas la noter une seconde fois.
        val proposal = proposalId?.let { "; carte de proposition $it NOTÉE par cet enregistrement : ne la note pas une seconde fois" }.orEmpty()
        return "[$verb par l'action $actionId : $list" + products.joinToString("") { "; $it" } + pending + proposal + "]"
    }

    companion object {
        /** Carte d'une action d'ajout, de correction ou de suppression de consommations. */
        fun from(result: ActionResult, kind: RecordKind, question: String? = null): RecordCard {
            val changes = result.consumptionChanges
            val shown = changes.map { change -> if (kind == RecordKind.Deleted) change.before ?: change.after else change.after }
            val items = changes.zip(shown) { change, c ->
                val base = item(c)
                val before = change.before?.takeIf { kind == RecordKind.Corrected && it.isActive }
                if (before == null) {
                    base
                } else {
                    val beforeQuantity = before.food.quantity?.let { cardQuantity(it.value, it.unit) }
                    base.copy(beforeQuantity = beforeQuantity?.takeIf { it != base.quantity }, beforeKcal = before.food.nutrition.kcal)
                }
            }
            return RecordCard(
                actionId = result.id.value,
                kind = kind,
                items = items,
                assumptions = shown.flatMap { it.food.assumptions }.distinct(),
                question = question?.takeIf { it.isNotBlank() },
            )
        }

        fun item(c: Consumption) = CardItem(
            entryId = c.entryId.value,
            label = c.food.label,
            quantity = c.food.quantity?.let { cardQuantity(it.value, it.unit) },
            moment = c.moment.code,
            day = c.day.toString(),
            kcal = c.food.nutrition.kcal,
            proteinG = c.food.nutrition.proteinG,
            carbsG = c.food.nutrition.carbsG,
            fatG = c.food.nutrition.fatG,
            productId = c.food.productId,
            recipeId = c.food.recipeId,
            assumptions = c.food.assumptions,
        )
    }
}

@Serializable
enum class ProductOperation { Memorized, MadeRecurring, Renamed, ShortcutAdded, TermCorrected, TermRemoved, Corrected, Remembered, Forgotten }

/** Carte d'une opération sur une fiche produit : mémoriser, nommer, renommer, raccourci. Annulable. */
@Serializable
@SerialName("product")
data class ProductCard(
    override val actionId: Long,
    val operation: ProductOperation,
    val productId: String,
    val name: String,
    /** Précision affichée : ancien nom, raccourci ajouté… */
    val detail: String? = null,
) : ChatCard {
    val status: String
        get() = when (operation) {
            ProductOperation.Memorized -> "Produit mémorisé"
            ProductOperation.MadeRecurring -> "Produit récurrent"
            ProductOperation.Renamed -> "Produit renommé"
            ProductOperation.ShortcutAdded -> "Raccourci ajouté"
            ProductOperation.TermCorrected -> "Mot corrigé"
            ProductOperation.TermRemoved -> "Correction retirée"
            ProductOperation.Corrected -> "Produit modifié"
            ProductOperation.Remembered -> "Retenu"
            ProductOperation.Forgotten -> "Oublié"
        }

    override fun summaryForModel() =
        "[$status par l'action $actionId : $name${if (productId.isEmpty()) "" else ", produit $productId"}${detail?.let { " ($it)" }.orEmpty()}]"
}

/** Source consultée par la recherche web : adresse (http ou https) et libellé court. */
@Serializable
data class SourceLink(val url: String, val label: String) {
    companion object {
        /** `null` si l'adresse n'est pas une page web (seuls http et https sont ouverts). */
        fun of(citation: Citation): SourceLink? {
            val url = citation.url.trim().replace(Regex("[?&]utm_source=openai$"), "")
            val host = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: return null
            val short = host.removePrefix("www.")
            return SourceLink(url, citation.title?.takeIf { it.length <= 60 } ?: short)
        }
    }
}

/**
 * Sources consultées par la recherche web pendant un tour, affichées discrètement sous la réponse
 * (obligation d'affichage de la doc OpenAI : citations visibles et cliquables). Aucune action liée.
 */
@Serializable
@SerialName("sources")
data class SourcesCard(val sources: List<SourceLink>, override val actionId: Long = 0) : ChatCard {
    override fun summaryForModel() = ""
}

/**
 * Annulation faite par l'assistant (« annule ça »), ou rétablissement qui n'est pas un enregistrement de
 * consommations (objectif, plat…). Un rétablissement de consommations a sa vraie carte ([RecordKind.Restored]).
 */
@Serializable
@SerialName("undo")
data class UndoCard(
    override val actionId: Long,
    val undoneActionId: Long,
    val title: String,
) : ChatCard {
    override fun summaryForModel() = "[Action $undoneActionId annulée par l'action $actionId : $title]"

    /** Ce qui a été annulé ou rétabli, sans le préfixe du journal (« Ajout : Pomme (80 kcal) »). */
    val subject: String get() = title.removePrefix("Annulation : ").removePrefix("Rétablissement : ")

    /** Cette action rétablit une action annulée (et ne l'annule pas). */
    val restores: Boolean get() = title.startsWith("Rétablissement : ")
}

/** Format JSON des cartes dans l'historique du chat. */
object ChatCards {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "card"
    }

    fun encode(cards: List<ChatCard>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatCard.serializer()), cards)

    /**
     * Une carte d'un type inconnu (version plus récente) ou illisible est ignorée, seule : les autres cartes du
     * message restent. Un texte qui n'est pas une liste rend une liste vide plutôt qu'une erreur. (Les versions
     * jusqu'à la 1.1 ignoraient toutes les cartes du message dans ce cas, sans planter.)
     */
    fun decode(text: String): List<ChatCard> {
        val list = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { element -> runCatching { json.decodeFromJsonElement(ChatCard.serializer(), element) }.getOrNull() }
    }
}

/** « 2 pièces », « 150 g » : l'unité s'accorde, comme dans la feuille de repas ([QuantityText]). */
internal fun cardQuantity(value: Double, unit: String): String = QuantityText.format(value, unit, formatNumber(value))

internal fun formatNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString().replace('.', ',')
