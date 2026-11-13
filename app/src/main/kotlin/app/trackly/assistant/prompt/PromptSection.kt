package app.trackly.assistant.prompt

/**
 * Section des consignes système (`instructions` de la requête). Les consignes sont
 * **stables** : aucune date, aucun chiffre du jour (ils vont dans le contexte, voir
 * `assistant/context`). Ainsi le préfixe de la requête reste identique d'un message à
 * l'autre et profite du cache d'entrée d'OpenAI.
 *
 * Ajouter une section : un `object` dans `prompt/sections/`, puis une ligne dans
 * [SystemPrompt.default], à sa place dans l'ordre (les plus générales d'abord).
 */
interface PromptSection {
    /** Identifiant stable (tests, journal). */
    val id: String

    /** Titre affiché au modèle. */
    val title: String

    /** Texte de la section, en français, sans donnée variable. */
    val body: String
}

/** Consignes système assemblées dans l'ordre des sections. */
class SystemPrompt(val sections: List<PromptSection>) {
    init {
        require(sections.map { it.id }.toSet().size == sections.size) { "Deux sections portent le même identifiant" }
    }

    fun render(): String = sections.joinToString("\n\n") { "# ${it.title}\n${it.body.trim()}" }

    companion object {
        fun default() = SystemPrompt(
            listOf(
                RoleSection,
                ToneSection,
                KnowledgeSection,
                RecordingRulesSection,
                DictationSection,
                DayAndMomentSection,
                EstimationSection,
                CorrectionsSection,
                ProductsSection,
                PhotosSection,
                WebSearchSection,
                DishesSection,
                ProvisionsSection,
                ProteinSection,
                GoalSection,
                RepliesSection,
            ),
        )
    }
}
