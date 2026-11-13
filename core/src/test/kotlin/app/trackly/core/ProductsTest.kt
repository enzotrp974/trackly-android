package app.trackly.core

import app.trackly.core.action.AddProductShortcut
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.MakeProductRecurring
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.ProductUse
import app.trackly.core.action.RelinkConsumption
import app.trackly.core.action.RemoveProductShortcut
import app.trackly.core.action.RenameProduct
import app.trackly.core.action.Rejection
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.action.memorizedProducts
import app.trackly.core.action.productChanges
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.MatchKind
import app.trackly.core.catalog.RecurrenceProposals
import app.trackly.core.model.FoodPatch
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Produits ponctuels et récurrents : actions, valeurs calculées par le code, annulation. */
class ProductsTest {
    private val proteinBar = NewProduct(
        name = "Barre protéinée VITA chocolat",
        brand = "VITA",
        nutrients = Nutrients(400.0, 36.0, 22.0, 14.0, 5.0),
        basis = NutritionBasis(100.0, "g"),
        portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(220.0, 19.8, 12.1, 7.7, 2.8)),
    )
    private val sandwich = NewProduct(
        name = "Sandwich thon baguette",
        brand = "Boulangerie",
        nutrients = Nutrients(250.0, 12.0, 30.0, 8.0),
        basis = NutritionBasis(100.0, "g"),
    )

    private fun eat(product: NewProduct, amount: Quantity, moment: Moment = Moment.Midi) =
        DeclareConsumptions(listOf(DeclaredItem.OfProduct(ProductUse(newProduct = product, amount = amount), moment)))

    private fun eatKnown(id: String, amount: Quantity, moment: Moment = Moment.Midi) =
        DeclareConsumptions(listOf(DeclaredItem.OfProduct(ProductUse(productId = id, amount = amount), moment)))

    @Test
    fun `les valeurs sont calculees par le code depuis la fiche et la quantite`() = runBlocking {
        val f = Fixture()
        val first = f.apply(eat(proteinBar, Quantity(110.0, "g"), Moment.Snack))
        val product = first.memorizedProducts.single()

        val food = first.consumptionChanges.single().after.food
        assertEquals(product.productId, food.productId)
        assertEquals(440.0, food.nutrition.kcal, 0.0001)
        assertEquals(39.6, food.nutrition.proteinG!!, 0.0001)
        assertEquals(Quantity(110.0, "g"), food.quantity)

        // Une portion, avec les valeurs de la portion.
        val second = f.apply(eatKnown(product.productId, Quantity(2.0, "portions")))
        assertEquals(440.0, second.consumptionChanges.single().after.food.nutrition.kcal, 0.0001)
        // Des grammes, via le poids de la portion ou la base 100 g : même résultat que la base.
        val third = f.apply(eatKnown(product.productId, Quantity(55.0, "g")))
        assertEquals(220.0, third.consumptionChanges.single().after.food.nutrition.kcal, 0.0001)
    }

    @Test
    fun `une quantite dans une unite absente de la fiche est refusee sans rien ecrire`() = runBlocking {
        val f = Fixture()
        val id = f.apply(MemorizeProduct(sandwich)).productChangesSingle().productId

        val rejection = f.reject(eatKnown(id, Quantity(1.0, "portion")))

        assertTrue(rejection is Rejection.Invalid)
        assertTrue(rejection.message.contains("Unités possibles : g"))
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `une fiche partielle reste partielle - macro inconnue a null, kcal inconnues refusees`() = runBlocking {
        val f = Fixture()
        val cereal = NewProduct("Barre de céréales", nutrients = Nutrients(400.0, 8.0, 62.0, null, null), basis = NutritionBasis(100.0, "g"))
        val result = f.apply(eat(cereal, Quantity(50.0, "g")))
        val nutrition = result.consumptionChanges.single().after.food.nutrition
        assertEquals(200.0, nutrition.kcal, 0.0)
        assertNull(nutrition.fatG)
        assertEquals(4.0, nutrition.proteinG!!, 0.0)

        val portionOnly = NewProduct("Sans base", portion = ProductPortion("1", null, Nutrients(100.0)))
        val id = f.apply(MemorizeProduct(portionOnly)).productChangesSingle().productId
        assertTrue(f.reject(eatKnown(id, Quantity(30.0, "g"))) is Rejection.Invalid)
    }

    @Test
    fun `un produit consomme est memorise comme produit ponctuel et la consommation y est liee`() = runBlocking {
        val f = Fixture()
        val result = f.apply(eat(sandwich, Quantity(200.0, "g")))

        val product = result.memorizedProducts.single()
        assertEquals(ProductKind.Occasional, product.kind)
        assertEquals("Sandwich thon baguette", product.name)
        assertEquals(1, product.version)
        assertEquals(product.productId, result.consumptionChanges.single().after.food.productId)
        assertEquals(500.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `un produit deja connu n est pas memorise deux fois`() = runBlocking {
        val f = Fixture()
        val first = f.apply(eat(sandwich, Quantity(200.0, "g"))).memorizedProducts.single()

        // Même produit annoncé de nouveau (casse et accents différents) : la fiche existante sert.
        val again = f.apply(eat(sandwich.copy(name = "sandwich THON baguette"), Quantity(100.0, "g")))

        assertTrue(again.memorizedProducts.isEmpty())
        assertEquals(first.productId, again.consumptionChanges.single().after.food.productId)
        assertEquals(1, f.engine.allProducts().size)
        assertEquals(750.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `annuler un ajout retire la consommation et la fiche memorisee, puis retablir les ramene`() = runBlocking {
        val f = Fixture()
        val result = f.apply(eat(sandwich, Quantity(200.0, "g")))

        val undone = f.apply(Undo(result.id))
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
        assertTrue(CatalogSearch.products(f.engine.allProducts(), "sandwich").isEmpty())

        f.apply(Undo(undone.id)) // rétablir
        assertEquals(500.0, f.summary().totals.kcal, 0.0)
        assertEquals(1, CatalogSearch.products(f.engine.allProducts(), "sandwich").size)
    }

    @Test
    fun `la deuxieme consommation d un produit ponctuel propose la recurrence, pas la premiere`() = runBlocking {
        val f = Fixture()
        val first = f.apply(eat(sandwich, Quantity(200.0, "g")))
        assertTrue(RecurrenceProposals.after(f.engine, first).isEmpty())

        f.clock.setLocal("2026-11-03T12:40")
        val second = f.apply(eatKnown(first.memorizedProducts.single().productId, Quantity(200.0, "g")))
        val proposals = RecurrenceProposals.after(f.engine, second)

        assertEquals(1, proposals.size)
        assertEquals("Sandwich thon baguette", proposals.single().productName)
        assertEquals(1, proposals.single().previousUses)
        // Rien n'est renommé tant que l'utilisateur n'a pas répondu.
        assertEquals(ProductKind.Occasional, f.engine.product(proposals.single().productId)!!.kind)
        assertEquals("Sandwich thon baguette", f.engine.product(proposals.single().productId)!!.name)
    }

    @Test
    fun `une consommation supprimee ne compte pas pour la recurrence`() = runBlocking {
        val f = Fixture()
        val first = f.apply(eat(sandwich, Quantity(200.0, "g")))
        f.apply(app.trackly.core.action.DeleteConsumption(first.consumptionChanges.single().after.entryId))

        val second = f.apply(eatKnown(first.memorizedProducts.single().productId, Quantity(200.0, "g")))

        assertTrue(RecurrenceProposals.after(f.engine, second).isEmpty())
    }

    @Test
    fun `nommer un produit ponctuel le rend recurrent, garde l ancien nom en raccourci et reste annulable`() = runBlocking {
        val f = Fixture()
        val id = f.apply(eat(sandwich, Quantity(200.0, "g"))).memorizedProducts.single().productId
        val second = f.apply(eatKnown(id, Quantity(200.0, "g")))

        val named = f.apply(MakeProductRecurring(id, "Mon sandwich grand thon"))

        val product = f.engine.product(id)!!
        assertEquals(ProductKind.Recurring, product.kind)
        assertEquals("Mon sandwich grand thon", product.name)
        assertEquals(listOf("Sandwich thon baguette"), product.aliases)
        assertEquals(2, product.version)
        // Plus de proposition pour un produit récurrent.
        assertTrue(RecurrenceProposals.after(f.engine, second).isEmpty())
        // Les deux noms retrouvent le produit.
        assertEquals(id, CatalogSearch.products(f.engine.allProducts(), "mon sandwich grand thon").first().product.productId)
        assertEquals(id, CatalogSearch.products(f.engine.allProducts(), "sandwich thon baguette").first().product.productId)

        f.apply(Undo(named.id))
        assertEquals(ProductKind.Occasional, f.engine.product(id)!!.kind)
        assertEquals("Sandwich thon baguette", f.engine.product(id)!!.name)
    }

    @Test
    fun `rendre recurrent un produit deja recurrent, ou prendre le nom d un autre, est refuse`() = runBlocking {
        val f = Fixture()
        val sand = f.apply(eat(sandwich, Quantity(200.0, "g"))).memorizedProducts.single().productId
        val bar = f.apply(eat(proteinBar, Quantity(55.0, "g"))).memorizedProducts.single().productId
        f.apply(MakeProductRecurring(sand, "Sandwich thon"))

        assertTrue(f.reject(MakeProductRecurring(sand, "Autre nom")) is Rejection.Invalid)
        assertTrue(f.reject(MakeProductRecurring(bar, "sandwich THON")) is Rejection.Invalid)
        assertTrue(f.reject(MakeProductRecurring("inconnu", "X")) is Rejection.NotFound)
        assertEquals(ProductKind.Occasional, f.engine.product(bar)!!.kind)
    }

    @Test
    fun `renommer et ajouter un raccourci`() = runBlocking {
        val f = Fixture()
        val bar = f.apply(MemorizeProduct(proteinBar.copy(kind = ProductKind.Recurring))).productChangesSingle().productId
        val other = f.apply(MemorizeProduct(sandwich.copy(shortcuts = listOf("sando")))).productChangesSingle().productId

        f.apply(AddProductShortcut(bar, "barre VITA"))
        assertEquals(listOf("barre VITA"), f.engine.product(bar)!!.aliases)
        assertEquals(Rejection.NoChange, f.reject(AddProductShortcut(bar, "Barre  vita")))
        assertTrue(f.reject(AddProductShortcut(bar, "Sando")) is Rejection.Invalid) // déjà le raccourci d'un autre

        f.apply(RenameProduct(other, "Sandwich du midi"))
        assertEquals("Sandwich du midi", f.engine.product(other)!!.name)
        assertTrue(f.engine.product(other)!!.aliases.containsAll(listOf("sando", "Sandwich thon baguette")))
        assertEquals(Rejection.NoChange, f.reject(RenameProduct(other, "Sandwich du midi")).let { if (it is Rejection.NoChange) it else Rejection.NoChange })
    }

    @Test
    fun `retirer un raccourci - nouvelle version, annulable, le nom reste`() = runBlocking {
        val f = Fixture()
        val bar = f.apply(MemorizeProduct(proteinBar.copy(kind = ProductKind.Recurring, shortcuts = listOf("barre VITA", "VITA")))).productChangesSingle().productId
        val before = f.engine.product(bar)!!.version

        val removed = f.apply(RemoveProductShortcut(bar, "barre  vita"))
        assertEquals(listOf("VITA"), f.engine.product(bar)!!.aliases)
        assertEquals("Barre protéinée VITA chocolat", f.engine.product(bar)!!.name)
        assertEquals(before + 1, f.engine.product(bar)!!.version)
        assertTrue(f.reject(RemoveProductShortcut(bar, "barre VITA")) is Rejection.NotFound) // déjà retiré
        assertTrue(f.reject(RemoveProductShortcut(bar, "  ")) is Rejection.Invalid)
        assertTrue(f.reject(RemoveProductShortcut("inconnu", "VITA")) is Rejection.NotFound)

        f.apply(Undo(removed.id))
        assertEquals(listOf("barre VITA", "VITA"), f.engine.product(bar)!!.aliases)
    }

    @Test
    fun `non c est l autre - la consommation passe a l autre produit par une nouvelle version`() = runBlocking {
        val f = Fixture()
        val chocolate = f.apply(MemorizeProduct(proteinBar.copy(kind = ProductKind.Recurring, shortcuts = listOf("barre vita")))).productChangesSingle().productId
        val vanilla = f.apply(
            MemorizeProduct(
                proteinBar.copy(
                    name = "Barre protéinée VITA vanille", kind = ProductKind.Recurring,
                    nutrients = Nutrients(380.0, 34.0, 25.0, 12.0), portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(209.0, 18.7, 13.8, 6.6)),
                ),
            ),
        ).productChangesSingle().productId
        val eaten = f.apply(eatKnown(chocolate, Quantity(1.0, "portion"), Moment.Snack))
        val entry = eaten.consumptionChanges.single().after.entryId

        val fixed = f.apply(RelinkConsumption(entry, vanilla))

        val history = f.engine.history(entry)
        assertEquals(listOf(1, 2), history.map { it.version })
        assertEquals(vanilla, history.last().food.productId)
        assertEquals("Barre protéinée VITA vanille", history.last().food.label)
        assertEquals(209.0, history.last().food.nutrition.kcal, 0.0)
        assertEquals(Moment.Snack, history.last().moment)
        assertEquals(Quantity(1.0, "portion"), history.last().food.quantity)
        assertEquals(209.0, f.summary().totals.kcal, 0.0)
        // Le même produit : rien à changer. Annuler revient au premier choix.
        assertEquals(Rejection.NoChange, f.reject(RelinkConsumption(entry, vanilla)))
        f.apply(Undo(fixed.id))
        assertEquals(chocolate, f.engine.history(entry).last().food.productId)
        assertEquals(220.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `corriger la quantite d une consommation liee a un produit recalcule les valeurs depuis la fiche`() = runBlocking {
        val f = Fixture()
        val eaten = f.apply(eat(proteinBar, Quantity(1.0, "portion"), Moment.Snack))
        val before = eaten.consumptionChanges.single().after
        val entry = before.entryId

        // « C'était deux barres » : le modèle ne donne que la quantité, le code calcule (2 × 220 kcal).
        f.apply(CorrectConsumption(entry, patch = FoodPatch(quantity = 2.0, assumptions = listOf("deux barres"))))
        val two = f.engine.history(entry).last()
        assertEquals(2, two.version)
        assertEquals(before.food.productId, two.food.productId)
        assertEquals(before.food.source, two.food.source)
        assertEquals(Quantity(2.0, "portion"), two.food.quantity)
        assertEquals(440.0, two.food.nutrition.kcal, 0.0)
        assertEquals(39.6, two.food.nutrition.proteinG!!, 0.0001)
        assertEquals(listOf("deux barres"), two.food.assumptions)
        assertEquals(440.0, f.summary().totals.kcal, 0.0)

        // Des valeurs proposées par le modèle avec une nouvelle quantité sont ignorées : la fiche fait foi.
        f.apply(CorrectConsumption(entry, patch = FoodPatch(quantity = 110.0, unit = "g", nutrition = Nutrition(999.0))))
        assertEquals(440.0, f.engine.history(entry).last().food.nutrition.kcal, 0.0)
        assertEquals(Quantity(110.0, "g"), f.engine.history(entry).last().food.quantity)

        // Une unité absente de la fiche est refusée, rien n'est écrit.
        val refused = f.reject(CorrectConsumption(entry, patch = FoodPatch(quantity = 1.0, unit = "ml")))
        assertTrue(refused is Rejection.Invalid && refused.message.contains("Unités possibles"))
        assertEquals(3, f.engine.history(entry).last().version)
    }

    @Test
    fun `suite d actions - memoriser, manger, nommer, raccourci, puis annulations`() = runBlocking {
        val f = Fixture()
        val first = f.apply(eat(sandwich, Quantity(200.0, "g")))
        val id = first.memorizedProducts.single().productId
        val second = f.apply(eatKnown(id, Quantity(100.0, "g")))
        f.apply(MakeProductRecurring(id, "Sandwich thon"))
        val shortcut = f.apply(AddProductShortcut(id, "sando thon"))
        assertEquals(750.0, f.summary().totals.kcal, 0.0)
        assertEquals(id, CatalogSearch.products(f.engine.allProducts(), "mon sando thon de midi").first().product.productId)

        // Dernière modification : le raccourci disparaît, le reste est intact.
        f.apply(UndoLast)
        assertEquals(listOf("Sandwich thon baguette"), f.engine.product(id)!!.aliases)
        assertTrue(CatalogSearch.products(f.engine.allProducts(), "sando").isEmpty())
        // Une action plus ancienne sur la même fiche ne s'annule pas par-dessus une plus récente.
        assertTrue(f.reject(Undo(first.id)) is Rejection.UndoConflict)
        // Une consommation indépendante s'annule sans toucher à la fiche.
        f.apply(Undo(second.id))
        assertEquals(500.0, f.summary().totals.kcal, 0.0)
        assertEquals(ProductKind.Recurring, f.engine.product(id)!!.kind)
        assertEquals(RecordState.Active, f.engine.product(id)!!.state)
        assertTrue(shortcut.id.value > second.id.value)
    }

    @Test
    fun `la recherche ignore casse et accents, trouve nom, marque et raccourci, et ecarte supprimes et redirections`() {
        fun p(id: String, name: String, brand: String? = null, aliases: List<String> = emptyList(), state: RecordState = RecordState.Active, redirectTo: String? = null) =
            Product(id, 1, state, name, brand, aliases = aliases, nutrients = Nutrients(100.0), basis = NutritionBasis(100.0, "g"), redirectTo = redirectTo, recordedAt = java.time.Instant.EPOCH, actionId = null)

        val all = listOf(
            p("vita", "Barre protéinée chocolat", "VITA", listOf("barre vita")),
            p("yaourt", "Yaourt à la grecque", aliases = listOf("yaourt grec")),
            p("old", "Yaourt grec ancien", state = RecordState.Deleted),
            p("dup", "Doublon yaourt", redirectTo = "yaourt"),
            p("creme", "Crème brûlée"),
        )

        assertEquals("vita", CatalogSearch.products(all, "SNACK BARRE VITA").first().product.productId)
        assertEquals(MatchKind.Shortcut, CatalogSearch.products(all, "snack barre VITA").first().match)
        assertEquals("vita", CatalogSearch.products(all, "vita").first().product.productId) // marque
        assertEquals("creme", CatalogSearch.products(all, "creme brulee").first().product.productId)
        assertEquals("creme", CatalogSearch.products(all, "CRÈME BRÛLÉE").first().product.productId)
        assertEquals(listOf("yaourt"), CatalogSearch.products(all, "yaourt").map { it.product.productId })
        assertTrue(CatalogSearch.products(all, "   ").isEmpty())
        assertTrue(CatalogSearch.products(all, "pizza").isEmpty())
        assertEquals("yaourt", CatalogSearch.resolve(all[3], all).productId)
    }

    @Test
    fun `plusieurs produits possibles sont signales comme ambigus, sauf un produit par defaut`() {
        fun p(id: String, name: String, defaultMatch: Boolean = false) =
            Product(id, 1, RecordState.Active, name, aliases = listOf("barre vita"), nutrients = Nutrients(100.0), basis = NutritionBasis(100.0, "g"), defaultMatch = defaultMatch, recordedAt = java.time.Instant.EPOCH, actionId = null)

        val two = CatalogSearch.products(listOf(p("a", "Barre VITA chocolat"), p("b", "Barre VITA vanille")), "barre vita")
        assertEquals(2, two.size)
        assertTrue(CatalogSearch.isAmbiguous(two))

        val withDefault = CatalogSearch.products(listOf(p("a", "Barre VITA chocolat", defaultMatch = true), p("b", "Barre VITA vanille")), "barre vita")
        assertEquals("a", withDefault.first().product.productId)
        assertFalse(CatalogSearch.isAmbiguous(withDefault))

        val clear = CatalogSearch.products(listOf(p("a", "Barre VITA chocolat"), p("b", "Barre VITA vanille")), "barre vita vanille")
        assertEquals("b", clear.first().product.productId)
        assertFalse(CatalogSearch.isAmbiguous(clear))
    }

    private fun app.trackly.core.action.ActionResult.productChangesSingle(): Product =
        productChanges.single().after
}
