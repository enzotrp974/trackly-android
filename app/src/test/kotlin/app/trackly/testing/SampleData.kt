package app.trackly.testing

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jeu d'essai fictif : dimanche 1er et lundi 2 novembre
 * 2026, objectif 2 600 kcal. Il passe par les actions du moteur, comme de vraies saisies.
 */
object SampleData {
    /** Lundi 2 novembre 2026, 12 h 30, heure locale. */
    fun clock(local: String = "2026-11-02T12:30"): Clock =
        Clock.fixed(LocalDateTime.parse(local).atZone(TrackingDay.ZONE).toInstant(), TrackingDay.ZONE)

    fun emptyEngine(clock: Clock = clock()) = TracklyEngine(InMemoryStore(), clock)

    fun engine(clock: Clock = clock()): TracklyEngine = emptyEngine(clock).also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            val sunday = LocalDate.parse("2026-11-01")
            val monday = LocalDate.parse("2026-11-02")
            add(engine, sunday, Moment.Matin, "Pancakes, yaourt grec, banane, café", 610, 28, 88, 16)
            add(engine, sunday, Moment.Midi, "Riz, saumon, brocolis, huile d’olive", 820, 52, 98, 24)
            add(engine, sunday, Moment.Snack, "Fromage blanc, miel, noix", 280, 22, 28, 9)
            add(engine, sunday, Moment.Soir, "Pâtes complètes, bolognaise, salade verte", 770, 38, 102, 24)
            add(engine, monday, Moment.Matin, "Œufs brouillés, pain complet, café", 520, 32, 48, 20)
            add(engine, monday, Moment.Midi, "Salade sauce A, poulet", 640, 44, 89, 20)
            add(engine, monday, Moment.Snack, "Barre Nuvo, pomme 150 g", 300, 20, 31, 8)
        }
    }

    /** Même jeu d'essai, avec en plus un repas le jeudi 15 octobre (historique plus long, pour le calendrier). */
    fun engineWithHistory(clock: Clock = clock()): TracklyEngine = engine(clock).also { engine ->
        runBlocking { add(engine, LocalDate.parse("2026-10-15"), Moment.Midi, "Riz, poulet, courgettes", 690, 48, 80, 16) }
    }

    /** Export de reprise synthétique (`fixtures/parity/bundle.json`). */
    val historyBundle: String by lazy { checkNotNull(SampleData::class.java.getResource("/bundle.json")).readText() }

    private suspend fun add(engine: TracklyEngine, day: LocalDate, moment: Moment, label: String, kcal: Int, p: Int, c: Int, f: Int) {
        val food = Food(label, Nutrition(kcal.toDouble(), p.toDouble(), c.toDouble(), f.toDouble()))
        check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = day))) is ActionOutcome.Applied)
    }
}
