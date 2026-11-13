package app.trackly.assistant

import app.trackly.assistant.openai.Citations
import app.trackly.assistant.turn.StreamingText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pendant le streaming, aucun balisage de citation n'est visible, même pas à moitié écrit. */
class StreamingTextTest {
    private val open = ""
    private val sep = ""
    private val close = ""

    @Test
    fun `repere de citation complet - retire, avec l espace qui le precede`() {
        assertEquals("Un Big Mac fait environ 500 kcal.", StreamingText.visible("Un Big Mac fait environ 500 kcal ${open}cite${sep}turn0view0$close."))
    }

    @Test
    fun `repere ouvert pas encore ferme en fin de flux - retire aussi`() {
        assertEquals("Un Big Mac fait environ 500 kcal", StreamingText.visible("Un Big Mac fait environ 500 kcal ${open}cite${sep}turn0vi"))
        assertEquals("Un Big Mac", StreamingText.visible("Un Big Mac $open"))
    }

    @Test
    fun `repere puis suite du texte - seule la fin ouverte est retiree`() {
        assertEquals("Oui. Ensuite non", StreamingText.visible("Oui. ${open}cite${sep}a$close Ensuite non ${open}cite${sep}b"))
    }

    @Test
    fun `texte sans repere - inchange, espaces compris`() {
        assertEquals("Bonjour  toi ", StreamingText.visible("Bonjour  toi "))
    }

    @Test
    fun `citation en lien Markdown - toujours retiree`() {
        assertEquals("Oui.", StreamingText.visible("Oui. ([site](https://exemple.fr/page))"))
        assertEquals("Oui.", StreamingText.visible("Oui. ([site](https://exe"))
    }

    // --- Un repère jamais fermé ne cache pas toute la suite, et ne laisse pas de caractère privé

    @Test
    fun `repere ouvert jamais ferme et plus long que la borne - la suite du texte reste visible`() {
        val orphan = "\uE200cite" + "x".repeat(300)
        val shown = StreamingText.visible("Oui. $orphan et la suite du texte")
        assertTrue(shown, shown.endsWith("et la suite du texte"))
        assertFalse(shown, shown.contains('\uE200'))
    }

    @Test
    fun `repere ouvert dans la borne en fin de flux - toujours cache`() {
        assertEquals("Oui.", StreamingText.visible("Oui. \uE200cite" + "x".repeat(150)))
    }

    @Test
    fun `texte final - un repere jamais ferme ne laisse aucun caractere prive`() {
        val part = buildJsonObject { put("text", "Un Big Mac fait 500 kcal \uE200cite" + "x".repeat(300) + " fin.\uE201 puis \uE202 fin") }
        val text = Citations.visibleText(part)
        assertTrue(text, text.none { it in '\uE000'..'\uF8FF' })
        assertTrue(text, text.contains("Un Big Mac fait 500 kcal"))
        assertTrue(text, text.contains("puis  fin") || text.contains("puis fin"))
    }

    @Test
    fun `texte final - un caractere prive isole est retire, le reste est intact`() {
        assertEquals("ab", Citations.visibleText(buildJsonObject { put("text", "a\uE201b") }))
        assertEquals("Bonjour  toi", Citations.visibleText(buildJsonObject { put("text", "Bonjour  toi") }))
    }

    @Test
    fun `texte final avec citation - le retrait d une sous-liste est garde`() {
        val part = buildJsonObject { put("text", "- a\n  - b \uE200cite\uE202x\uE201 fin") }
        assertEquals("- a\n  - b fin", Citations.visibleText(part))
    }
}
