package app.trackly.dishes

import app.trackly.core.model.Nutrients
import app.trackly.core.model.Product
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Noms propres à l'affichage : les traces de l'ancien suivi disparaissent, le vrai nom ne change pas. */
class ProductNamesTest {
    private fun clean(name: String) = ProductsBook.cleanName(name)

    @Test
    fun `prefixes de moment retires`() {
        assertEquals("Galette de riz", clean("Déjeuner — Galette de riz"))
        assertEquals("Muesli croustillant", clean("Petit-déjeuner — Muesli croustillant"))
        assertEquals("Soupe de légumes", clean("Dîner — Soupe de légumes"))
        assertEquals("Barre aux noix", clean("Snack — Barre aux noix"))
        assertEquals("Thé glacé pêche", clean("Boisson — Thé glacé pêche"))
    }

    @Test
    fun `traces de fin retirees, chaque motif`() {
        assertEquals("Biscuit avoine", clean("Biscuit avoine (EAN 1234567890123)"))
        assertEquals("Wrap maison", clean("Wrap maison — nom partiellement masqué"))
        assertEquals("Pizza jambon", clean("Pizza jambon — garniture partiellement masquée"))
        assertEquals("Crème dessert vanille", clean("Crème dessert vanille, identité partielle"))
        assertEquals("Fromage frais", clean("Fromage frais — identifié par correspondance secondaire FDDB"))
        assertEquals("Fromage frais", clean("Fromage frais (identifié par correspondance secondaire FDDB)"))
        assertEquals("Fromage frais", clean("Fromage frais, identifiée par correspondance secondaire FDDB"))
        assertEquals("Pain suédois", clean("Pain suédois — 388 kcal/100 g (blé complet présumé)"))
        assertEquals("Pain suédois — 2 tranches", clean("Pain suédois — 350 kcal/100 g — 2 tranches"))
        assertEquals("Biscuit avoine", clean("Snack — Biscuit avoine (EAN 12345678) — nom partiellement masqué"))
    }

    @Test
    fun `ce qui a du sens reste`() {
        assertEquals("Salade composée — 245 g", clean("Salade composée — 245 g"))
        assertEquals("Snack-bar aux céréales", clean("Snack-bar aux céréales"))
        assertEquals("Boisson au soja", clean("Boisson au soja"))
        assertEquals("Lait — 100 kcal", clean("Lait — 100 kcal"))
        assertEquals("Yaourt (EAN maison)", clean("Yaourt (EAN maison)"))
        // Rien de lisible après nettoyage : le nom d'origine.
        assertEquals("Déjeuner — (EAN 12345678)", clean("Déjeuner — (EAN 12345678)"))
        assertEquals("— nom partiellement masqué", clean("— nom partiellement masqué"))
    }

    private fun product(name: String, brand: String?) =
        Product("p", 1, RecordState.Active, name, brand = brand, nutrients = Nutrients(100.0), recordedAt = Instant.EPOCH, actionId = null)

    @Test
    fun `la marque deja dans le nom n est pas repetee`() {
        assertEquals("Fourré Choco Max", ProductsBook.title(product("Fourré Choco Max", "Fourré")))
        assertEquals("Chips paprika Exemple", ProductsBook.title(product("Chips paprika Exemple", "exemple")))
        assertEquals("Bio Lait Plus Avoine", ProductsBook.title(product("Bio Lait Plus Avoine", "Bio / Lait Plus")))
        assertEquals("Avoine Plus (Maison)", ProductsBook.title(product("Avoine Plus", "Maison")))
        assertEquals("Avoine Plus (Maison)", ProductsBook.title(product("Snack — Avoine Plus", "Maison (identifié par correspondance secondaire FDDB")))
    }

    @Test
    fun `une piece en francais dans la ligne de portion`() {
        val p = product("Croissant", null).copy(portion = ProductPortion("1 piece", Quantity(60.0, "g"), Nutrients(250.0)))
        assertEquals("250 kcal par 1 pièce", ProductsBook.summary(p))
    }
}
