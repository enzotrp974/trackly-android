package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.TemporaryGoalCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.core.goal.TemporaryGoalStatus
import app.trackly.core.goal.TemporaryPhase
import app.trackly.core.model.ActionId
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.review.CatchUpAdvices
import app.trackly.core.review.WeekRange
import app.trackly.review.ReviewUiState
import app.trackly.review.page.Advice
import app.trackly.review.page.AdviceActions
import app.trackly.review.page.AdviceKind
import app.trackly.review.page.AdviceState
import app.trackly.review.page.ReportPage
import app.trackly.review.page.ReportPageBuilder
import app.trackly.review.page.ReportPageFixtures
import app.trackly.review.page.ReportPageFixtures.Scenario
import app.trackly.ui.review.AdviceTags
import app.trackly.ui.review.AdviceUi
import app.trackly.ui.review.ReportPageScreen
import app.trackly.ui.review.ReportPageTags
import app.trackly.ui.review.ReviewActions
import app.trackly.ui.review.ReviewScreen
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/**
 * Conseils chiffrés (section « Conseils ») : étiquette cobalt, grand chiffre, filet encre de 4 dp,
 * « Appliquer », puis « Appliqué · retour à X … » et « Annuler ». Page du bilan et carte du chat du bilan. Données fictives.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h2000dp-xxhdpi")
class AdviceCapturesTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-11-06")
    private val goals = listOf(Goal(LocalDate.parse("2026-09-01"), 1, RecordState.Active, GoalTargets(2300.0, 135.0, 250.0, 70.0, 120.0), Instant.EPOCH, null))

    private fun page(): ReportPage {
        val figures = ReportPageFixtures.figures(Scenario.Retard)
        return ReportPageBuilder.build(figures, "C’est rattrapable. Le retard vient surtout du week-end.", today, CatchUpAdvices.compute(figures, goals, today))
    }

    private fun status(phase: TemporaryPhase, stoppedOn: LocalDate? = null, canToggle: Boolean = true) = TemporaryGoalStatus(
        action = ActionId(40), head = ActionId(40), phase = phase, start = LocalDate.parse("2026-11-07"), days = 4,
        returnOn = LocalDate.parse("2026-11-11"), temporaryKcal = 2200.0, baseKcal = 2300.0, stoppedOn = stoppedOn, canToggle = canToggle,
    )

    private fun states(page: ReportPage, temporary: AdviceState, date: AdviceState = AdviceState.Open): Map<String, AdviceState> =
        page.advice.filter { it.kind != AdviceKind.Steps }.associate { AdviceActions.key(it) to if (it.kind == AdviceKind.Temporary) temporary else date }

    private fun show(page: ReportPage, ui: AdviceUi?) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) { ReportPageScreen(page, onBack = {}, onAsk = {}, advice = ui) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ReportPageTags.ADVICE).performScrollTo()
    }

    private fun capture(name: String) = compose.onNodeWithTag(ReportPageTags.ADVICE).captureRoboImage("src/test/screenshots/$name.png")

    @Test fun proposes() { val p = page(); show(p, AdviceUi(states(p, AdviceState.Open))); capture("bilan-conseils") }

    @Test
    fun appliques() {
        val p = page()
        show(p, AdviceUi(states(p, AdviceState.Temporary(status(TemporaryPhase.Scheduled)), AdviceState.DateSet(41))))
        compose.onNodeWithText("Appliqué · retour à 2 300 mercredi 11").assertExists()
        capture("bilan-conseils-appliques")
    }

    @Test fun arreteEnCoursDeRoute() { val p = page(); show(p, AdviceUi(states(p, AdviceState.Temporary(status(TemporaryPhase.Stopped, LocalDate.parse("2026-11-09"), canToggle = false))))); capture("bilan-conseils-arrete") }

    @Test fun termine() { val p = page(); show(p, AdviceUi(states(p, AdviceState.Temporary(status(TemporaryPhase.Finished, canToggle = false))))); capture("bilan-conseils-termine") }

    @Test
    fun appliquerPasseParLeRappel() {
        val p = page()
        val applied = mutableListOf<Advice>()
        show(p, AdviceUi(states(p, AdviceState.Open), onApply = { applied += it }))
        compose.onNodeWithText("Appliquer").performClick()
        assertEquals(listOf(AdviceKind.Temporary), applied.map { it.kind })
        compose.onNodeWithText("Changer la date").performClick()
        assertEquals(AdviceKind.Date, applied.last().kind)
    }

    @Test
    fun annulerPasseParLeRappel() {
        val p = page()
        val toggled = mutableListOf<AdviceState>()
        val running = AdviceState.Temporary(status(TemporaryPhase.Running))
        show(p, AdviceUi(states(p, running), onToggle = { toggled += it }))
        compose.onNodeWithTag(AdviceTags.TOGGLE).performClick()
        assertEquals(listOf<AdviceState>(running), toggled)
    }

    @Test
    fun carteDuChat() {
        val advice = page().advice.first()
        val messages = listOf(
            ChatMessage(1, Instant.parse("2026-11-06T19:10:00Z"), ChatRole.User, "Ok, montre-moi l’objectif plus bas."),
            ChatMessage(2, Instant.parse("2026-11-06T19:10:20Z"), ChatRole.Assistant, "Voilà : 4 jours un peu plus bas, puis ton objectif revient tout seul. À toi de voir.", listOf(TemporaryGoalCard(advice))),
        )
        val state = ReviewUiState(period = WeekRange(LocalDate.parse("2026-10-31"), today), messages = messages, advice = mapOf(AdviceActions.key(advice) to AdviceState.Open))
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) { ReviewScreen(state = state, onBack = {}, actions = ReviewActions()) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/bilan-carte-objectif-temporaire.png")
    }
}
