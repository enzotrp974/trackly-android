package app.trackly.core.model

/**
 * Notes qui parlent de mise en forme des réponses. La mise en page est fixée par l'app (Markdown et cartes) :
 * une vieille note du genre « l'app n'affiche pas le Markdown, écris en texte brut » contredirait l'écran.
 *
 * Critère volontairement étroit, pour ne jamais retirer une préférence alimentaire par erreur. Une note n'est retirée
 * que si les trois conditions sont vraies :
 * - c'est une note de la mémoire de l'assistant, ou une règle « façon de répondre » ou « affichage » ; jamais un mot
 *   corrigé, une cuisson, une portion, un produit par défaut ni un raccourci de plat ;
 * - elle est en vigueur (ni supprimée ni désactivée) ;
 * - son sujet ou son texte contient un mot de mise en forme sans autre sens ([MARKERS]) et aucun mot de goût, de
 *   cuisine, de régime ou de courses ([CONTENT]) : une note qui mêle les deux est un doute, elle reste ;
 * - chaque phrase de son texte (coupée aux `.`, `;`, `:`, `!`, `?` et retours à la ligne) contient un de ces mots de
 *   forme : « Régime : végétarien. Présenter les suggestions en tableaux. » dit aussi autre chose, elle reste (CHAT-05) ;
 * - **tous** les mots de son sujet et de son texte sont des mots de forme connus ([FORM_ONLY] ou [MARKERS]) : un seul
 *   autre mot (« Sans sucre et sans sel, réponses en tableaux. ») et la note reste. Une note de forme oubliée coûte
 *   peu, une préférence perdue coûte cher.
 * Les mots sont comparés entiers, sans accents ni majuscules. « Pas de fibres dans les réponses » ou « présenter la
 * semaine du lundi au dimanche » parlent du contenu, pas de la forme : ces notes restent.
 */
object FormattingNotes {
    private val KINDS = setOf(AgentMemory.KIND, "interaction_preference", "display_preference")

    /** Normalisés comme [TextMatch.normalize] : minuscules, sans accents. */
    val MARKERS = listOf(
        "markdown", "texte brut", "mise en forme", "mise en page", "formatage", "tableau", "tableaux",
        "emoji", "emojis", "emoticone", "emoticones", "smiley", "smileys", "en gras", "italique", "puces", "html",
        "asterisque", "asterisques",
    )

    /** Un de ces mots fait garder la note : elle dit aussi quelque chose de ce qu'il mange. */
    val CONTENT = listOf(
        "aime", "aimes", "adore", "deteste", "evite", "evites", "allergie", "allergique", "intolerance", "intolerant",
        "prefere", "preferes", "gout", "gouts", "recette", "recettes", "courses", "magasin", "cuisson", "cuit", "cru",
        "portion", "portions", "mange", "manger",
        // CHAT-05 : régimes et exclusions, qui ne sont jamais de la forme.
        "regime", "regimes", "vegetarien", "vegetarienne", "vegetalien", "vegetalienne", "vegan", "vegane", "halal",
        "casher", "kasher", "gluten", "lactose", "porc", "viande", "poisson",
    )

    private val SENTENCE = Regex("[.;:!?\\n]+")

    /**
     * Les seuls autres mots permis dans une note retirée : mots outils, et vocabulaire de l'affichage des réponses.
     * Normalisés comme [TextMatch.normalize]. Tout mot absent de cette liste fait garder la note.
     */
    val FORM_ONLY = setOf(
        // Mots outils.
        "l", "la", "le", "les", "un", "une", "des", "de", "du", "d", "en", "dans", "a", "au", "aux", "et", "ou", "par",
        "pour", "avec", "sans", "sur", "ne", "n", "pas", "jamais", "plus", "toujours", "que", "qu", "qui", "ce", "c",
        "ces", "cet", "cette", "il", "elle", "on", "y", "se", "s", "est", "sont", "ni", "mes", "tes", "ses", "leur",
        // Ce qui s'affiche et comment.
        "reponse", "reponses", "message", "messages", "texte", "application", "app", "ecran", "chat", "proposition",
        "propositions", "suggestion", "suggestions", "recapitulatif", "recap", "macro", "macros", "ligne", "lignes",
        "liste", "listes", "format", "style", "affichage", "simple", "simples",
        "ecrire", "ecris", "ecrit", "faire", "fais", "fait", "utiliser", "utilise", "mettre", "mets", "met", "afficher",
        "affiche", "affiches", "presenter", "presente", "casse", "cassent", "lit", "lire", "gere", "supporte",
    )

    fun matches(note: Preference): Boolean {
        if (note.kind !in KINDS || !note.isActive || !note.active) return false
        val text = " " + TextMatch.normalize(note.trigger + " " + note.rule) + " "
        if (!MARKERS.any { text.contains(" $it ") } || CONTENT.any { text.contains(" $it ") }) return false
        // Une expression de forme (« en gras », « texte brut ») compte entière : retirée d'abord, ses mots seuls
        // (« sans gras ») ne sont plus des mots de forme. Dans le doute, la note reste.
        var rest = text
        for (phrase in MARKERS.filter { ' ' in it }) {
            while (rest.contains(" $phrase ")) rest = rest.replace(" $phrase ", " ")
        }
        val formWords = FORM_ONLY + MARKERS.filter { ' ' !in it }
        if (!rest.split(' ').filter { it.isNotEmpty() }.all { it in formWords }) return false
        return note.rule.split(SENTENCE).map { " " + TextMatch.normalize(it) + " " }.filter { it.isNotBlank() }
            .all { sentence -> MARKERS.any { sentence.contains(" $it ") } }
    }
}
