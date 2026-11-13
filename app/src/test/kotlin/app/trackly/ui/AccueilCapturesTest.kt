package app.trackly.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.testing.SampleData
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
 * Captures d'acceptation de l'accueil « Barres longues » : taille d'écran de référence (412 × 868 dp)
 * et données fictives (objectif 2 300 kcal, 135 / 280 / 70 g). Aucune donnée réelle.
 * Enregistrer : `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h868dp-xxhdpi")
class AccueilCapturesTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    /** Repas fictifs (matin, gros midi), puis de quoi charger la journée. */
    private val repas = listOf(
        Triple(Moment.Matin, "Skyr, flocons d’avoine, banane, café", listOf(500, 32, 70, 11)),
        Triple(Moment.Midi, "Tartiflette, salade verte, tarte aux pommes", listOf(1220, 38, 128, 62)),
        Triple(Moment.Snack, "Pomme, amandes", listOf(210, 6, 20, 12)),
        Triple(Moment.Soir, "Soupe de légumes, pain complet, fromage blanc", listOf(480, 26, 58, 12)),
    )

    /**
     * Trois jours d'habitude (30 et 31 octobre, 1er novembre) dont la moyenne donne les traits attendus :
     * 520 kcal, 30 / 72 / 14 g après le petit-déjeuner ; 1 150 kcal, 72 / 150 / 38 g après le midi.
     */
    private val habitude = listOf(
        Triple(Moment.Matin, "Petit-déjeuner habituel", listOf(520, 30, 72, 14)),
        Triple(Moment.Midi, "Déjeuner habituel", listOf(630, 42, 78, 24)),
    )

    private fun jour(n: Int, avecHabitude: Boolean = false): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun ajouter(day: LocalDate, moment: Moment, label: String, v: List<Int>) {
                val food = Food(label, Nutrition(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble()))
                check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = day))) is ActionOutcome.Applied)
            }
            if (avecHabitude) {
                for (iso in listOf("2026-10-30", "2026-10-31", "2026-11-01")) {
                    for ((moment, label, v) in habitude) ajouter(LocalDate.parse(iso), moment, label, v)
                }
            }
            for ((moment, label, v) in repas.take(n)) ajouter(monday, moment, label, v)
        }
    }

    private fun capture(n: Int, name: String, fontScale: Float = 1f, avecHabitude: Boolean = false) {
        val engine = jour(n, avecHabitude)
        compose.setContent {
            AvecPolice(fontScale) { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), onOpenReview = {}) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun jourVide() = capture(0, "accueil-vide")

    @Test fun apresLePetitDejeuner() = capture(1, "accueil-matin")

    @Test fun apresUnGrosMidi() = capture(2, "accueil-midi")

    @Test fun journeeChargee() = capture(4, "accueil-journee-chargee")

    /** Après le petit-déjeuner, le trait pâle de la moyenne arrive sur la jauge et les trois barres. */
    @Test fun traitApresLePetitDejeuner() = capture(1, "accueil-trait-matin", avecHabitude = true)

    /** Après un gros midi, le remplissage dépasse le trait (blanc sur le remplissage, encre au-delà). */
    @Test fun traitApresUnGrosMidi() = capture(2, "accueil-trait-midi", avecHabitude = true)

    /** Journée vide malgré l'historique, aucun trait. */
    @Test fun traitJourneeVide() = capture(0, "accueil-trait-vide", avecHabitude = true)

    @Test fun traitPolice200() = capture(2, "accueil-trait-police-200", fontScale = 2f, avecHabitude = true)

    @Test fun police200() = capture(2, "accueil-police-200", fontScale = 2f)

    @Test fun police200JourneeChargee() = capture(4, "accueil-police-200-journee-chargee", fontScale = 2f)

    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @Test fun petitEcran() = capture(4, "accueil-petit-ecran")

    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @Test fun petitEcranVide() = capture(0, "accueil-petit-ecran-vide")
}
