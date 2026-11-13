package app.trackly.core

import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.model.EntryId
import app.trackly.core.model.Moment
import app.trackly.core.model.RecordState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Couture principale : suite d'actions → bilan du jour et historique. */
class ConsumptionActionsTest {
    private val f = Fixture()

    @Test
    fun `le bilan additionne les consommations actives du jour`() = runTest {
        f.add("Œufs brouillés", 520.0, Moment.Matin, protein = 32.0, carbs = 48.0, fat = 20.0)
        f.add("Salade sauce A, poulet", 640.0, Moment.Midi, protein = 44.0, carbs = 89.0, fat = 20.0)

        val s = f.summary()
        assertEquals(1160.0, s.totals.kcal, 0.0)
        assertEquals(76.0, s.totals.protein.value!!, 0.0)
        assertEquals(137.0, s.totals.carbs.value!!, 0.0)
        assertEquals(40.0, s.totals.fat.value!!, 0.0)
    }

    @Test
    fun `les consommations s affichent par moment puis par heure reelle`() = runTest {
        f.clock.setLocal("2026-11-02T19:00")
        f.add("Pâtes", 700.0, Moment.Soir)
        f.clock.setLocal("2026-11-02T19:05")
        f.add("Café", 10.0, Moment.Matin)
        f.clock.setLocal("2026-11-02T19:10")
        f.add("Pomme", 80.0, Moment.Snack)
        f.clock.setLocal("2026-11-02T19:15")
        f.add("Croissant", 250.0, Moment.Matin)

        val s = f.summary()
        assertEquals(listOf("Café", "Croissant", "Pomme", "Pâtes"), s.consumptions.map { it.food.label })
        assertEquals(listOf(Moment.Matin, Moment.Midi, Moment.Snack, Moment.Soir), s.moments.map { it.moment })
        assertEquals(260.0, s.moments[0].kcal, 0.0)
        assertTrue(s.moments[1].consumptions.isEmpty())
    }

    @Test
    fun `une declaration de plusieurs aliments cree une consommation par aliment, en une action`() = runTest {
        val result = f.apply(
            AddConsumptions(
                listOf(
                    NewConsumption(food("Barre protéinée", 220.0), Moment.Snack),
                    NewConsumption(food("Pomme 150 g", 80.0), Moment.Snack),
                ),
            ),
        )
        assertEquals(2, result.consumptionChanges.size)
        assertEquals(setOf(day("2026-11-02")), result.days)
        assertEquals("Ajout : Barre protéinée (220 kcal), Pomme 150 g (80 kcal)", result.title)
        assertEquals(300.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `corriger cree une nouvelle version complete et garde l historique`() = runTest {
        val id = f.add("Barre protéinée", 220.0, Moment.Snack)
        val result = f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))

        val change = result.consumptionChanges.single()
        assertEquals(1, change.before!!.version)
        assertEquals(2, change.after.version)
        assertEquals(240.0, f.summary().totals.kcal, 0.0)
        val history = f.engine.history(id)
        assertEquals(listOf(1, 2), history.map { it.version })
        assertEquals("Barre protéinée", history[0].food.label)
        assertEquals(Moment.Snack, history[1].moment)
    }

    @Test
    fun `supprimer cree une version deleted, exclue du bilan`() = runTest {
        val pomme = f.add("Pomme", 80.0)
        f.add("Riz", 400.0)
        f.apply(DeleteConsumption(pomme))

        val s = f.summary()
        assertEquals(400.0, s.totals.kcal, 0.0)
        assertEquals(listOf("Riz"), s.consumptions.map { it.food.label })
        assertEquals(listOf(RecordState.Active, RecordState.Deleted), f.engine.history(pomme).map { it.state })
    }

    @Test
    fun `corriger le jour deplace la consommation d un bilan a l autre`() = runTest {
        val id = f.add("Pizza", 900.0, Moment.Soir)
        f.apply(CorrectConsumption(id, day = day("2026-11-01")))

        assertEquals(0.0, f.summary("2026-11-02").totals.kcal, 0.0)
        assertEquals(900.0, f.summary("2026-11-01").totals.kcal, 0.0)
    }

    @Test
    fun `une macro inconnue rend le total incomplet, sans inventer de zero`() = runTest {
        f.add("Poulet", 300.0, protein = 40.0, carbs = 0.0, fat = null)
        f.add("Riz", 350.0, protein = 7.0, carbs = 78.0, fat = 1.0)

        val fat = f.summary().totals.fat
        assertNull(fat.value)
        assertEquals(1.0, fat.known, 0.0)
        assertEquals(47.0, f.summary().totals.protein.value!!, 0.0)
    }

    @Test
    fun `refus typés, sans aucune ecriture`() = runTest {
        val id = f.add("Pomme", 80.0)
        f.apply(DeleteConsumption(id))

        assertTrue(f.reject(DeleteConsumption(id)) is Rejection.NotFound)
        assertTrue(f.reject(CorrectConsumption(id, moment = Moment.Soir)) is Rejection.NotFound)
        assertTrue(f.reject(CorrectConsumption(EntryId("inconnu"))) is Rejection.NotFound)
        assertTrue(f.reject(AddConsumptions(emptyList())) is Rejection.Invalid)
        assertEquals(2, f.engine.history(id).size)
    }

    @Test
    fun `une correction sans changement est refusee`() = runTest {
        val id = f.add("Pomme", 80.0, Moment.Snack)
        assertEquals(Rejection.NoChange, f.reject(CorrectConsumption(id, moment = Moment.Snack)))
        assertEquals(1, f.engine.history(id).size)
    }

    @Test
    fun `un ajout refuse en cours de route n ecrit rien du tout`() = runTest {
        val existing = f.add("Pomme", 80.0)
        val rejection = f.reject(
            AddConsumptions(
                listOf(
                    NewConsumption(food("Banane", 100.0), Moment.Snack),
                    NewConsumption(food("Pomme encore", 80.0), Moment.Snack, entryId = existing),
                ),
            ),
        )
        assertTrue(rejection is Rejection.Invalid)
        assertEquals(listOf("Pomme"), f.summary().consumptions.map { it.food.label })
    }
}
