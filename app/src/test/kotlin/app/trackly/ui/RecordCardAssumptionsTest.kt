package app.trackly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.CardView
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Carte d'enregistrement : hypothèses repliées par défaut, déroulées d'un toucher, avec un accès clair pour
 * TalkBack. Une carte enregistrée avant ce changement s'affiche sans erreur. Captures replié / déroulé, police à 200 %.
 * Enregistrer : `./gradlew recordRoborazziDebug` ; comparer : `./gradlew verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class RecordCardAssumptionsTest {
    @get:Rule val compose = createComposeRule()

    private val twoLines = listOf("Deux œufs moyens, avec 5 g de beurre pour la cuisson.", "Une tranche de 40 g, sans garniture supplémentaire.")

    private fun card(assumptions: List<String>, id: Long = 5) = RecordCard(
        actionId = id,
        kind = RecordKind.Added,
        items = listOf(
            CardItem("e1", "Œufs brouillés", "2 pièces", "matin", "2026-11-02", 188.0, 16.2, 1.0, 14.0),
            CardItem("e2", "Tartine de pain complet", "40 g", "matin", "2026-11-02", 100.0, 3.0, 17.0, 1.0),
        ),
        assumptions = assumptions,
    )

    private fun show(card: RecordCard, fontScale: Float = 1f, reduced: Boolean = true) {
        compose.setContent {
            AvecPolice(fontScale) {
                CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = reduced)) {
                    Box(Modifier.background(TracklyColors.Paper).padding(16.dp)) { CardView(card, false, false, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private val toggle get() = compose.onNodeWithTag(CardTags.ASSUMPTIONS_TOGGLE)

    @Test
    fun `les hypotheses sont repliees par defaut et se deroulent d un toucher`() {
        show(card(twoLines))
        // Repliées : la ligne est là, les textes non ; le reste de la carte est intact.
        toggle.assertIsDisplayed()
        compose.onNodeWithText(twoLines[0], substring = true).assertDoesNotExist()
        compose.onNodeWithTag(CardTags.UNDO).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.CORRECT).assertIsDisplayed()

        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText("– ${twoLines[0]}").assertIsDisplayed()
        compose.onNodeWithText("– ${twoLines[1]}").assertIsDisplayed()

        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(twoLines[0], substring = true).assertDoesNotExist()
    }

    @Test
    fun `une seule hypothese se lit sans tiret`() {
        show(card(twoLines.take(1)))
        compose.onNodeWithText(twoLines[0]).assertDoesNotExist()
        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(twoLines[0]).assertIsDisplayed()
    }

    @Test
    fun `sans hypothese la carte n affiche aucune ligne`() {
        show(card(emptyList()))
        compose.onNodeWithTag(CardTags.ASSUMPTIONS).assertDoesNotExist()
        compose.onNodeWithTag(CardTags.UNDO).assertIsDisplayed()
    }

    @Test
    fun `TalkBack lit l etat replie ou deplie et propose l action`() {
        show(card(twoLines))
        val config = { toggle.fetchSemanticsNode().config }
        assertEquals("2 hypothèses d’estimation", config()[SemanticsProperties.ContentDescription].single())
        assertEquals("Repliées", config()[SemanticsProperties.StateDescription])
        // Le texte visible est masqué : TalkBack ne lit pas deux fois la même ligne.
        assertTrue(SemanticsProperties.Text !in config())
        assertEquals("Afficher les hypothèses", config()[SemanticsActions.OnClick].label)
        assertTrue("action « déplier » exposée", SemanticsActions.Expand in config())

        toggle.performClick()
        compose.waitForIdle()
        assertEquals("Dépliées", config()[SemanticsProperties.StateDescription])
        assertEquals("Masquer les hypothèses", config()[SemanticsActions.OnClick].label)
        assertTrue("action « replier » exposée", SemanticsActions.Collapse in config())

        // L'action d'accessibilité replie sans toucher.
        toggle.performSemanticsAction(SemanticsActions.Collapse)
        compose.waitForIdle()
        assertEquals("Repliées", config()[SemanticsProperties.StateDescription])
        toggle.performSemanticsAction(SemanticsActions.Expand)
        compose.waitForIdle()
        assertEquals("Dépliées", config()[SemanticsProperties.StateDescription])
    }

    @Test
    fun `une carte enregistree avant le changement s affiche sans erreur`() {
        // JSON tel que l'historique du chat le garde : aucun champ ajouté par ce changement.
        val stored = """[{"card":"record","actionId":7,"kind":"Added","items":[{"entryId":"e1","label":"Pomme","quantity":"150 g","moment":"snack","day":"2026-11-02","kcal":78.0,"proteinG":0.4,"carbsG":19.0,"fatG":0.3,"assumptions":["Pomme moyenne"]}],"assumptions":["Pomme moyenne"]}]"""
        val cards = ChatCards.decode(stored)
        assertEquals(1, cards.size)
        show(cards.single() as RecordCard)
        toggle.assertIsDisplayed()
        compose.onNodeWithText("Pomme moyenne").assertDoesNotExist()
        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pomme moyenne").assertIsDisplayed()
    }

    @Test
    fun `une carte plus ancienne encore sans hypothese s affiche sans ligne a replier`() {
        val bare = """[{"card":"record","actionId":8,"kind":"Added","items":[{"entryId":"e1","label":"Pomme","moment":"snack","day":"2026-11-02","kcal":78.0}]}]"""
        show(ChatCards.decode(bare).single() as RecordCard)
        compose.onNodeWithTag(CardTags.RECORD).assertIsDisplayed()
        compose.onNodeWithTag(CardTags.ASSUMPTIONS).assertDoesNotExist()
    }

    @Test
    fun `l etat suit la carte au defilement et ne se partage pas entre cartes`() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                LazyColumn(Modifier.height(500.dp).testTag("fil")) {
                    items(20, key = { it }) { n -> CardView(card(twoLines, id = n + 1L), false, false, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
        // Déplier la première carte, partir tout en bas, revenir : seule la première est dépliée.
        compose.onAllNodes(hasTestTag(CardTags.ASSUMPTIONS_TOGGLE)).onFirst().performClick()
        compose.onNodeWithTag("fil").performScrollToIndex(19)
        compose.waitForIdle()
        compose.onNodeWithTag("fil").performScrollToIndex(0)
        compose.waitForIdle()
        val states = compose.onAllNodes(hasTestTag(CardTags.ASSUMPTIONS_TOGGLE)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.StateDescription] }
        assertEquals("Dépliées", states.first())
        assertTrue("les autres cartes restent repliées : $states", states.drop(1).all { it == "Repliées" })
    }

    @Test
    fun `l etat survit a la rotation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                Box(Modifier.background(TracklyColors.Paper).padding(16.dp)) { CardView(card(twoLines), false, false, {}, {}) }
            }
        }
        compose.waitForIdle()
        toggle.performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Dépliées", toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithText("– ${twoLines[0]}").assertIsDisplayed()
    }

    @Test
    fun `le deroule anime fonctionne aussi quand les animations sont actives`() {
        show(card(twoLines), reduced = false)
        compose.onNodeWithText(twoLines[0], substring = true).assertDoesNotExist()
        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText("– ${twoLines[0]}").assertIsDisplayed()
        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(twoLines[0], substring = true).assertDoesNotExist()
    }

    @Test
    fun capturesRepliee() {
        show(card(twoLines))
        capture("carte-hypotheses-repliees")
    }

    @Test
    fun capturesDeployee() {
        show(card(twoLines))
        toggle.performClick()
        compose.waitForIdle()
        capture("carte-hypotheses-deployees")
    }

    @Test
    fun capturesPolice200Repliee() {
        show(card(twoLines), fontScale = 2f)
        capture("carte-hypotheses-repliees-200")
    }

    @Test
    fun capturesPolice200Deployee() {
        show(card(twoLines), fontScale = 2f)
        toggle.performClick()
        compose.waitForIdle()
        capture("carte-hypotheses-deployees-200")
    }
}
