package app.trackly.review.page

import app.trackly.backup.MutableClock
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.TemporaryPhase
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.review.CatchUpAdvices
import app.trackly.core.review.TemporaryPlan
import app.trackly.core.store.InMemoryStore
import app.trackly.review.page.ReportPageFixtures.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Conseils chiffrés de la page du bilan : textes figés depuis les chiffres de l'application, puis boutons lus en direct
 * dans les objectifs. Données fictives des scénarios de démonstration.
 */
class AdviceTest {
    private val base = GoalTargets(2300.0, 135.0, 250.0, 70.0, 120.0)
    private val goals = listOf(Goal(LocalDate.parse("2026-09-01"), 1, RecordState.Active, base, Instant.EPOCH, null))
    private val today = ReportPageFixtures.range.end // vendredi 6 novembre

    private fun page(scenario: Scenario): ReportPage {
        val figures = ReportPageFixtures.figures(scenario)
        return ReportPageBuilder.build(figures, "", today, CatchUpAdvices.compute(figures, goals, today))
    }

    @Test
    fun `en retard avec 400 kcal au-dessus - l objectif temporaire, la phrase des pas et la date`() {
        val advice = page(Scenario.Retard).advice
        assertEquals(listOf(AdviceKind.Temporary, AdviceKind.Steps, AdviceKind.Date), advice.map { it.kind })

        val temp = advice[0]
        assertEquals("Objectif temporaire", temp.kicker)
        assertEquals("Rattraper les 400 kcal", temp.title)
        assertEquals("2\u202F200", temp.big)
        assertEquals(listOf("Du samedi 7 au mardi 10 novembre, −100 par jour", "Mercredi 11, retour à 2\u202F300 tout seul"), temp.rows)
        assertEquals("2026-11-07", temp.start)
        assertEquals(4, temp.days)

        val steps = advice[1].title
        assertTrue(steps, steps.startsWith("Ou bouger plus : environ +"))
        assertTrue(steps, steps.contains("pas par jour pendant 4 jours"))
        assertTrue(steps, steps.contains("kcal de plus par jour à ton poids actuel ("))

        assertTrue(advice[2].title, advice[2].title.startsWith("Ou garder ton rythme et viser le "))
        assertEquals(62.0, advice[2].targetKg!!, 0.0)
    }

    @Test
    fun `aucun conseil quand tout va bien ni sur un ralentissement normal`() {
        for (s in listOf(Scenario.Avance, Scenario.Temps, Scenario.Ralenti)) {
            assertTrue("$s", page(s).advice.isEmpty())
        }
    }

    @Test
    fun `la page garde ses conseils, et une ancienne page sans conseil se lit encore`() {
        val page = page(Scenario.Retard)
        assertEquals(page, ReportContent.decode(ReportContent.encode(page)))
        val old = ReportContent.encode(page.copy(advice = emptyList())).replace(""","advice":[]""", "")
        assertEquals(emptyList<Advice>(), ReportContent.decode(old)!!.advice)
    }

    @Test
    fun `boutons - appliquer, annuler, retablir, puis proposition passee`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-06T19:00:00Z"))
        val engine = TracklyEngine(InMemoryStore(), clock)
        assertTrue(engine.execute(SetGoal(base, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        val actions = AdviceActions(engine)
        val temp = AdviceText.temporary(TemporaryPlan(LocalDate.parse("2026-11-07"), 4, 100.0, 2300.0), 400.0)

        assertEquals(AdviceState.Open, actions.state(temp))
        assertNull(actions.apply(temp))
        val applied = actions.state(temp) as AdviceState.Temporary
        assertEquals(TemporaryPhase.Scheduled, applied.status.phase)
        assertEquals(2200.0, engine.daySummary(LocalDate.parse("2026-11-08")).goal!!.kcal, 0.0)

        assertNull(actions.toggle(applied))
        assertEquals(TemporaryPhase.Cancelled, (actions.state(temp) as AdviceState.Temporary).status.phase)
        assertEquals(2300.0, engine.daySummary(LocalDate.parse("2026-11-08")).goal!!.kcal, 0.0)

        // Une autre proposition, jamais appliquée : le lendemain de son premier jour, plus de bouton.
        val later = AdviceText.temporary(TemporaryPlan(LocalDate.parse("2026-11-09"), 3, 100.0, 2300.0), 300.0)
        clock.instant = Instant.parse("2026-11-10T09:00:00Z")
        assertEquals(AdviceState.Expired, actions.state(later))
    }

    @Test
    fun `un autre objectif temporaire applique depuis le chat - la carte de la page ne dit pas Applique sous d autres chiffres`() = runBlocking {
        val engine = TracklyEngine(InMemoryStore(), MutableClock(Instant.parse("2026-11-06T19:00:00Z")))
        assertTrue(engine.execute(SetGoal(base, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        val actions = AdviceActions(engine)
        val page = AdviceText.temporary(TemporaryPlan(LocalDate.parse("2026-11-07"), 4, 100.0, 2300.0), 400.0)
        val chat = AdviceText.temporary(TemporaryPlan(LocalDate.parse("2026-11-07"), 7, 60.0, 2300.0), 400.0)
        assertNull(actions.apply(chat))
        assertEquals(false, (actions.state(page) as AdviceState.Temporary).matches)
        assertEquals(true, (actions.state(chat) as AdviceState.Temporary).matches)
        // Et la carte de la page ne peut plus en ajouter un second.
        assertTrue(actions.apply(page)!!.contains("un seul à la fois"))
    }

    @Test
    fun `changer la date - bouton secondaire, annulable`() = runBlocking {
        val engine = TracklyEngine(InMemoryStore(), MutableClock(Instant.parse("2026-11-06T19:00:00Z")))
        val date = AdviceText.date(62.0, LocalDate.parse("2027-03-13"), alone = false)
        val actions = AdviceActions(engine)
        assertEquals(AdviceState.Open, actions.state(date))
        assertNull(actions.apply(date))
        val set = actions.state(date) as AdviceState.DateSet
        assertEquals(LocalDate.parse("2027-03-13"), engine.weightTarget().date)
        assertNull(actions.toggle(set))
        assertEquals(AdviceState.Open, actions.state(date))
        assertEquals(LocalDate.parse("2027-03-01"), engine.weightTarget().date)
    }

    @Test
    fun `la phrase des pas n a aucun bouton`() = runBlocking {
        val engine = TracklyEngine(InMemoryStore(), MutableClock(Instant.parse("2026-11-06T19:00:00Z")))
        val steps = page(Scenario.Retard).advice.first { it.kind == AdviceKind.Steps }
        assertNull(AdviceActions(engine).state(steps))
        assertNull(AdviceActions(engine).apply(steps))
    }
}
