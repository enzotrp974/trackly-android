package app.trackly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import app.trackly.day.formatKcal
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.GoalCard
import app.trackly.assistant.cards.GoalValues
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.goals.GoalsNotice
import app.trackly.goals.GoalsUiState
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.CardView
import app.trackly.ui.settings.GoalsActions
import app.trackly.ui.settings.GoalsSettingsRoute
import app.trackly.ui.settings.GoalsSettingsSection
import app.trackly.ui.settings.GoalsTags
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Réglages › Objectifs et cohérence avec le chat. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class GoalsSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-11-02")
    private val base = GoalTargets(2600.0, 140.0, 352.0, 70.0)

    private fun goal(from: String, targets: GoalTargets, version: Int = 1) = app.trackly.core.model.Goal(
        effectiveFrom = LocalDate.parse(from),
        version = version,
        state = app.trackly.core.model.RecordState.Active,
        targets = targets,
        recordedAt = java.time.Instant.parse("2026-11-02T10:00:00Z"),
        actionId = null,
    )

    private fun state(vararg goals: app.trackly.core.model.Goal, notice: GoalsNotice? = null) = GoalsUiState.of(goals.toList(), today, notice)

    @Composable
    private fun Screen(state: GoalsUiState, actions: GoalsActions) =
        SettingsScreen(InMemoryApiKeyStore(), onClose = {}, groups = SettingsGroups(goals = { GoalsSettingsSection(state, actions) }), startPage = SettingsPage.Goals)

    private fun digits(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.filter(Char::isDigit)

    private fun field(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(text)
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `affiche l objectif en vigueur, sa date d effet et un objectif prevu`() {
        compose.setContent {
            Screen(state(goal("2026-10-01", base), goal("2026-11-03", GoalTargets(2200.0, 140.0, 253.0, 70.0))), GoalsActions())
        }
        compose.onNodeWithTag(GoalsTags.CURRENT).performScrollTo().assertIsDisplayed()
        assertEquals("2600", digits(GoalsTags.CURRENT_KCAL))
        compose.onNodeWithText("P 140 g  ·  G 352 g  ·  L 70 g").assertExists()
        compose.onNodeWithText("En vigueur depuis le 1er octobre 2026.").assertExists()
        compose.onNodeWithTag(GoalsTags.UPCOMING).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-objectifs.png")
    }

    @Test
    fun `sans objectif, la section le dit et propose d en fixer un`() {
        compose.setContent { Screen(state(), GoalsActions()) }
        compose.onNodeWithText("Aucun objectif.").performScrollTo().assertExists()
        compose.onNodeWithTag(GoalsTags.EDIT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `modifier - formulaire prerempli, depart aujourd hui par defaut, action SetGoal partagee`() {
        var saved: SetGoal? = null
        compose.setContent { Screen(state(goal("2026-10-01", base)), GoalsActions(save = { saved = it })) }

        tap(GoalsTags.EDIT)
        compose.onNodeWithTag(GoalsTags.START_TODAY).performScrollTo().assertIsSelected()
        compose.onNodeWithText("2600", substring = true).assertExists() // préremplissage (champ des calories)
        field(GoalsTags.FIELD_KCAL, "2300")
        field(GoalsTags.FIELD_CARBS, "278")
        tap(GoalsTags.SAVE)

        val action = saved!!
        assertEquals(GoalTargets(2300.0, 140.0, 278.0, 70.0), action.targets)
        assertNull(action.from?.takeIf { it != today }) // aujourd'hui : le jour de suivi en cours
        assertEquals(today, action.from)
        assertEquals(false, action.allowPast)
    }

    @Test
    fun `depart demain, ou une autre date`() {
        var saved: SetGoal? = null
        compose.setContent { Screen(state(goal("2026-10-01", base)), GoalsActions(save = { saved = it })) }

        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2300")
        field(GoalsTags.FIELD_CARBS, "278")
        tap(GoalsTags.START_TOMORROW)
        compose.onNodeWithTag(GoalsTags.START_TOMORROW).assertIsSelected()
        tap(GoalsTags.SAVE)
        assertEquals(LocalDate.parse("2026-11-03"), saved!!.from)

        saved = null
        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2250")
        field(GoalsTags.FIELD_CARBS, "265")
        tap(GoalsTags.START_OTHER)
        field(GoalsTags.OTHER_DATE, "15/11/2026")
        tap(GoalsTags.SAVE)
        assertEquals(LocalDate.parse("2026-11-15"), saved!!.from)
    }

    @Test
    fun `date passee, date illisible, calories vides - message a corriger, rien n est enregistre`() {
        var saves = 0
        compose.setContent { Screen(state(goal("2026-10-01", base)), GoalsActions(save = { saves++ })) }

        tap(GoalsTags.EDIT)
        tap(GoalsTags.START_OTHER)
        field(GoalsTags.OTHER_DATE, "01/11/2026")
        tap(GoalsTags.SAVE)
        compose.onNodeWithTag(GoalsTags.ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Les jours passés gardent leur objectif", substring = true).assertExists()

        field(GoalsTags.OTHER_DATE, "pas une date")
        tap(GoalsTags.SAVE)
        compose.onNodeWithText("Date de départ invalide", substring = true).assertExists()

        tap(GoalsTags.START_TODAY)
        compose.onNodeWithTag(GoalsTags.FIELD_KCAL).performScrollTo()
        compose.onNodeWithTag(GoalsTags.FIELD_KCAL).performTextClearance()
        tap(GoalsTags.SAVE)
        compose.onNodeWithText("Indique l’objectif en calories", substring = true).assertExists()
        assertEquals(0, saves)
    }

    @Test
    fun `macros qui ne collent pas aux calories - rappel, sans bloquer`() {
        var saves = 0
        compose.setContent { Screen(state(goal("2026-10-01", base)), GoalsActions(save = { saves++ })) }
        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2300")
        compose.onNodeWithTag(GoalsTags.HINT).performScrollTo().assertIsDisplayed()
        tap(GoalsTags.SAVE)
        assertEquals(1, saves) // l'utilisateur choisit ses chiffres
    }

    private val decimals = GoalTargets(2350.0, 152.25, 296.59, 61.62)

    @Test
    fun `grammes arrondis a l unite a l affichage, dans la ligne comme dans le formulaire`() {
        compose.setContent { Screen(state(goal("2026-10-01", decimals)), GoalsActions()) }
        compose.onNodeWithText("P 152 g  ·  G 297 g  ·  L 62 g").performScrollTo().assertExists()
        tap(GoalsTags.EDIT)
        compose.onNodeWithTag(GoalsTags.FIELD_PROTEIN).assertTextContains("152")
        compose.onNodeWithTag(GoalsTags.FIELD_CARBS).assertTextContains("297")
        compose.onNodeWithTag(GoalsTags.FIELD_FAT).assertTextContains("62")
    }

    @Test
    fun `les macros ne font plus les kcal - l ecran le dit et Ajuster en proportion les remet d aplomb`() {
        var saved: SetGoal? = null
        compose.setContent { Screen(state(goal("2026-10-01", decimals)), GoalsActions(save = { saved = it })) }
        tap(GoalsTags.EDIT)
        assertEquals(0, compose.onAllNodesWithTag(GoalsTags.HINT).fetchSemanticsNodes().size) // 2 350 kcal pour 2 350 : rien à dire
        field(GoalsTags.FIELD_KCAL, "2400")

        compose.onNodeWithTag(GoalsTags.HINT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tes macros font ${formatKcal(2354)} kcal", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-objectifs-macros-ecart.png")

        tap(GoalsTags.ADJUST)
        // Mêmes proportions, arrondies au gramme, calories de l'objectif rejointes.
        compose.onNodeWithTag(GoalsTags.FIELD_PROTEIN).assertTextContains("155")
        compose.onNodeWithTag(GoalsTags.FIELD_CARBS).assertTextContains("303")
        compose.onNodeWithTag(GoalsTags.FIELD_FAT).assertTextContains("63")
        assertEquals(0, compose.onAllNodesWithTag(GoalsTags.HINT).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(GoalsTags.ADJUST).fetchSemanticsNodes().size)
        assertNull(saved) // ajuster ne enregistre rien tout seul

        tap(GoalsTags.SAVE)
        assertEquals(GoalTargets(2400.0, 155.0, 303.0, 63.0), saved!!.targets)
    }

    /** 70 kg : idéal 140 g, minimum de protéines 112 g. */
    private val withMinimum = base.copy(proteinMinG = 112.0)

    @Test
    fun `le minimum de proteines n est affiche nulle part, mais reste un plancher`() {
        var saves = 0
        compose.setContent { Screen(state(goal("2026-10-01", withMinimum)), GoalsActions(save = { saves++ })) }
        // Aucun repère du minimum : ni le mot, ni sa valeur (choix de conception : pas de minimum visuel).
        assertEquals(0, compose.onAllNodesWithText("minimum", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("112", substring = true).fetchSemanticsNodes().size)
        tap(GoalsTags.EDIT)
        assertEquals(0, compose.onAllNodesWithText("112", substring = true).fetchSemanticsNodes().size)

        // Ajuster en proportion vers 1 500 kcal : les protéines s'arrêtent au minimum.
        field(GoalsTags.FIELD_KCAL, "1500")
        tap(GoalsTags.ADJUST)
        compose.onNodeWithTag(GoalsTags.FIELD_PROTEIN).assertTextContains("112")

        // Saisir moins que le minimum : message, rien n'est enregistré.
        field(GoalsTags.FIELD_PROTEIN, "100")
        tap(GoalsTags.SAVE)
        compose.onNodeWithText("Les protéines ne descendent pas sous ton minimum de 112 g", substring = true).assertExists()
        assertEquals(0, saves)
    }

    @Test
    fun `sans les trois macros, pas de rappel ni d ajustement`() {
        compose.setContent { Screen(state(goal("2026-10-01", GoalTargets(2600.0))), GoalsActions()) }
        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2400")
        assertEquals(0, compose.onAllNodesWithTag(GoalsTags.HINT).fetchSemanticsNodes().size)
    }

    @Test
    fun `la touche du clavier passe au champ suivant puis enregistre`() {
        var saved: SetGoal? = null
        compose.setContent { Screen(state(goal("2026-10-01", base)), GoalsActions(save = { saved = it })) }
        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2600")
        // Les trois premiers champs avancent (Suivant), le dernier enregistre (Terminé).
        compose.onNodeWithTag(GoalsTags.FIELD_KCAL).assert(hasImeAction(ImeAction.Next))
        compose.onNodeWithTag(GoalsTags.FIELD_PROTEIN).assert(hasImeAction(ImeAction.Next))
        compose.onNodeWithTag(GoalsTags.FIELD_CARBS).assert(hasImeAction(ImeAction.Next))
        compose.onNodeWithTag(GoalsTags.FIELD_FAT).assert(hasImeAction(ImeAction.Done))
        assertNull(saved)
        compose.onNodeWithTag(GoalsTags.FIELD_FAT).performScrollTo()
        compose.onNodeWithTag(GoalsTags.FIELD_FAT).performImeAction()
        compose.waitForIdle()
        assertEquals(base, saved!!.targets)
    }

    @Test
    fun `historique consultable - chaque periode, version et statut`() {
        compose.setContent {
            Screen(
                state(goal("2026-09-01", GoalTargets(2800.0, 150.0, 380.0, 75.0)), goal("2026-10-01", base, version = 3), goal("2026-11-03", GoalTargets(2200.0, 140.0, 253.0, 70.0))),
                GoalsActions(),
            )
        }
        assertEquals(0, compose.onAllNodesWithTag(GoalsTags.HISTORY_ROW).fetchSemanticsNodes().size)
        tap(GoalsTags.HISTORY_TOGGLE)
        assertEquals(3, compose.onAllNodesWithTag(GoalsTags.HISTORY_ROW).fetchSemanticsNodes().size)
        compose.onNodeWithText("Du 1er octobre 2026 au 2 novembre 2026 · modifié (version 3)").assertExists()
        compose.onNodeWithText("Du 1er septembre 2026 au 30 septembre 2026").assertExists()
        compose.onNodeWithText("Depuis le 3 novembre 2026").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-objectifs-historique.png")
    }

    @Test
    fun `message d enregistrement avec Annuler ce changement`() {
        var undone: Long? = null
        compose.setContent {
            Screen(state(goal("2026-10-01", base), notice = GoalsNotice("Objectif enregistré : 2300 kcal dès lundi 2 novembre.", undoActionId = 7)), GoalsActions(undo = { undone = it }))
        }
        compose.onNodeWithTag(GoalsTags.NOTICE).performScrollTo().assertIsDisplayed()
        tap(GoalsTags.UNDO)
        assertEquals(7L, undone)
    }

    // ───── Moteur réel : Réglages ↔ chat ─────

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(base, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    @Composable
    private fun Real(engine: TracklyEngine) =
        SettingsScreen(InMemoryApiKeyStore(), onClose = {}, groups = SettingsGroups(goals = { GoalsSettingsRoute(engine) }), startPage = SettingsPage.Goals)

    @Test
    fun `chat vers reglages - 2 300 kcal dit au chat apparait aussitot dans les Reglages`() {
        val engine = engine()
        compose.setContent { Real(engine) }
        compose.waitForIdle()
        assertEquals("2600", digits(GoalsTags.CURRENT_KCAL))

        val transport = ScriptedTransport.replies("objectif-1-appel-outil.json", "objectif-2-reponse.json")
        val outcome = runBlocking {
            TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine).run(TurnInput("finalement je veux être à 2 300 kcal"))
        }
        assertTrue(outcome is TurnOutcome.Completed)
        compose.waitForIdle()

        assertEquals("2300", digits(GoalsTags.CURRENT_KCAL))
        compose.onNodeWithText("P 140 g  ·  G 278 g  ·  L 70 g").assertExists()
        compose.onNodeWithText("En vigueur depuis le 2 novembre 2026.").assertExists()
        // Les jours passés gardent leur objectif.
        assertEquals(base, runBlocking { engine.daySummary(LocalDate.parse("2026-11-01")).goal })
        assertEquals(2300.0, runBlocking { engine.daySummary(today).goal!!.kcal }, 0.0)
    }

    @Test
    fun `reglages vers chat - l objectif enregistre ici est celui que l assistant voit, et Annuler le retire`() {
        val engine = engine()
        compose.setContent { Real(engine) }
        compose.waitForIdle()

        tap(GoalsTags.EDIT)
        field(GoalsTags.FIELD_KCAL, "2400")
        field(GoalsTags.FIELD_CARBS, "303")
        tap(GoalsTags.SAVE)

        assertEquals("2400", digits(GoalsTags.CURRENT_KCAL))
        assertEquals(2400.0, runBlocking { engine.daySummary(today).goal!!.kcal }, 0.0)
        val context = runBlocking {
            app.trackly.assistant.context.ContextBuilder.default().build(app.trackly.assistant.context.ContextEnv(engine, engine.now()))
        }
        assertTrue(context, context.contains("2400 kcal, protéines 140 g, glucides 303 g, lipides 70 g"))

        compose.onNodeWithTag(GoalsTags.NOTICE).performScrollTo().assertIsDisplayed()
        tap(GoalsTags.UNDO)
        assertEquals("2600", digits(GoalsTags.CURRENT_KCAL))
        assertEquals(base, runBlocking { engine.daySummary(today).goal })
        // La date retirée par l'annulation disparaît de la chronologie (ses versions restent gardées, voir GoalFromDateTest).
        assertEquals(1, runBlocking { engine.goals().size })
    }

    @Test
    fun `la carte du chat - avant, apres, date d effet, Annuler`() {
        val card = GoalCard(
            actionId = 12,
            effectiveFrom = "2026-11-02",
            before = GoalValues.of(base),
            after = GoalValues(2300.0, 140.0, 278.0, 70.0),
        )
        var undone: Long? = null
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(18.dp)) {
                    CardView(card, undone = false, animate = false, onUndo = { undone = it.actionId }, onCorrect = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CardTags.GOAL).assertIsDisplayed()
        compose.onNodeWithText("Macros ajustées pour rester cohérentes avec les calories.").assertExists()
        compose.onNodeWithText("Avant").assertExists()
        compose.onNodeWithText("Après").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/carte-objectif.png")
        compose.onNodeWithTag(CardTags.UNDO).performClick()
        assertEquals(12L, undone)
        assertNotNull(card.summaryForModel())
    }

    @Test
    fun `la carte annulee devient une ligne en pointilles`() {
        val card = GoalCard(12, "2026-11-02", GoalValues.of(base), GoalValues(2300.0, 140.0, 278.0, 70.0))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                CardView(card, undone = true, animate = false, onUndo = {}, onCorrect = {})
            }
        }
        compose.onNodeWithTag(CardTags.UNDONE).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.GOAL).assertDoesNotExist()
    }
}
