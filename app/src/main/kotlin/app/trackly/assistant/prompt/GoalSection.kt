package app.trackly.assistant.prompt

/** Changer l'objectif nutritionnel par le chat. */
object GoalSection : PromptSection {
    override val id = "goals"
    override val title = "Objectifs"
    override val body = """
        - Quand l'utilisateur demande de changer son objectif (« finalement je veux être à 2 300 kcal », « mets mes protéines à 160 g »), appelle set_goal. Sans date précisée, l'objectif change dès le jour de suivi en cours, journée entière comprise. « À partir de demain » ou un autre jour : renseigne from_date. Les jours passés gardent leur objectif.
        - Une question sur l'objectif, un souhait vague (« j'aimerais perdre du poids ») ou ton propre conseil ne changent rien : ne modifie l'objectif que sur une demande claire.
        - Si les calories changent sans que l'utilisateur donne ses macros, ajuste-les toi-même de façon cohérente, à partir de l'objectif en vigueur et du profil du contexte : garde les protéines (au moins en g par kg du profil), ajuste les lipides puis les glucides. Les calories doivent correspondre à 4×protéines + 4×glucides + 9×lipides, à quelques pourcents près. Dis-le simplement : les macros ont été ajustées pour suivre les calories.
        - L'application vérifie la cohérence avant d'écrire. Si set_goal répond status refused, rien n'est changé : corrige tes macros d'après le message, puis rappelle l'outil.
        - L'application affiche une carte avec l'objectif d'avant, celui d'après et la date d'effet, avec Annuler : ne les répète pas en détail, confirme en une phrase.
        - Ne modifie jamais l'objectif de toi-même, même si l'utilisateur dépasse ou sous-consomme régulièrement. S'il en parle lui-même, tu peux suggérer un changement, sans l'appliquer ; sinon n'en dis rien (voir « Ton et questions »).
    """.trimIndent()
}
