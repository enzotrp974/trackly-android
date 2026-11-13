package app.trackly.assistant

import app.trackly.assistant.markdown.Align
import app.trackly.assistant.markdown.Inline
import app.trackly.assistant.markdown.Markdown
import app.trackly.assistant.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réponses libres du chat et du bilan : le Markdown du modèle devient des blocs dessinés par l'app.
 * Entrées difficiles : tableaux sans ligne de séparation, Markdown cassé ou inachevé (streaming), HTML.
 */
class MarkdownTest {
    private fun plain(text: String) = Markdown.plain(text)

    private fun runs(block: MdBlock): List<Inline> = when (block) {
        is MdBlock.Paragraph -> block.inlines
        is MdBlock.Heading -> block.inlines
        else -> error("pas de texte direct : $block")
    }

    // --- Texte simple : une réponse courte reste un seul paragraphe, inchangé

    @Test
    fun `phrase simple - un paragraphe, texte intact`() {
        val blocks = Markdown.parse("C’est noté pour ton snack. Il te reste 2 300 kcal.")
        assertEquals(1, blocks.size)
        assertEquals("C’est noté pour ton snack. Il te reste 2 300 kcal.", plain("C’est noté pour ton snack. Il te reste 2 300 kcal."))
        assertTrue(Markdown.isSimple(blocks))
    }

    @Test
    fun `retours a la ligne simples gardes dans le paragraphe`() {
        assertEquals("Ligne 1\nLigne 2", plain("Ligne 1\nLigne 2"))
        assertEquals(1, Markdown.parse("Ligne 1\nLigne 2").size)
    }

    // --- En ligne : gras, italique, code, liens, échappements

    @Test
    fun `gras et italique deviennent des styles, sans asterisques`() {
        val inlines = runs(Markdown.parse("Il te reste **2 300 kcal** et *un peu* de __marge__.").single())
        assertEquals("Il te reste 2 300 kcal et un peu de marge.", inlines.joinToString("") { it.text })
        assertTrue(inlines.single { it.text == "2 300 kcal" }.bold)
        assertTrue(inlines.single { it.text == "un peu" }.italic)
        assertTrue(inlines.single { it.text == "marge" }.bold)
    }

    @Test
    fun `gras et italique imbriques`() {
        val inlines = runs(Markdown.parse("***fort*** et **gras *penché***").single())
        assertEquals("fort et gras penché", inlines.joinToString("") { it.text })
        val fort = inlines.single { it.text == "fort" }
        assertTrue(fort.bold && fort.italic)
        val penche = inlines.single { it.text == "penché" }
        assertTrue(penche.bold && penche.italic)
    }

    @Test
    fun `asterisques et soulignes ordinaires restent du texte`() {
        assertEquals("2 * 3 = 6, record_consumptions et snake_case_ok", plain("2 * 3 = 6, record_consumptions et snake_case_ok"))
        assertEquals("note ** seule", plain("note ** seule"))
        assertEquals("**pas fermé", plain("**pas fermé"))
        assertEquals("5*", plain("5*"))
    }

    @Test
    fun `code en ligne garde son contenu tel quel`() {
        val inlines = runs(Markdown.parse("Utilise `**brut**` ici").single())
        assertEquals("Utilise **brut** ici", inlines.joinToString("") { it.text })
        assertTrue(inlines.single { it.text == "**brut**" }.code)
    }

    @Test
    fun `liens - seul le texte reste, jamais l adresse ni un lien actif`() {
        assertEquals("Voir Ciqual et ça.", plain("Voir [Ciqual](https://ciqual.anses.fr) et [ça](javascript:alert(1))."))
        assertEquals("Image : banane", plain("Image : ![banane](https://x.fr/b.png)"))
        assertEquals("Site https://exemple.fr ok", plain("Site <https://exemple.fr> ok"))
    }

    @Test
    fun `HTML retire, saut de ligne garde`() {
        assertEquals("gras\nsuite", plain("<b>gras</b><br>suite"))
        assertEquals("moins de 3 < 5 kcal <3", plain("moins de 3 < 5 kcal <3"))
        assertEquals("rien", plain("<script>rien</script>"))
    }

    @Test
    fun `echappements`() {
        assertEquals("*pas italique* et #pas titre", plain("\\*pas italique\\* et \\#pas titre"))
    }

    @Test
    fun `barre`() {
        val inlines = runs(Markdown.parse("~~ancien~~ nouveau").single())
        assertTrue(inlines.single { it.text == "ancien" }.strike)
    }

    // --- Blocs

    @Test
    fun `titres de tout niveau, diese final ote`() {
        val blocks = Markdown.parse("# Un\n## Deux ##\n#### Quatre\n#sansespace")
        assertEquals(listOf(1, 2, 4), blocks.filterIsInstance<MdBlock.Heading>().map { it.level })
        assertEquals("Un\nDeux\nQuatre\n#sansespace", plain("# Un\n## Deux ##\n#### Quatre\n#sansespace"))
        assertFalse(Markdown.isSimple(blocks))
    }

    @Test
    fun `listes a puces et numerotees, imbriquees`() {
        val blocks = Markdown.parse(
            """
            Idées :
            - Pâtes thon sauce tomate
            - Omelette
              - avec fromage
            * Salade

            1. Un
            2) Deux
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        val bullets = blocks[1] as MdBlock.ListBlock
        assertEquals(listOf("Pâtes thon sauce tomate", "Omelette", "avec fromage", "Salade"), bullets.items.map { it.inlines.joinToString("") { r -> r.text } })
        assertEquals(listOf(0, 0, 1, 0), bullets.items.map { it.level })
        assertTrue(bullets.items.all { it.marker == null })
        val numbers = blocks[2] as MdBlock.ListBlock
        assertEquals(listOf("1.", "2."), numbers.items.map { it.marker })
    }

    @Test
    fun `ligne de suite d un element de liste - rattachee a l element`() {
        val list = Markdown.parse("1. **Poulet riz**\n   environ 650 kcal\n2. Wrap").single() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("Poulet riz\nenviron 650 kcal", list.items[0].inlines.joinToString("") { it.text })
    }

    @Test
    fun `liste apres une ligne vide - meme liste`() {
        val blocks = Markdown.parse("- a\n\n- b")
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks.single() as MdBlock.ListBlock).items.size)
    }

    @Test
    fun `citation, separateur et bloc de code`() {
        val blocks = Markdown.parse("> Astuce : **pèse** le riz cuit\n\n---\n\n```\ncode **brut**\n```")
        assertTrue(blocks[0] is MdBlock.Quote)
        assertTrue(blocks[1] is MdBlock.Rule)
        assertEquals("code **brut**", (blocks[2] as MdBlock.Code).text)
    }

    // --- Tableaux : plus jamais de « ||| »

    private val macros = """
        | Repas | kcal | P | G | L |
        |---|---:|:---:|---|---|
        | Pâtes thon | 650 | 42 g | 80 g | 12 g |
        | Omelette | 420 | 28 g | 4 g | 30 g |
    """.trimIndent()

    @Test
    fun `tableau GFM - en-tete, lignes, alignements`() {
        val table = Markdown.parse(macros).single() as MdBlock.Table
        assertEquals(listOf("Repas", "kcal", "P", "G", "L"), table.header.map { cell -> cell.joinToString("") { it.text } })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Omelette", "420", "28 g", "4 g", "30 g"), table.rows[1].map { cell -> cell.joinToString("") { it.text } })
        assertEquals(Align.End, table.aligns[1])
        assertEquals(Align.Center, table.aligns[2])
        // Sans alignement écrit : une colonne de chiffres s'aligne à droite, une colonne de texte à gauche.
        assertEquals(Align.End, table.aligns[3])
        assertEquals(Align.Start, table.aligns[0])
        assertFalse(plain(macros).contains("|"))
    }

    @Test
    fun `tableau sans barres au bord et cellule en gras`() {
        val table = Markdown.parse("Repas | kcal\n--- | ---\n**Total** | 1 070").single() as MdBlock.Table
        assertEquals(1, table.rows.size)
        assertTrue(table.rows[0][0].single().bold)
    }

    @Test
    fun `tableau irregulier - lignes completees ou tronquees a la largeur de l en-tete`() {
        val table = Markdown.parse("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |").single() as MdBlock.Table
        assertEquals(listOf(3, 3), table.rows.map { it.size })
        assertEquals(3, table.aligns.size)
    }

    @Test
    fun `barre echappee ou dans du code - reste dans la cellule`() {
        val table = Markdown.parse("| a | b |\n|---|---|\n| x \\| y | `p|q` |").single() as MdBlock.Table
        assertEquals(listOf("x | y", "p|q"), table.rows[0].map { cell -> cell.joinToString("") { it.text } })
    }

    @Test
    fun `tableau colle a un paragraphe`() {
        val blocks = Markdown.parse("Voici :\n| a | b |\n|---|---|\n| 1 | 2 |\nBon appétit")
        assertEquals(listOf("Paragraph", "Table", "Paragraph"), blocks.map { it::class.simpleName })
    }

    @Test
    fun `barre isolee dans une phrase - pas un tableau`() {
        val blocks = Markdown.parse("Riz | pâtes, au choix")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    // --- Markdown inachevé (streaming) ou cassé : lisible, jamais d'exception

    @Test
    fun `streaming - en-tete de tableau seul, deja un tableau`() {
        val table = Markdown.parse("| Repas | kcal |", streaming = true).single() as MdBlock.Table
        assertEquals(0, table.rows.size)
        val partial = Markdown.parse("| Repas | kcal |\n|---|--", streaming = true).single() as MdBlock.Table
        assertEquals(0, partial.rows.size)
        val growing = Markdown.parse("| Repas | kcal |\n|---|---|\n| Pâtes th", streaming = true).single() as MdBlock.Table
        assertEquals("Pâtes th", growing.rows.single()[0].joinToString("") { it.text })
    }

    @Test
    fun `streaming - gras ouvert en fin de texte, deja en gras et sans asterisques`() {
        val inlines = runs(Markdown.parse("Il te reste **2 30", streaming = true).single())
        assertEquals("Il te reste 2 30", inlines.joinToString("") { it.text })
        assertTrue(inlines.last().bold)
        assertEquals("Il te reste", Markdown.plain("Il te reste **", streaming = true).trim())
        assertEquals("Code", Markdown.plain("Code `", streaming = true).trim())
    }

    @Test
    fun `streaming - le gras ouvert d un paragraphe precedent reste litteral`() {
        assertEquals("a **b\nc", Markdown.plain("a **b\n\nc", streaming = true))
    }

    @Test
    fun `fin de texte - bloc de code jamais ferme, garde son contenu`() {
        assertEquals("ligne 1\nligne 2", (Markdown.parse("```kotlin\nligne 1\nligne 2").single() as MdBlock.Code).text)
    }

    @Test
    fun `entrees cassees - aucune exception, texte garde`() {
        val nasty = listOf(
            "", "   ", "|", "||", "|||", "| |\n|", "---", "#", "######## trop", "- ", "1.", ">", "> ", "```", "**", "***", "*_*_",
            "[lien](", "![", "<", "<a href=", "\\", "`", "| a |\n|---|\n| **b |", "\u0000￿",
            "*".repeat(5000), "[".repeat(2000), "- ".repeat(1000), "| ".repeat(500) + "\n" + "|---".repeat(500),
        )
        for (text in nasty) {
            Markdown.parse(text)
            Markdown.parse(text, streaming = true)
            Markdown.plain(text)
        }
        assertEquals("|||", plain("|||"))
    }

    @Test
    fun `texte lisible complet - ni diese, ni asterisques, ni barres d un tableau`() {
        val reply = listOf("### 4 idées pour ce soir", "1. **Pâtes thon** sauce tomate", "2. *Omelette* fromage", "", macros).joinToString("\n")
        val text = plain(reply)
        assertFalse(text, text.contains("**") || text.contains("###") || text.contains("|"))
        assertTrue(text, text.contains("Pâtes thon sauce tomate"))
        assertTrue(text, text.contains("Omelette · 420 · 28 g · 4 g · 30 g"))
    }

    // --- Relecture : cas ajoutés

    @Test
    fun `sous-liste apres une ligne vide - reste dans sa liste, en retrait`() {
        val list = Markdown.parse("1. Petit-déj\n\n   - œufs\n   - pain\n2. Midi").single() as MdBlock.ListBlock
        assertEquals(listOf(0, 1, 1, 0), list.items.map { it.level })
        assertEquals(listOf("1.", null, null, "2."), list.items.map { it.marker })
    }

    @Test
    fun `streaming - une derniere ligne faite de marques n est pas encore affichee`() {
        for (tail in listOf("*", "-", "***", "|", "---", "|---|--")) {
            assertEquals(tail, "Voici :", Markdown.plain("Voici :\n$tail", streaming = true))
        }
        // Une fois la réponse finie, la même ligne compte (filet, ligne de texte).
        assertTrue(Markdown.parse("Voici :\n---").last() is MdBlock.Rule)
    }

    @Test
    fun `streaming - un asterisque colle a un chiffre n ouvre pas d italique`() {
        val inlines = runs(Markdown.parse("Prends 2*3 portions", streaming = true).single())
        assertEquals("Prends 2*3 portions", inlines.joinToString("") { it.text })
        assertTrue(inlines.none { it.italic })
    }

    @Test
    fun `chevrons de texte ordinaire - jamais pris pour du HTML`() {
        assertEquals("si glucides<b et lipides>c alors", plain("si glucides<b et lipides>c alors"))
        assertEquals("gras", plain("<span style=\"color:red\">gras</span>"))
    }
}
