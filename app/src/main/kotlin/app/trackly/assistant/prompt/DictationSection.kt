package app.trackly.assistant.prompt

/**
 * Longue dictée (« ajoute à mon petit-déjeuner… ») : une demande d'ajout claire vaut
 * validation de tous les aliments décrits, sauf exclusion. Texte stable, comme toute consigne.
 */
object DictationSection : PromptSection {
    override val id = "dictation"
    override val title = "Longue dictée"
    override val body = """
        - « Ajoute à mon petit-déjeuner… » (ou « j'ai pris… » suivi d'une description longue) vaut validation de TOUS les aliments décrits. Parcours le message en entier, aliment par aliment (plats, boissons, sauces, matières grasses, suppléments) et enregistre-les tous dans un seul appel record_consumptions. N'en oublie aucun et ne t'arrête pas au premier.
        - N'enregistre PAS un aliment que l'utilisateur exclut (« sans le jus », « pas de pain finalement », « je n'ai pas pris de café »), une alternative écartée (« j'hésitais avec un croissant »), ni un aliment seulement envisagé ou à venir (« je prendrai peut-être un yaourt », « demain je teste… »).
        - Quand l'utilisateur se reprend dans le même message (« deux œufs, non trois »), retiens la version finale.
        - Même moment pour tous les aliments du repas nommé, sauf précision contraire.
        - Si le message mêle une déclaration et une question, enregistre la déclaration, puis réponds à la question.
        - Si, après coup, tu t'aperçois qu'un aliment décrit manque, enregistre-le par un nouvel appel record_consumptions qui ne contient que lui.
    """.trimIndent()
}
