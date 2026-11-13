package app.trackly.core

import app.trackly.core.action.CorrectTerm
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.Rejection
import app.trackly.core.action.RemoveTermCorrection
import app.trackly.core.action.UndoLast
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ParityBundle
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Preference
import app.trackly.core.model.RecordState
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.TermCorrection
import app.trackly.core.model.TermCorrections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mots corrigés : remplacement en mot entier, action atomique, annulation d'un geste. */
class TermCorrectionsTest {
    private val vita = TermCorrection("p1", "vita", "Vitta")

    private fun fix(text: String, vararg c: TermCorrection) = TermCorrections.apply(text, c.toList())

    @Test
    fun `remplace le mot entier sans tenir compte de la casse`() {
        assertEquals("Vitta", fix("vita", vita))
        assertEquals("Vitta", fix("VITA", vita))
        assertEquals("barre Vitta, puis Vitta.", fix("barre Vita, puis vita.", vita))
        assertEquals("(Vitta)", fix("(vita)", vita))
        assertEquals("l'Vitta", fix("l'vita", vita))
    }

    @Test
    fun `un texte deja juste n est pas double`() {
        val express = TermCorrection("p3", "express", "Vitta Express")
        assertEquals("Sandwich Vitta Express", fix("Sandwich Vitta Express", express))
        assertEquals("Sandwich Vitta Express", fix("Sandwich express", express))
        val cola = TermCorrection("p4", "cola", "Soda-Cola")
        assertEquals("Soda-Cola Zero", fix("Soda-Cola Zero", cola))
        assertEquals("un Soda-Cola", fix("un cola", cola))
        // Correction de casse seulement : elle s'applique bien.
        assertEquals("Vitta", fix("vitta", TermCorrection("p5", "vitta", "Vitta")))
    }

    @Test
    fun `ne touche pas un mot qui contient le mot entendu`() {
        assertEquals("un plat vitaminé", fix("un plat vitaminé", vita))
        assertEquals("le revitalisant", fix("le revitalisant", vita))
        assertEquals("vita2 et 3vita", fix("vita2 et 3vita", vita))
        assertEquals("vitaé", fix("vitaé", vita))
    }

    @Test
    fun `ignore les accents et garde ceux du vrai mot`() {
        val creme = TermCorrection("p2", "creme", "Crème")
        assertEquals("Crème dessert", fix("crème dessert", creme))
        assertEquals("Crème dessert", fix("CREME dessert", creme))
        assertEquals("Café", fix("cafe", TermCorrection("p3", "café", "Café")))
        assertEquals("Frère", fix("frère", TermCorrection("p4", "frere", "Frère")))
    }

    @Test
    fun `un mot de plusieurs mots et un seul passage`() {
        assertEquals("barre Vitta choco", fix("barre  vita choco", TermCorrection("p5", "barre vita", "barre Vitta")))
        // Pas de chaîne : a → b puis b → c ne donne pas c.
        assertEquals("b c", fix("a b", TermCorrection("x", "a", "b"), TermCorrection("y", "b", "c")))
        assertEquals("rien à corriger", fix("rien à corriger"))
    }

    @Test
    fun `seules les corrections actives du bon type sont lues`() = runBlocking {
        val f = Fixture()
        f.apply(CorrectTerm("vita", "Vitta"))
        f.apply(CorrectTerm("choc", "Chocolat"))
        f.apply(RemoveTermCorrection("choc"))

        val active = f.engine.activeTermCorrections()
        assertEquals(listOf("vita" to "Vitta"), active.map { it.heard to it.correct })
        assertTrue(TermCorrections.KIND == "term_correction")
    }

    private val bar = NewProduct(
        name = "Barre protéinée VITA chocolat", brand = "VITA",
        nutrients = Nutrients(400.0, 36.0, 22.0, 14.0, 5.0), basis = NutritionBasis(100.0, "g"),
    )

    @Test
    fun `corrige les noms et marques des produits et plats, l'ancien nom reste un raccourci`() = runBlocking {
        val f = Fixture()
        f.apply(MemorizeProduct(bar))
        f.apply(MemorizeProduct(bar.copy(name = "Vitaminé muesli", brand = "Revital")))
        f.apply(CreateDish("Salade VITA", listOf(DishIngredientInput("Riz", 100.0, Nutrients(130.0, 3.0, 28.0, 0.3)))))

        val result = f.apply(CorrectTerm("vita", "Vitta"))

        val products = f.engine.allProducts().associateBy { it.name }
        val renamed = products.getValue("Barre protéinée Vitta chocolat")
        assertEquals("Vitta", renamed.brand)
        assertTrue("Barre protéinée VITA chocolat" in renamed.aliases)
        // « Vitaminé » et « Revital » ne sont pas des mots entiers « vita ».
        assertEquals("Revital", products.getValue("Vitaminé muesli").brand)
        assertEquals(listOf("Salade Vitta"), f.engine.allRecipes().map { it.name })
        assertEquals("Salade VITA", f.engine.allRecipes().single().aliases.single())
        assertEquals(1, f.engine.activeTermCorrections().size)
        // Une seule action : préférence + produit + plat.
        assertEquals(setOf("preference", "product", "recipe"), result.changes.map { it.kind }.toSet())
    }

    @Test
    fun `une seule annulation defait tout`() = runBlocking {
        val f = Fixture()
        f.apply(MemorizeProduct(bar))
        f.apply(CreateDish("Salade VITA", listOf(DishIngredientInput("Riz", 100.0, Nutrients(130.0, 3.0, 28.0, 0.3)))))
        val before = f.engine.allProducts() to f.engine.allRecipes()

        f.apply(CorrectTerm("vita", "Vitta"))
        f.apply(UndoLast)

        assertEquals(emptyList<Any>(), f.engine.activeTermCorrections())
        assertEquals("Barre protéinée VITA chocolat", f.engine.allProducts().single().name)
        assertEquals("VITA", f.engine.allProducts().single().brand)
        assertEquals("Salade VITA", f.engine.allRecipes().single().name)
        assertTrue(f.engine.allRecipes().single().aliases.isEmpty())
        assertEquals(before.first.single().productId, f.engine.allProducts().single().productId)
    }

    @Test
    fun `corriger deux fois ne change rien, une regle changee est une nouvelle version`() = runBlocking {
        val f = Fixture()
        f.apply(CorrectTerm("vita", "Vitta"))
        assertTrue(f.reject(CorrectTerm("VITA", "Vitta")) is Rejection.NoChange)
        f.apply(CorrectTerm("vita", "VITTA"))
        assertEquals("VITTA", f.engine.activeTermCorrections().single().correct)
    }

    @Test
    fun `retirer desactive sans rien supprimer`() = runBlocking {
        val f = Fixture()
        f.apply(CorrectTerm("vita", "Vitta"))
        f.apply(RemoveTermCorrection("Vita"))
        assertEquals(emptyList<Any>(), f.engine.activeTermCorrections())
        assertTrue(f.reject(RemoveTermCorrection("vita")) is Rejection.NotFound)
        // Réactivée par une nouvelle déclaration, même identifiant.
        f.apply(CorrectTerm("vita", "Vitta"))
        assertEquals(1, f.engine.activeTermCorrections().size)
        f.apply(UndoLast)
        assertEquals(emptyList<Any>(), f.engine.activeTermCorrections())
    }

    @Test
    fun `un nom deja pris par une autre fiche n'est pas fusionne en silence`() = runBlocking {
        val f = Fixture()
        f.apply(MemorizeProduct(bar))
        f.apply(MemorizeProduct(bar.copy(name = "Barre protéinée Vitta chocolat", brand = null)))

        f.apply(CorrectTerm("vita", "Vitta"))

        val names = f.engine.allProducts().map { it.name }.sorted()
        assertEquals(listOf("Barre protéinée VITA chocolat", "Barre protéinée Vitta chocolat"), names)
        // La fiche laissée telle quelle garde aussi sa marque.
        assertEquals("VITA", f.engine.allProducts().first { it.name.endsWith("VITA chocolat") }.brand)
    }

    @Test
    fun `corriger une marque ne cree pas deux fiches identiques`() = runBlocking {
        val f = Fixture()
        f.apply(MemorizeProduct(NewProduct("Lait", brand = "vita", nutrients = Nutrients(47.0, 3.3, 4.8, 1.6), basis = NutritionBasis(100.0, "g"))))
        f.apply(MemorizeProduct(NewProduct("Lait", brand = "Vitta", nutrients = Nutrients(64.0, 3.2, 4.7, 3.5), basis = NutritionBasis(100.0, "g"))))
        f.apply(CorrectTerm("vita", "Vitta"))
        val laits = f.engine.allProducts().filter { it.isActive && it.name == "Lait" }.map { it.brand }
        assertEquals(listOf("Vitta", "vita"), laits.sortedBy { it })
    }

    @Test
    fun `refuse les mots vides ou identiques`() = runBlocking {
        val f = Fixture()
        assertTrue(f.reject(CorrectTerm("  ", "Vitta")) is Rejection.Invalid)
        assertTrue(f.reject(CorrectTerm("vita", " ")) is Rejection.Invalid)
        assertTrue(f.reject(CorrectTerm("Vitta", "Vitta")) is Rejection.Invalid)
    }

    @Test
    fun `une reprise de l historique ne touche pas les mots corriges, et lit un type inconnu`() = runBlocking {
        val f = Fixture()
        f.apply(CorrectTerm("vita", "Vitta"))

        val outcome = HistoryImport.run(f.engine, ParityBundle.text)

        assertTrue(outcome.toString(), outcome is ImportOutcome.Imported)
        // La règle du moteur Python (« riz ») arrive à côté ; le mot corrigé reste actif, intact.
        val all = f.engine.activePreferences()
        assertEquals(setOf("riz", "vita"), all.map { it.trigger }.toSet())
        assertEquals("Vitta", f.engine.activeTermCorrections().single().correct)
        assertEquals(1, f.engine.activeTermCorrections().size)
        // L'ancien moteur ne connaît pas ce type : il est lu comme n'importe quel autre texte, jamais refusé.
        val unknown = Preference("tc-x", 1, RecordState.Active, "futur_type_inconnu", "a", "b", true, java.time.Instant.EPOCH, null)
        assertTrue(TermCorrections.active(listOf(unknown)).isEmpty())
    }
}
