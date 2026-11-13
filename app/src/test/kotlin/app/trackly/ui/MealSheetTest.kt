package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.model.RecordState
import app.trackly.day.DayView
import app.trackly.testing.SampleData
import app.trackly.ui.day.MealActions
import app.trackly.ui.day.MealTags
import app.trackly.ui.day.correctionDraft
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Feuille d'un repas : détail par aliment, « Supprimer » annulable, « Corriger ». */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class MealSheetTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    /**
     * Le jeu d'essai, avec un midi à deux aliments détaillés (quantité, hypothèses, produit lié, macro inconnue).
     * Les identifiants sont imposés : à heure égale, le moteur range par identifiant, donc un UUID aléatoire
     * donnait un ordre variable (cause de l'instabilité de ce test et des captures de la feuille).
     */
    private fun engineDetaille(): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            engine.execute(
                AddConsumptions(
                    listOf(
                        NewConsumption(
                            Food(
                                "Riz basmati", Nutrition(260.0, 5.0, 56.0, 1.0),
                                quantity = Quantity(200.0, "g"),
                                assumptions = listOf("Poids supposé cuit", "Assimilé au riz blanc de ta fiche"),
                                productId = "p-riz",
                            ),
                            Moment.Midi,
                            entryId = EntryId("a-riz"),
                        ),
                        NewConsumption(Food("Poulet rôti", Nutrition(240.0, 40.0, 0.0, null), quantity = Quantity(150.0, "g")), Moment.Midi, entryId = EntryId("b-poulet")),
                    ),
                ),
            )
            engine.execute(AddConsumptions(NewConsumption(Food("Pomme", Nutrition(80.0, 0.0, 21.0, 0.0)), Moment.Snack)))
        }
    }

    private fun ouvrir(engine: TracklyEngine) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
    }

    @Test
    fun `toucher une ligne de repas ouvre la feuille avec chaque aliment`() {
        ouvrir(engineDetaille())
        compose.onNodeWithTag(MealTags.SHEET).assertDoesNotExist()

        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MealTags.SHEET).assertIsDisplayed()
        compose.onNodeWithText("Riz basmati").assertIsDisplayed()
        compose.onNodeWithText("200 g").assertIsDisplayed()
        compose.onNodeWithText("260 kcal").assertIsDisplayed()
        compose.onNodeWithText("P 5 g · G 56 g · L 1 g").assertIsDisplayed()
        // Hypothèses : une par ligne.
        compose.onNodeWithText("HYPOTHÈSES").assertIsDisplayed()
        compose.onNodeWithText("Poids supposé cuit").assertIsDisplayed()
        compose.onNodeWithText("Assimilé au riz blanc de ta fiche").assertIsDisplayed()
        compose.onNodeWithText("Produit mémorisé").assertIsDisplayed()
        // Deuxième aliment : une macro inconnue n'est jamais affichée comme zéro.
        compose.onNodeWithText("Poulet rôti").assertIsDisplayed()
        compose.onNodeWithText("P 40 g · G 0 g · L inconnu").assertIsDisplayed()
        compose.onNodeWithText("500 kcal").assertIsDisplayed()
    }

    @Test
    fun `un moment vide n a pas de ligne a ouvrir`() {
        ouvrir(engineDetaille())
        compose.onNodeWithText("Matin").assertDoesNotExist()
        compose.onNodeWithTag(MealTags.SHEET).assertDoesNotExist()
    }

    @Test
    fun `supprimer retire le repas, annuler le remet`() {
        val engine = engineDetaille()
        ouvrir(engine)
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MealTags.DELETE).performClick()
        compose.waitForIdle()

        // Feuille fermée, total du jour recalculé (il ne reste que la pomme), bandeau « Annuler » visible.
        compose.onNodeWithTag(MealTags.SHEET).assertDoesNotExist()
        assertEquals(80.0, runBlocking { engine.daySummary(monday) }.totals.kcal, 0.0)
        compose.onNodeWithTag(MealTags.UNDO_BANNER).assertIsDisplayed()
        compose.onNodeWithText("Midi supprimé").assertIsDisplayed()
        compose.onNodeWithTag(MealTags.UNDO).assertIsDisplayed()

        compose.onNodeWithTag(MealTags.UNDO).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MealTags.UNDO_BANNER).assertDoesNotExist()
        assertEquals(580.0, runBlocking { engine.daySummary(monday) }.totals.kcal, 0.0)
        // L'écran suit le moteur par un flux : on attend la ligne du midi, rétablie à l'écran.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Riz basmati, Poulet rôti").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Riz basmati, Poulet rôti").assertIsDisplayed()
        // Rien n'a été effacé : trois versions (active, supprimée, rétablie).
        val riz = runBlocking { engine.daySummary(monday) }.consumptions.first { it.food.label == "Riz basmati" }.entryId
        assertEquals(listOf(RecordState.Active, RecordState.Deleted, RecordState.Active), runBlocking { engine.history(riz) }.map { it.state })
    }

    @Test
    fun `corriger transmet les identifiants des aliments et ouvre le chat`() {
        val engine = engineDetaille()
        val summaries = runBlocking { engine.observeDays(monday.minusDays(1), monday).first() }
        val days = summaries.map { DayView.from(it, today = monday) }
        var corrected: List<String>? = null
        var draft: String? = null
        compose.setContent {
            TracklyApp(
                motion = MotionPolicy(reduced = true),
                days = days,
                meals = MealActions(onDelete = { _, _ -> }, onCorrect = { ids, text -> corrected = ids; draft = text }),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MealTags.CORRECT).performClick()
        compose.waitForIdle()

        val expected = days.last().slots[1].consumptionIds
        assertEquals(2, expected.size)
        assertEquals(expected, corrected)
        // Brouillon prêt à l'emploi pour le chat.
        assertEquals("Corrige le midi (Riz basmati, Poulet rôti) : ", draft)
        compose.onNodeWithText("Dis-moi ce que tu as mangé.").assertIsDisplayed()
        compose.onNodeWithTag(MealTags.SHEET).assertDoesNotExist()
    }

    @Test
    fun `le brouillon de correction nomme le moment, les aliments et le jour s il est passe`() {
        val engine = SampleData.engine()
        val summaries = runBlocking { engine.observeDays(monday.minusDays(7), monday).first() }
        val today = DayView.from(summaries.last(), today = monday)
        val yesterday = DayView.from(summaries[summaries.lastIndex - 1], today = monday)
        val older = DayView.from(summaries.first(), today = monday)
        assertEquals("Corrige le midi (Salade sauce A, poulet) : ", correctionDraft(today, today.slots[1]))
        assertEquals("Corrige le soir d’hier (Pâtes complètes, bolognaise, salade verte) : ", correctionDraft(yesterday, yesterday.slots[3]))
        assertEquals("Corrige le matin du lundi 26 octobre : ", correctionDraft(older, older.slots[0]))
    }

    @Test
    fun `feuille ouverte, le jour dessous sort de l arbre d accessibilite`() {
        ouvrir(engineDetaille())
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jour précédent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Écrire à Trackly").assertDoesNotExist()
        compose.onNodeWithContentDescription("Corriger le midi").assertIsDisplayed()
    }

    @Test
    fun `toucher hors de la feuille la ferme`() {
        ouvrir(engineDetaille())
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Fermer le détail du repas").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MealTags.SHEET).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("Midi").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `un jour passe peut aussi etre ouvert et supprime`() {
        val engine = SampleData.engine()
        ouvrir(engine)
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Midi").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Riz, saumon, brocolis, huile d’olive").assertIsDisplayed()
        compose.onNodeWithTag(MealTags.DELETE).performClick()
        compose.waitForIdle()
        assertEquals(2480.0 - 820.0, runBlocking { engine.daySummary(LocalDate.parse("2026-11-01")) }.totals.kcal, 0.0)
        compose.onNodeWithText("Midi supprimé").assertIsDisplayed()
    }
}
