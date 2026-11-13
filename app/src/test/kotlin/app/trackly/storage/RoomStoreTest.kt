package app.trackly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Confidence
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.FoodSource
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.KcalRange
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.storage.db.TracklyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Le stockage Room respecte le même contrat que le stockage en mémoire du moteur. */
@RunWith(AndroidJUnit4::class)
class RoomStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, TracklyDatabase::class.java).build()
    private val store = RoomTracklyStore(db)

    // Lundi 2 novembre 2026, 13 h 30 heure locale (fuseau des tests).
    private val engine = TracklyEngine(store, Clock.fixed(Instant.parse("2026-11-02T12:30:00Z"), ZoneOffset.UTC))
    private val today = LocalDate.parse("2026-11-02")

    @After
    fun close() = db.close()

    private suspend fun applied(outcome: ActionOutcome) = (outcome as ActionOutcome.Applied).result

    private val detailedFood = Food(
        label = "Poulet rôti",
        nutrition = Nutrition(kcal = 330.0, proteinG = 45.0, carbsG = 0.0, fatG = null, fiberG = 0.0),
        quantity = Quantity(200.0, "g"),
        assumptions = listOf("poulet supposé cuit, pesé cuit", "avec matière grasse"),
        kcalRange = KcalRange(280.0, 380.0),
        productId = "prod-1",
        source = FoodSource("generic_estimate", "Valeurs typiques"),
        confidence = Confidence.Medium,
    )

    @Test
    fun `une consommation complete fait l aller-retour sans perte`() = runBlocking {
        val result = applied(engine.execute(AddConsumptions(NewConsumption(detailedFood, Moment.Midi))))
        val stored = engine.daySummary(today).consumptions.single()
        assertEquals(result.consumptionChanges.single().after, stored)
        assertEquals(detailedFood, stored.food)
    }

    @Test
    fun `ajouter, corriger, supprimer puis annuler donne versions et bilan`() = runBlocking {
        val add = applied(engine.execute(AddConsumptions(NewConsumption(detailedFood, Moment.Midi))))
        val id = add.consumptionChanges.single().after.entryId
        applied(engine.execute(CorrectConsumption(id, moment = Moment.Soir)))
        applied(engine.execute(DeleteConsumption(id)))
        assertEquals(0.0, engine.daySummary(today).totals.kcal, 0.0)

        applied(engine.execute(UndoLast))
        val history = engine.history(id)
        assertEquals(listOf(1, 2, 3, 4), history.map { it.version })
        assertEquals(RecordState.Active, history.last().state)
        assertEquals(Moment.Soir, history.last().moment)
        assertEquals(330.0, engine.daySummary(today).totals.kcal, 0.0)
    }

    @Test
    fun `objectifs dates et annulation precise`() = runBlocking {
        applied(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))))
        val raise = applied(engine.execute(SetGoal(GoalTargets(2800.0, 150.0, 380.0, 75.0))))
        assertEquals(2800.0, engine.daySummary(today).goal!!.kcal, 0.0)
        assertEquals(2600.0, engine.daySummary(today.minusDays(1)).goal!!.kcal, 0.0)

        applied(engine.execute(Undo(raise.id)))
        assertEquals(2600.0, engine.daySummary(today).goal!!.kcal, 0.0)
    }

    @Test
    fun `une action refusee ne laisse aucune trace`() = runBlocking {
        val existing = applied(engine.execute(AddConsumptions(NewConsumption(detailedFood, Moment.Midi))))
            .consumptionChanges.single().after.entryId
        val outcome = engine.execute(
            AddConsumptions(
                listOf(
                    NewConsumption(Food("Banane", Nutrition(100.0)), Moment.Snack),
                    NewConsumption(Food("Doublon", Nutrition(1.0)), Moment.Snack, entryId = existing),
                ),
            ),
        )
        assertTrue((outcome as ActionOutcome.Rejected).rejection is Rejection.Invalid)
        assertEquals(listOf("Poulet rôti"), engine.daySummary(today).consumptions.map { it.food.label })
        assertEquals(1, engine.observeRecentActions(10).first().size)
    }

    @Test
    fun `une version existante n est jamais remplacee`() = runBlocking {
        val first = applied(engine.execute(AddConsumptions(NewConsumption(detailedFood, Moment.Midi))))
            .consumptionChanges.single().after
        val error = runCatching { store.transaction { consumptions.insert(first.copy(food = Food("Autre", Nutrition(1.0)))) } }
        assertTrue(error.isFailure)
        assertEquals(detailedFood, engine.history(first.entryId).single().food)
    }

    @Test
    fun `l ecran observe les bilans et voit chaque changement`() = runBlocking {
        val flow = engine.observeDays(today.minusDays(1), today)
        assertEquals(listOf(0.0, 0.0), flow.first().map { it.totals.kcal })
        applied(engine.execute(AddConsumptions(NewConsumption(detailedFood, Moment.Midi, explicitDay = today.minusDays(1)))))
        assertEquals(listOf(330.0, 0.0), flow.first().map { it.totals.kcal })
        assertEquals(today.minusDays(1), engine.observeFirstDay().first())
        assertEquals("Ajout : Poulet rôti (330 kcal)", engine.observeRecentActions(1).first().single().title)
        assertTrue(engine.history(EntryId("absent")).isEmpty())
    }
}
