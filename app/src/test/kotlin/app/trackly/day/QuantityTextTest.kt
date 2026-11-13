package app.trackly.day

import app.trackly.assistant.cards.cardQuantity
import org.junit.Assert.assertEquals
import org.junit.Test

/** « 2 pièce » devait s'écrire « 2 pièces » : l'unité s'accorde au même endroit pour la carte du chat et la feuille de repas. */
class QuantityTextTest {
    @Test
    fun `une piece au singulier, deux pieces au pluriel, un et demi au singulier`() {
        assertEquals("1 pièce", formatQuantity(1.0, "pièce"))
        assertEquals("1,5 pièce", formatQuantity(1.5, "pièce"))
        assertEquals("2 pièces", formatQuantity(2.0, "pièce"))
        assertEquals("0,5 pièce", formatQuantity(0.5, "pièce"))
        assertEquals("12 pièces", formatQuantity(12.0, "pièce"))
    }

    @Test
    fun `les unites en mots s accordent`() {
        assertEquals("1 tranche", formatQuantity(1.0, "tranche"))
        assertEquals("3 tranches", formatQuantity(3.0, "tranche"))
        assertEquals("2 portions", formatQuantity(2.0, "portion"))
        assertEquals("2 verres", formatQuantity(2.0, "verre"))
        assertEquals("1 verre", formatQuantity(1.0, "verre"))
        assertEquals("2 barres", formatQuantity(2.0, "barre"))
        assertEquals("2 cuillères à soupe", formatQuantity(2.0, "cuillère à soupe"))
        assertEquals("1 cuillère à soupe", formatQuantity(1.0, "cuillère à soupe"))
        assertEquals("2 pots de yaourt", formatQuantity(2.0, "pot de yaourt"))
        assertEquals("2 bocaux", formatQuantity(2.0, "bocal"))
    }

    @Test
    fun `les unites abregees et deja au pluriel ne changent pas`() {
        for (unit in listOf("g", "ml", "kg", "cl", "kcal", "l")) assertEquals("250 $unit", formatQuantity(250.0, unit))
        assertEquals("2 pièces", formatQuantity(2.0, "pièces"))
        assertEquals("3 noix", formatQuantity(3.0, "noix"))
    }

    @Test
    fun `la carte du chat et la feuille de repas disent la meme chose`() {
        assertEquals("2 pièces", cardQuantity(2.0, "pièce"))
        assertEquals("1,5 pièce", cardQuantity(1.5, "pièce"))
        assertEquals("150 g", cardQuantity(150.0, "g"))
        assertEquals(formatQuantity(3.0, "tranche"), cardQuantity(3.0, "tranche"))
    }

    @Test
    fun `une carte deja enregistree avec 2 piece est remise d accord a l affichage`() {
        assertEquals("2 pièces", QuantityText.agree("2 pièce"))
        assertEquals("1 pièce", QuantityText.agree("1 pièce"))
        assertEquals("1,5 pièce", QuantityText.agree("1,5 pièce"))
        assertEquals("2,5 tranches", QuantityText.agree("2,5 tranche"))
        assertEquals("150 g", QuantityText.agree("150 g"))
        assertEquals("2 pièces", QuantityText.agree("2 pièces"))
        assertEquals("une poignée", QuantityText.agree("une poignée"))
    }

    @Test
    fun `piece, unite anglaise de l ancien suivi, s ecrit en francais`() {
        assertEquals("1 pièce", QuantityText.agree("1 piece"))
        assertEquals("2 pièces", QuantityText.agree("2 pieces"))
        assertEquals("1 pièce", formatQuantity(1.0, "piece"))
    }
}
