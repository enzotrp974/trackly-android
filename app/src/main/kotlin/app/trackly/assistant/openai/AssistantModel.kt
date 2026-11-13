package app.trackly.assistant.openai

import app.trackly.usage.PriceTable

/**
 * Modèle de l'assistant et du bilan du lundi. GPT-6.1 Sol par défaut ; GPT-6.1 Terra, moins cher,
 * sert aussi à comparer Terra et Sol sur une journée complète.
 *
 * Les deux acceptent les mêmes niveaux de raisonnement du sélecteur (bas à maximum, voir
 * [ReasoningEffort]) : Terra accepte en plus `none`, que Sol refuse, et que l'app n'envoie jamais.
 */
enum class AssistantModel(val code: String, val label: String) {
    Sol("gpt-6.1-sol", "GPT-6.1 Sol"),
    Terra("gpt-6.1-terra", "GPT-6.1 Terra"),
    ;

    /** Tarif standard par million de jetons (entrée, sortie), d'après la table de prix unique. */
    val price: PriceTable.TextPrice? get() = PriceTable.textModel(code)?.standard

    companion object {
        val Default = Sol

        /** Modèle d'un code enregistré ; un code inconnu (version plus récente, fichier abîmé) donne [Default]. */
        fun fromCode(code: String?): AssistantModel = entries.firstOrNull { it.code == code } ?: Default
    }
}
