package app.trackly.core

import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.ProvisionPlan
import app.trackly.core.action.Rejection
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.action.provisionChanges
import app.trackly.core.catalog.Provisions
import app.trackly.core.model.Nutrients
import app.trackly.core.suggestion.MealSuggestions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provisions : « j'ai » / « je n'ai plus », jamais une consommation ; suggestions fondées sur elles. */
class ProvisionsTest {
    private suspend fun Fixture.held() = Provisions.held(engine.allProvisions()).map { it.name }

    @Test
    fun `retiens que j ai des pommes, des bananes, une nouvelle whey - sans toucher au journal`() = runBlocking {
        val f = Fixture()
        val result = f.apply(UpdateProvisions(have = listOf(ProvisionInput("des pommes"), ProvisionInput("bananes"), ProvisionInput("nouvelle whey"))))

        assertEquals(3, result.provisionChanges.size)
        assertEquals(listOf("bananes", "des pommes", "nouvelle whey"), f.held())
        // Une provision n'est jamais une consommation.
        assertTrue(f.summary().consumptions.isEmpty())
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
        assertTrue(result.consumptionChanges.isEmpty())
    }

    @Test
    fun `je n ai plus de thon - nouvelle version supprimee, la fiche reste, et revient par j ai`() = runBlocking {
        val f = Fixture()
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("thon"), ProvisionInput("riz"))))

        f.apply(UpdateProvisions(out = listOf("Thon")))
        assertEquals(listOf("riz"), f.held())
        val thon = f.engine.allProvisions().first { it.name == "thon" }
        assertEquals(2, thon.version)

        f.apply(UpdateProvisions(have = listOf(ProvisionInput("thon"))))
        assertEquals(listOf("riz", "thon"), f.held())
        assertEquals(3, f.engine.allProvisions().first { it.name == "thon" }.version)
        assertEquals(2, f.engine.allProvisions().size) // la même fiche, trois versions
    }

    @Test
    fun `pluriel et casse ne font pas de doublon, et ce qui est deja la ne change rien`() = runBlocking {
        val f = Fixture()
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("Pommes"))))
        assertTrue(f.reject(UpdateProvisions(have = listOf(ProvisionInput("pomme")))) is Rejection.NoChange)
        assertEquals(1, f.engine.allProvisions().size)
        // Une provision et son contraire dans la même demande : refus net.
        assertTrue(f.reject(UpdateProvisions(have = listOf(ProvisionInput("thon")), out = listOf("Thon"))) is Rejection.Invalid)
    }

    @Test
    fun `retirer ce qui n etait pas dans la liste est signale par le plan`() {
        val plan = ProvisionPlan.of(emptyList(), have = listOf(ProvisionInput("pommes")), out = listOf("caviar"))
        assertEquals(listOf("caviar"), plan.notFound)
        assertEquals(1, plan.created.size)
    }

    @Test
    fun `un produit relie est garde, et ajoute plus tard a une provision connue`() = runBlocking {
        val f = Fixture()
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("whey"))))
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("whey", productId = "prd-whey"))))
        assertEquals("prd-whey", f.engine.allProvisions().single().productId)
        assertEquals(2, f.engine.allProvisions().single().version)
    }

    @Test
    fun `annuler une mise a jour des provisions rend la liste d avant`() = runBlocking {
        val f = Fixture()
        val first = f.apply(UpdateProvisions(have = listOf(ProvisionInput("thon"), ProvisionInput("riz"))))
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("oeufs")), out = listOf("thon")))
        assertEquals(listOf("oeufs", "riz"), f.held())

        val undone = f.apply(UndoLast)
        assertEquals(listOf("riz", "thon"), f.held())
        // La première mise à jour ne s'annule plus seule : « thon » a été modifié depuis.
        assertTrue(f.reject(Undo(first.id)) is Rejection.UndoConflict)
        // Annuler l'annulation rétablit.
        f.apply(Undo(undone.id))
        assertEquals(listOf("oeufs", "riz"), f.held())
    }

    @Test
    fun `thon couvre thon en boite, pas le riz`() {
        val thon = app.trackly.core.model.Provision("p", 1, app.trackly.core.model.RecordState.Active, "thon", null, java.time.Instant.EPOCH, null)
        assertTrue(Provisions.covers(thon, "Thon en boîte", null))
        assertTrue(Provisions.covers(thon, "Thons", null))
        assertFalse(Provisions.covers(thon, "Riz", null))
        assertTrue(Provisions.covers(thon.copy(name = "autre", productId = "prd-1"), "Truc", "prd-1"))
    }

    // ───── Suggestions ─────

    private val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
    private val thon = DishIngredientInput("Thon au naturel", 80.0, Nutrients(110.0, 25.0, 0.0, 1.0))
    private val riz = DishIngredientInput("Riz cuit", 250.0, Nutrients(130.0, 2.7, 28.0, 0.3))
    private val poulet = DishIngredientInput("Poulet", 150.0, Nutrients(165.0, 31.0, 0.0, 3.6))

    @Test
    fun `les suggestions viennent des plats actifs, jamais d un plat en pause, et tiennent compte des provisions`() = runBlocking {
        val f = Fixture()
        f.apply(CreateDish("Salade de thon", listOf(laitue, thon), dishId = "rcp-salade"))
        f.apply(CreateDish("Bol de riz poulet", listOf(riz, poulet), dishId = "rcp-bol"))
        f.apply(CreateDish("Poulet seul", listOf(poulet), dishId = "rcp-poulet"))
        f.apply(SetDishPaused("rcp-poulet", paused = true))
        f.apply(UpdateProvisions(have = listOf(ProvisionInput("thon"), ProvisionInput("laitue"))))
        f.add("Petit-déjeuner", 500.0, moment = app.trackly.core.model.Moment.Matin)
        val before = f.summary().totals.kcal

        val remaining = MealSuggestions.remaining(f.summary())
        val book = f.engine.dishBook()
        val candidates = MealSuggestions.candidates(book.dishes, f.engine.allProvisions(), remaining)

        assertEquals(setOf("Salade de thon", "Bol de riz poulet"), candidates.map { it.label }.toSet())
        assertFalse(candidates.any { it.label == "Poulet seul" })
        // La salade a tous ses ingrédients en provision ; le bol non.
        val salade = candidates.first { it.label == "Salade de thon" }
        assertTrue(salade.missingIngredients.isEmpty())
        assertEquals(listOf("Riz cuit", "Poulet"), candidates.first { it.label == "Bol de riz poulet" }.missingIngredients)
        assertEquals("Salade de thon", candidates.first().label)
        // Rien n'a été compté.
        assertEquals(before, f.summary().totals.kcal, 0.0)
        assertEquals(1, f.summary().consumptions.size)
    }

    @Test
    fun `les restes sont calcules par le code depuis l objectif`() = runBlocking {
        val f = Fixture()
        f.apply(app.trackly.core.action.SetGoal(app.trackly.core.model.GoalTargets(2000.0, 150.0, 200.0, 60.0), from = day("2026-10-01")))
        f.add("Repas", 700.0, protein = 50.0, carbs = 60.0, fat = 20.0)
        val remaining = MealSuggestions.remaining(f.summary())
        assertEquals(1300.0, remaining.kcal!!, 0.0)
        assertEquals(100.0, remaining.proteinG!!, 0.0)
        assertEquals(140.0, remaining.carbsG!!, 0.0)
        assertEquals(40.0, remaining.fatG!!, 0.0)
        assertTrue(remaining.macrosComplete)
        // Un plat de 1 500 kcal ne tient pas dans le reste.
        f.apply(CreateDish("Festin", listOf(DishIngredientInput("Tout", 500.0, Nutrients(300.0))), dishId = "rcp-festin"))
        val festin = MealSuggestions.candidates(f.engine.dishBook().dishes, emptyList(), remaining).single()
        assertEquals(false, festin.fitsKcal)
        assertNotNull(festin.perPortion)
    }

    @Test
    fun `une variante en pause n est pas proposee, la variante par defaut l est`() = runBlocking {
        val f = Fixture()
        f.apply(CreateDish("Salade", listOf(laitue), dishId = "rcp-s", variantName = "nature"))
        f.apply(AddDishVariant("rcp-s", "thon", listOf(laitue, thon), makeDefault = true, variantId = "rcp-st"))
        val remaining = MealSuggestions.remaining(f.summary())
        val one = MealSuggestions.candidates(f.engine.dishBook().dishes, emptyList(), remaining).single()
        assertEquals("Salade (thon)", one.label)
        assertTrue(one.isDefault)
    }
}
