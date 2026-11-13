package app.trackly.core

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.NewConsumption
import app.trackly.core.model.ActionId
import app.trackly.core.model.EntryId
import app.trackly.core.model.Moment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Des aliments déclarés ensemble s'affichent dans l'ordre de la déclaration (et non dans l'ordre d'un UUID aléatoire).
 * Les identifiants étant aléatoires, chaque cas est rejoué sur plusieurs moteurs neufs.
 */
class DeclarationOrderTest {
    private val labels = listOf("Zucchini", "Riz", "Poulet", "Avocat", "Yaourt", "Basilic", "Noix", "Citron")

    private fun items() = labels.map { NewConsumption(food(it, 100.0), Moment.Midi) }

    @Test
    fun `une declaration garde l ordre des aliments`() = runTest {
        repeat(25) {
            val f = Fixture()
            f.apply(AddConsumptions(items()))
            assertEquals(labels, f.summary().consumptions.map { it.food.label })
        }
    }

    @Test
    fun `une declaration avec produits garde aussi l ordre`() = runTest {
        repeat(25) {
            val f = Fixture()
            f.apply(DeclareConsumptions(items().map { DeclaredItem.Free(it) }))
            assertEquals(labels, f.summary().consumptions.map { it.food.label })
        }
    }

    @Test
    fun `deux declarations a la meme heure restent dans l ordre d enregistrement`() = runTest {
        repeat(25) {
            val f = Fixture()
            f.apply(AddConsumptions(items().take(3)))
            f.apply(AddConsumptions(items().drop(3)))
            assertEquals(labels, f.summary().consumptions.map { it.food.label })
        }
    }

    @Test
    fun `corriger un aliment ne le deplace pas`() = runTest {
        val f = Fixture()
        val result = f.apply(AddConsumptions(items()))
        val ids = result.consumptionChanges.map { it.after.entryId }
        f.apply(CorrectConsumption(ids[1], food = food("Riz complet", 120.0)))
        f.apply(CorrectConsumption(ids[0], food = food("Courgette", 30.0)))
        assertEquals(listOf("Courgette", "Riz complet") + labels.drop(2), f.summary().consumptions.map { it.food.label })
    }

    @Test
    fun `l heure reelle prime toujours sur la position`() = runTest {
        val f = Fixture()
        val avant = zurich("2026-11-02T08:00")
        f.apply(
            AddConsumptions(
                listOf(
                    NewConsumption(food("Tard", 10.0), Moment.Midi),
                    NewConsumption(food("Tôt", 10.0), Moment.Midi, consumedAt = avant),
                ),
            ),
        )
        assertEquals(listOf("Tôt", "Tard"), f.summary().consumptions.map { it.food.label })
    }

    @Test
    fun `l identifiant ordonne est un UUID valide qui se range par action puis position`() {
        val ids = listOf(ActionId(1) to 0, ActionId(1) to 1, ActionId(1) to 4095, ActionId(2) to 0, ActionId(300) to 7)
            .map { (action, position) -> EntryId.ordered(action, position).value }
        ids.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertEquals(ids, ids.sorted())
        assertTrue(ids.all { it[14] == '7' })
        // Au-delà de 4095 : plafonné, reste un UUID valide.
        UUID.fromString(EntryId.ordered(ActionId(1), 10_000).value)
    }
}
