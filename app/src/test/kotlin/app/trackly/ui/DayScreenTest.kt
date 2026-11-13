package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.day.DayView
import app.trackly.day.formatKcal
import app.trackly.testing.SampleData
import app.trackly.ui.day.CalendarTags
import app.trackly.ui.day.DayTags
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Parcours de l'écran du jour sur le moteur (stockage en mémoire), mouvements réduits. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DayScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun ouvrir(engine: TracklyEngine = SampleData.engine()) {
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) }
        compose.waitForIdle()
    }

    @Test
    fun `ouvre sur le bilan du jour calcule par le moteur`() {
        ouvrir()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
        compose.onNodeWithText("DONNÉES D’EXEMPLE").assertDoesNotExist()
        compose.onNodeWithText("IL TE RESTE").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 140").assertIsDisplayed()
        compose.onNodeWithText("kcal restantes").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 460").assertIsDisplayed()
        compose.onNodeWithContentDescription("Protéines : 96 sur 140 grammes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Glucides : 168 sur 352 grammes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lipides : 48 sur 70 grammes").assertIsDisplayed()
    }

    @Test
    fun `etat vide propre, sans objectif ni donnees d exemple`() {
        ouvrir(SampleData.emptyEngine())
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
        compose.onNodeWithText("CONSOMMÉ").assertIsDisplayed()
        compose.onNodeWithText("kcal ce jour").assertIsDisplayed()
        compose.onNodeWithText("SANS OBJECTIF").assertIsDisplayed()
        compose.onNodeWithContentDescription("Protéines : 0 grammes").assertIsDisplayed()
        // Rien de noté : une carte avec sa phrase, pas de lignes de moment vides.
        compose.onNodeWithText("Pas encore de repas aujourd’hui").assertIsDisplayed()
        for (moment in listOf("Matin", "Midi", "Snack", "Soir")) compose.onNodeWithText(moment).assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithText("Rien pour l’instant").fetchSemanticsNodes().size)
    }

    @Test
    fun `repas classes par moment, seuls les moments notes ont une ligne`() {
        ouvrir()
        for (moment in listOf("Matin", "Midi", "Snack")) compose.onNodeWithText(moment).assertIsDisplayed()
        compose.onNodeWithText("Salade sauce A, poulet").assertIsDisplayed()
        compose.onNodeWithText("Soir").assertDoesNotExist()
        compose.onNodeWithText("Rien pour l’instant").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ajouter au soir").assertDoesNotExist()
    }

    @Test
    fun `barre du bas - champ, Aliments, micro et Bilan, sans plus`() {
        compose.setContent {
            TracklyRoot(engine = SampleData.engine(), motion = MotionPolicy(reduced = true), onOpenReview = {})
        }
        compose.waitForIdle()
        compose.onNodeWithText("Écrire à Trackly…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Écrire à Trackly").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dicter à Trackly").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mes aliments").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bilan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter un repas").assertDoesNotExist()
    }

    @Test
    fun `jour precedent puis retour a aujourd hui`() {
        ouvrir()
        compose.onNodeWithContentDescription("Jour suivant").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dim. 1er nov.").assertIsDisplayed()
        compose.onNodeWithContentDescription("120").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jour précédent").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Jour suivant").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `avant 5 h, l ecran ouvre sur la veille`() {
        ouvrir(SampleData.engine(SampleData.clock("2026-11-03T01:30")))
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jour suivant").assertIsNotEnabled()
    }

    @Test
    fun `le champ ouvre le chat, le retour ramene au jour`() {
        ouvrir()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dis-moi ce que tu as mangé.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dis-moi ce que tu as mangé.").assertDoesNotExist()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `chat ouvert, le jour cache n est plus atteignable par TalkBack`() {
        ouvrir()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jour précédent").assertDoesNotExist()
        compose.onNodeWithText("Salade sauce A, poulet").assertDoesNotExist()
    }

    @Test
    fun `sans jour marque aujourd hui, le plus recent est affiche`() {
        val engine = SampleData.engine()
        val monday = LocalDate.parse("2026-11-02")
        val summaries = runBlocking { engine.observeDays(monday.minusDays(1), monday).first() }
        val jours = summaries.map { DayView.from(it, today = monday.plusDays(7)) }
        compose.setContent { TracklyApp(motion = MotionPolicy(reduced = true), days = jours) }
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `glisser vers la droite montre hier, vers la gauche revient`() {
        ouvrir()
        compose.onNodeWithTag(DayTags.BODY).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Dim. 1er nov.").assertIsDisplayed()
        compose.onNodeWithContentDescription("120").assertIsDisplayed()
        // Rien avant le premier jour : le glissement revient en place.
        compose.onNodeWithTag(DayTags.BODY).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Dim. 1er nov.").assertIsDisplayed()
        compose.onNodeWithTag(DayTags.BODY).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `choisir une date au calendrier`() {
        ouvrir(SampleData.engineWithHistory())
        compose.onNodeWithText("Lun. 2 nov.").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalendarTags.PANEL).assertIsDisplayed()
        compose.onNodeWithText("Novembre 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lundi 2 novembre, suivi").assertIsSelected()
        compose.onNodeWithContentDescription("Mois suivant").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mois précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Octobre 2026").assertIsDisplayed()
        // Avant le premier jour enregistré : pas accessible.
        compose.onNodeWithContentDescription("Mercredi 14 octobre").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Vendredi 16 octobre").assertIsNotSelected()
        compose.onNodeWithContentDescription("Jeudi 15 octobre, suivi").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalendarTags.PANEL).assertDoesNotExist()
        compose.onNodeWithText("Jeu. 15 oct.").assertIsDisplayed()
        compose.onNodeWithText("Riz, poulet, courgettes").assertIsDisplayed()
        compose.onNodeWithContentDescription(formatKcal(2600 - 690)).assertIsDisplayed()
        // Glisser depuis là va au jour suivant, même sans repas.
        compose.onNodeWithTag(DayTags.BODY).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Ven. 16 oct.").assertIsDisplayed()
    }

    @Test
    fun `le calendrier revient a aujourd hui et se ferme au retour`() {
        ouvrir(SampleData.engineWithHistory())
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dim. 1er nov.").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aujourd’hui").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CalendarTags.PANEL).assertDoesNotExist()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `une description longue tient sur une ligne, TalkBack lit tout, la feuille garde le detail`() {
        val long = "Déjeuner — salade de crudités, sauce yaourt citron 40 g, croûtons à l’ail 20 g, thon au naturel 120 g, " +
            "œuf dur, maïs doux 60 g, deux tranches de pain complet grillé et un filet d’huile de noix"
        val engine = SampleData.emptyEngine().also { e ->
            runBlocking { check(e.execute(AddConsumptions(NewConsumption(Food(long, Nutrition(980.0, 52.0, 70.0, 48.0)), Moment.Midi))) is ActionOutcome.Applied) }
        }
        ouvrir(engine)
        val detail = compose.onNodeWithTag(DayTags.rowDetail(Moment.Midi), useUnmergedTree = true)
        detail.assertTextEquals(long)
        val line = compose.onNodeWithText("Midi", useUnmergedTree = true).fetchSemanticsNode().size.height
        // Une seule ligne de détail, coupée par « … » : pas plus haute que le titre du moment.
        val height = detail.fetchSemanticsNode().size.height
        assert(height <= line * 1.2f) { "Détail sur plus d'une ligne : $height px pour un titre de $line px" }
    }
}
