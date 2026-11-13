package app.trackly.dishes

import app.trackly.core.catalog.Dish
import app.trackly.core.model.Consumption
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.Nutrition
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.Origin
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Recipe
import app.trackly.core.model.RecordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** « Mes aliments » : une seule liste, triée et découpée selon ce qu'on cherche. */
class RecurringBookRankingTest {
    private val at = Instant.parse("2026-10-20T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 20)

    private fun product(id: String, name: String, brand: String? = null, favorite: Boolean = false, active: Boolean = true, redirectTo: String? = null) =
        Product(
            productId = id, version = 1, state = RecordState.Active, name = name, brand = brand, nutrients = Nutrients(100.0),
            basis = NutritionBasis(100.0, "g"), favorite = favorite, active = active, redirectTo = redirectTo,
            kind = ProductKind.Recurring, recordedAt = at, actionId = null,
        )

    private var n = 0
    private fun ate(productId: String, times: Int = 1, day: LocalDate = today) = List(times) {
        n++
        Consumption(
            EntryId("e$n"), 1, RecordState.Active, day, at.plusSeconds(n.toLong()), Moment.Midi,
            Food("x", Nutrition(100.0, 1.0, 1.0, 1.0), productId = productId), Origin.App, at, null,
        )
    }

    private val souvent = product("p-souvent", "Fromage blanc")
    private val parfois = product("p-parfois", "Chocolat noir")
    private val neuf = product("p-neuf", "Pain de seigle")
    private val rare = product("p-rare", "Sauce soja")
    private val une = product("p-une", "Biscotte")
    private val items = RecurringBook.all(emptyList(), listOf(souvent, parfois, neuf, rare, une))
    private val usage = RecurringBook.usage(
        emptyList(),
        ate("p-souvent", 4, today.minusDays(20)) + ate("p-souvent", 1, today.minusDays(9)) +
            ate("p-parfois", 2, today.minusDays(40)) + ate("p-une", 1, today.minusDays(1)),
        listOf(souvent, parfois, neuf, rare, une),
    )
    private val fresh = setOf("p-neuf")

    private fun sections(sort: FoodSort) =
        RecurringBook.sections(items, usage, fresh, sort, today).map { section -> section.title to section.items.map { it.id } }

    @Test
    fun `les plus manges - decoupes par frequence, les nouveaux d abord`() {
        assertEquals(
            listOf(
                "NOUVEAUX" to listOf("p-neuf"),
                "SOUVENT" to listOf("p-souvent"),
                "PARFOIS" to listOf("p-parfois"),
                "UNE FOIS" to listOf("p-une"),
                "PAS MANGÉ RÉCEMMENT" to listOf("p-rare"),
            ),
            sections(FoodSort.Frequent),
        )
    }

    @Test
    fun `recents - decoupes selon le dernier repas, le plus recent d abord`() {
        assertEquals(
            listOf(
                "NOUVEAUX" to listOf("p-neuf"),
                "CES 7 DERNIERS JOURS" to listOf("p-une"),
                "CES 30 DERNIERS JOURS" to listOf("p-souvent"),
                "PLUS ANCIEN" to listOf("p-parfois"),
                "PAS MANGÉ RÉCEMMENT" to listOf("p-rare"),
            ),
            sections(FoodSort.Recent),
        )
    }

    @Test
    fun `a-z - une seule liste, sans tenir compte des accents ni de la casse`() {
        val all = RecurringBook.all(emptyList(), listOf(product("a", "zeste"), product("b", "Éclair"), product("c", "Abricot")))
        val only = RecurringBook.sections(all, emptyMap(), emptySet(), FoodSort.Alphabetical, today).single()
        assertEquals(null, only.title)
        assertEquals(listOf("c", "b", "a"), only.items.map { it.id })
    }

    @Test
    fun `un plat en pause ferme la liste, meme s il est tres mange`() {
        val salade = Recipe("rcp-test", 1, RecordState.Active, "Salade", active = false, recordedAt = at, actionId = null)
        val all = RecurringBook.all(listOf(Dish(salade, emptyList())), listOf(souvent))
        val eaten = mapOf("rcp-test" to Usage(50, at, today))
        val shown = RecurringBook.sections(all, eaten, emptySet(), FoodSort.Frequent, today)
        assertEquals("EN PAUSE", shown.last().title)
        assertEquals(listOf("rcp-test"), shown.last().items.map { it.id })
    }

    @Test
    fun `un aliment mange n est plus nouveau`() {
        val eaten = RecurringBook.usage(emptyList(), ate("p-neuf"), listOf(neuf))
        val shown = RecurringBook.sections(RecurringBook.all(emptyList(), listOf(neuf)), eaten, fresh, FoodSort.Frequent, today)
        assertEquals(listOf("UNE FOIS"), shown.map { it.title })
    }

    @Test
    fun `le dernier repas en peu de mots`() {
        fun label(days: Long) = RecurringBook.lastEaten(today.minusDays(days), today)
        assertEquals("aujourd’hui", label(0))
        assertEquals("hier", label(1))
        assertEquals("il y a 6\u00A0j", label(6))
        assertEquals("il y a 1\u00A0sem.", label(7))
        assertEquals("il y a 8\u00A0sem.", label(59))
        assertEquals("il y a 2\u00A0mois", label(60))
    }

    @Test
    fun `le dernier repas suit le jour du journal, pas l heure de saisie`() {
        val eaten = RecurringBook.usage(emptyList(), ate("p-une", 1, today.minusDays(3)) + ate("p-une", 1, today.minusDays(8)), listOf(une))
        assertEquals(today.minusDays(3), eaten["p-une"]?.lastDay)
    }

    @Test
    fun `une ligne garde ses valeurs et ses proteines, sans repeter les autres macros`() {
        val barre = product("p", "Barre").copy(nutrients = Nutrients(100.0, 20.0, 30.0, 4.0))
        assertEquals("100 kcal pour 100 g · P\u00A020\u00A0g", RecurringBook.rowDetail(RecurringItem.OfProduct(barre)))
    }

    @Test
    fun `consommations d un ancien doublon inactif comptees pour la fiche active de meme nom et marque`() {
        val actif = product("p-yaourt", "Yaourt nature 1,5 %", brand = "Laiterie")
        val ancien = product("p-yaourt-ancien", "Yaourt  nature 1,5 % ", brand = "laiterie", active = false)
        val autreMarque = product("p-yaourt-fermier", "Yaourt nature 1,5 %", brand = "Fermier", active = false)
        val redirige = product("p-vieux-pain", "Vieux pain", redirectTo = "p-pain")
        val pain = product("p-pain", "Pain complet")
        val all = listOf(actif, ancien, autreMarque, redirige, pain)
        val usage = RecurringBook.usage(emptyList(), ate("p-yaourt-ancien", 6) + ate("p-yaourt") + ate("p-yaourt-fermier") + ate("p-vieux-pain", 2), all)
        assertEquals(7, usage["p-yaourt"]?.count)
        assertEquals(1, usage["p-yaourt-fermier"]?.count)
        assertEquals(2, usage["p-pain"]?.count)
        // L'ancien doublon n'est plus une ligne à part.
        val shown = RecurringBook.sorted(RecurringBook.all(emptyList(), all), usage, FoodSort.Frequent).map { it.id }
        assertEquals("p-yaourt", shown.first())
        assertTrue("p-yaourt-ancien" !in shown)
    }
}
