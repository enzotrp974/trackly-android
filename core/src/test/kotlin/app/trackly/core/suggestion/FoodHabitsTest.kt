package app.trackly.core.suggestion

import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Origin
import app.trackly.core.model.Product
import app.trackly.core.model.RecordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FoodHabitsTest {
    private fun product(id: String, name: String, vararg aliases: String) = Product(
        productId = id, version = 1, state = RecordState.Active, name = name, aliases = aliases.toList(),
        nutrients = Nutrients(kcal = 100.0), recordedAt = Instant.EPOCH, actionId = null,
    )

    private var next = 0

    private fun meal(day: Int, moment: Moment, label: String, kcal: Double = 500.0): Consumption {
        next++
        return Consumption(
            entryId = EntryId("e$next"), version = 1, state = RecordState.Active,
            day = LocalDate.of(2026, 10, day), consumedAt = Instant.parse("2026-10-${"%02d".format(day)}T12:00:00Z"),
            moment = moment, food = Food(label, Nutrition(kcal, null, null, null)), origin = Origin.Assistant,
            recordedAt = Instant.EPOCH, actionId = null,
        )
    }

    private val chicken = product("poulet", "Filet de poulet", "poulet")
    private val egg = product("oeuf", "Œuf entier", "œufs")
    private val sandwich = product("sandwich", "Sandwich thon et œuf")
    private val tuna = product("thon", "Thon en conserve", "thon")
    private val matcher = FoodMatcher(listOf(chicken, egg, sandwich, tuna), emptyList())

    @Test
    fun `retrouve les aliments cites, ecrits avec ou sans œ, au singulier ou au pluriel`() {
        assertEquals(setOf("oeuf"), matcher.ids(meal(1, Moment.Matin, "Petit-déjeuner — 3 oeufs et ketchup")))
        assertEquals(setOf("oeuf"), matcher.ids(meal(1, Moment.Matin, "1 œuf")))
        assertEquals(setOf("poulet"), matcher.ids(meal(1, Moment.Midi, "salade, filet de poulet 170 g")))
    }

    @Test
    fun `un sandwich au thon et a l oeuf ne compte ni pour la boite de thon ni pour l oeuf`() {
        assertEquals(setOf("sandwich"), matcher.ids(meal(1, Moment.Midi, "Sandwich thon et œuf 165 g")))
    }

    @Test
    fun `habitudes par moment - frequents, moyenne des calories, et presque jamais`() {
        val meals = (1..8).map { meal(it, Moment.Midi, "salade et poulet 170 g", 800.0) } +
            (1..8).map { meal(it, Moment.Matin, "3 œufs", 300.0) } +
            listOf(meal(9, Moment.Soir, "2 œufs", 200.0))
        val habits = FoodHabits.of(meals, matcher).associateBy { it.moment }

        val morning = habits.getValue(Moment.Matin)
        assertEquals(8, morning.days)
        assertEquals(300, morning.averageKcal)
        assertEquals(FoodCount("Œuf entier", 8), morning.usual.single())
        assertTrue("le poulet n'est jamais du matin", "Filet de poulet" in morning.rare)
        assertFalse("Snack vide : pas de ligne", Moment.Snack in habits)
    }

    @Test
    fun `une consommation supprimee ne compte pas`() {
        val deleted = meal(1, Moment.Soir, "poulet").copy(state = RecordState.Deleted)
        assertTrue(FoodHabits.of(listOf(deleted), matcher).isEmpty())
    }

    @Test
    fun `un repas relie a une fiche ne compte que pour elle, meme avec un homonyme`() {
        val first = product("barre-a", "Barre protéinée")
        val second = product("barre-b", "Barre protéinée")
        val linked = meal(1, Moment.Snack, "Barre protéinée").let { it.copy(food = it.food.copy(productId = "barre-a")) }
        assertEquals(setOf("barre-a"), FoodMatcher(listOf(first, second), emptyList()).ids(linked))
    }

    @Test
    fun `des mots de deux aliments differents ne font pas un plat`() {
        val bowl = product("bol", "Riz au poulet")
        val rice = product("riz", "Riz")
        val found = FoodMatcher(listOf(bowl, rice, chicken), emptyList()).ids(meal(1, Moment.Midi, "poulet 150 g, riz 200 g"))
        assertEquals(setOf("poulet", "riz"), found)
    }

    @Test
    fun `ce qui suit sans n est pas mange`() {
        val sugar = product("sucre", "Sucre")
        val found = FoodMatcher(listOf(sugar, chicken), emptyList()).ids(meal(1, Moment.Matin, "café sans sucre, poulet"))
        assertEquals(setOf("poulet"), found)
    }
}
