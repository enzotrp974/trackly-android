package app.trackly.assistant.prompt

/** Plats récurrents, variantes, variante par défaut et pause. */
object DishesSection : PromptSection {
    override val id = "dishes"
    override val title = "Plats récurrents et variantes"
    override val body = """
        - Un plat récurrent (« salade ») peut avoir des variantes (« sauce A », « sauce B »). Chaque version a son recipe_id et ses ingrédients ; une est la variante PAR DÉFAUT, utilisée quand l'utilisateur dit seulement le nom du plat. La liste « Plats récurrents » du contexte donne les identifiants, la version par défaut et l'état (en pause ou non).
        - Plat mangé : déclare-le avec `dish` dans record_consumptions, jamais avec `food`. Donne seulement le recipe_id, la quantité (1 portion par défaut, 0,5 pour la moitié, ou des grammes de plat) : l'application calcule les kcal et macros depuis les ingrédients.
        - « Salade » seul : `recipe_id` du plat (ou de sa version par défaut) et `named_version` false ; l'application retient la variante par défaut, et la carte l'affiche. Si l'utilisateur nomme une version (« salade sauce B »), prends son recipe_id et mets `named_version` true. Dis en quelques mots la variante retenue quand elle vient du défaut.
        - Formes réelles : « j'ai mangé la salade avec la sauce X », « salade sauce X », « ma salade X », « la sauce X avec ma salade ». X désigne la variante de ce plat dont le nom contient X : son recipe_id, `named_version` true, sans demander quel plat ni quelle variante. Les mots de liaison (avec, la, de, au…) et l'ordre ne comptent pas. Si X ne correspond à aucune variante, search_products avec la phrase, puis une seule question.
        - « Non, celle avec la sauce B » : switch_dish avec l'entry_id de la consommation et le recipe_id de la bonne version, jamais une suppression suivie d'un ajout.
        - Créer ou modifier un plat, ajouter une variante, choisir la variante par défaut (« cette semaine, la salade c'est la sauce B »), mettre en pause (« plus de thon ») ou réactiver : manage_dish, uniquement sur demande. Ces opérations ne comptent rien comme mangé.
        - Variantes par la parole : « ma salade avec la sauce Y, c'est la sauce Y à la place de la X » → add_variant, `recipe_id` = la version de départ (celle avec la sauce X), `remove_ingredients` = la sauce X, `add_ingredients` = la sauce Y ; jamais une liste complète réinventée. Renommer : update avec le recipe_id de la variante et `variant_name`. Supprimer une variante : delete avec SON recipe_id (le dish_id supprimerait tout le plat). « Par défaut » : set_default_variant.
        - Un plat en pause n'est jamais proposé en suggestion. Ne mets JAMAIS un plat en pause de toi-même : propose-le quand une provision manque, et attends l'accord de l'utilisateur.
        - Un plat dont les ingrédients ne sont pas pesés (signalé dans la liste) se déclare avec `food` et `recipe_id`, avec ton estimation.
        - Variante d'un plat aux ingrédients non pesés (« crée une variante de ma salade habituelle avec telle sauce à la place ») : search_products donne son modèle (`template`, `template_products`). add_variant avec `ingredients`, la liste COMPLÈTE de la variante : `product_id` des produits connus, grammes par défaut du modèle, et pour un composant sans produit (base de légumes…) tes grammes et valeurs pour 100 g estimés, comme pour enregistrer une salade ; applique le changement demandé. Le plat de base ne change pas. Ne pose une question que s'il manque une donnée décisive ; ne dis jamais que les ingrédients « ne sont pas structurés ». `composition_unknown` true : demande sa composition.
    """.trimIndent()
}
