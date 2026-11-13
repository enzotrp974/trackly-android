package app.trackly.assistant.cards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DishOperation { Created, VariantAdded, Updated, DefaultChanged, Paused, Resumed, Deleted }

/**
 * Carte d'une opération sur un plat récurrent (créé, variante ajoutée, modifié, variante par
 * défaut, pause, réactivé, supprimé). Annulable. Construite par le code depuis le résultat de l'action.
 */
@Serializable
@SerialName("dish")
data class DishCard(
    override val actionId: Long,
    val operation: DishOperation,
    val dishId: String,
    val name: String,
    /** Précision affichée : variante par défaut… */
    val detail: String? = null,
) : ChatCard {
    val status: String
        get() = when (operation) {
            DishOperation.Created -> "Plat créé"
            DishOperation.VariantAdded -> "Variante ajoutée"
            DishOperation.Updated -> "Plat modifié"
            DishOperation.DefaultChanged -> "Variante par défaut"
            DishOperation.Paused -> "Plat en pause"
            DishOperation.Resumed -> "Plat réactivé"
            DishOperation.Deleted -> "Plat supprimé"
        }

    override fun summaryForModel() = "[$status par l'action $actionId : $name, plat $dishId${detail?.let { " ($it)" }.orEmpty()}]"
}

/**
 * Carte des provisions retenues : ce que l'utilisateur a, ce qu'il n'a plus. Ce n'est pas une
 * consommation. Annulable.
 */
@Serializable
@SerialName("provisions")
data class ProvisionsCard(
    override val actionId: Long,
    val have: List<String> = emptyList(),
    val out: List<String> = emptyList(),
    /** Plats actifs qui utilisent un aliment retiré : l'utilisateur peut les mettre en pause. */
    val affectedDishes: List<String> = emptyList(),
) : ChatCard {
    override fun summaryForModel() = buildString {
        append("[Provisions mises à jour par l'action $actionId (aucune consommation)")
        if (have.isNotEmpty()) append(" ; j'ai : ").append(have.joinToString(", "))
        if (out.isNotEmpty()) append(" ; plus de : ").append(out.joinToString(", "))
        append(']')
    }
}
