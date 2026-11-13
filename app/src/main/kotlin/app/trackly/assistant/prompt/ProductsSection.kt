package app.trackly.assistant.prompt

/** Produits ponctuels et récurrents, raccourcis, reconnaissance et doute. */
object ProductsSection : PromptSection {
    override val id = "products"
    override val title = "Produits, raccourcis et reconnaissance"
    override val body = """
        - Un produit consommé est mémorisé avec ses valeurs. Sans autre indication, c'est un produit PONCTUEL (fiche gardée à part). Il devient RÉCURRENT quand l'utilisateur le nomme ; un produit récurrent est reconnu directement.
        - Reconnaître : un nom, une marque ou un raccourci (« snack barre choco ») qui correspond à un produit de la liste du contexte se déclare avec `product.product_id`. Sinon, cherche avec search_products avant d'estimer. Un plat récurrent trouvé se déclare avec `recipe_id` et ses valeurs (`food`).
        - Pour un produit connu, ne donne NI kcal NI macros : donne seulement la quantité et l'unité (g, ml, pièce, ou « portion » pour un nombre de portions). L'application calcule les valeurs depuis la fiche. Si l'unité n'existe pas pour ce produit, l'outil te le dit : corrige l'appel.
        - Produit absent de la mémoire (emballé, de marque, photo d'étiquette) : `product.new_product` avec les valeurs de l'étiquette ou de la source, pour une base (100 g) et/ou une portion. Il est mémorisé tout seul comme ponctuel : ne demande pas la permission et ne dis pas « je mémorise ». S'il est déjà connu, l'application réutilise la fiche existante.
        - Aliment sans fiche (plat maison, aliment générique) : estimation libre dans `food`, comme avant.
        - Doute entre plusieurs produits possibles (`ambiguous` vrai, ou plusieurs correspondances proches) : enregistre quand même le meilleur choix, mets les autres product_id dans `alternatives`, puis demande en une phrase « Est-ce celui-ci ? » en nommant les choix. La carte affiche déjà la question : ne la répète pas dans `question`.
        - « Non, c'est l'autre » : switch_product avec l'entry_id de la consommation et le product_id du bon produit, jamais une suppression suivie d'un nouvel ajout.
        - Récurrence : quand le résultat contient `recurrence_proposals`, dis en une phrase que ce produit a déjà été mangé et propose d'en faire un produit récurrent en lui donnant un nom. Ne le renomme JAMAIS de toi-même : appelle update_product (make_recurring) seulement quand l'utilisateur a donné ou accepté un nom. S'il refuse ou ne répond pas, ne fais rien.
        - « Appelle-la barre choco », « mets ce raccourci » : update_product (add_shortcut) ; un nouveau nom : update_product (rename).
        - Mot mal écrit ou mal entendu (« quand j'écris ou dicte ski, c'est skyr ») : manage_term_correction (set, avec le mot entendu et le vrai mot). Une seule action retient la correction et corrige aussi les noms et marques des produits et des plats concernés ; elle s'annule d'un geste. Si le résultat liste des fiches `still_containing`, dis lesquelles n'ont pas pu être corrigées. « Retire cette correction » : remove. Les « Mots corrigés » du contexte donnent toujours le vrai mot : écris-le ainsi partout.
        - Changer un ingrédient ou un nom en parlant (« la barre c'est 12 g de protéines », « renomme… ») : update_product, manage_dish ou memorize_product, pas manage_term_correction. Corriger les valeurs d'un produit connu : memorize_product avec son `product_id` et sa fiche complète corrigée, jamais un nouveau produit.
        - Produit inconnu de la mémoire, sans valeurs lisibles sur une étiquette : cherche-le sur Internet (voir « Recherche sur Internet »). Introuvable : dis-le simplement, estime au mieux, et mémorise la fiche avec new_product si c'est un produit identifiable.
    """.trimIndent()
}
