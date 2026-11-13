package app.trackly.core

import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.action.UndoLast
import app.trackly.core.model.Confidence
import app.trackly.core.model.Food
import app.trackly.core.model.FoodPatch
import app.trackly.core.model.FoodSource
import app.trackly.core.model.KcalRange
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles des corrections par phrase : ajustement qui conserve, remplacement, dates exprimées. */
class CorrectionRulesTest {
    private val f = Fixture()

    private val riche = Food(
        label = "Poulet grillé",
        nutrition = Nutrition(220.0, 40.0, 0.0, 6.0, 0.0),
        quantity = Quantity(150.0, "g"),
        assumptions = listOf("poulet supposé cuit, pesé cuit"),
        kcalRange = KcalRange(200.0, 240.0),
        productId = "produit-poulet",
        recipeId = "plat-poulet",
        source = FoodSource("label", "Étiquette du fabricant"),
        confidence = Confidence.Medium,
    )

    private suspend fun addRiche() = f.apply(AddConsumptions(NewConsumption(riche, Moment.Midi)))
        .consumptionChanges.single().after.entryId

    @Test
    fun `ajuster une quantite conserve hypotheses, produit lie, plat lie, source et confiance`() = runTest {
        val id = addRiche()

        f.apply(CorrectConsumption(id, patch = FoodPatch(quantity = 200.0, nutrition = Nutrition(293.0, 53.0, 0.0, 8.0, 0.0))))

        val food = f.engine.history(id).last().food
        assertEquals(Quantity(200.0, "g"), food.quantity)
        assertEquals(293.0, food.nutrition.kcal, 0.0)
        assertEquals(listOf("poulet supposé cuit, pesé cuit"), food.assumptions)
        assertEquals("produit-poulet", food.productId)
        assertEquals("plat-poulet", food.recipeId)
        assertEquals("Étiquette du fabricant", food.source!!.description)
        assertEquals(Confidence.Medium, food.confidence)
        assertEquals("Poulet grillé", food.label)
        assertEquals(293.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `la fourchette ne survit pas a un changement de kcal, mais survit a un changement de nom`() = runTest {
        val id = addRiche()

        f.apply(CorrectConsumption(id, patch = FoodPatch(label = "Poulet rôti")))
        assertEquals(KcalRange(200.0, 240.0), f.engine.history(id).last().food.kcalRange)

        f.apply(CorrectConsumption(id, patch = FoodPatch(quantity = 100.0, nutrition = Nutrition(147.0, 27.0, 0.0, 4.0))))
        assertNull(f.engine.history(id).last().food.kcalRange)
    }

    @Test
    fun `changer la quantite sans les valeurs recalculees est refuse, rien n est ecrit`() = runTest {
        val id = addRiche()

        val rejection = f.reject(CorrectConsumption(id, patch = FoodPatch(quantity = 200.0)))

        assertTrue(rejection is Rejection.Invalid)
        assertEquals(1, f.engine.history(id).size)
    }

    @Test
    fun `donner une quantite a un aliment qui n en avait pas peut garder ses kcal`() = runTest {
        val id = f.add("Soupe", 180.0)

        f.apply(CorrectConsumption(id, patch = FoodPatch(quantity = 300.0, unit = "ml")))

        assertEquals(Quantity(300.0, "ml"), f.engine.history(id).last().food.quantity)
        assertEquals(180.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `remplacer l aliment ne garde rien de l ancien produit`() = runTest {
        val id = addRiche()

        f.apply(CorrectConsumption(id, food = Food("Tofu fumé", Nutrition(190.0, 20.0, 4.0, 11.0))))

        val food = f.engine.history(id).last().food
        assertEquals("Tofu fumé", food.label)
        assertNull(food.productId)
        assertNull(food.source)
        assertTrue(food.assumptions.isEmpty())
    }

    @Test
    fun `remplacer et ajuster en meme temps est impossible`() {
        assertThrows(IllegalArgumentException::class.java) {
            CorrectConsumption(app.trackly.core.model.EntryId("x"), food = food("A", 1.0), patch = FoodPatch(label = "B"))
        }
    }

    @Test
    fun `une correction de moment ou de quantite s annule en une fois`() = runTest {
        val id = addRiche()
        f.apply(CorrectConsumption(id, moment = Moment.Soir))
        f.apply(UndoLast)

        assertEquals(Moment.Midi, f.engine.history(id).last().moment)
        assertEquals(3, f.engine.history(id).size)
        assertEquals(riche, f.engine.history(id).last().food)
    }

    @Test
    fun `une date exprimee prime sur la regle des 5 h, sans toucher a l heure reelle`() = runTest {
        f.clock.setLocal("2026-11-02T12:00")
        val id = f.add("Yaourt", 75.0, Moment.Soir, explicitDay = day("2026-11-01"))

        assertEquals(75.0, f.summary("2026-11-01").totals.kcal, 0.0)
        assertEquals(0.0, f.summary("2026-11-02").totals.kcal, 0.0)
        assertEquals(zurich("2026-11-02T12:00"), f.engine.history(id).single().consumedAt)
    }

    @Test
    fun `a 0 h 30 un aujourd hui exprime compte pour la date du jour, pas pour la veille`() = runTest {
        f.clock.setLocal("2026-11-03T00:30")
        f.add("Verre de lait", 90.0, Moment.Soir)
        f.add("Biscuit", 60.0, Moment.Snack, explicitDay = day("2026-11-03"))

        assertEquals(90.0, f.summary("2026-11-02").totals.kcal, 0.0)
        assertEquals(60.0, f.summary("2026-11-03").totals.kcal, 0.0)
    }

    @Test
    fun `une date future est refusee a l ajout et a la correction, jamais corrigee en silence`() = runTest {
        val id = f.add("Pomme", 80.0)

        val add = f.reject(AddConsumptions(NewConsumption(food("Pizza", 900.0), Moment.Soir, explicitDay = day("2026-11-03"))))
        val fix = f.reject(CorrectConsumption(id, day = day("2026-11-03")))

        assertTrue(add is Rejection.Invalid && add.message.startsWith("Date future refusée"))
        assertTrue(fix is Rejection.Invalid)
        assertEquals(80.0, f.summary().totals.kcal, 0.0)
        assertEquals(0.0, f.summary("2026-11-03").totals.kcal, 0.0)
        assertEquals(1, f.engine.history(id).size)
    }
}
