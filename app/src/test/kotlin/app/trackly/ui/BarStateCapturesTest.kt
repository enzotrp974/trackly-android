package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
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
import app.trackly.ui.chat.CardView
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Captures d'acceptation de la couleur d'alerte, état par état, avec un exemple type : la tablette de chocolat qui fait
 * passer les lipides en jaune puis en rouge. Données fictives (objectif 2 300 kcal, 135 g de protéines avec un minimum de
 * 112 g, 280 g de glucides, limite de 70 g de lipides). Seul le remplissage de la barre change.
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h868dp-xxhdpi")
class BarStateCapturesTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.parse("2026-11-02")

    private data class Meal(val moment: Moment, val label: String, val kcal: Double, val p: Double, val c: Double, val f: Double)

    /** Trois jours d'habitude (30 et 31 octobre, 1er novembre), soir compris : 2 200 kcal, 125 g de protéines, 60 g de lipides. */
    private val habitude = listOf(
        Meal(Moment.Matin, "Petit-déjeuner habituel", 500.0, 30.0, 70.0, 10.0),
        Meal(Moment.Midi, "Déjeuner habituel", 700.0, 40.0, 80.0, 20.0),
        Meal(Moment.Snack, "Collation habituelle", 200.0, 5.0, 30.0, 5.0),
        Meal(Moment.Soir, "Dîner habituel", 800.0, 50.0, 100.0, 25.0),
    )

    private val matin = Meal(Moment.Matin, "Skyr, flocons d’avoine, banane, café", 500.0, 32.0, 70.0, 11.0)
    private val midi = Meal(Moment.Midi, "Poulet, riz, légumes", 700.0, 45.0, 80.0, 24.0)

    /** 100 g : 540 kcal, 31 g de lipides. Lipides du jour : 66 g sur 70, le soir habituel en ajoute 25. */
    private val tablette = Meal(Moment.Snack, "Tablette de chocolat (100 g)", 540.0, 6.0, 57.0, 31.0)

    /** 150 g : 810 kcal, 47 g de lipides. Lipides du jour : 82 g sur 70, dépassé alors qu'il reste le soir. */
    private val grandeTablette = Meal(Moment.Snack, "Tablette de chocolat (150 g)", 810.0, 9.0, 86.0, 47.0)

    private fun engine(today: List<Meal>, yesterday: List<Meal> = habitude): TracklyEngine = SampleData.emptyEngine().also { engine ->
        runBlocking {
            check(engine.execute(SetGoal(GoalTargets(2300.0, 135.0, 280.0, 70.0, proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            suspend fun add(day: String, m: Meal) {
                val food = Food(m.label, Nutrition(m.kcal, m.p, m.c, m.f))
                check(engine.execute(AddConsumptions(NewConsumption(food, m.moment, explicitDay = LocalDate.parse(day)))) is ActionOutcome.Applied)
            }
            for (day in listOf("2026-10-30", "2026-10-31")) habitude.forEach { add(day, it) }
            yesterday.forEach { add("2026-11-01", it) }
            today.forEach { add("2026-11-02", it) }
        }
    }

    private fun show(engine: TracklyEngine, fontScale: Float = 1f, reduced: Boolean = true) {
        compose.mainClock.autoAdvance = reduced
        compose.setContent {
            AvecPolice(fontScale) { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = reduced), onOpenReview = {}) }
        }
        // Animations actives : on laisse tout finir (entrée, jauge, barres, odomètres) avant de regarder.
        if (!reduced) compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
    }

    private fun capture(engine: TracklyEngine, name: String, fontScale: Float = 1f, reduced: Boolean = true) {
        show(engine, fontScale, reduced)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // ───── Accueil, état par état ─────

    /** Jaune : les repas restants habituels feraient dépasser. Calories et lipides. */
    @Test fun jauneLipidesEtCalories() = capture(engine(listOf(matin, midi, tablette)), "accueil-etat-jaune-lipides")

    /** Rouge : la limite des lipides est dépassée alors qu'il reste un repas ; les calories restent jaunes. */
    @Test fun rougeLipides() = capture(engine(listOf(matin, midi, grandeTablette)), "accueil-etat-rouge-lipides")

    @Test fun rougeLipidesPolice200() = capture(engine(listOf(matin, midi, grandeTablette)), "accueil-etat-rouge-lipides-police-200", fontScale = 2f)

    /** Même état, animations actives et menées à leur terme : le rendu final est celui des animations désactivées. */
    @Test fun rougeLipidesAvecAnimations() =
        capture(engine(listOf(matin, midi, grandeTablette)), "accueil-etat-rouge-lipides-animations", reduced = false)

    /** Rouge sur la jauge : 2 500 kcal sur 2 300. Les macros, elles, restent normales. */
    @Test fun rougeCalories() = capture(
        engine(listOf(Meal(Moment.Matin, "Petit-déjeuner copieux", 800.0, 40.0, 90.0, 10.0), Meal(Moment.Midi, "Déjeuner", 1000.0, 50.0, 120.0, 20.0), Meal(Moment.Snack, "Pizza", 700.0, 30.0, 90.0, 10.0))),
        "accueil-etat-rouge-calories",
    )

    /** Jaune sur les protéines : les repas habituels à venir ne suffiraient pas pour le minimum. */
    @Test fun jauneProteines() = capture(engine(listOf(Meal(Moment.Matin, "Pain, confiture, café", 300.0, 5.0, 60.0, 5.0))), "accueil-etat-jaune-proteines")

    /** Rouge sur les protéines : le soir est noté et le minimum n'est pas atteint. Aucun repère du minimum. */
    @Test fun rougeProteines() = capture(
        engine(listOf(matin.copy(p = 20.0), Meal(Moment.Soir, "Pâtes, légumes", 700.0, 20.0, 110.0, 14.0))),
        "accueil-etat-rouge-proteines",
    )

    /** Glucides très au-dessus de la cible : jamais de couleur. */
    @Test fun glucidesJamaisDeCouleur() = capture(
        engine(listOf(Meal(Moment.Matin, "Gâteau et skyr", 600.0, 30.0, 350.0, 10.0))),
        "accueil-etat-glucides-hauts",
    )

    /** Hier : pas de jaune de prévision, le rouge seulement. La tablette de 150 g d'hier est restée rouge. */
    @Test fun hierRougeSeulement() {
        show(engine(today = emptyList(), yesterday = listOf(matin, midi, grandeTablette)))
        compose.onNodeWithContentDescription("Jour précédent").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/accueil-etat-hier-rouge.png")
    }

    // ───── Récap du chat ─────

    private fun carte(overLimit: List<String>) = RecordCard(
        actionId = 7,
        kind = RecordKind.Added,
        items = listOf(CardItem("e1", "Tablette de chocolat", "150 g", "snack", "2026-11-02", 810.0, 9.0, 86.0, 47.0)),
        overLimit = overLimit,
    )

    private fun showCard(card: RecordCard, fontScale: Float = 1f) {
        compose.setContent {
            AvecPolice(fontScale) {
                CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                    Box(Modifier.background(TracklyColors.Paper).padding(16.dp)) { CardView(card, false, false, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun captureCard(card: RecordCard, name: String, fontScale: Float = 1f) {
        showCard(card, fontScale)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** Les lipides après la tablette : en rouge dans le récap. */
    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun carteLipidesRouges() = captureCard(carte(listOf("fat")), "carte-rouge-lipides")

    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun carteProteinesEtLipidesRouges() = captureCard(carte(listOf("protein", "fat")), "carte-rouge-proteines-lipides")

    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun carteRougePolice200() = captureCard(carte(listOf("fat")), "carte-rouge-police-200", fontScale = 2f)

    /** Une carte enregistrée avant la 1.3 : rien en rouge, comme avant. */
    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun carteAncienneSansRouge() = captureCard(carte(emptyList()), "carte-sans-rouge")
}
