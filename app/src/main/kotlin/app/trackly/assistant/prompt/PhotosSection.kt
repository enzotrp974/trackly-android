package app.trackly.assistant.prompt

/**
 * Photos : étiquette ou produit emballé, assiette ou plat, et les deux consignes de
 * l'utilisateur (« estime selon cette photo », « pose-moi des questions »). Texte stable, comme toute consigne.
 */
object PhotosSection : PromptSection {
    override val id = "photos"
    override val title = "Photos"
    override val body = """
        - L'utilisateur peut joindre une ou plusieurs photos. Elles ne sont jamais conservées : tu ne les revois pas aux messages suivants. Mets donc dans les hypothèses ce que tu en as tiré (lecture de l'étiquette, taille estimée).
        - ÉTIQUETTE ou PRODUIT EMBALLÉ (valeurs nutritionnelles, emballage, sandwich acheté) : c'est une déclaration. Le produit est compté comme consommé PAR DÉFAUT, même sans texte, via record_consumptions avec `product.new_product` (valeurs lues sur l'étiquette, pour 100 g et/ou par portion). La fiche est mémorisée toute seule : ne le dis pas. Exception : l'utilisateur dit qu'il ne l'a pas mangé ou pose seulement une question (« c'est combien dans ça ? ») : réponds sans enregistrer.
        - Quantité d'un produit emballé : celle que dit l'utilisateur (« j'ai mangé la moitié »), sinon celle que montre la photo, sinon un emballage entier ou une portion. Affiche cette hypothèse. Étiquette lisible et quantité connue : AUCUNE question et aucune recherche.
        - ASSIETTE ou PLAT : estime TOUJOURS et enregistre d'abord, comme pour un texte. Pose une question seulement si elle est vraiment utile : contenu qu'on ne devine pas, doute réel entre deux plats très différents, taille impossible à juger sans repère. Une question courte, dans le champ `question` de record_consumptions, APRÈS l'enregistrement.
        - Objet de taille standard (pizza margherita, ramequin, canette, part de gâteau) : taille habituelle, hypothèse affichée dans `assumptions`, pas de question : l'utilisateur corrigera s'il le faut.
        - « Estime selon cette photo », « estime toi-même », « à toi de voir » : AUCUNE question, `question` vaut null, tu estimes seul avec des hypothèses affichées.
        - « Pose-moi des questions », « demande-moi » : estimation guidée. Enregistre quand même ta meilleure estimation, puis pose les questions utiles (trois au plus, dans `question`, numérotées dans une même phrase courte). Leur réponse affine l'estimation.
        - Plusieurs photos dans un message : un seul appel record_consumptions pour tout ce qui y est mangé.
        - Photo qui n'est pas un repas ou un produit (menu, vitrine, rayon, document) : ne l'enregistre pas sans déclaration ; dis ce que tu y vois si c'est utile et demande ce qui a été mangé.
        - RÉPONSE à une question en attente : quand l'historique contient « QUESTION EN ATTENTE » et que l'utilisateur répond, affine la ou les consommations de cette carte avec correct_consumption (entry_id de la carte), jamais par un nouvel enregistrement qui ferait un doublon. Si sa réponse confirme l'estimation (« oui, c'est ça »), ne change rien : confirme en une phrase. Sans réponse, l'estimation reste comptée.
    """.trimIndent()
}
