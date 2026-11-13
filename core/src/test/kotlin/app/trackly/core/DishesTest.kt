package app.trackly.core

import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.DeleteDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.Rejection
import app.trackly.core.action.RelinkConsumptionToDish
import app.trackly.core.action.SetDefaultVariant
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.UndoLast
import app.trackly.core.action.UpdateDish
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Quantity
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecordState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plats récurrents : création, variantes, variante par défaut, pause, calcul par le code, annulation. */
class DishesTest {
    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val sauceA = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
    private val sauceB = DishIngredientInput("Sauce vinaigrette", 20.0, Nutrients(450.0, 0.0, 3.0, 48.0))

    private suspend fun Fixture.salade(): String =
        apply(CreateDish("Salade", listOf(laitue, thon, sauceA), shortcuts = listOf("ma salade"), variantName = "sauce A", dishId = "rcp-salade")).let { "rcp-salade" }

    private suspend fun Fixture.recipes() = engine.allRecipes()

    @Test
    fun `creer un plat ecrit une version complete et calcule ses valeurs par portion`() = runBlocking {
        val f = Fixture()
        f.salade()

        val recipe = f.recipes().single()
        assertEquals("Salade", recipe.name)
        assertEquals("sauce A", recipe.variantName)
        assertEquals(listOf("ma salade"), recipe.aliases)
        // 100 g de laitue + 80 g de thon + 30 g de sauce : 15 + 88 + 24 = 127 kcal ; protéines 1,4 + 20 + 0,9 = 22,3.
        val portion = RecipeServing.perPortion(recipe)!!
        assertEquals(127.0, portion.kcal, 0.001)
        assertEquals(22.3, portion.proteinG!!, 0.001)
        assertEquals(2.2, portion.fatG!!, 0.001)
    }

    @Test
    fun `un plat sans kcal connues pour un ingredient est refuse`() = runBlocking {
        val f = Fixture()
        val rejection = f.reject(CreateDish("Mystère", listOf(DishIngredientInput("Truc", 100.0, Nutrients(proteinG = 3.0)))))
        assertTrue(rejection.message, rejection.message.contains("kcal"))
        assertTrue(f.recipes().isEmpty())
    }

    @Test
    fun `un nom de plat deja pris, nom ou raccourci, est refuse`() = runBlocking {
        val f = Fixture()
        f.salade()
        assertTrue(f.reject(CreateDish("SALADE", listOf(laitue))) is Rejection.Invalid)
        assertTrue(f.reject(CreateDish("Salade du chef", listOf(laitue), shortcuts = listOf("Ma salade"))) is Rejection.Invalid)
        assertTrue(f.reject(CreateDish("Vide", emptyList())) is Rejection.Invalid)
    }

    @Test
    fun `une variante part d une copie du plat, a ses ingredients et peut devenir le defaut`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        val result = f.apply(AddDishVariant(id, "sauce B", ingredients = listOf(laitue, thon, sauceB), makeDefault = true, variantId = "rcp-b"))

        val dish = Dishes.group(f.recipes()).single()
        assertEquals(listOf("sauce B"), dish.variants.map { it.variantName })
        assertEquals("Salade", dish.variants.single().name)
        assertEquals("rcp-b", dish.defaultVersion.recipeId)
        assertEquals(2, result.changes.size) // la variante, et le plat qui la désigne par défaut
        // Copie : sans ingrédients donnés, elle reprend ceux de la base.
        f.apply(AddDishVariant(id, "sans sauce", ingredients = null))
        val copy = Dishes.group(f.recipes()).single().variants.first { it.variantName == "sans sauce" }
        assertEquals(Dishes.group(f.recipes()).single().base.ingredients, copy.ingredients)
        // Un nom de version se prend une seule fois par plat.
        assertTrue(f.reject(AddDishVariant(id, "Sauce B")) is Rejection.Invalid)
        assertTrue(f.reject(AddDishVariant(id, "sauce A")) is Rejection.Invalid)
    }

    @Test
    fun `choisir la variante par defaut, ou revenir a la base`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, sauceB), variantId = "rcp-b"))
        assertEquals(id, Dishes.group(f.recipes()).single().defaultVersion.recipeId)

        f.apply(SetDefaultVariant(id, "rcp-b"))
        assertEquals("rcp-b", Dishes.group(f.recipes()).single().defaultVersion.recipeId)
        assertTrue(f.reject(SetDefaultVariant(id, "rcp-b")) is Rejection.NoChange)
        assertTrue(f.reject(SetDefaultVariant(id, "rcp-inconnu")) is Rejection.NotFound)

        f.apply(SetDefaultVariant(id, null))
        assertEquals(id, Dishes.group(f.recipes()).single().defaultVersion.recipeId)
        // Chaque choix est une nouvelle version du plat : rien n'est écrasé.
        assertEquals(3, f.store.transaction { recipes.version(id, 3) }?.version)
    }

    @Test
    fun `salade sans preciser prend la variante par defaut, une variante nommee est prise telle quelle`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, thon, sauceB), variantId = "rcp-b"))
        f.apply(SetDefaultVariant(id, "rcp-b"))

        val byDefault = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(1.0, "portion"), Moment.Midi))))
        val first = byDefault.consumptionChanges.single().after.food
        assertEquals("Salade (sauce B)", first.label)
        assertEquals("rcp-b", first.recipeId)
        // 100 g laitue (15) + 80 g thon (88) + 20 g sauce B (90) = 193 kcal, calculés par le code.
        assertEquals(193.0, first.nutrition.kcal, 0.001)
        assertTrue(first.assumptions.single().contains("Variante par défaut : sauce B"))

        // L'utilisateur nomme une variante : elle est prise telle quelle, sans mention du défaut.
        val named = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe("rcp-b", Quantity(1.0, "portion"), Moment.Soir))))
        assertEquals("Salade (sauce B)", named.consumptionChanges.single().after.food.label)
        assertTrue(named.consumptionChanges.single().after.food.assumptions.isEmpty())

        // Il désigne la version de base (sauce A) alors que le défaut est B : useDefault = false.
        val base = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(1.0, "portion"), Moment.Soir, useDefault = false))))
        assertEquals("Salade (sauce A)", base.consumptionChanges.single().after.food.label)
        assertEquals(127.0, base.consumptionChanges.single().after.food.nutrition.kcal, 0.001)
    }

    @Test
    fun `une demi portion ou des grammes de plat se calculent depuis les ingredients`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        val half = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(0.5, "portion"), Moment.Midi)))).consumptionChanges.single().after.food
        assertEquals(63.5, half.nutrition.kcal, 0.001)
        // Poids total des ingrédients : 210 g ; 105 g de plat préparé = moitié.
        val grams = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(105.0, "g"), Moment.Midi)))).consumptionChanges.single().after.food
        assertEquals(63.5, grams.nutrition.kcal, 0.001)
        assertTrue(f.reject(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(2.0, "pièce"), Moment.Midi)))) is Rejection.Invalid)
        assertEquals(2, f.summary().consumptions.size)
    }

    @Test
    fun `non celle avec la sauce B - nouvelle version complete, valeurs recalculees, annulable`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, thon, sauceB), variantId = "rcp-b"))
        val entry = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(1.0, "portion"), Moment.Midi))))
            .consumptionChanges.single().after.entryId
        assertEquals(127.0, f.summary().totals.kcal, 0.001)

        val fixed = f.apply(RelinkConsumptionToDish(entry, "rcp-b"))

        assertEquals(listOf(1, 2), f.engine.history(entry).map { it.version })
        val food = f.engine.history(entry).last().food
        assertEquals("Salade (sauce B)", food.label)
        assertEquals("rcp-b", food.recipeId)
        assertEquals(193.0, f.summary().totals.kcal, 0.001)
        assertEquals(Quantity(1.0, "portion"), food.quantity)
        assertTrue(f.reject(RelinkConsumptionToDish(entry, "rcp-b")) is Rejection.NoChange)

        f.apply(UndoLast)
        assertEquals(127.0, f.summary().totals.kcal, 0.001)
        assertEquals(fixed.id.value + 1, f.engine.observeRecentActions().first().first().id.value)
    }

    @Test
    fun `pause et reactivation sont versionnees et annulables, le defaut ne se met pas en pause`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, sauceB), variantId = "rcp-b", makeDefault = true))

        f.apply(SetDishPaused(id, paused = true))
        val paused = Dishes.group(f.recipes()).single()
        assertTrue(paused.isPaused)
        assertTrue(paused.suggestible.isEmpty())
        assertTrue(f.reject(SetDishPaused(id, paused = true)) is Rejection.NoChange)

        f.apply(SetDishPaused(id, paused = false))
        assertFalse(Dishes.group(f.recipes()).single().isPaused)
        // La variante par défaut reste active ; sa voisine peut se mettre en pause.
        assertTrue(f.reject(SetDishPaused("rcp-b", paused = true)) is Rejection.Invalid)
        f.apply(SetDefaultVariant(id, null))
        f.apply(SetDishPaused("rcp-b", paused = true))
        assertTrue(f.reject(SetDefaultVariant(id, "rcp-b")) is Rejection.Invalid)

        f.apply(UndoLast)
        assertTrue(Dishes.group(f.recipes()).single().variants.single().active)
        // Rien n'a disparu : plusieurs versions du plat sont gardées.
        assertTrue(f.store.transaction { recipes.version(id, 2) } != null)
    }

    @Test
    fun `un plat en pause se mange encore et la variante en pause n est pas le defaut`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(SetDishPaused(id, paused = true))
        // Il était dans le congélateur : le repas est enregistré quand même.
        val result = f.apply(DeclareConsumptions(listOf(DeclaredItem.OfRecipe(id, Quantity(1.0, "portion"), Moment.Midi))))
        assertEquals(127.0, result.consumptionChanges.single().after.food.nutrition.kcal, 0.001)
    }

    @Test
    fun `modifier ingredients et nom - version complete, les variantes suivent le nom`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, sauceB), variantId = "rcp-b"))

        val kept = f.recipes().first { it.recipeId == id }.ingredients.map(DishIngredientInput::of)
        f.apply(UpdateDish(id, name = "Salade composée", ingredients = kept.map { if (it.label == "Laitue") it.copy(quantityG = 150.0) else it }))

        val dish = Dishes.group(f.recipes()).single()
        assertEquals("Salade composée", dish.name)
        assertEquals("Salade composée", dish.variants.single().name)
        assertEquals(150.0, dish.base.ingredients.first { it.label == "Laitue" }.quantityG, 0.0)
        // Les identifiants d'ingrédients sont conservés d'une version à l'autre.
        assertEquals(f.store.transaction { recipes.version(id, 1) }!!.ingredients.map { it.ingredientId }, dish.base.ingredients.map { it.ingredientId })
        assertEquals(2, dish.base.version)
        // Renommer une variante, pas le plat.
        f.apply(UpdateDish("rcp-b", variantName = "vinaigrette"))
        assertEquals("Salade composée (vinaigrette)", Dishes.label(f.recipes().first { it.recipeId == "rcp-b" }))
        assertTrue(f.reject(UpdateDish("rcp-b", name = "Autre")) is Rejection.Invalid)
        assertTrue(f.reject(UpdateDish(id)) is Rejection.NoChange)
        // Le nom d'un autre plat est refusé.
        f.apply(CreateDish("Pâtes", listOf(laitue), dishId = "rcp-pates"))
        assertTrue(f.reject(UpdateDish("rcp-pates", name = "Salade composée")) is Rejection.Invalid)
    }

    @Test
    fun `un ingredient peut venir d un produit connu - valeurs calculees par le code pour 100 g`() = runBlocking {
        val f = Fixture()
        f.apply(
            MemorizeProduct(
                NewProduct(
                    productId = "prd-riz", name = "Riz basmati cuit", nutrients = Nutrients(130.0, 2.7, 28.0, 0.3),
                    basis = NutritionBasis(100.0, "g"), kind = ProductKind.Recurring,
                ),
            ),
        )
        f.apply(CreateDish("Bol de riz", listOf(DishIngredientInput(null, 200.0, null, productId = "prd-riz")), dishId = "rcp-riz"))

        val ingredient = f.recipes().single().ingredients.single()
        assertEquals("Riz basmati cuit", ingredient.label)
        assertEquals("prd-riz", ingredient.productId)
        assertEquals(130.0, ingredient.per100g.kcal!!, 0.0)
        assertEquals(260.0, RecipeServing.perPortion(f.recipes().single())!!.kcal, 0.001)
        assertTrue(f.reject(CreateDish("Fantôme", listOf(DishIngredientInput(null, 100.0, null, productId = "prd-absent")))) is Rejection.NotFound)
    }

    @Test
    fun `supprimer un plat supprime ses variantes, supprimer la variante par defaut revient a la base - annulable`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, sauceB), variantId = "rcp-b", makeDefault = true))

        f.apply(DeleteDish("rcp-b"))
        var dish = Dishes.group(f.recipes()).single()
        assertTrue(dish.variants.isEmpty())
        assertNull(dish.base.defaultVariantId)

        f.apply(UndoLast)
        dish = Dishes.group(f.recipes()).single()
        assertEquals(listOf("sauce B"), dish.variants.map { it.variantName })

        f.apply(DeleteDish(id))
        assertTrue(Dishes.group(f.recipes()).isEmpty())
        assertEquals(RecordState.Deleted, f.recipes().first { it.recipeId == id }.state)
        f.apply(UndoLast)
        assertEquals(1, Dishes.group(f.recipes()).size)
    }

    @Test
    fun `la recherche trouve le plat par nom, raccourci et nom de variante`() = runBlocking {
        val f = Fixture()
        val id = f.salade()
        f.apply(AddDishVariant(id, "sauce B", listOf(laitue, sauceB), variantId = "rcp-b"))
        val all = f.recipes()

        assertEquals(setOf(id, "rcp-b"), CatalogSearch.recipes(all, "salade").map { it.recipe.recipeId }.toSet())
        assertEquals("rcp-b", CatalogSearch.recipes(all, "ma salade sauce B").first().recipe.recipeId)
        assertEquals("rcp-b", CatalogSearch.recipes(all, "SALADE sauce b").first().recipe.recipeId)
        assertTrue(CatalogSearch.recipes(all, "ma salade").isNotEmpty()) // le raccourci du plat
        assertTrue(CatalogSearch.recipes(all, "pizza").isEmpty())
    }

    @Test
    fun `un plat importe sans poids complets ne se calcule pas et le dit`() {
        val imported = Recipe("rcp-old", 1, RecordState.Active, "Plat repris", sourceTemplateJson = "{}", recordedAt = java.time.Instant.EPOCH, actionId = null)
        val error = runCatching { RecipeServing.nutrition(imported, Quantity(1.0, "portion")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("ingrédients"))
    }

    @Test
    fun `variante pesee d un plat repris sans ingredients - calculee, le plat de base garde son modele`() = runBlocking {
        val f = Fixture()
        val template = """{"components":[{"name":"base salade/légumes"},{"ingredient_ref":"sauce-x","default_quantity":{"unit":"g","value":70}}]}"""
        val imported = Recipe("rcp-old", 1, RecordState.Active, "Salade habituelle", sourceTemplateJson = template, recordedAt = java.time.Instant.EPOCH, actionId = null)
        f.store.transaction { recipes.insert(imported) }

        // Sans ingrédients : rien à copier, refus clair.
        val refused = f.reject(AddDishVariant("rcp-old", "sauce B"))
        assertTrue("$refused", refused is Rejection.Invalid && refused.message.contains("pas d'ingrédients pesés"))

        f.apply(AddDishVariant("rcp-old", "sauce B", listOf(laitue, sauceB), makeDefault = true, variantId = "rcp-old-b"))
        val dish = Dishes.group(f.recipes()).single()
        assertEquals(listOf("rcp-old", "rcp-old-b"), dish.versions.map { it.recipeId })
        assertEquals("rcp-old-b", dish.defaultVersion.recipeId)
        assertEquals(105.0, RecipeServing.perPortion(dish.defaultVersion)!!.kcal, 0.001) // 15 + 90
        // Le plat de base garde son modèle et reste sans ingrédients.
        assertEquals(template, dish.base.sourceTemplateJson)
        assertTrue(dish.base.ingredients.isEmpty())
        assertNull(RecipeServing.perPortion(dish.base))
    }

    @Test
    fun `le moteur annonce chaque action appliquee pour recharger Mes plats`() = runBlocking {
        val f = Fixture()
        val before = f.engine.revision.value
        f.salade()
        assertEquals(before + 1, f.engine.revision.value)
        f.reject(CreateDish("Salade", listOf(laitue)))
        assertEquals(before + 1, f.engine.revision.value)
        assertEquals(listOf("Salade"), f.engine.dishBook().dishes.map { it.name })
    }
}
