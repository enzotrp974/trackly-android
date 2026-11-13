package app.trackly.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import app.trackly.day.DayView
import app.trackly.day.EnergyNote
import app.trackly.day.EstimateLine
import app.trackly.day.Goals
import app.trackly.day.MacroAmount
import app.trackly.day.Meal
import app.trackly.testing.SampleData
import app.trackly.ui.day.MealTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Captures de l'écran du jour : jour passé, grandes polices, feuille d'un repas,
 * dépassement et valeur basse. Enregistrer : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DayLayoutCapturesTest {
    @get:Rule val compose = createComposeRule()

    private fun ouvrir(engine: TracklyEngine = SampleData.engine(), fontScale: Float = 1f) {
        compose.setContent { AvecPolice(fontScale) { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true)) } }
        compose.waitForIdle()
    }

    private fun hier() {
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
    }

    private fun engineDetaille(): TracklyEngine = SampleData.engine().also { engine ->
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
                            Moment.Soir,
                            entryId = EntryId("a-riz"),
                        ),
                        NewConsumption(Food("Poulet rôti", Nutrition(240.0, 40.0, 0.0, null), quantity = Quantity(150.0, "g")), Moment.Soir, entryId = EntryId("b-poulet")),
                    ),
                ),
            )
        }
    }

    @Test
    fun hierVide() {
        ouvrir(SampleData.emptyEngine())
        hier()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-hier-vide.png")
    }

    @Test
    fun hierAvecRepas() {
        ouvrir()
        hier()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-hier.png")
    }

    @Test
    fun police130() {
        ouvrir(fontScale = 1.3f)
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-police-130.png")
    }

    @Test
    fun police130Hier() {
        ouvrir(fontScale = 1.3f)
        hier()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-police-130-hier.png")
    }

    @Test
    fun police200() {
        ouvrir(fontScale = 2f)
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-police-200.png")
    }

    @Test
    fun police200Hier() {
        ouvrir(fontScale = 2f)
        hier()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-police-200-hier.png")
    }

    @Test
    fun feuilleDeRepas() {
        ouvrir(engineDetaille())
        compose.onNodeWithText("Soir").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/feuille-repas.png")
    }

    @Test
    fun feuilleDeRepasPolice130() {
        ouvrir(engineDetaille(), fontScale = 1.3f)
        compose.onNodeWithText("Soir").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/feuille-repas-police-130.png")
    }

    @Test
    fun bandeauAnnuler() {
        ouvrir(engineDetaille())
        compose.onNodeWithText("Soir").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MealTags.DELETE).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/repas-supprime-annuler.png")
    }

    @Test
    fun depassementDUneMacro() {
        val engine = SampleData.engine()
        runBlocking {
            engine.execute(AddConsumptions(NewConsumption(Food("Huile", Nutrition(300.0, 0.0, 0.0, 40.0)), Moment.Soir)))
        }
        ouvrir(engine)
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-depassement-macro.png")
    }

    /** Journée reprise de l'ancien suivi : borne minimale, macros « au moins ». */
    @Test
    fun valeurBasse() {
        val day = DayView(
            date = LocalDate.parse("2026-11-02"),
            label = "Lundi 2 novembre",
            isToday = true,
            goals = Goals(2350, 152, 297, 62),
            meals = listOf(
                Meal("a", Moment.Midi, "Burger au restaurant", 1200.0),
                Meal("b", Moment.Snack, "Barre de céréales", 450.0),
            ),
            consumedKcal = 1550,
            protein = MacroAmount(64, complete = false),
            carbs = MacroAmount(165, complete = false),
            fat = MacroAmount(80, complete = false),
            energyNote = EnergyNote.AtLeast,
            estimate = EstimateLine("Estimation globale validée (essai)", 1500, "au moins 1 500 kcal", atLeast = true),
            relative = "Aujourd’hui",
        )
        compose.setContent { TracklyApp(motion = MotionPolicy(reduced = true), days = listOf(day)) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/jour-valeur-basse.png")
    }
}
