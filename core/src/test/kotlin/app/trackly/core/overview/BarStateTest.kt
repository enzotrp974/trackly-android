package app.trackly.core.overview

import app.trackly.core.day
import app.trackly.core.model.Consumption
import app.trackly.core.model.DailyEstimate
import app.trackly.core.model.DayTracking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * États d'une barre de l'accueil : normal, attention (jaune), dépassé (rouge). Journées construites à la main ;
 * « aujourd'hui » est le lundi 2 novembre 2026. Objectif : 2 300 kcal, 135 g de protéines (minimum 112 g), 280 g de glucides,
 * limite de 70 g de lipides.
 *
 * Jour habituel (celui de l'historique) : matin 500 kcal / 30 / 70 / 10, midi 700 / 40 / 80 / 20, snack 200 / 5 / 30 / 5,
 * soir 800 / 50 / 100 / 25 — soit 2 200 kcal, 125 g de protéines, 60 g de lipides.
 */
class BarStateTest {
    private val goal = GoalTargets(2300.0, 135.0, 280.0, 70.0, proteinMinG = 112.0)

    private data class Meal(val moment: Moment, val kcal: Double, val p: Double? = 10.0, val c: Double? = 20.0, val f: Double? = 5.0)

    private fun tracking(iso: String, complete: Boolean) = DayTracking(
        day = day(iso),
        version = 1,
        state = RecordState.Active,
        trackingComplete = complete,
        recordedAt = zurich("${iso}T22:00"),
        actionId = null,
    )

    private fun summary(
        iso: String,
        meals: List<Meal>,
        goal: GoalTargets? = this.goal,
        estimates: List<DailyEstimate> = emptyList(),
        declaredIncomplete: Boolean = false,
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
        val flags = if (declaredIncomplete) listOf(tracking(iso, complete = false)) else emptyList()
        return DaySummary.of(d, consumptions, goals, estimates, flags)
    }

    private val usualDay = listOf(
        Meal(Moment.Matin, 500.0, 30.0, 70.0, 10.0),
        Meal(Moment.Midi, 700.0, 40.0, 80.0, 20.0),
        Meal(Moment.Snack, 200.0, 5.0, 30.0, 5.0),
        Meal(Moment.Soir, 800.0, 50.0, 100.0, 25.0),
    )
    private val usualDays = listOf("2026-10-30", "2026-10-31", "2026-11-01").associateWith { usualDay }

    private fun history(days: Map<String, List<Meal>> = usualDays) = days.map { (iso, meals) -> summary(iso, meals) }

    private fun overview(todayMeals: List<Meal>, days: Map<String, List<Meal>> = usualDays, goal: GoalTargets? = this.goal) =
        DayOverviews.of(summary("2026-11-02", todayMeals, goal), history(days))

    private fun DayOverview.states() = Bar.entries.associateWith { this[it].state }

    private val allNormal = Bar.entries.associateWith { BarState.Normal }

    // ───── Journée vide, rien à dire ─────

    @Test
    fun `aucun repas note - toutes les barres restent normales`() {
        assertEquals(allNormal, overview(emptyList()).states())
    }

    @Test
    fun `sans objectif - toutes les barres restent normales`() {
        assertEquals(allNormal, overview(listOf(Meal(Moment.Matin, 5000.0)), goal = null).states())
    }

    // ───── Calories et lipides : attention puis dépassé ─────

    @Test
    fun `les repas restants habituels feraient depasser les lipides - jaune`() {
        // Tablette de chocolat en snack : 65 g de lipides sur 70, et le soir habituel en ajoute 25.
        val today = listOf(
            Meal(Moment.Matin, 500.0, f = 10.0),
            Meal(Moment.Midi, 700.0, f = 20.0),
            Meal(Moment.Snack, 540.0, f = 35.0),
        )
        val o = overview(today)
        assertEquals(BarState.Warn, o[Bar.Fat].state)
    }

    @Test
    fun `la tablette de chocolat qui fait depasser la limite - rouge, meme avec un repas a venir`() {
        // 10 + 20 + 45 = 75 g de lipides sur 70 : dépassé alors qu'il reste le soir.
        val today = listOf(
            Meal(Moment.Matin, 500.0, f = 10.0),
            Meal(Moment.Midi, 700.0, f = 20.0),
            Meal(Moment.Snack, 540.0, f = 45.0),
        )
        assertEquals(BarState.Bad, overview(today)[Bar.Fat].state)
    }

    @Test
    fun `lipides sous la limite et assez de marge pour le reste habituel - normal`() {
        val today = listOf(Meal(Moment.Matin, 500.0, f = 10.0), Meal(Moment.Midi, 700.0, f = 20.0))
        // 30 + (5 + 25) = 60 sur 70.
        assertEquals(BarState.Normal, overview(today)[Bar.Fat].state)
    }

    @Test
    fun `les calories suivent la meme regle - jaune puis rouge`() {
        // 1 900 + (snack 200 + soir 800) = 2 900 sur 2 300.
        assertEquals(BarState.Warn, overview(listOf(Meal(Moment.Matin, 900.0), Meal(Moment.Midi, 1000.0))).states()[Bar.Kcal])
        // 2 400 déjà : dépassé.
        assertEquals(BarState.Bad, overview(listOf(Meal(Moment.Matin, 1200.0), Meal(Moment.Midi, 1200.0))).states()[Bar.Kcal])
        // 1 200 + (200 + 800) = 2 200 : rien à signaler.
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 500.0), Meal(Moment.Midi, 700.0))).states()[Bar.Kcal])
    }

    @Test
    fun `rien ne reste a manger - jaune impossible, rouge seulement si depasse`() {
        val soir = Meal(Moment.Soir, 0.0)
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 2200.0), soir)).states()[Bar.Kcal])
        assertEquals(BarState.Bad, overview(listOf(Meal(Moment.Matin, 2400.0), soir)).states()[Bar.Kcal])
    }

    @Test
    fun `le rouge suit ce que l ecran affiche - arrondi au gramme`() {
        // 70,4 g s'affiche « 70 / 70 g » (pas de « +N g »), 70,6 s'affiche « 71 » donc « +1 g ».
        val soir = Meal(Moment.Soir, 0.0, f = 0.0)
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 100.0, f = 70.4), soir))[Bar.Fat].state)
        assertEquals(BarState.Bad, overview(listOf(Meal(Moment.Matin, 100.0, f = 70.6), soir))[Bar.Fat].state)
    }

    @Test
    fun `le seuil du jaune est une marge nommee autour de la limite`() {
        // Reste habituel des lipides après le matin : midi 20 + snack 5 + soir 25 = 50. Limite 70.
        fun fatState(consumed: Double) = overview(listOf(Meal(Moment.Matin, 100.0, f = consumed)))[Bar.Fat].state
        val exactlyAtThreshold = 70.0 * (1 + DayOverviews.WARN_MARGIN) - 50.0
        assertEquals(BarState.Normal, fatState(exactlyAtThreshold - 0.01))
        assertEquals(BarState.Warn, fatState(exactlyAtThreshold + 0.5))
    }

    // ───── Glucides : jamais de couleur ─────

    @Test
    fun `les glucides ne changent jamais de couleur`() {
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 100.0, c = 500.0)))[Bar.Carbs].state)
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 100.0, c = 500.0), Meal(Moment.Soir, 100.0, c = 500.0)))[Bar.Carbs].state)
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 100.0, c = 100.0)))[Bar.Carbs].state)
    }

    // ───── Protéines : le minimum, jamais l'idéal ─────

    @Test
    fun `les repas restants habituels ne suffisent pas pour le minimum - jaune`() {
        // 5 g le matin + 40 + 5 + 50 habituels = 100 g < 112 g (avec la marge : 106,4).
        assertEquals(BarState.Warn, overview(listOf(Meal(Moment.Matin, 300.0, p = 5.0)))[Bar.Protein].state)
    }

    @Test
    fun `le minimum est a portee des repas habituels - normal`() {
        // 30 + 95 = 125 g.
        assertEquals(BarState.Normal, overview(listOf(Meal(Moment.Matin, 300.0, p = 30.0)))[Bar.Protein].state)
    }

    @Test
    fun `le minimum deja atteint - normal, meme sous l ideal`() {
        // 120 g : au-dessus du minimum (112), sous l'idéal (135), soir noté.
        val today = listOf(Meal(Moment.Matin, 300.0, p = 60.0), Meal(Moment.Soir, 300.0, p = 60.0))
        assertEquals(BarState.Normal, overview(today)[Bar.Protein].state)
    }

    @Test
    fun `le soir est note et le minimum n est pas atteint - rouge`() {
        val today = listOf(Meal(Moment.Matin, 300.0, p = 50.0), Meal(Moment.Soir, 300.0, p = 50.0))
        assertEquals(BarState.Bad, overview(today)[Bar.Protein].state)
    }

    @Test
    fun `le minimum atteint pile au soir - pas de rouge`() {
        val today = listOf(Meal(Moment.Matin, 300.0, p = 56.0), Meal(Moment.Soir, 300.0, p = 56.0))
        assertEquals(BarState.Normal, overview(today)[Bar.Protein].state)
    }

    @Test
    fun `sans soir note, jamais de rouge sur les proteines, meme tres bas`() {
        val today = listOf(Meal(Moment.Matin, 300.0, p = 1.0), Meal(Moment.Midi, 300.0, p = 1.0), Meal(Moment.Snack, 300.0, p = 1.0))
        assertTrue(overview(today)[Bar.Protein].state != BarState.Bad)
    }

    @Test
    fun `sans minimum enregistre, les proteines ne changent pas de couleur - l ideal ne sert jamais`() {
        val noMinimum = GoalTargets(2300.0, 135.0, 280.0, 70.0)
        val today = listOf(Meal(Moment.Matin, 300.0, p = 5.0), Meal(Moment.Soir, 300.0, p = 5.0))
        assertEquals(BarState.Normal, overview(today, goal = noMinimum)[Bar.Protein].state)
    }

    @Test
    fun `une proteine inconnue dans un repas - on ne peut pas dire qu il manque des proteines`() {
        val today = listOf(Meal(Moment.Matin, 300.0, p = 5.0), Meal(Moment.Soir, 300.0, p = null))
        assertEquals(BarState.Normal, overview(today)[Bar.Protein].state)
    }

    // ───── Pas assez de données ─────

    @Test
    fun `moins de 3 jours d historique - pas de jaune`() {
        val twoDays = usualDays.entries.take(2).associate { it.key to it.value }
        val today = listOf(Meal(Moment.Matin, 900.0, p = 1.0, f = 60.0), Meal(Moment.Midi, 1000.0, p = 1.0, f = 5.0))
        val states = overview(today, twoDays).states()
        assertEquals(BarState.Normal, states[Bar.Kcal])
        assertEquals(BarState.Normal, states[Bar.Fat])
        assertEquals(BarState.Normal, states[Bar.Protein])
    }

    @Test
    fun `moins de 3 jours d historique - le rouge reste possible, il ne depend pas des habitudes`() {
        val today = listOf(Meal(Moment.Matin, 2400.0, f = 80.0))
        val states = overview(today, emptyMap()).states()
        assertEquals(BarState.Bad, states[Bar.Kcal])
        assertEquals(BarState.Bad, states[Bar.Fat])
    }

    @Test
    fun `sans historique, le rouge des proteines au soir reste possible`() {
        val today = listOf(Meal(Moment.Soir, 300.0, p = 20.0))
        assertEquals(BarState.Bad, overview(today, emptyMap())[Bar.Protein].state)
    }

    @Test
    fun `une valeur inconnue dans les repas restants ecarte ce jour pour cette barre seulement`() {
        val unknownFatInTheEvening = usualDay.map { if (it.moment == Moment.Soir) it.copy(f = null) else it }
        val days = mapOf(
            "2026-10-30" to unknownFatInTheEvening,
            "2026-10-31" to unknownFatInTheEvening,
            "2026-11-01" to usualDay,
        )
        val today = listOf(Meal(Moment.Matin, 900.0, f = 60.0), Meal(Moment.Midi, 1000.0, f = 5.0))
        val states = overview(today, days).states()
        // Lipides : un seul jour sûr, pas de prévision. Calories : trois jours sûrs, 1 900 + 800 > 2 300.
        assertEquals(BarState.Normal, states[Bar.Fat])
        assertEquals(BarState.Warn, states[Bar.Kcal])
    }

    // ───── Jours déclarés incomplets ─────

    @Test
    fun `un jour declare incomplet est ecarte des repas restants habituels`() {
        // Trois jours habituels (reste après le midi : 1 000 kcal) et un jour dont la fin a été oubliée (déclaré incomplet, reste 0).
        val forgotten = usualDay.filter { it.moment == Moment.Matin || it.moment == Moment.Midi }
        val previous = history() + summary("2026-10-29", forgotten, declaredIncomplete = true)
        val today = summary("2026-11-02", listOf(Meal(Moment.Matin, 700.0), Meal(Moment.Midi, 800.0)))
        // Sans lui : 1 500 + 1 000 = 2 500 > 2 300 + marge. Avec lui, le reste moyen tomberait à 750 et le jaune disparaîtrait.
        assertEquals(BarState.Warn, DayOverviews.of(today, previous)[Bar.Kcal].state)
    }

    @Test
    fun `sans declaration, un jour sans soir compte comme un jour sobre`() {
        // Contre-épreuve : les mêmes trois jours sans soir, non déclarés incomplets, comptent. Reste moyen après le midi :
        // (3 x 1 000 + 3 x 200) / 6 = 600 ; 1 700 + 600 = 2 300, pas de jaune.
        val noEvening = usualDay.filter { it.moment != Moment.Soir }
        val days = listOf("2026-10-27", "2026-10-28", "2026-10-29")
        val today = summary("2026-11-02", listOf(Meal(Moment.Matin, 800.0), Meal(Moment.Midi, 900.0)))
        val counted = history() + days.map { summary(it, noEvening) }
        assertEquals(BarState.Normal, DayOverviews.of(today, counted)[Bar.Kcal].state)
        // Déclarés incomplets, ils sortent : reste 1 000, 1 700 + 1 000 = 2 700 -> jaune.
        val declared = history() + days.map { summary(it, noEvening, declaredIncomplete = true) }
        assertEquals(BarState.Warn, DayOverviews.of(today, declared)[Bar.Kcal].state)
    }

    @Test
    fun `les jours declares incomplets comptent pour le minimum de 3 jours - ils ne le completent pas`() {
        val previous = history(usualDays.entries.take(2).associate { it.key to it.value }) +
            summary("2026-11-01", usualDay, declaredIncomplete = true)
        val today = summary("2026-11-02", listOf(Meal(Moment.Matin, 900.0), Meal(Moment.Midi, 1000.0)))
        assertEquals(BarState.Normal, DayOverviews.of(today, previous)[Bar.Kcal].state)
    }

    @Test
    fun `un jour declare incomplet est aussi ecarte du trait de moyenne`() {
        val previous = history(usualDays.entries.take(2).associate { it.key to it.value }) +
            summary("2026-11-01", usualDay, declaredIncomplete = true)
        val today = summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0)))
        // Deux jours complets seulement : aucun trait.
        assertNull(DayOverviews.of(today, previous)[Bar.Kcal].average)
        // Avec un troisième jour complet, le trait revient, calculé sans le jour déclaré incomplet.
        val more = previous + summary("2026-10-29", listOf(Meal(Moment.Matin, 800.0), Meal(Moment.Soir, 100.0)))
        assertEquals(500.0 * 2 / 3 + 800.0 / 3, DayOverviews.of(today, more)[Bar.Kcal].average!!, 1e-9)
    }

    @Test
    fun `aujourd hui declare incomplet - pas de rouge sur les proteines, le rouge des limites reste`() {
        val meals = listOf(Meal(Moment.Matin, 2400.0, p = 20.0, f = 80.0), Meal(Moment.Soir, 100.0, p = 20.0))
        val o = DayOverviews.of(summary("2026-11-02", meals, declaredIncomplete = true), history())
        assertEquals(BarState.Normal, o[Bar.Protein].state)
        assertEquals(BarState.Bad, o[Bar.Kcal].state)
        assertEquals(BarState.Bad, o[Bar.Fat].state)
    }

    // ───── Jour passé, journée estimée ─────

    @Test
    fun `un jour passe n a jamais de jaune de prevision, seulement le rouge`() {
        val today = day("2026-11-02")
        val warnToday = listOf(Meal(Moment.Matin, 900.0), Meal(Moment.Midi, 1000.0))
        val pastWarn = summary("2026-11-01", warnToday)
        assertEquals(allNormal, DayOverviews.of(pastWarn, history(), today).states())
        val pastOver = summary("2026-11-01", listOf(Meal(Moment.Matin, 2400.0, f = 80.0), Meal(Moment.Soir, 100.0, p = 20.0)))
        val states = DayOverviews.of(pastOver, history(), today).states()
        assertEquals(BarState.Bad, states[Bar.Kcal])
        assertEquals(BarState.Bad, states[Bar.Fat])
        assertEquals(BarState.Bad, states[Bar.Protein])
        assertEquals(BarState.Normal, states[Bar.Carbs])
    }

    @Test
    fun `un jour passe n a pas de trait de moyenne`() {
        val past = summary("2026-11-01", listOf(Meal(Moment.Matin, 300.0)))
        assertEquals(Bar.entries.map { null }, Bar.entries.map { DayOverviews.of(past, history(), day("2026-11-02"))[it].average })
    }

    @Test
    fun `une journee estimee ou bornee - aucun etat, comme pour le trait`() {
        for (kind in EstimateKind.entries) {
            val estimated = summary(
                "2026-11-02",
                listOf(Meal(Moment.Matin, 5000.0, f = 200.0), Meal(Moment.Soir, 100.0, p = 1.0)),
                estimates = listOf(estimate("2026-11-02", kind)),
            )
            assertEquals(allNormal, DayOverviews.of(estimated, history()).states())
        }
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

    // ───── Pour la carte du chat ─────

    @Test
    fun `les barres depassees d un jour, sans historique - la carte du chat les fige`() {
        val over = summary("2026-11-02", listOf(Meal(Moment.Matin, 2500.0, p = 20.0, c = 400.0, f = 80.0), Meal(Moment.Soir, 100.0, p = 20.0)))
        assertEquals(setOf(Bar.Kcal, Bar.Protein, Bar.Fat), DayOverviews.exceeded(over))
        val fine = summary("2026-11-02", listOf(Meal(Moment.Matin, 300.0)))
        assertEquals(emptySet<Bar>(), DayOverviews.exceeded(fine))
    }

    @Test
    fun `la carte du chat ne fige jamais de jaune`() {
        val warnOnly = summary("2026-11-02", listOf(Meal(Moment.Matin, 900.0), Meal(Moment.Midi, 1000.0)))
        assertEquals(emptySet<Bar>(), DayOverviews.exceeded(warnOnly))
    }

    @Test
    fun `un etat n ajoute aucun trait et un trait n ajoute aucun etat`() {
        val o = overview(listOf(Meal(Moment.Matin, 300.0)))
        assertNotNull(o[Bar.Kcal].average)
        assertEquals(BarState.Normal, o[Bar.Kcal].state)
    }
}
