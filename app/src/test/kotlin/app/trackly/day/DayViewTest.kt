package app.trackly.day

import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarOverview
import app.trackly.core.overview.DayOverview
import app.trackly.core.summary.DaySummary
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Présentation du bilan du moteur pour l'écran du jour. */
class DayViewTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun view(summary: DaySummary) = DayView.from(summary, today = monday)

    @Test
    fun `totaux et calories restantes viennent du bilan du moteur`() = runBlocking {
        val d = view(SampleData.engine().daySummary(monday))
        assertEquals(1460, d.consumedKcal)
        assertEquals(MacroAmount(96), d.protein)
        assertEquals(MacroAmount(168), d.carbs)
        assertEquals(MacroAmount(48), d.fat)
        assertEquals(Headline(kicker = "Il te reste", kcal = 1140, unit = "kcal restantes"), d.headline)
        assertEquals("Lundi 2 novembre", d.label)
        assertTrue(d.isToday)
    }

    @Test
    fun `au-dela de l objectif le bandeau annonce le depassement sans valeur negative`() {
        val d = view(SampleData.engine().let { runBlocking { it.daySummary(monday) } }).copy(consumedKcal = 2750)
        assertEquals(Headline(kicker = "Tu as dépassé de", kcal = 150, unit = "kcal en plus"), d.headline)
    }

    @Test
    fun `la barre des proteines vise l ideal, jamais le minimum`() = runBlocking {
        val engine = SampleData.emptyEngine()
        engine.execute(app.trackly.core.action.SetGoal(app.trackly.core.model.GoalTargets(2600.0, 140.0, 352.0, 70.0, proteinMinG = 112.0)))
        // L'accueil ne reçoit que l'idéal : aucun repère visuel du minimum (choix voulu).
        assertEquals(Goals(kcal = 2600, protein = 140, carbs = 352, fat = 70), view(engine.daySummary(monday)).goals)
    }

    @Test
    fun `sans objectif, le bandeau montre ce qui est consomme`() = runBlocking {
        val d = view(SampleData.emptyEngine().daySummary(monday))
        assertNull(d.goals)
        assertEquals(Headline(kicker = "Consommé", kcal = 0, unit = "kcal ce jour"), d.headline)
    }

    @Test
    fun `les moments sont toujours affiches dans l ordre de la journee`() = runBlocking {
        val slots = view(SampleData.engine().daySummary(monday)).slots
        assertEquals(listOf(Moment.Matin, Moment.Midi, Moment.Snack, Moment.Soir), slots.map { it.moment })
        assertEquals("Salade sauce A, poulet", slots[1].description)
        assertEquals(300, slots[2].kcal)
        assertNull(slots[3].kcal)
        assertNull(slots[3].description)
    }

    @Test
    fun `une macro inconnue est signalee comme incomplete, avec le total connu`() = runBlocking {
        val engine = SampleData.engine()
        val food = Food("Poulet du traiteur", Nutrition(kcal = 300.0, proteinG = 40.0, carbsG = 0.0, fatG = null))
        engine.execute(AddConsumptions(NewConsumption(food, Moment.Soir)))
        val d = view(engine.daySummary(monday))
        assertEquals(MacroAmount(48, complete = false), d.fat)
        assertEquals(MacroAmount(136), d.protein)
        assertFalse(d.fat.complete)
    }

    @Test
    fun `dates compactes de l en-tete`() {
        assertEquals("Dim. 1er nov.", DayLabels.compact(LocalDate.parse("2026-11-01")))
        assertEquals("Lun. 2 nov.", DayLabels.compact(LocalDate.parse("2026-11-02")))
        assertEquals("Ven. 4 sept.", DayLabels.compact(LocalDate.parse("2026-09-04")))
        assertEquals("Mer. 30 déc.", DayLabels.compact(LocalDate.parse("2026-12-30")))
        assertEquals("Ven. 1er mai", DayLabels.compact(LocalDate.parse("2026-05-01")))
    }

    @Test
    fun `aujourd hui et hier devant la date, rien pour les autres jours`() {
        assertEquals("Aujourd’hui", DayLabels.relative(monday, today = monday))
        assertEquals("Hier", DayLabels.relative(monday.minusDays(1), today = monday))
        assertNull(DayLabels.relative(monday.minusDays(2), today = monday))
        val d = view(runBlocking { SampleData.engine().daySummary(monday) })
        assertEquals("Aujourd’hui, lundi 2 novembre", d.spokenLabel)
        assertEquals("Lun. 2 nov.", d.shortLabel)
    }

    @Test
    fun `un jour passe parle a l imparfait`() = runBlocking {
        val sunday = DayView.from(SampleData.engine().daySummary(monday.minusDays(1)), today = monday)
        assertEquals("Hier", sunday.relative)
        assertEquals(Headline(kicker = "Il restait", kcal = 120, unit = "kcal"), sunday.headline)
        assertEquals("Hier, dimanche 1er novembre", sunday.spokenLabel)
    }

    @Test
    fun `un jour passe depasse dit tu avais depasse`() = runBlocking {
        val sunday = DayView.from(SampleData.engine().daySummary(monday.minusDays(1)), today = monday).copy(consumedKcal = 2750)
        assertEquals(Headline(kicker = "Tu avais dépassé de", kcal = 150, unit = "kcal en plus"), sunday.headline)
    }

    @Test
    fun `un jour passe sans rien est non suivi, sans chiffre ni pour l instant`() = runBlocking {
        val past = DayView.from(SampleData.engine().daySummary(monday.minusDays(3)), today = monday)
        assertTrue(past.untracked)
        assertEquals("Journée non suivie", past.headline.kicker)
        assertNull(past.headline.kcal)
        assertFalse(past.headline.unit.contains("pour l’instant"))
        // Aujourd'hui, une journée vide reste « en cours » : ce n'est pas une journée non suivie.
        val today = DayView.from(SampleData.emptyEngine().daySummary(monday), today = monday)
        assertFalse(today.untracked)
        assertEquals("Consommé", today.headline.kicker)
    }

    @Test
    fun `valeur basse - le total de la jauge se dit au moins`() = runBlocking {
        val d = view(SampleData.engine().daySummary(monday))
        assertFalse(d.consumedAtLeast)
    }

    @Test
    fun `quantite, hypotheses et produit lie arrivent dans la feuille du repas`() = runBlocking {
        val engine = SampleData.emptyEngine()
        val food = Food(
            "Riz basmati", Nutrition(260.0, 5.0, 56.0, 1.0),
            quantity = Quantity(200.0, "g"),
            assumptions = listOf("Poids supposé cuit", "Assimilé au riz blanc"),
            productId = "p-riz",
        )
        engine.execute(AddConsumptions(NewConsumption(food, Moment.Midi)))
        val meal = view(engine.daySummary(monday)).meals.single()
        assertEquals("200 g", meal.quantity)
        assertEquals(listOf("Poids supposé cuit", "Assimilé au riz blanc"), meal.assumptions)
        assertTrue(meal.productLinked)
        assertEquals(1, view(engine.daySummary(monday)).slots[1].consumptionIds.size)
    }

    @Test
    fun `quantites lisibles`() {
        assertEquals("250 g", formatQuantity(250.0, "g"))
        assertEquals("1,5 pièce", formatQuantity(1.5, "pièce"))
    }

    @Test
    fun `libelles des jours en francais`() {
        assertEquals("Dimanche 1er novembre", DayLabels.format(LocalDate.parse("2026-11-01")))
        assertEquals("Lundi 2 novembre", DayLabels.format(LocalDate.parse("2026-11-02")))
        assertEquals("mercredi 30 décembre", DayLabels.inSentence(LocalDate.parse("2026-12-30")))
    }

    // ───── Trait de moyenne ─────

    @Test
    fun `le trait est la moyenne ramenee a la cible, de 0 a 1`() = runBlocking {
        // Objectif du jeu d'essai : 2 600 kcal, 140 / 352 / 70 g.
        val overview = DayOverview(mapOf(Bar.Kcal to BarOverview(1300.0), Bar.Protein to BarOverview(70.0), Bar.Fat to BarOverview(500.0)))
        val d = DayView.from(SampleData.engine().daySummary(monday), today = monday, overview = overview)
        assertEquals(0.5f, d.averageMark(Bar.Kcal)!!, 1e-6f)
        assertEquals(0.5f, d.averageMark(Bar.Protein)!!, 1e-6f)
        // Une moyenne au-delà de la cible reste sur la barre, à son extrémité.
        assertEquals(1f, d.averageMark(Bar.Fat)!!, 0f)
        // Pas de moyenne pour cette barre : pas de trait.
        assertNull(d.averageMark(Bar.Carbs))
    }

    @Test
    fun `sans vue du jour ou sans objectif - aucun trait`() = runBlocking {
        val d = view(SampleData.engine().daySummary(monday))
        assertTrue(Bar.entries.all { d.averageMark(it) == null })
        val overview = DayOverview(Bar.entries.associateWith { BarOverview(100.0) })
        val noGoal = DayView.from(SampleData.emptyEngine().daySummary(monday), today = monday, overview = overview)
        assertTrue(Bar.entries.all { noGoal.averageMark(it) == null })
    }
}
