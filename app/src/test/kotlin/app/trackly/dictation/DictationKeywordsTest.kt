package app.trackly.dictation

import app.trackly.core.model.Nutrients
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.RecordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Mots-clés transmis aux modèles : raccourcis et noms des produits récurrents, nettoyés et bornés. */
class DictationKeywordsTest {
    private fun product(
        id: String,
        name: String,
        aliases: List<String> = emptyList(),
        kind: ProductKind = ProductKind.Recurring,
        active: Boolean = true,
    ) = Product(
        productId = id,
        version = 1,
        state = RecordState.Active,
        name = name,
        aliases = aliases,
        nutrients = Nutrients(kcal = 100.0),
        kind = kind,
        active = active,
        recordedAt = Instant.EPOCH,
        actionId = null,
    )

    @Test
    fun `raccourcis d abord, puis noms, tries`() {
        val words = DictationKeywords.build(
            listOf(
                product("p1", "Barre protéinée PRO chocolat", aliases = listOf("PRO")),
                product("p2", "Skyr nature", aliases = listOf("skyr")),
            ),
            emptyList(),
        )
        assertEquals(listOf("PRO", "skyr", "Barre protéinée PRO chocolat", "Skyr nature"), words)
    }

    @Test
    fun `le vrai mot d une correction passe en tete des mots-cles`() {
        val words = DictationKeywords.build(
            listOf(product("p1", "Barre protéinée Nutrio chocolat", aliases = listOf("barre nutri"))),
            emptyList(),
            correctWords = listOf("Nutrio"),
        )
        assertEquals(listOf("Nutrio", "barre nutri", "Barre protéinée Nutrio chocolat"), words)
    }

    @Test
    fun `le sigle d un nom passe seul en tete - noye dans un nom long, il est mal transcrit`() {
        val words = DictationKeywords.build(
            listOf(product("p1", "Barre protéinée PRO chocolat", aliases = listOf("barre pro"))),
            emptyList(),
        )
        assertEquals(listOf("PRO", "barre pro", "Barre protéinée PRO chocolat"), words)
    }

    @Test
    fun `un mot en capitales d une seule lettre ou un nombre seul n est pas un sigle`() {
        assertEquals(listOf("BIO"), DictationKeywords.acronymsIn("Yaourt A BIO 500 (g)"))
    }

    @Test
    fun `un produit ponctuel (jamais nomme) n est pas un mot-cle`() {
        val words = DictationKeywords.build(
            listOf(
                product("p1", "Sandwich de la gare", kind = ProductKind.Occasional),
                product("p3", "Skyr nature"),
            ),
            emptyList(),
        )
        assertEquals(listOf("Skyr nature"), words)
    }

    @Test
    fun `chevrons et sauts de ligne sont retires - l API rejetterait toute la requete`() {
        val words = DictationKeywords.limit(listOf("Pâtes <bio>", "ligne1\nligne2", "retour\r\nchariot", "<>", "  espaces   multiples  "))
        assertEquals(listOf("Pâtes bio", "ligne1 ligne2", "retour chariot", "espaces multiples"), words)
        assertTrue(words.none { w -> w.any { it in "<>\r\n" } })
    }

    @Test
    fun `doublons ignores sans tenir compte de la casse ni des accents`() {
        assertEquals(listOf("Crème fraîche"), DictationKeywords.limit(listOf("Crème fraîche", "creme fraiche", "CRÈME FRAÎCHE")))
    }

    @Test
    fun `mots vides et phrases trop longues ecartes`() {
        val phrase = "mot ".repeat(30)
        assertEquals(listOf("PRO"), DictationKeywords.limit(listOf("", "   ", phrase, "PRO")))
    }

    @Test
    fun `nombre de mots-cles borne`() {
        val many = (1..300).map { "produit $it" }
        val words = DictationKeywords.limit(many)
        assertTrue(words.size <= DictationKeywords.MAX_COUNT)
        assertEquals("produit 1", words.first())
    }

    @Test
    fun `total de caracteres borne`() {
        val long = (1..200).map { "a".repeat(50) + it }
        val words = DictationKeywords.limit(long)
        assertTrue(words.sumOf { it.length } <= DictationKeywords.MAX_CHARS)
        assertFalse(words.isEmpty())
    }

    @Test
    fun `catalogue vide - aucun mot-cle`() {
        assertEquals(emptyList<String>(), DictationKeywords.build(emptyList(), emptyList()))
    }
}
