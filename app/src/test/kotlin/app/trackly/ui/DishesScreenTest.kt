package app.trackly.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.backup.MutableClock
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.ProductUse
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Preference
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.dishes.DishesUiState
import app.trackly.dishes.RecurringBook
import app.trackly.testing.SampleData
import app.trackly.ui.day.DayTags
import app.trackly.ui.dishes.DishesActions
import app.trackly.ui.dishes.DishesRoute
import app.trackly.ui.dishes.DishesScreen
import app.trackly.ui.dishes.DishesTags
import app.trackly.ui.dishes.KnowledgeSections
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * « Récurrents » (ex-« Mes plats ») : la liste des plats et aliments récurrents d'abord, les plus mangés en tête ;
 * toucher une ligne ouvre sa fiche ; « Ce que Trackly sait » tout en bas ; retour système d'un niveau. Mêmes actions que l'assistant.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DishesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val sauceA = DishIngredientInput("Sauce yaourt", 30.0, Nutrients(80.0, 3.0, 6.0, 4.0))
    private val sauceB = DishIngredientInput("Sauce vinaigrette", 20.0, Nutrients(450.0, 0.0, 3.0, 48.0))
    private val riz = DishIngredientInput("Riz cuit", 250.0, Nutrients(130.0, 2.7, 28.0, 0.3))
    private val poulet = DishIngredientInput("Poulet", 150.0, Nutrients(165.0, 31.0, 0.0, 3.6))

    private var closed = 0

    private fun open(engine: TracklyEngine, fontScale: Float = 1f) {
        compose.setContent {
            AvecPolice(fontScale) {
                CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) { DishesRoute(engine, onClose = { closed++ }) }
            }
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        // Le bouton « Nouveau plat » est dans l'en-tête, hors de la colonne qui défile.
        if (tag != DishesTags.NEW) compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performTextInput(text)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun ok(outcome: ActionOutcome) = check(outcome is ActionOutcome.Applied) { "$outcome" }

    private val barre = NewProduct(
        productId = "prd-barre", name = "Barre protéinée chocolat", brand = "Nuvo", kind = ProductKind.Recurring,
        nutrients = Nutrients(400.0, 36.0, 22.0, 14.0), basis = NutritionBasis(100.0, "g"), shortcuts = listOf("barre Nuvo"),
    )
    private val yaourt = NewProduct(
        productId = "prd-yaourt", name = "Yaourt grec", kind = ProductKind.Occasional,
        nutrients = Nutrients(97.0, 9.0, 4.0, 5.0), basis = NutritionBasis(100.0, "g"),
    )

    /** Salade (deux variantes), bol, pâtes en pause, barre récurrente, yaourt ponctuel, provisions ; mangés : barre ×3, bol ×2, salade ×1. */
    private fun engineWithSalade(): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            ok(engine.execute(CreateDish("Salade", listOf(laitue, thon, sauceA), shortcuts = listOf("ma salade"), variantName = "sauce A", dishId = "rcp-salade")))
            ok(engine.execute(AddDishVariant("rcp-salade", "sauce B", listOf(laitue, thon, sauceB), variantId = "rcp-salade-b")))
            ok(engine.execute(CreateDish("Bol de riz poulet", listOf(riz, poulet), dishId = "rcp-bol")))
            ok(engine.execute(CreateDish("Pâtes au thon", listOf(DishIngredientInput("Pâtes cuites", 200.0, Nutrients(150.0, 5.0, 30.0, 1.0)), thon), dishId = "rcp-pates")))
            ok(engine.execute(SetDishPaused("rcp-pates", paused = true)))
            ok(engine.execute(UpdateProvisions(have = listOf(ProvisionInput("pommes"), ProvisionInput("bananes"), ProvisionInput("riz")))))
            ok(engine.execute(MemorizeProduct(barre)))
            ok(engine.execute(MemorizeProduct(yaourt)))
            val eaten = List(3) { DeclaredItem.OfProduct(ProductUse("prd-barre", amount = Quantity(55.0, "g")), Moment.Snack) } +
                List(2) { DeclaredItem.OfRecipe("rcp-bol", moment = Moment.Midi) } +
                DeclaredItem.OfRecipe("rcp-salade-b", moment = Moment.Soir)
            ok(engine.execute(DeclareConsumptions(eaten)))
        }
    }

    // ───── Liste ─────

    @Test
    fun `la liste - les plus manges d abord, plats et aliments ensemble, en pause a la fin, puis le reste`() {
        open(engineWithSalade())
        compose.onNodeWithText("Mes aliments").assertIsDisplayed()
        val order = listOf(DishesTags.productRow("prd-barre"), DishesTags.dish("rcp-bol"), DishesTags.dish("rcp-salade"), DishesTags.dish("rcp-pates")).map(::top)
        assertEquals(order.sorted(), order)
        compose.onNodeWithTag(DishesTags.paused("rcp-pates"), useUnmergedTree = true).assertExists()
        // Une ligne utile : variante par défaut, kcal d'une portion, nombre de variantes ; la barre, ses valeurs.
        compose.onNodeWithText("sauce A · 127 kcal la portion · 2 variantes", substring = true).assertExists()
        compose.onNodeWithText("400 kcal pour 100 g", substring = true).assertExists()
        // Le stock a sa page ; ce qui n'a pas été mangé ferme la liste, juste avant les plats en pause.
        assertFalse(exists(DishesTags.provision("pommes")))
        compose.onNodeWithTag(DishesTags.foodSection("PAS MANGÉ RÉCEMMENT")).performScrollTo().assertExists()
        // La recherche est toujours là.
        compose.onNodeWithTag(DishesTags.SEARCH).assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.KNOWLEDGE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `sans rien de recurrent, une ligne courte et le bouton nouveau plat`() {
        open(SampleData.emptyEngine())
        compose.onNodeWithTag(DishesTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.NEW).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-vide.png")
    }

    // ───── Fiche d'un plat ─────

    @Test
    fun `toucher un plat ouvre sa fiche - variantes lisibles avec leurs ingredients`() {
        open(engineWithSalade())
        tap(DishesTags.dish("rcp-salade"))
        compose.onNodeWithTag(DishesTags.DISH_PAGE).assertIsDisplayed()
        compose.onNodeWithText("Salade").assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.defaultTag("rcp-salade")).assertExists()
        compose.onNodeWithText("Laitue\u00A0100\u00A0g · Thon au naturel\u00A080\u00A0g · Sauce vinaigrette\u00A020\u00A0g").assertExists()
        compose.onNodeWithText("Choisir par défaut").assertExists()
        compose.onNodeWithText("Raccourcis : ma salade").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-fiche-plat.png")
    }

    @Test
    fun `fiche - renommer une variante, et nommer la version de base`() {
        val engine = SampleData.emptyEngine()
        runBlocking {
            ok(engine.execute(CreateDish("Salade", listOf(laitue, thon), dishId = "rcp-salade")))
            ok(engine.execute(AddDishVariant("rcp-salade", "sauce B", listOf(laitue, thon, sauceB), variantId = "rcp-salade-b")))
        }
        open(engine)
        tap(DishesTags.dish("rcp-salade"))

        tap(DishesTags.rename("rcp-salade-b"))
        compose.onNodeWithTag(DishesTags.renameField("rcp-salade-b")).performTextClearance()
        type(DishesTags.renameField("rcp-salade-b"), "vinaigrette")
        tap(DishesTags.renameSave("rcp-salade-b"))
        assertEquals("vinaigrette", runBlocking { Dishes.group(engine.allRecipes()) }.single().variants.single().variantName)

        // La version de base n'a pas de nom : « Nommer ».
        compose.onNodeWithText("Version de base").assertExists()
        compose.onNodeWithText("Nommer").assertExists()
        tap(DishesTags.rename("rcp-salade"))
        type(DishesTags.renameField("rcp-salade"), "nature")
        compose.onNodeWithTag(DishesTags.renameField("rcp-salade")).performImeAction()
        compose.waitForIdle()
        assertEquals("nature", runBlocking { Dishes.group(engine.allRecipes()) }.single().base.variantName)
        // Annulable comme toute action du moteur.
        compose.onNodeWithTag(DishesTags.UNDO).assertExists()
    }

    @Test
    fun `fiche - ajouter une variante puis la choisir par defaut`() {
        val engine = SampleData.emptyEngine()
        runBlocking { ok(engine.execute(CreateDish("Salade", listOf(laitue, thon, sauceA), variantName = "sauce A", dishId = "rcp-salade"))) }
        open(engine)
        tap(DishesTags.dish("rcp-salade"))

        tap(DishesTags.variant("rcp-salade"))
        // Le formulaire part d'une copie des ingrédients du plat.
        compose.onNodeWithTag(DishesTags.ingName(0)).assertExists()
        type(DishesTags.VERSION, "sauce B")
        tap(DishesTags.SAVE)

        val dish = runBlocking { Dishes.group(engine.allRecipes()) }.single()
        val variantId = dish.variants.single().recipeId
        // Retour sur la fiche : la version de base est par défaut, la variante propose de le devenir.
        compose.onNodeWithTag(DishesTags.DISH_PAGE).assertExists()
        compose.onNodeWithTag(DishesTags.defaultTag("rcp-salade")).assertExists()
        tap(DishesTags.makeDefault(variantId))
        assertEquals(variantId, runBlocking { Dishes.group(engine.allRecipes()) }.single().defaultVersion.recipeId)
        compose.onNodeWithTag(DishesTags.defaultTag(variantId)).assertExists()
        assertFalse(exists(DishesTags.defaultTag("rcp-salade")))
    }

    @Test
    fun `fiche - mettre le plat en pause, une variante en pause, le message dit ce qui s est passe`() {
        val engine = engineWithSalade()
        open(engine)
        tap(DishesTags.dish("rcp-salade"))

        tap(DishesTags.pause("rcp-salade"))
        compose.onNodeWithText("Salade en pause (toutes les variantes)").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { Dishes.group(engine.allRecipes()) }.first { it.name == "Salade" }.isPaused)
        tap(DishesTags.UNDO)
        assertFalse(runBlocking { Dishes.group(engine.allRecipes()) }.first { it.name == "Salade" }.isPaused)

        tap(DishesTags.pause("rcp-salade-b"))
        compose.onNodeWithText("Variante « sauce B » de Salade en pause").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `supprimer le plat depuis sa fiche ramene a la liste`() {
        val engine = engineWithSalade()
        open(engine)
        tap(DishesTags.dish("rcp-bol"))
        tap(DishesTags.edit("rcp-bol"))
        tap(DishesTags.DELETE)
        assertTrue(runBlocking { Dishes.group(engine.allRecipes()) }.none { it.base.recipeId == "rcp-bol" })
        compose.onNodeWithTag(DishesTags.LIST).assertExists()
    }

    // ───── Formulaire ─────

    @Test
    fun `creer un plat - nom, ingredients, apercu calcule, action du moteur`() {
        val engine = SampleData.emptyEngine()
        open(engine)

        tap(DishesTags.NEW)
        type(DishesTags.NAME, "Salade")
        type(DishesTags.ingName(0), "Laitue")
        type(DishesTags.ingGrams(0), "100")
        type(DishesTags.ingKcal(0), "15")
        tap(DishesTags.ADD_INGREDIENT)
        type(DishesTags.ingName(1), "Thon au naturel")
        type(DishesTags.ingGrams(1), "80")
        type(DishesTags.ingKcal(1), "110")
        type(DishesTags.ingProtein(1), "25")
        // L'aperçu est calculé par le code d'après les lignes (15 + 88 = 103 kcal) ; protéines inconnues pour la laitue : pas de total.
        compose.onNodeWithTag(DishesTags.PREVIEW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Une portion : 103 kcal").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-formulaire.png")

        tap(DishesTags.SAVE)

        val dish = runBlocking { Dishes.group(engine.allRecipes()) }.single()
        assertEquals("Salade", dish.name)
        assertEquals(listOf("Laitue", "Thon au naturel"), dish.base.ingredients.map { it.label })
        // La liste montre le nouveau plat et un message annulable.
        compose.onNodeWithTag(DishesTags.dish(dish.base.recipeId)).assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.NOTICE).assertIsDisplayed()
        tap(DishesTags.UNDO)
        assertTrue(runBlocking { Dishes.group(engine.allRecipes()) }.isEmpty())
    }

    @Test
    fun `un formulaire incomplet explique ce qu il faut corriger`() {
        val engine = SampleData.emptyEngine()
        open(engine)
        tap(DishesTags.NEW)
        tap(DishesTags.SAVE)
        compose.onNodeWithTag(DishesTags.ERROR).assertIsDisplayed()
        assertTrue(runBlocking { engine.allRecipes() }.isEmpty())
    }

    @Test
    fun `un produit connu devient un ingredient aux valeurs calculees`() {
        val engine = SampleData.emptyEngine()
        runBlocking {
            ok(
                engine.execute(
                    MemorizeProduct(
                        NewProduct(
                            productId = "prd-riz", name = "Riz basmati cuit", nutrients = Nutrients(130.0, 2.7, 28.0, 0.3),
                            basis = NutritionBasis(100.0, "g"), kind = ProductKind.Recurring,
                        ),
                    ),
                ),
            )
        }
        open(engine)
        tap(DishesTags.NEW)
        type(DishesTags.NAME, "Bol de riz")
        tap(DishesTags.PICK_PRODUCT)
        tap(DishesTags.product("prd-riz"))
        compose.onNodeWithText("Valeurs pour 100 g (reprises du produit connu)").assertExists()
        tap(DishesTags.SAVE) // grammes laissés à 100 par défaut
        val recipe = runBlocking { engine.allRecipes() }.single()
        assertEquals("prd-riz", recipe.ingredients.single().productId)
        assertEquals(130.0, recipe.ingredients.single().per100g.kcal!!, 0.0)
    }

    // ───── Retour système ─────

    @Test
    fun `retour systeme - formulaire, puis fiche, puis liste, puis fermeture`() {
        open(engineWithSalade())
        tap(DishesTags.dish("rcp-salade"))
        tap(DishesTags.edit("rcp-salade"))
        compose.onNodeWithTag(DishesTags.SAVE).assertExists()

        back()
        compose.onNodeWithTag(DishesTags.DISH_PAGE).assertExists()
        back()
        compose.onNodeWithTag(DishesTags.LIST).assertExists()
        tap(DishesTags.KNOWLEDGE)
        compose.onNodeWithTag(DishesTags.KNOWLEDGE_PAGE).assertExists()
        back()
        compose.onNodeWithTag(DishesTags.LIST).assertExists()
        assertEquals(0, closed)
        back()
        assertEquals(1, closed)
    }

    // ───── Fiche d'un produit ─────

    private fun openProduct(engine: TracklyEngine) {
        open(engine)
        tap(DishesTags.productRow("prd-barre"))
    }

    @Test
    fun `produit - renommer garde l ancien nom comme raccourci`() {
        val engine = engineWithSalade()
        openProduct(engine)
        compose.onNodeWithTag(DishesTags.PRODUCT_PAGE).assertIsDisplayed()
        // Les kcal et les macros connues, en une ligne.
        compose.onNodeWithTag(DishesTags.PRODUCT_VALUES).assertTextEquals("400 kcal pour 100 g · P\u00A036\u00A0g · G\u00A022\u00A0g · L\u00A014\u00A0g")
        compose.onNodeWithText("barre Nuvo").assertExists()
        tap(DishesTags.productRename("prd-barre"))
        compose.onNodeWithTag(DishesTags.productRenameField("prd-barre")).performTextClearance()
        type(DishesTags.productRenameField("prd-barre"), "Barre Nuvo chocolat")
        tap(DishesTags.productRenameSave("prd-barre"))

        val product = runBlocking { engine.product("prd-barre")!! }
        assertEquals("Barre Nuvo chocolat", product.name)
        assertTrue(product.aliases.containsAll(listOf("barre Nuvo", "Barre protéinée chocolat")))
        compose.onNodeWithTag(DishesTags.NOTICE).assertIsDisplayed()
    }

    @Test
    fun `produit - ajouter un raccourci, le retirer, et un raccourci deja pris est refuse`() {
        val engine = engineWithSalade()
        runBlocking { ok(engine.execute(MemorizeProduct(yaourt.copy(productId = "prd-sandwich", name = "Sandwich", kind = ProductKind.Recurring, shortcuts = listOf("sando"))))) }
        openProduct(engine)

        tap(DishesTags.productShortcut("prd-barre"))
        type(DishesTags.productShortcutField("prd-barre"), "Nuvo")
        tap(DishesTags.productShortcutSave("prd-barre"))
        assertEquals(listOf("barre Nuvo", "Nuvo"), runBlocking { engine.product("prd-barre")!!.aliases })

        tap(DishesTags.productShortcutRemove("prd-barre", "barre Nuvo"))
        assertEquals(listOf("Nuvo"), runBlocking { engine.product("prd-barre")!!.aliases })

        tap(DishesTags.productShortcut("prd-barre"))
        type(DishesTags.productShortcutField("prd-barre"), "sando")
        tap(DishesTags.productShortcutSave("prd-barre"))
        assertEquals(listOf("Nuvo"), runBlocking { engine.product("prd-barre")!!.aliases })
        compose.onNodeWithText("désigne déjà", substring = true).assertExists()
        compose.onNodeWithTag(DishesTags.productShortcutField("prd-barre")).assertExists()
    }

    // ───── Ce que Trackly sait ─────

    private fun openKnowledge(engine: TracklyEngine) {
        open(engine)
        tap(DishesTags.KNOWLEDGE)
    }

    @Test
    fun `mes aliments - en tete le stock et l assistant, puis tout ce qui est connu`() {
        val engine = engineWithSalade()
        open(engine)
        compose.onNodeWithTag(DishesTags.STOCK).assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.KNOWLEDGE).assertIsDisplayed()
        // Le produit ponctuel est listé avec les autres : rien à rendre récurrent pour le voir.
        compose.onNodeWithTag(DishesTags.productRow("prd-yaourt")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DishesTags.productRow("prd-barre")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `mes aliments - nommer un produit ponctuel demande le nom, ne le choisit jamais`() {
        val engine = engineWithSalade()
        open(engine)
        compose.onNodeWithTag(DishesTags.productRow("prd-yaourt")).performScrollTo()
        tap(DishesTags.productRow("prd-yaourt"))
        tap(DishesTags.productRename("prd-yaourt"))
        compose.onNodeWithTag(DishesTags.productRenameField("prd-yaourt")).performTextClearance()
        type(DishesTags.productRenameField("prd-yaourt"), "Yaourt du matin")
        tap(DishesTags.productRenameSave("prd-yaourt"))
        val product = runBlocking { engine.product("prd-yaourt")!! }
        assertEquals(ProductKind.Recurring, product.kind)
        assertEquals("Yaourt du matin", product.name)
        assertTrue(product.aliases.contains("Yaourt grec"))
    }

    @Test
    fun `mon stock - j ai, plus la, remettre sans rien reecrire, touche du clavier, sans rien compter`() {
        val engine = engineWithSalade()
        open(engine)
        tap(DishesTags.STOCK)

        type(DishesTags.PROVISION_FIELD, "thon")
        compose.onNodeWithTag(DishesTags.PROVISION_FIELD).assert(hasImeAction(ImeAction.Done))
        compose.onNodeWithTag(DishesTags.PROVISION_FIELD).performImeAction()
        compose.waitForIdle()
        assertTrue(Provisions.held(runBlocking { engine.allProvisions() }).any { it.name == "thon" })
        compose.onNodeWithTag(DishesTags.provision("thon")).assertExists()

        type(DishesTags.PROVISION_FIELD, "kiwis")
        tap(DishesTags.PROVISION_ADD)
        assertTrue(Provisions.held(runBlocking { engine.allProvisions() }).any { it.name == "kiwis" })

        val before = runBlocking { engine.daySummary(engine.today()).consumptions.size }
        tap(DishesTags.provisionOut("pommes"))
        assertFalse(Provisions.held(runBlocking { engine.allProvisions() }).any { it.name == "pommes" })
        assertEquals(before, runBlocking { engine.daySummary(engine.today()).consumptions.size })
    }

    // ───── Captures : beaucoup d'éléments, police 200 % ─────

    private fun preference(id: String, kind: String, trigger: String, rule: String) =
        Preference(id, 1, RecordState.Active, kind, trigger, rule, true, Instant.EPOCH, null)

    private val preferences = listOf(
        preference("p1", "cooking_default", "riz", "Le riz est pesé cuit sauf mention contraire."),
        preference("p2", "portion_default", "yaourt", "Un yaourt fait 125 g."),
        preference("p3", "portion_default", "œuf", "Un œuf moyen fait 50 g sans coquille."),
        preference("p4", "interaction_preference", "réponses", "Réponses courtes, sans détail des calculs."),
        preference("m1", RecurringBook.TERM_CORRECTION, "bol thé", "bol thon"),
        preference("m2", RecurringBook.TERM_CORRECTION, "skier", "skyr"),
        preference("m3", RecurringBook.TERM_CORRECTION, "nouvo", "Nuvo"),
    )

    private fun crowded(): TracklyEngine = engineWithSalade().also { engine ->
        runBlocking {
            val oeuf = DishIngredientInput("Œufs", 150.0, Nutrients(145.0, 12.6, 0.7, 9.9))
            val avoine = DishIngredientInput("Flocons d’avoine", 60.0, Nutrients(370.0, 13.0, 59.0, 7.0))
            val lait = DishIngredientInput("Lait demi-écrémé", 200.0, Nutrients(46.0, 3.2, 4.8, 1.6))
            ok(engine.execute(CreateDish("Omelette 3 œufs", listOf(oeuf), dishId = "rcp-omelette")))
            ok(engine.execute(CreateDish("Porridge du matin", listOf(avoine, lait), dishId = "rcp-porridge")))
            ok(engine.execute(CreateDish("Wrap poulet crudités", listOf(poulet, laitue), dishId = "rcp-wrap")))
            fun portion(id: String, name: String, label: String, grams: Double, n: Nutrients, per100: Nutrients) = NewProduct(
                productId = id, name = name, kind = ProductKind.Recurring, nutrients = per100, basis = NutritionBasis(100.0, "g"),
                portion = ProductPortion(label, Quantity(grams, "g"), n),
            )
            ok(engine.execute(MemorizeProduct(portion("prd-skyr", "Skyr nature", "1 pot", 150.0, Nutrients(95.0, 16.5, 6.0, 0.3), Nutrients(63.0, 11.0, 4.0, 0.2)))))
            ok(engine.execute(MemorizeProduct(portion("prd-whey", "Whey vanille", "1 dose", 30.0, Nutrients(114.0, 23.4, 2.1, 1.5), Nutrients(380.0, 78.0, 7.0, 5.0)))))
            ok(engine.execute(MemorizeProduct(portion("prd-pain", "Pain complet", "1 tranche", 40.0, Nutrients(98.0, 3.6, 17.0, 1.2), Nutrients(245.0, 9.0, 42.0, 3.0)))))
            ok(engine.execute(MemorizeProduct(barre.copy(productId = "prd-pomme", name = "Pomme", brand = null, nutrients = Nutrients(52.0, 0.3, 14.0, 0.2), shortcuts = emptyList()))))
            ok(engine.execute(DeclareConsumptions(List(2) { DeclaredItem.OfProduct(ProductUse("prd-skyr", amount = Quantity(1.0, "portion")), Moment.Matin) })))
        }
    }

    @Test
    fun `capture - beaucoup de recurrents, recherche simple`() {
        open(crowded())
        compose.onNodeWithTag(DishesTags.SEARCH).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-liste.png")
        type(DishesTags.SEARCH, "poulet")
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.dish("rcp-bol")).assertExists()
        compose.onNodeWithTag(DishesTags.dish("rcp-wrap")).assertExists()
        assertFalse(exists(DishesTags.dish("rcp-salade")))
    }

    @Test
    fun `capture - un aliment deja mange a 200 pour cent, le nom garde la largeur`() {
        open(crowded(), fontScale = 2f)
        type(DishesTags.SEARCH, "skyr")
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.productRow("prd-skyr")).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/aliments-mange-police-200.png")
    }

    @Test
    fun `capture - liste a 200 pour cent de police`() {
        open(crowded(), fontScale = 2f)
        compose.onNodeWithTag(DishesTags.LIST).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-liste-police-200.png")
    }

    @Test
    fun `capture - fiche d un plat a 200 pour cent de police`() {
        open(engineWithSalade(), fontScale = 2f)
        tap(DishesTags.dish("rcp-salade"))
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-fiche-plat-police-200.png")
    }

    /** « Ce que Trackly sait » avec préférences et mots corrigés : lu depuis l'état (les préférences viennent de la reprise). */
    @Composable
    private fun KnowledgeWithPreferences(engine: TracklyEngine) {
        val state = runBlocking {
            val book = engine.dishBook()
            DishesUiState(dishes = book.dishes, provisions = book.provisions, catalog = engine.allProducts(), preferences = preferences)
        }
        CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) { DishesScreen(state, DishesActions(), onClose = {}) }
    }

    private fun openAllSections() {
        tap(DishesTags.KNOWLEDGE)
        listOf(KnowledgeSections.TERMS, KnowledgeSections.PREFERENCES).forEach { tap(DishesTags.section(it)) }
    }

    @Test
    fun `ce que Trackly sait - preferences en lecture par type, mots corriges a part`() {
        val engine = engineWithSalade()
        compose.setContent { KnowledgeWithPreferences(engine) }
        compose.waitForIdle()
        tap(DishesTags.KNOWLEDGE)
        compose.onNodeWithText("4").assertExists() // préférences hors mots corrigés
        tap(DishesTags.section(KnowledgeSections.PREFERENCES))
        compose.onNodeWithText("PORTION").assertExists()
        compose.onNodeWithText("Un yaourt fait 125 g.").assertExists()
        assertFalse(exists(DishesTags.preference("m1")))
        tap(DishesTags.section(KnowledgeSections.TERMS))
        compose.onNodeWithTag(DishesTags.preference("m1")).assertExists()
        compose.onNodeWithText("bol thé  →  bol thon").assertExists()
        // Lecture seule : aucune action sur une préférence.
        assertFalse(exists(DishesTags.PROVISION_FIELD))
    }

    @Test
    fun `capture - ce que Trackly sait, preferences et mots corriges ouverts`() {
        val engine = engineWithSalade()
        compose.setContent { KnowledgeWithPreferences(engine) }
        compose.waitForIdle()
        openAllSections()
        compose.onRoot().captureRoboImage("src/test/screenshots/ce-que-trackly-sait.png")
    }

    @Test
    fun `capture - ce que Trackly sait a 200 pour cent de police`() {
        val engine = engineWithSalade()
        compose.setContent { AvecPolice(2f) { KnowledgeWithPreferences(engine) } }
        compose.waitForIdle()
        tap(DishesTags.KNOWLEDGE)
        tap(DishesTags.section(KnowledgeSections.TERMS))
        tap(DishesTags.section(KnowledgeSections.PREFERENCES))
        compose.onRoot().captureRoboImage("src/test/screenshots/ce-que-trackly-sait-police-200.png")
    }

    @Test
    fun `depuis l ecran du jour, le bol ouvre Recurrents`() {
        val engine = SampleData.engine()
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag(DayTags.DISHES).assertIsDisplayed()
        compose.onNodeWithContentDescription("Mes aliments").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.SCREEN).assertIsDisplayed()
    }

    // ───── Liste courte, noms propres (historique importé) ─────

    /**
     * Catalogue repris de l'ancien suivi (noms bruts inventés) mémorisé il y a 40 jours : six produits mangés deux
     * fois, la sauce une fois, le thé jamais. Le skyr, mémorisé aujourd'hui, est « Nouveau » sans avoir été mangé.
     */
    private fun historique(): TracklyEngine {
        val clock = MutableClock(Instant.parse("2026-09-25T10:00:00Z"))
        return SampleData.emptyEngine(clock).also { engine ->
            runBlocking {
                fun brut(id: String, name: String, brand: String? = null, portion: ProductPortion? = null) = NewProduct(
                    productId = id, name = name, brand = brand, kind = ProductKind.Recurring,
                    nutrients = Nutrients(380.0, 12.0, 60.0, 9.0), basis = NutritionBasis(100.0, "g"), portion = portion,
                )
                listOf(
                    brut("prd-galette", "Déjeuner — Galette sarrasin jambon emmental (EAN 7610000000017)", "Ferme du Val"),
                    brut("prd-drink", "Prix Doux Drink Protéiné Vanille", "Prix Doux"),
                    brut("prd-flocons", "Petit-déjeuner — Flocons d’avoine croustillants au miel — nom partiellement masqué", "Nordique"),
                    brut("prd-barre-fibra", "Snack — Barre Fibra noisette, identité partielle", "Fibra", ProductPortion("1 piece", Quantity(35.0, "g"), Nutrients(150.0))),
                    brut("prd-pain-crq", "Pain croustillant — 388 kcal/100 g (seigle complet présumé)", "Prix Doux / Nordique"),
                    brut("prd-salade-cmp", "Salade composée — 245 g", "Prix Doux"),
                    brut("prd-sauce-soja", "Sauce soja salée (EAN 4000000000001)", "Kibo"),
                    brut("prd-the", "Boisson — Thé glacé pêche — identifié par correspondance secondaire FDDB", "Kibo"),
                ).forEach { ok(engine.execute(MemorizeProduct(it))) }
                clock.instant = Instant.parse("2026-11-04T10:00:00Z")
                ok(engine.execute(MemorizeProduct(brut("prd-skyr-citron", "Skyr citron — 150 g"))))
                val twice = listOf("prd-galette", "prd-drink", "prd-flocons", "prd-barre-fibra", "prd-pain-crq", "prd-salade-cmp")
                val eaten = twice.flatMap { id -> List(2) { DeclaredItem.OfProduct(ProductUse(id, amount = Quantity(50.0, "g")), Moment.Midi) } } +
                    DeclaredItem.OfProduct(ProductUse("prd-sauce-soja", amount = Quantity(15.0, "g")), Moment.Soir)
                ok(engine.execute(DeclareConsumptions(eaten)))
            }
        }
    }

    @Test
    fun `capture - liste aux noms propres, sans traces de l ancien suivi`() {
        open(historique())
        listOf(
            "Galette sarrasin jambon emmental (Ferme du Val)", "Prix Doux Drink Protéiné Vanille",
            "Flocons d’avoine croustillants au miel (Nordique)", "Barre Fibra noisette", "Pain croustillant (Prix Doux / Nordique)",
            "Salade composée — 245 g (Prix Doux)", "Skyr citron — 150 g",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("150 kcal par 1 pièce", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/recurrents-noms-propres.png")
    }

    @Test
    fun `la recherche trouve un produit, et sa fiche montre le vrai nom`() {
        open(historique())
        type(DishesTags.SEARCH, "soja")
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.productRow("prd-sauce-soja")).assertExists()
        compose.onNodeWithTag(DishesTags.SEARCH).performTextClearance()
        compose.waitForIdle()

        compose.onNodeWithText("Thé glacé pêche (Kibo)").performScrollTo().assertExists()
        tap(DishesTags.productRow("prd-the"))
        compose.onNodeWithTag(DishesTags.PRODUCT_PAGE).assertIsDisplayed()
        compose.onNodeWithText("Nom enregistré : Boisson — Thé glacé pêche — identifié par correspondance secondaire FDDB").assertExists()
        back()
        compose.onNodeWithTag(DishesTags.LIST).assertExists()
    }

    @Test
    fun `le tri - trois ordres, chaque ligne dit combien de fois et quand`() {
        open(historique())
        compose.onNodeWithText("SOUVENT", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PARFOIS · 6").assertExists()
        compose.onNodeWithText("UNE FOIS · 1").performScrollTo().assertExists()
        compose.onNodeWithText("1\u00A0fois", useUnmergedTree = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/aliments-les-plus-manges.png")

        tap(DishesTags.sort("Recent"))
        compose.onNodeWithText("CES 7 DERNIERS JOURS · 7").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/aliments-recents.png")

        tap(DishesTags.sort("Alphabetical"))
        compose.onNodeWithText("PARFOIS", substring = true).assertDoesNotExist()
        val order = listOf("prd-barre-fibra", "prd-flocons", "prd-galette", "prd-pain-crq").map { top(DishesTags.productRow(it)) }
        assertEquals(order.sorted(), order)
        compose.onRoot().captureRoboImage("src/test/screenshots/aliments-a-z.png")
    }

    @Test
    fun `mettre un plat en pause depuis sa fiche garde la position de la liste au retour`() {
        // Police à 200 % : la liste est assez haute pour défiler.
        open(crowded(), fontScale = 2f)
        tap(DishesTags.sort("Alphabetical"))
        compose.onNodeWithTag(DishesTags.dish("rcp-wrap")).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.SEARCH).assertIsNotDisplayed() // la liste a bien défilé
        tap(DishesTags.dish("rcp-wrap"))
        tap(DishesTags.pause("rcp-wrap"))
        back()
        compose.waitForIdle()
        // Le message de la pause s'affiche en haut de la liste et décale les lignes : on vérifie que la liste n'est pas remontée.
        compose.onNodeWithText("Wrap poulet crudités en pause", substring = true).assertExists()
        compose.onNodeWithTag(DishesTags.SEARCH).assertIsNotDisplayed()
    }

    @Test
    fun `aller voir une fiche ne perd ni le tri ni la recherche ni la position`() {
        // Police à 200 % : la liste est assez haute pour défiler.
        open(historique(), fontScale = 2f)
        tap(DishesTags.sort("Alphabetical"))
        compose.onNodeWithTag(DishesTags.productRow("prd-the")).performScrollTo()
        compose.waitForIdle()
        val before = top(DishesTags.productRow("prd-the"))
        compose.onNodeWithTag(DishesTags.SEARCH).assertIsNotDisplayed() // la liste a bien défilé
        tap(DishesTags.productRow("prd-the"))
        compose.onNodeWithTag(DishesTags.PRODUCT_PAGE).assertIsDisplayed()
        back()
        compose.waitForIdle()
        assertEquals(before, top(DishesTags.productRow("prd-the")))
        compose.onNodeWithText("PARFOIS", substring = true).assertDoesNotExist()

        compose.onNodeWithTag(DishesTags.SEARCH).performScrollTo()
        type(DishesTags.SEARCH, "soja")
        compose.waitForIdle()
        tap(DishesTags.productRow("prd-sauce-soja"))
        back()
        compose.waitForIdle()
        compose.onNodeWithTag(DishesTags.productRow("prd-sauce-soja")).assertExists()
        assertFalse(exists(DishesTags.productRow("prd-the")))
    }
}
