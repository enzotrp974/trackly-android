package app.trackly.core

import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.RecipeNutrition
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ParityBundle
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.ProductUse
import app.trackly.core.model.Moment
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Quantity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Produits et plats repris de l'ancien suivi : ils alimentent la recherche et la reconnaissance. */
class ImportedCatalogTest {
    private suspend fun imported(): Fixture = Fixture().also {
        assertTrue(HistoryImport.run(it.engine, ParityBundle.text) is ImportOutcome.Imported)
    }

    @Test
    fun `les produits repris sont recurrents et se retrouvent par nom ou alias`() = runBlocking {
        val f = imported()
        val products = f.engine.allProducts()

        assertTrue(products.all { it.kind == ProductKind.Recurring })
        val byAlias = CatalogSearch.products(products, "yaourt")
        assertEquals("yaourt-nature-essai", byAlias.first().product.productId)
        assertEquals("yaourt-nature-essai", CatalogSearch.products(products, "j'ai mangé un YAOURT nature").first().product.productId)
        // Marque + alias : la barre de céréales de « Marque générique ».
        assertEquals("barre-cereales-essai", CatalogSearch.products(products, "barre marque generique").first().product.productId)
    }

    @Test
    fun `un produit repris se consomme avec ses valeurs de la fiche`() = runBlocking {
        val f = imported()

        val result = f.apply(
            DeclareConsumptions(listOf(DeclaredItem.OfProduct(ProductUse(productId = "yaourt-nature-essai", amount = Quantity(150.0, "g")), Moment.Matin))),
        )

        val food = result.consumptionChanges.single().after.food
        assertEquals("yaourt-nature-essai", food.productId)
        assertEquals(91.5, food.nutrition.kcal, 0.0001) // 61 kcal / 100 g x 150 g
        assertEquals(7.2, food.nutrition.proteinG!!, 0.0001)
        // Une fiche déjà connue n'est pas recopiée : ni nouvelle fiche, ni nouvelle version.
        assertEquals(3, f.engine.allProducts().size)
        assertEquals(1, f.engine.product("yaourt-nature-essai")!!.version)
    }

    @Test
    fun `les plats repris se retrouvent, avec un total calcule par le code`() = runBlocking {
        val f = imported()
        val recipes = f.engine.allRecipes()

        val hit = CatalogSearch.recipes(recipes, "mon porridge du matin").single()
        assertEquals("porridge-essai", hit.recipe.recipeId)
        assertEquals(2, hit.recipe.version) // version courante
        val total = RecipeNutrition.total(hit.recipe)!!
        assertEquals(260.0, total.totalG, 0.0)
        assertEquals(60 * 3.70 + 200 * 0.46, total.nutrients.kcal!!, 0.0001)
        // Plat en pause : reconnu (signalé en pause), mais sans total tant que les poids manquent.
        val paused = CatalogSearch.recipes(recipes, "salade composee").single().recipe
        assertTrue(!paused.active)
        assertNull(RecipeNutrition.total(recipes.first { it.recipeId == "salade-essai" }))
    }
}
