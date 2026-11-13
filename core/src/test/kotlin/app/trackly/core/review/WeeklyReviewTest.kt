package app.trackly.core.review

import app.trackly.core.Fixture
import app.trackly.core.action.SetGoal
import app.trackly.core.day
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ParityBundle
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.store.ImportedSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.temporal.ChronoUnit

/**
 * Chiffres de la semaine calculés par le moteur (bilan du lundi). Les deux premiers cas reprennent
 * ceux du moteur Python (semaine du 7 au 13 septembre 2026) : mêmes données, mêmes résultats.
 */
class WeeklyReviewTest {
    private val week = WeekRange(day("2026-09-07"))

    // Lundi 14 septembre 2026 : le bilan porte sur la semaine du 7 au 13.
    private fun fixture() = Fixture("2026-09-14T09:00")

    /** Un jour « complet » : un repas du soir est déclaré, comme dans le moteur Python (`dinner`). */
    private suspend fun Fixture.completeDay(iso: String, kcal: Double, protein: Double? = 140.0, carbs: Double? = 250.0, fat: Double? = 70.0) {
        add("Repas du $iso", kcal, Moment.Soir, protein, carbs, fat, explicitDay = day(iso))
    }

    @Test
    fun `semaine complete a l objectif - comme weekly_report py`() = runBlocking {
        val f = fixture()
        f.apply(SetGoal(GoalTargets(2200.0), from = day("2026-09-01")))
        for (n in 7..13) f.completeDay("2026-09-%02d".format(n), 2200.0)

        val figures = f.engine.weeklyFigures(week)

        // Python : average_kcal 2200, average_difference_kcal 0, estimated_deficit_kcal 5600, perte 5600/7700 kg.
        assertEquals(7, figures.coverage.countedDays)
        assertEquals(2200.0, figures.intake.averageKcal!!, 1e-9)
        assertEquals(0.0, figures.goal.averageDifferenceKcal!!, 1e-9)
        assertEquals(2200.0, figures.goal.averageTargetKcal!!, 1e-9)
        assertEquals(5600.0, figures.balance!!.deficitKcal, 1e-9)
        assertEquals(-5600.0 / 7700.0, figures.balance!!.theoreticalChangeKg, 1e-9)
        assertEquals(0, figures.coverage.missing.size)
        assertEquals(5600.0 / 7700.0, figures.cumulative.theoreticalLossKg!!, 1e-9)
    }

    @Test
    fun `jours partiels, manquants et hors semaine ne gonflent pas la perte - comme weekly_report py`() = runBlocking {
        val f = fixture()
        f.apply(SetGoal(GoalTargets(2200.0), from = day("2026-09-01")))
        f.completeDay("2026-09-01", 2200.0) // avant la semaine : compte seulement dans le cumul
        f.completeDay("2026-09-07", 2300.0)
        f.add("Petit-déjeuner seul", 300.0, Moment.Matin, explicitDay = day("2026-09-08")) // partiel : pas de repas du soir
        // Le 14 est après la semaine : ignoré (jour de la déclaration).
        f.add("Trop tard", 9000.0, Moment.Soir, explicitDay = day("2026-09-14"))

        val figures = f.engine.weeklyFigures(week)

        // Python (CLI) : « 100 kcal/jour au-dessus », semaine 0,09 kg, cumul 0,19 kg ; couverture : 1 complet, 1 partiel, 5 manquants.
        assertEquals(2300.0, figures.intake.averageKcal!!, 1e-9)
        assertEquals(100.0, figures.goal.averageDifferenceKcal!!, 1e-9)
        assertEquals(700.0, figures.balance!!.deficitKcal, 1e-9)
        assertEquals(0.09, -figures.balance!!.theoreticalChangeKg, 0.005)
        assertEquals(listOf(day("2026-09-08")), figures.coverage.partial)
        assertEquals(5, figures.coverage.missing.size)
        assertEquals(day("2026-09-01"), figures.cumulative.from)
        assertEquals(2, figures.cumulative.countedDays)
        assertEquals(1500.0, figures.cumulative.theoreticalDeficitKcal!!, 1e-9)
        assertEquals(0.19, figures.cumulative.theoreticalLossKg!!, 0.005)
        assertEquals(100.0 + 0.0, figures.cumulative.differenceToGoalKcal!!, 1e-9) // (2200-2200) + (2300-2200)
        assertTrue(figures.missing.any { it.code == MissingCode.UntrackedDays })
    }

    @Test
    fun `macros moyennes sur les jours au detail connu, objectif change en cours de semaine`() = runBlocking {
        val f = fixture()
        f.apply(SetGoal(GoalTargets(2000.0, 150.0, 200.0, 60.0), from = day("2026-09-01")))
        f.apply(SetGoal(GoalTargets(2400.0, 150.0, 280.0, 70.0), from = day("2026-09-10")))
        f.completeDay("2026-09-07", 2100.0, protein = 130.0, carbs = 200.0, fat = 60.0)
        f.completeDay("2026-09-10", 2500.0, protein = 170.0, carbs = 300.0, fat = 80.0)
        f.completeDay("2026-09-11", null.let { 2300.0 }, protein = null, carbs = 250.0, fat = 70.0) // protéines inconnues

        val figures = f.engine.weeklyFigures(week)

        assertEquals(3, figures.coverage.countedDays)
        assertEquals((2100.0 + 2500.0 + 2300.0) / 3, figures.intake.averageKcal!!, 1e-9)
        // Macros : seulement les deux jours aux trois macros connues.
        assertEquals(2, figures.intake.macroDays)
        assertEquals(150.0, figures.intake.averageProteinG!!, 1e-9)
        assertEquals(250.0, figures.intake.averageCarbsG!!, 1e-9)
        assertTrue(figures.missing.any { it.code == MissingCode.MacrosUnknown })
        // Écart : chaque jour contre l'objectif qui valait ce jour-là (2000, 2400, 2400).
        assertTrue(figures.goal.goalChanged)
        assertEquals(((2100.0 - 2000) + (2500 - 2400) + (2300 - 2400)) / 3, figures.goal.averageDifferenceKcal!!, 1e-9)
        assertEquals(((130.0 - 150) + (170 - 150)) / 2, figures.goal.protein.averageDifferenceG!!, 1e-9)
    }

    // ───── Dépense estimée ─────

    @Test
    fun `sans pas ni seances, la depense reste la reference et le dit`() = runBlocking {
        val f = fixture()
        f.completeDay("2026-09-07", 2500.0)

        val e = f.engine.weeklyFigures(week).expenditure

        assertEquals(3000.0, e.kcalPerDay, 1e-9)
        assertFalse(e.adjusted)
        assertNull(e.stepsAdjustmentKcal)
        assertNull(e.sessionsAdjustmentKcal)
    }

    @Test
    fun `20 000 pas par jour - aucun ajustement pour les pas`() {
        assertEquals(0.0, expenditure(steps = 20_000, sessions = 3).stepsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `ajustement des pas proportionnel au poids - 0,5 kcal par kg et par 1 000 pas, moitie au-dessus de la reference`() {
        // Sans pesée : poids de travail 70 kg. 15 000 pas : 5 tranches de moins x 0,5 x 70 = -175.
        val low = expenditure(steps = 15_000, sessions = 2)
        assertEquals(-175.0, low.stepsAdjustmentKcal!!, 1e-9)
        assertEquals(WeightBasis.Default, low.weightBasis)
        assertEquals(70.0, low.weightKg, 0.0)
        // 24 000 pas : 4 tranches, comptées pour moitié : +4 x 0,5 x 0,5 x 70 = +70.
        val high = expenditure(steps = 24_000, sessions = 5)
        assertEquals(70.0, high.stepsAdjustmentKcal!!, 1e-9)
        // Séances de musculation : 250 kcal, écart à 3,5 sur 7 jours. 2 séances : -1,5 x 250 / 7.
        assertEquals(-375.0 / 7, low.sessionsAdjustmentKcal!!, 1e-9)
        assertEquals(3000.0 - 175.0 - 375.0 / 7, low.kcalPerDay, 1e-9)
        assertEquals(3000.0 + 70.0 + 375.0 / 7, high.kcalPerDay, 1e-9)
        // 3 et 4 séances (la référence « 3 à 4 ») ne s'écartent que d'une demi-séance.
        assertEquals(-125.0 / 7, expenditure(steps = 20_000, sessions = 3).sessionsAdjustmentKcal!!, 1e-9)
        assertEquals(125.0 / 7, expenditure(steps = 20_000, sessions = 4).sessionsAdjustmentKcal!!, 1e-9)
    }

    @Test
    fun `seule une information manque - l autre ajuste, la manquante n ajuste rien et est signalee`() = runBlocking {
        val f = fixture()
        f.completeDay("2026-09-07", 2500.0)
        f.apply(DeclareWeekInfo(week.start, steps = listOf(DaySteps(day("2026-09-07"), 16_000), DaySteps(day("2026-09-08"), 18_000))))

        val figures = f.engine.weeklyFigures(week)

        assertEquals(StepsSource.PerDay, figures.activity.stepsSource)
        assertEquals(17_000.0, figures.activity.averageSteps!!, 1e-9)
        assertEquals(3000.0 - 3 * 0.5 * 70.0, figures.expenditure.kcalPerDay, 1e-9) // 17 000 pas, poids de travail 70 kg
        assertNull(figures.expenditure.sessionsAdjustmentKcal)
        val codes = figures.missing.map { it.code }
        assertTrue(MissingCode.StepsPartial in codes) // 2 jours sur 7
        assertTrue(MissingCode.SessionsUnknown in codes)
        assertFalse(MissingCode.StepsUnknown in codes)
        assertTrue(figures.expenditure.assumptions.any { it.contains("hypothèse") })
    }

    @Test
    fun `balance energetique avec depense ajustee`() = runBlocking {
        val f = fixture()
        for (n in 7..9) f.completeDay("2026-09-%02d".format(n), 2400.0)
        f.apply(DeclareWeekInfo(week.start, stepsAverage = 20_000, sessions = listOf(SessionCount("musculation", 4))))

        val figures = f.engine.weeklyFigures(week)

        val perDay = 3000.0 + 0.5 * 250 / 7
        assertEquals(perDay, figures.expenditure.kcalPerDay, 1e-9)
        assertEquals((perDay - 2400.0) * 3, figures.balance!!.deficitKcal, 1e-9)
    }

    @Test
    fun `le bilan energetique porte la fourchette de la depense`() = runBlocking {
        val f = fixture()
        for (n in 7..9) f.completeDay("2026-09-%02d".format(n), 2400.0)

        val figures = f.engine.weeklyFigures(week)

        // Aucune information : ± 300 kcal par jour sur 3 jours = ± 900 kcal, soit environ ± 0,12 kg.
        assertEquals(300.0, figures.expenditure.uncertaintyKcal, 1e-9)
        assertEquals(900.0, figures.balance!!.deficitUncertaintyKcal, 1e-9)
        assertEquals(900.0 / 7700.0, figures.balance!!.changeUncertaintyKg, 1e-9)
    }

    // ───── Jours estimés, bornes ─────

    @Test
    fun `jeu de parite - memes chiffres que weekly_report py (estime, borne basse, partiels, manquant)`() = runBlocking {
        val f = Fixture()
        assertTrue(HistoryImport.run(f.engine, ParityBundle.text) is ImportOutcome.Imported)

        val figures = f.engine.weeklyFigures(WeekRange(day("2026-08-31")))

        // Python (`weekly_report(tests/fixtures/parity/data, "2026-09-07")`) :
        // complets 09-01, 09-03 (estimé), 09-06 ; partiels 09-02 et 09-05 ; borne basse 09-04 (1 550) ; 1 manquant ;
        // moyenne 2 067,17 ; perte théorique indisponible (borne basse) ; cumul du 09-01 au 09-06 sans manquant.
        assertEquals(listOf(day("2026-09-01"), day("2026-09-03"), day("2026-09-06")), figures.coverage.counted)
        assertEquals(listOf(day("2026-09-03")), figures.coverage.estimated)
        assertEquals(listOf(day("2026-09-02"), day("2026-09-05")), figures.coverage.partial)
        assertEquals(day("2026-09-04"), figures.coverage.bounded.single().day)
        assertEquals(1550.0, figures.coverage.bounded.single().kcalMin, 1e-9)
        assertEquals(1, figures.coverage.missing.size)
        assertEquals(2067.17, figures.intake.averageKcal!!, 0.005)
        assertEquals(2067.17 - 2350.0, -282.83, 0.005) // Python : écart à la cible actuelle (2 350)
        assertNull(figures.balance)
        assertNull(figures.cumulative.theoreticalDeficitKcal)
        assertEquals(day("2026-09-01"), figures.cumulative.from)
        assertEquals(0, figures.cumulative.missingDays)
        assertEquals(1, figures.cumulative.boundedDays)
        // Les jours estimés n'ont pas de macros : les macros ne portent que sur les 2 jours au détail complet.
        assertEquals(2, figures.intake.macroDays)
        val codes = figures.missing.map { it.code }
        assertTrue(MissingCode.BoundedDays in codes && MissingCode.MacrosUnknown in codes && MissingCode.UntrackedDays in codes)
        // Écart à l'objectif de CHAQUE jour (2 600 le 1er et le 3, 2 350 le 6), pas à l'objectif actuel seul.
        assertTrue(figures.goal.goalChanged)
        assertEquals(((2101.5 - 2600) + (2600 - 2600) + (1500 - 2350)) / 3, figures.goal.averageDifferenceKcal!!, 1e-6)
    }

    @Test
    fun `semaine sans aucun jour complet - moyennes indisponibles, signale`() = runBlocking {
        val figures = fixture().engine.weeklyFigures(week)
        assertNull(figures.intake.averageKcal)
        assertNull(figures.balance)
        assertEquals(7, figures.coverage.missing.size)
        assertTrue(figures.missing.any { it.code == MissingCode.NoIntakeDays })
    }

    // ───── Trajectoire ─────

    private val weeksToTarget = ChronoUnit.DAYS.between(day("2026-09-13"), day("2027-03-01")) / 7.0

    @Test
    fun `sans pesee - trajectoire inconnue cette semaine, aucun poids invente`() = runBlocking {
        val f = fixture()
        f.completeDay("2026-09-07", 2300.0)

        val t = f.engine.weeklyFigures(week).trajectory

        assertEquals(TrajectoryStatus.Unknown, t.status)
        assertEquals(0, t.weighInCount)
        assertEquals(WeighInReliability.None, t.reliability)
        assertNull(t.weekAverageKg)
        assertNull(t.requiredKgPerWeek)
        assertNull(t.trend)
        assertTrue(f.engine.weeklyFigures(week).missing.any { it.code == MissingCode.NoWeighIn })
    }

    @Test
    fun `pesees declarees - moyenne de la semaine, rythme necessaire, deux semaines ne suffisent pas`() = runBlocking {
        val f = fixture()
        f.apply(DeclareWeekInfo(week.start, weighIns = listOf(WeighIn(day("2026-09-08"), 72.0), WeighIn(day("2026-09-11"), 71.6))))
        f.apply(DeclareWeekInfo(day("2026-08-31"), weighIns = listOf(WeighIn(day("2026-09-02"), 72.5))))

        val t = f.engine.weeklyFigures(week).trajectory

        assertEquals(71.8, t.weekAverageKg!!, 1e-9)
        assertEquals(2, t.weighInCount)
        assertEquals(weeksToTarget, t.weeksRemaining, 1e-9)
        assertEquals((60.0 - 71.8) / weeksToTarget, t.requiredKgPerWeek!!, 1e-9)
        assertEquals(2, t.weighedWeeks)
        assertNull(t.trend) // 2 semaines pesées sur les 3 minimum
        assertEquals(TrajectoryStatus.TooEarly, t.status)
    }

    @Test
    fun `le rythme du bilan energetique reste indicatif et ne decide pas du statut`() = runBlocking {
        val f = fixture()
        for (n in 7..13) f.completeDay("2026-09-%02d".format(n), 2300.0)
        f.apply(DeclareWeekInfo(week.start, weighIns = listOf(WeighIn(day("2026-09-08"), 72.0))))

        val t = f.engine.weeklyFigures(week).trajectory

        assertNull(t.trend)
        assertEquals(-700.0 * 7 / 7700, t.theoreticalKgPerWeek!!, 1e-9) // 3000 - 2300 = 700 kcal par jour
        assertEquals(TrajectoryStatus.TooEarly, t.status)
    }

    @Test
    fun `objectif atteint`() = runBlocking {
        val f = fixture()
        f.apply(DeclareWeekInfo(week.start, weighIns = listOf(WeighIn(day("2026-09-08"), 60.02))))
        assertEquals(TrajectoryStatus.Reached, f.engine.weeklyFigures(week).trajectory.status)
    }

    @Test
    fun `les pesees reprises de l ancien suivi sont utilisables, une pesee declaree du meme jour les remplace`() = runBlocking {
        val f = fixture()
        val json = """{"schema_version":1,"measurements":[{"day":"2026-09-01","kg":73.4},{"day":"2026-09-08","kg":72.9},{"day":"2026-09-09","kg":"oups"},{"day":"pas-une-date","kg":70}]}"""
        f.store.transaction { imports.insert(ImportedSource("weights", "weights", 1, "sha", json, ActionId(1))) }

        val before = f.engine.weeklyFigures(week).trajectory
        assertEquals(72.9, before.weekAverageKg!!, 1e-9)
        assertEquals(1, before.weighInCount)

        f.apply(DeclareWeekInfo(week.start, weighIns = listOf(WeighIn(day("2026-09-08"), 72.5))))
        assertEquals(72.5, f.engine.weeklyFigures(week).trajectory.weekAverageKg!!, 1e-9)
    }

    // ───── Aides ─────

    private fun expenditure(steps: Int, sessions: Int): Expenditure {
        val info = WeekInfo(
            weekStart = week.start, version = 1, app.trackly.core.model.RecordState.Active,
            stepsAverage = steps, sessions = listOf(SessionCount("musculation", sessions)),
            recordedAt = java.time.Instant.EPOCH, actionId = null,
        )
        return WeeklyReview.compute(week, emptyList(), info, WeightHistory.Empty).expenditure
    }
}
