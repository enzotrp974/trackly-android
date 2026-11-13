package app.trackly.assistant.context

import app.trackly.core.review.WeekRange
import app.trackly.core.engine.TracklyEngine
import java.time.Instant

/** Ce que les fournisseurs de contexte peuvent lire. */
class ContextEnv(
    val engine: TracklyEngine,
    /** Instant du message de l'utilisateur. */
    val now: Instant,
    /**
     * Cible d'une correction demandée par le bouton « Corriger » d'une carte, ou `null`.
     * Un fournisseur de contexte peut s'en servir (variantes de produit).
     */
    val focus: CorrectionFocus? = null,
    /** Texte du message de l'utilisateur (pour retrouver les produits pertinents). */
    val text: String = "",
    /** Période du bilan de cet envoi ([app.trackly.assistant.turn.TurnInput.period]), ou `null` : celle de [now]. */
    val period: WeekRange? = null,
)

/**
 * Enregistrement visé quand l'utilisateur a touché « Corriger » : sur la carte d'une action ([actionId]),
 * ou sur un repas de l'écran du jour ([actionId] `null`).
 */
data class CorrectionFocus(val actionId: Long?, val entryIds: List<String>)

/**
 * Un élément du contexte envoyé au modèle à chaque message (profil, objectifs, journée…).
 * Il est rendu en texte, ou `null` s'il n'a rien à dire.
 *
 * Ajouter un élément (produits, plats, provisions…) : un `object` dans ce dossier, puis
 * une ligne dans [ContextBuilder.default], à sa place : du plus stable au plus variable.
 */
interface ContextProvider {
    val id: String
    val title: String

    suspend fun render(env: ContextEnv): String?
}

/**
 * Assemble le contexte du message en un seul message `developer`, placé juste avant le
 * message de l'utilisateur (après l'historique) : les consignes, les outils et l'historique
 * forment un préfixe stable pour le cache d'entrée ; seul ce bloc change d'un message à l'autre.
 */
class ContextBuilder(val providers: List<ContextProvider>) {
    init {
        require(providers.map { it.id }.toSet().size == providers.size) { "Deux éléments de contexte portent le même identifiant" }
    }

    suspend fun build(env: ContextEnv): String = buildString {
        append("Contexte calculé par l'application (fait foi, ne pas recalculer) :")
        for (provider in providers) {
            val text = provider.render(env)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            append("\n\n## ").append(provider.title).append('\n').append(text)
        }
    }

    companion object {
        fun default() = ContextBuilder(
            listOf(
                ProfileContext,
                GoalContext,
                NutritionProfileContext,
                MemoryContext,
                HabitsContext,
                PreferencesContext,
                ProductListContext,
                DishListContext,
                ProvisionsContext,
                NowContext,
                TrackingDayContext,
                RelevantProductsContext,
                RecentActionsContext,
                CorrectionFocusContext,
            ),
        )
    }
}
