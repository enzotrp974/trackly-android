package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.SetDefaultVariant
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.UndoLast
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Nutrients
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Plats, variantes et provisions font l'aller-retour dans Room (version 7), annulation comprise. */
@RunWith(AndroidJUnit4::class)
class RoomDishesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val engine = TracklyEngine(RoomTracklyStore(db), Clock.fixed(Instant.parse("2026-11-02T12:30:00Z"), ZoneOffset.UTC))

    @After
    fun close() = db.close()

    private suspend fun ok(action: app.trackly.core.action.Action) = check(engine.execute(action) is ActionOutcome.Applied) { "refusée : $action" }

    @Test
    fun `un plat avec variantes, defaut et pause fait l aller-retour et s annule`() = runBlocking {
        val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2), productId = "prd-laitue")
        ok(CreateDish("Salade", listOf(laitue), shortcuts = listOf("ma salade"), variantName = "sauce A", dishId = "rcp-s"))
        ok(AddDishVariant("rcp-s", "sauce B", listOf(laitue.copy(quantityG = 150.0)), makeDefault = true, variantId = "rcp-b"))
        ok(SetDefaultVariant("rcp-s", null))
        ok(SetDefaultVariant("rcp-s", "rcp-b"))
        ok(SetDishPaused("rcp-s", paused = true))

        val dish = Dishes.group(engine.allRecipes()).single()
        assertEquals("Salade", dish.name)
        assertEquals(listOf("ma salade"), dish.base.aliases)
        assertEquals("sauce A", dish.base.variantName)
        assertEquals("rcp-b", dish.base.defaultVariantId)
        assertTrue(dish.isPaused)
        val variant = dish.variants.single()
        assertEquals("rcp-s", variant.variantOf)
        assertEquals("sauce B", variant.variantName)
        // Le lien vers le produit d'un ingrédient est conservé.
        assertEquals("prd-laitue", variant.ingredients.single().productId)
        assertEquals(150.0, variant.ingredients.single().quantityG, 0.0)

        assertTrue(engine.execute(UndoLast) is ActionOutcome.Applied)
        assertEquals(false, Dishes.group(engine.allRecipes()).single().isPaused)
    }

    @Test
    fun `les provisions font l aller-retour, j ai puis plus puis j ai`() = runBlocking {
        ok(UpdateProvisions(have = listOf(ProvisionInput("pommes"), ProvisionInput("whey", productId = "prd-whey"))))
        ok(UpdateProvisions(out = listOf("pomme")))
        assertEquals(listOf("whey"), Provisions.held(engine.allProvisions()).map { it.name })
        assertEquals("prd-whey", engine.allProvisions().first { it.name == "whey" }.productId)
        ok(UpdateProvisions(have = listOf(ProvisionInput("pommes"))))
        assertEquals(listOf("pommes", "whey"), Provisions.held(engine.allProvisions()).map { it.name })
        assertEquals(3, engine.allProvisions().first { it.name == "pommes" }.version)
    }
}
