package app.trackly.assistant.turn

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.openai.Citations

/** Ce que fait l'assistant pendant l'attente, affiché à la place des trois points. */
enum class TurnStatus(val label: String) {
    Thinking("Trackly réfléchit"),
    Searching("Je cherche sur Internet…"),
    Reading("Je consulte ton journal…"),
    Recording("J’enregistre…"),
    ;

    companion object {
        /** Outil de lecture (`get_day`, `search_products`, `get_meal_ideas`…) ou d'écriture (tout le reste). */
        fun forTool(name: String): TurnStatus = if (name.startsWith("get_") || name.startsWith("search_")) Reading else Recording
    }
}

/**
 * Suit un tour pendant qu'il se déroule (chat). Sans suivi, le tour se déroule sans streaming,
 * exactement comme avant (bilan du lundi, tests).
 */
interface TurnListener {
    /** Étape en cours : réflexion, recherche web, lecture ou enregistrement. */
    suspend fun onStatus(status: TurnStatus) {}

    /** Texte de la réponse en cours, depuis son début ; vide quand une nouvelle requête repart. */
    suspend fun onText(text: String) {}

    /** Carte prête : l'action vient d'être appliquée par le moteur, avant même la fin du texte du modèle. */
    suspend fun onCard(card: ChatCard) {}
}

/**
 * Texte en cours d'arrivée, tel qu'on l'affiche : sans le balisage des citations web (« ([site](https://…)) »)
 * ni les repères internes de citation (caractères à usage privé, comme dans le texte final, voir `Citations`),
 * y compris une citation ou un repère encore incomplet en fin de texte. Le texte final, lui, est nettoyé par les
 * annotations (`Citations`) ; ceci n'évite que de voir passer les liens bruts pendant le streaming.
 */
object StreamingText {
    private val citation = Regex("""\s?\(\[[^\]\n]*]\([^)\s]*\)\)""")
    private val unfinished = Regex("""\s?\(\[[^\n]*$""")

    fun visible(raw: String): String = Citations.stripMarkers(raw, unfinished = true).replace(citation, "").replace(unfinished, "")
}
