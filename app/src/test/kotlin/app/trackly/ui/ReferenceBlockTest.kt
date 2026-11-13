package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.core.store.ImportedSource
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import app.trackly.review.page.ReferenceStatus
import app.trackly.review.page.ReferenceText
import app.trackly.review.page.ReferenceWeight
import app.trackly.ui.review.ReferenceBlock
import app.trackly.ui.review.ReferenceTags
import app.trackly.ui.review.ReferenceUi
import app.trackly.ui.review.rememberReferenceUi
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Bloc « Poids de référence » de la page du bilan : captures des quatre états (mis à jour avec Annuler, annulé, sans pesée,
 * refus qui dit quoi défaire) et Annuler branché sur un vrai moteur. Données fictives.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h900dp-xxhdpi")
class ReferenceBlockTest {
    @get:Rule val compose = createComposeRule()

    private val updated = ReferenceWeight(
        beforeKg = 74.0,
        afterKg = 73.5,
        lines = listOf("protéines 148 → 147 g", "minimum de protéines 118 → 117 g", "lipides max inchangé (69 g)", "glucides 255 → 257 g", ReferenceText.CALORIES),
        actionId = 7,
        source = "Moyenne de tes 4 pesées des 7 derniers jours.",
    )

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                AvecPolice(fontScale) {
                    Box(Modifier.fillMaxWidth().background(TracklyColors.Paper).padding(horizontal = TracklySpacing.Gutter, vertical = 16.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun misAJourAvecAnnuler() {
        var undone = 0
        show { ReferenceBlock(updated, ReferenceUi(undone = false) { undone++ }) }
        capture("bilan-poids-reference-mis-a-jour")
        compose.onNodeWithTag(ReferenceTags.UNDO).performClick()
        assertEquals(1, undone)
    }

    @Test
    fun annule() {
        show { ReferenceBlock(updated, ReferenceUi(undone = true)) }
        compose.onAllNodesWithTag(ReferenceTags.UNDO).assertCountEquals(0)
        compose.onNodeWithTag(ReferenceTags.UNDONE).assertExists()
        capture("bilan-poids-reference-annule")
    }

    @Test
    fun refusQuiDitQuoiDefaire() {
        show {
            ReferenceBlock(
                updated,
                ReferenceUi(
                    undone = false,
                    notice = "Annule d'abord « Objectif temporaire : 2 200 kcal du 7 au 10 novembre », puis le poids de référence : cet objectif a été calculé sur le nouveau poids.",
                ),
            )
        }
        compose.onNodeWithTag(ReferenceTags.NOTICE).assertExists()
        capture("bilan-poids-reference-refus")
    }

    @Test
    fun sansPesee_aucunBouton_aucuneAlerte() {
        val none = ReferenceWeight(
            74.0, 74.0, listOf(ReferenceText.CALORIES), status = ReferenceStatus.NoWeighIn,
            source = "Aucune pesée ces 14 derniers jours : il ne change pas. Donne une pesée au prochain bilan pour le mettre à jour.",
        )
        show { ReferenceBlock(none, ReferenceUi(undone = false)) }
        compose.onAllNodesWithTag(ReferenceTags.UNDO).assertCountEquals(0)
        capture("bilan-poids-reference-sans-pesee")
    }

    @Test
    fun grandePolice() {
        show(fontScale = 2f) { ReferenceBlock(updated, ReferenceUi(undone = false)) }
        capture("bilan-poids-reference-police-200")
    }

    @Test
    fun annulerBrancheSurLeMoteur() {
        val now = LocalDateTime.parse("2026-11-06T20:00").atZone(TrackingDay.ZONE).toInstant()
        val store = InMemoryStore()
        val engine = TracklyEngine(store, Clock.fixed(now, TrackingDay.ZONE))
        val outcome = runBlocking {
            store.transaction {
                imports.insert(ImportedSource("profile", "profile", 1, "sha", """{"current_reference_weight_kg":70.0}""", ActionId(1)))
            }
            assertTrue(engine.execute(SetGoal(GoalTargets(2300.0, 140.0, 277.5, 70.0, 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
            engine.execute(DeclareWeekInfo(LocalDate.parse("2026-10-26"), weighIns = listOf(WeighIn(LocalDate.parse("2026-11-05"), 66.0))))
            engine.updateReferenceWeight(LocalDate.parse("2026-11-06"))
        }
        val block = ReferenceText.of(outcome)!!
        show { ReferenceBlock(block, rememberReferenceUi(engine, block)) }
        compose.onNodeWithTag(ReferenceTags.UNDO).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ReferenceTags.UNDONE).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(70.0, runBlocking { engine.referenceWeight(LocalDate.parse("2026-11-06")) }!!, 0.0)
    }
}
