package app.trackly.core.overview

import app.trackly.core.day
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.EntryId
import app.trackly.core.model.EstimateKind
import app.trackly.core.model.Food
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Origin
import app.trackly.core.model.RecordState
import app.trackly.core.summary.DaySummary
import app.trackly.core.zurich
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Vue du jour : pour chaque barre, la position du trait de moyenne. Journées construites à la main ;
 * « aujourd'hui » est le lundi 2 novembre 2026 (`2026-11-02`).
 */
class DayOverviewTest {
    private val goal = GoalTargets(2300.0, 135.0, 280.0, 70.0)

    /** Un repas : moment, kcal, puis protéines, glucides et lipides (`null` : inconnu). */
    private data class Meal(val moment: Moment, val kcal: Double, val p: Double? = 10.0, val c: Double? = 20.0, val f: Double? = 5.0)

    private fun summary(
        iso: String,
        meals: List<Meal>,
        goal: GoalTargets? = this.goal,
        estimates: List<DailyEstimate> = emptyList(),
    ): DaySummary {
        val d = day(iso)
        val consumptions = meals.map { m ->
            Consumption(
                entryId = EntryId.random(),
                version = 1,
                state = RecordState.Active,
                day = d,
                consumedAt = zurich("${iso}T12:00"),
                moment = m.moment,
                food = Food("repas", Nutrition(m.kcal, m.p, m.c, m.f)),
                origin = Origin.App,
                recordedAt = zurich("${iso}T12:00"),
                actionId = null,
            )
        }
        val goals = listOfNotNull(goal?.let { Goal(day("2026-10-01"), 1, RecordState.Active, it, zurich("2026-10-01T08:00"), null) })
        return DaySummary.of(d, consumptions, goals, estimates)
    }

    private fun estimate(iso: String, kind: EstimateKind) = DailyEstimate(
        assessmentId = "estimation-$iso",
        version = 1,
        state = RecordState.Active,
        day = day(iso),
        kind = kind,
        kcal = if (kind == EstimateKind.Estimate) 2500.0 else null,
        kcalLow = 2200.0,
        kcalHigh = if (kind == EstimateKind.Estimate) 2800.0 else null,
        description = "journée non suivie",
        recordedAt = zurich("${iso}T22:00"),
        actionId = null,
    )

    private fun overview(
        todayMeals: List<Meal>,
        history: Map<String, List<Meal>>,
        goal: GoalTargets? = this.goal,
    ): DayOverview {
        val previous = history.map { (iso, meals) -> summary(iso, meals) }
        return DayOverviews.of(summary("2026-11-02", todayMeals, goal), previous)
    }

    private fun DayOverview.averages() = Bar.entries.map { this[it].average }

    private val noTrait = listOf(null, null, null, null)

    /** Trois jours pleins : matin 500 / 30 / 70 / 10, midi 700 / 40 / 80 / 20. */
    private val threeDays = listOf("2026-10-30", "2026-10-31", "2026-11-01").associateWith {
        listOf(Meal(Moment.Matin, 500.0, 30.0, 70.0, 10.0), Meal(Moment.Midi, 700.0, 40.0, 80.0, 20.0))
    }

    @Test
    fun `aucun repas note aujourd hui - aucun trait`() {
        assertEquals(noTrait, overview(emptyList(), threeDays).averages())
    }

    @Test
    fun `apres le petit dejeuner - moyenne du matin des jours precedents`() {
        val o = overview(listOf(Meal(Moment.Matin, 300.0)), threeDays)
        assertEquals(500.0, o[Bar.Kcal].average!!, 1e-9)
        assertEquals(30.0, o[Bar.Protein].average!!, 1e-9)
        assertEquals(70.0, o[Bar.Carbs].average!!, 1e-9)
        assertEquals(10.0, o[Bar.Fat].average!!, 1e-9)
    }

    @Test
    fun `apres le midi - moyenne cumulee jusqu au midi`() {
        val o = overview(listOf(Meal(Moment.Matin, 300.0), Meal(Moment.Midi, 900.0)), threeDays)
        assertEquals(1200.0, o[Bar.Kcal].average!!, 1e-9)
        assertEquals(70.0, o[Bar.Protein].average!!, 1e-9)
        assertEquals(150.0, o[Bar.Carbs].average!!, 1e-9)
        assertEquals(30.0, o[Bar.Fat].average!!, 1e-9)
    }

    @Test
    fun `le dernier moment note decide, meme si un moment precedent manque`() {
        // Seul un midi aujourd'hui : on compare au cumul matin + midi, pas au midi seul.
        assertEquals(1200.0, overview(listOf(Meal(Moment.Midi, 900.0)), threeDays)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `un moment sans repas ce jour-la compte pour zero dans la moyenne`() {
        val history = mapOf(
            "2026-10-30" to listOf(Meal(Moment.Matin, 600.0), Meal(Moment.Soir, 800.0)),
            "2026-10-31" to listOf(Meal(Moment.Midi, 900.0), Meal(Moment.Soir, 700.0)),
            "2026-11-01" to listOf(Meal(Moment.Matin, 300.0), Meal(Moment.Midi, 600.0)),
        )
        // Cumul jusqu'au matin : 600, 0 (pas de petit-déjeuner ce jour-là), 300.
        assertEquals(300.0, overview(listOf(Meal(Moment.Matin, 1.0)), history)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `les repas apres le dernier moment note n entrent pas dans la moyenne`() {
        val history = threeDays.mapValues { it.value + Meal(Moment.Soir, 2000.0) }
        assertEquals(500.0, overview(listOf(Meal(Moment.Matin, 300.0)), history)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `moins de 3 jours de donnees - aucun trait`() {
        val twoDays = threeDays.entries.take(2).associate { it.key to it.value }
        assertEquals(noTrait, overview(listOf(Meal(Moment.Matin, 300.0)), twoDays).averages())
    }

    @Test
    fun `exactement 3 jours - un trait`() {
        assertEquals(500.0, overview(listOf(Meal(Moment.Matin, 300.0)), threeDays)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `un jour sans repas ne compte pas parmi les jours de donnees`() {
        // Deux jours notés et un jour vide : 2 jours de données seulement, donc pas de trait.
        val history = mapOf(
            "2026-10-30" to threeDays.getValue("2026-10-30"),
            "2026-10-31" to emptyList(),
            "2026-11-01" to threeDays.getValue("2026-11-01"),
        )
        assertEquals(noTrait, overview(listOf(Meal(Moment.Matin, 300.0)), history).averages())
    }

    @Test
    fun `un jour vide ne tire pas la moyenne vers zero`() {
        val history = threeDays + mapOf("2026-10-29" to listOf(Meal(Moment.Matin, 500.0), Meal(Moment.Midi, 700.0)), "2026-10-28" to emptyList())
        assertEquals(500.0, overview(listOf(Meal(Moment.Matin, 300.0)), history)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `seuls les 7 jours precedents comptent`() {
        // Le 25 octobre est à 8 jours : hors fenêtre.
        val old = mapOf("2026-10-25" to listOf(Meal(Moment.Matin, 5000.0)))
        assertEquals(500.0, overview(listOf(Meal(Moment.Matin, 300.0)), threeDays + old)[Bar.Kcal].average!!, 1e-9)
        // Le 26 octobre est le septième jour précédent : dans la fenêtre.
        val edge = mapOf("2026-10-26" to listOf(Meal(Moment.Matin, 2500.0)))
        assertEquals(1000.0, overview(listOf(Meal(Moment.Matin, 300.0)), threeDays + edge)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `aujourd hui n entre jamais dans sa propre moyenne`() {
        val previous = threeDays.map { (iso, meals) -> summary(iso, meals) }
        val now = summary("2026-11-02", listOf(Meal(Moment.Matin, 9000.0)))
        assertEquals(500.0, DayOverviews.of(now, previous + now)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `une macro inconnue un jour ecarte ce jour pour cette barre seulement`() {
        val history = threeDays + mapOf("2026-10-29" to listOf(Meal(Moment.Matin, 900.0, p = null, c = 90.0, f = 20.0)))
        val o = overview(listOf(Meal(Moment.Matin, 300.0)), history)
        // Les calories connues comptent sur 4 jours ; les protéines sur 3 seulement.
        assertEquals((3 * 500.0 + 900.0) / 4, o[Bar.Kcal].average!!, 1e-9)
        assertEquals(30.0, o[Bar.Protein].average!!, 1e-9)
        assertEquals((3 * 70.0 + 90.0) / 4, o[Bar.Carbs].average!!, 1e-9)
    }

    @Test
    fun `une macro connue sur moins de 3 jours - pas de trait pour elle, les autres barres gardent le leur`() {
        val unknown = listOf(Meal(Moment.Matin, 500.0, p = null, c = 70.0, f = 10.0), Meal(Moment.Midi, 700.0))
        val history = threeDays.mapValues { (iso, meals) -> if (iso == "2026-11-01") meals else unknown }
        val o = overview(listOf(Meal(Moment.Matin, 300.0)), history)
        assertNull(o[Bar.Protein].average)
        assertEquals(500.0, o[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `une macro inconnue apres le dernier moment note ne gene pas`() {
        val history = threeDays.mapValues { it.value + Meal(Moment.Soir, 400.0, p = null) }
        assertEquals(30.0, overview(listOf(Meal(Moment.Matin, 300.0)), history)[Bar.Protein].average!!, 1e-9)
    }

    @Test
    fun `une barre sans objectif n a pas de trait`() {
        val o = overview(listOf(Meal(Moment.Matin, 300.0)), threeDays, goal = GoalTargets(2300.0, proteinG = 135.0))
        assertEquals(500.0, o[Bar.Kcal].average!!, 1e-9)
        assertEquals(30.0, o[Bar.Protein].average!!, 1e-9)
        assertNull(o[Bar.Carbs].average)
        assertNull(o[Bar.Fat].average)
    }

    @Test
    fun `sans objectif - aucun trait`() {
        assertEquals(noTrait, overview(listOf(Meal(Moment.Matin, 300.0)), threeDays, goal = null).averages())
    }

    @Test
    fun `un jour estime ou borne n entre pas dans la moyenne`() {
        val previous = listOf(
            summary("2026-10-30", threeDays.getValue("2026-10-30")),
            summary("2026-10-31", threeDays.getValue("2026-10-31")),
            summary("2026-11-01", threeDays.getValue("2026-11-01"), estimates = listOf(estimate("2026-11-01", EstimateKind.Estimate))),
        )
        // Deux jours détaillés seulement : pas de trait.
        assertEquals(noTrait, DayOverviews.of(summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0))), previous).averages())

        val bounded = previous.dropLast(1) + summary("2026-11-01", threeDays.getValue("2026-11-01"), estimates = listOf(estimate("2026-11-01", EstimateKind.LowerBound)))
        assertEquals(noTrait, DayOverviews.of(summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0))), bounded).averages())
    }

    @Test
    fun `un jour dont l estimation a ete supprimee n entre pas dans la moyenne`() {
        val deleted = estimate("2026-11-01", EstimateKind.Estimate).copy(state = RecordState.Deleted)
        val previous = listOf(
            summary("2026-10-30", threeDays.getValue("2026-10-30")),
            summary("2026-10-31", threeDays.getValue("2026-10-31")),
            summary("2026-11-01", threeDays.getValue("2026-11-01"), estimates = listOf(deleted)),
        )
        // Énergie inconnue (estimation supprimée sans détail déclaré complet) : 2 jours détaillés seulement.
        assertEquals(noTrait, DayOverviews.of(summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0))), previous).averages())
    }

    @Test
    fun `une moyenne nulle ne dessine pas de trait`() {
        // Jamais de petit-déjeuner : la moyenne du matin est 0 ; seules les barres à moyenne positive gardent leur trait.
        val history = listOf("2026-10-30", "2026-10-31", "2026-11-01").associateWith { listOf(Meal(Moment.Soir, 800.0, 0.0, 0.0, 0.0)) }
        assertEquals(noTrait, overview(listOf(Meal(Moment.Matin, 300.0)), history).averages())
    }

    @Test
    fun `une journee d aujourd hui estimee - aucun trait`() {
        val previous = threeDays.map { (iso, meals) -> summary(iso, meals) }
        for (kind in EstimateKind.entries) {
            val now = summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0)), estimates = listOf(estimate("2026-11-02", kind)))
            assertEquals(noTrait, DayOverviews.of(now, previous).averages())
        }
    }

    @Test
    fun `une consommation supprimee ne compte pas`() {
        val d = day("2026-11-01")
        val kept = summary("2026-11-01", threeDays.getValue("2026-11-01"))
        val gone = summary("2026-11-01", listOf(Meal(Moment.Matin, 4000.0))).consumptions.single()
        val deleted = gone.copy(version = 2, state = RecordState.Deleted)
        val withDeleted = DaySummary.of(d, kept.consumptions + gone + deleted, listOf(Goal(day("2026-10-01"), 1, RecordState.Active, goal, zurich("2026-10-01T08:00"), null)))
        val previous = listOf(summary("2026-10-30", threeDays.getValue("2026-10-30")), summary("2026-10-31", threeDays.getValue("2026-10-31")), withDeleted)
        assertEquals(500.0, DayOverviews.of(summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0))), previous)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `le resultat ne depend pas de l ordre des journees fournies`() {
        val previous = threeDays.map { (iso, meals) -> summary(iso, meals) }
        val now = summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0)))
        assertEquals(DayOverviews.of(now, previous), DayOverviews.of(now, previous.reversed()))
    }

    @Test
    fun `une vue vide ne porte aucun trait`() {
        assertEquals(noTrait, DayOverview.None.averages())
    }
}
