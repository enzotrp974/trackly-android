package app.trackly.core

import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ActionResult
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import org.junit.Assert.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Horloge réglable : les tests avancent le temps à la main. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = TrackingDay.ZONE
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now

    /** Règle l'horloge sur une heure locale (« 2026-11-02T12:30 »). */
    fun setLocal(local: String) {
        now = zurich(local)
    }
}

fun zurich(local: String): Instant = LocalDateTime.parse(local).atZone(TrackingDay.ZONE).toInstant()

fun day(iso: String): LocalDate = LocalDate.parse(iso)

fun food(label: String, kcal: Double, protein: Double? = 0.0, carbs: Double? = 0.0, fat: Double? = 0.0) =
    Food(label, Nutrition(kcal, protein, carbs, fat))

/** Moteur sur stockage en mémoire, horloge réglée au lundi 2 novembre 2026, 12 h 30. */
class Fixture(start: String = "2026-11-02T12:30") {
    val clock = MutableClock(zurich(start))
    val store = InMemoryStore()
    val engine = TracklyEngine(store, clock)

    suspend fun apply(action: Action): ActionResult = when (val outcome = engine.execute(action)) {
        is ActionOutcome.Applied -> outcome.result
        is ActionOutcome.Rejected -> throw AssertionError("Action refusée : ${outcome.rejection}")
    }

    suspend fun reject(action: Action): Rejection = when (val outcome = engine.execute(action)) {
        is ActionOutcome.Applied -> throw AssertionError("Action acceptée à tort : ${outcome.result.title}")
        is ActionOutcome.Rejected -> outcome.rejection
    }

    /** Ajoute un aliment et renvoie son identifiant. */
    suspend fun add(
        label: String,
        kcal: Double,
        moment: Moment = Moment.Midi,
        protein: Double? = 0.0,
        carbs: Double? = 0.0,
        fat: Double? = 0.0,
        explicitDay: LocalDate? = null,
    ): EntryId {
        val result = apply(AddConsumptions(NewConsumption(food(label, kcal, protein, carbs, fat), moment, explicitDay = explicitDay)))
        assertTrue(result.consumptionChanges.size == 1)
        return result.consumptionChanges.single().after.entryId
    }

    suspend fun summary(iso: String = "2026-11-02") = engine.daySummary(day(iso))
}
