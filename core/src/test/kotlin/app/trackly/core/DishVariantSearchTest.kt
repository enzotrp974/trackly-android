package app.trackly.core

import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dishes
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Recipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Des phrases de dictée réalistes trouvent la bonne variante d'un plat, quel que soit l'ordre, avec ou sans
 * mots de liaison (« j'ai mangé la salade avec la sauce César »), même quand un autre plat partage un mot.
 */
class DishVariantSearchTest {
    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val cesar = DishIngredientInput("Sauce César", 30.0, Nutrients(450.0, 2.0, 3.0, 48.0))
    private val yaourt = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
    private val balsamique = DishIngredientInput("Vinaigrette balsamique", 20.0, Nutrients(300.0, 0.0, 10.0, 28.0))
    private val soja = DishIngredientInput("Sauce soja", 15.0, Nutrients(60.0, 8.0, 5.0, 0.0))
    private val pates = DishIngredientInput("Pâtes cuites", 200.0, Nutrients(150.0, 5.0, 30.0, 1.0))
    private val riz = DishIngredientInput("Riz cuit", 200.0, Nutrients(130.0, 2.7, 28.0, 0.3))

    private val recipes: List<Recipe> = runBlocking {
        val f = Fixture()
        // Salade composée : version de base sans nom, trois variantes, raccourci « ma salade ».
        f.apply(CreateDish("Salade composée", listOf(laitue, thon), shortcuts = listOf("ma salade"), dishId = "salade"))
        f.apply(AddDishVariant("salade", "sauce César", listOf(laitue, thon, cesar), variantId = "salade-cesar"))
        f.apply(AddDishVariant("salade", "sauce yaourt", listOf(laitue, thon, yaourt), variantId = "salade-yaourt"))
        f.apply(AddDishVariant("salade", "vinaigrette balsamique", listOf(laitue, thon, balsamique), variantId = "salade-balsamique"))
        // Deux plats qui partagent des mots : « salade » et « sauce yaourt ».
        f.apply(CreateDish("Salade de pâtes", listOf(pates, thon), dishId = "salade-pates"))
        f.apply(CreateDish("Bowl thon", listOf(riz, thon), variantName = "sauce soja", dishId = "bowl"))
        f.apply(AddDishVariant("bowl", "sauce yaourt", listOf(riz, thon, yaourt), variantId = "bowl-yaourt"))
        f.engine.allRecipes()
    }

    private fun first(query: String): String {
        val hits = CatalogSearch.recipes(recipes, query)
        return hits.firstOrNull()?.recipe?.let { Dishes.label(it) } ?: "(rien)"
    }

    @Test
    fun `j ai mange la salade avec la sauce Cesar - la variante Cesar, malgre les mots de liaison`() {
        assertEquals("Salade composée (sauce César)", first("j'ai mangé la salade avec la sauce César"))
        assertEquals("Salade composée (sauce César)", first("J’ai mangé la salade avec la sauce cesar ce midi"))
    }

    @Test
    fun `salade sauce Cesar, ma salade Cesar, salade Cesar - la variante Cesar`() {
        assertEquals("Salade composée (sauce César)", first("salade sauce César"))
        assertEquals("Salade composée (sauce César)", first("ma salade César"))
        assertEquals("Salade composée (sauce César)", first("salade césar"))
    }

    @Test
    fun `l ordre ne compte pas`() {
        assertEquals("Salade composée (sauce César)", first("la sauce César avec ma salade"))
        assertEquals("Salade composée (vinaigrette balsamique)", first("la vinaigrette balsamique avec la salade"))
        assertEquals("Salade composée (sauce César)", first("César salade"))
    }

    @Test
    fun `une variante au nom partage avec un autre plat - le plat nomme decide`() {
        assertEquals("Salade composée (sauce yaourt)", first("j'ai mangé la salade avec la sauce yaourt"))
        assertEquals("Salade composée (sauce yaourt)", first("salade composée sauce yaourt"))
        assertEquals("Bowl thon (sauce yaourt)", first("le bowl thon avec la sauce yaourt"))
        assertEquals("Bowl thon (sauce yaourt)", first("sauce yaourt bowl"))
    }

    @Test
    fun `le plat seul donne le plat lui-meme, pas une variante ni un autre plat`() {
        assertEquals("Salade composée", first("j'ai mangé ma salade"))
        assertEquals("Salade composée", first("la salade composée"))
        assertEquals("Salade de pâtes", first("une salade de pâtes"))
    }

    @Test
    fun `une phrase courte ne designe pas un produit voisin`() = runBlocking {
        val f = Fixture()
        f.apply(MemorizeProduct(NewProduct("Café crème", brand = "Laiterie", nutrients = Nutrients(50.0, 1.0, 5.0, 2.0), basis = NutritionBasis(100.0, "g"))))
        val hits = CatalogSearch.products(f.engine.allProducts(), "j'ai bu un café", minScore = 50)
        assertEquals(emptyList<String>(), hits.map { it.product.name })
    }
}
