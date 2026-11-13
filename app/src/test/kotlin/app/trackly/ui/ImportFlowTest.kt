package app.trackly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.core.engine.TracklyEngine
import app.trackly.day.formatKcal
import app.trackly.importing.HistoryImportViewModel
import app.trackly.testing.SampleData
import app.trackly.ui.importing.ImportTags
import app.trackly.ui.theme.MotionPolicy
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reprise de l'historique depuis l'écran : ouverture à la demande, résumé, refus, accès par le calendrier. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ImportFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun ouvrir(engine: TracklyEngine = SampleData.emptyEngine()): HistoryImportViewModel {
        val model = HistoryImportViewModel(engine)
        compose.setContent { TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), importModel = model) }
        compose.waitForIdle()
        return model
    }

    private fun attendre(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `premier lancement, aucune invitation automatique`() {
        ouvrir()
        compose.onNodeWithTag(ImportTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithText("Reprends ton historique").assertDoesNotExist()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `open ouvre l invitation, Plus tard la referme`() {
        val model = ouvrir()
        model.open()
        compose.waitForIdle()
        compose.onNodeWithText("Reprends ton historique").assertIsDisplayed()
        compose.onNodeWithText("Choisir le fichier d’export").assertIsDisplayed()
        compose.onNodeWithText("Plus tard").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ImportTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `apres import, le resume donne journees, produits, plats et objectif actuel`() {
        val engine = SampleData.emptyEngine()
        val model = ouvrir(engine)
        model.importFrom { SampleData.historyBundle }
        attendre("C’est fait.")
        compose.onNodeWithText("HISTORIQUE REPRIS").assertIsDisplayed()
        compose.onNodeWithText("Journées").assertIsDisplayed()
        compose.onNodeWithText("du 1er septembre 2026 au 8 septembre 2026").assertIsDisplayed()
        compose.onNodeWithText("Produits").assertIsDisplayed()
        compose.onNodeWithText("Plats").assertIsDisplayed()
        compose.onNodeWithText("${formatKcal(2350)} kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("depuis le 4 septembre 2026").assertIsDisplayed()
        compose.onNodeWithText("journées identiques à l’ancien suivi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continuer").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ImportTags.SCREEN).assertDoesNotExist()
        // L'historique repris est navigable : la veille du jour en cours existe toujours.
        compose.onNodeWithText("Lun. 2 nov.").assertIsDisplayed()
    }

    @Test
    fun `reimporter le meme fichier ne donne rien de nouveau`() {
        val engine = SampleData.emptyEngine()
        val model = ouvrir(engine)
        model.importFrom { SampleData.historyBundle }
        attendre("C’est fait.")
        model.open()
        model.importFrom { SampleData.historyBundle }
        attendre("Rien de nouveau.")
        compose.onNodeWithText("Tout ce fichier est déjà dans l’app : rien n’a été écrit.").assertIsDisplayed()
    }

    @Test
    fun `fichier refuse, explication et rien n est repris`() {
        val model = ouvrir()
        model.importFrom { """{"format":"autre"}""" }
        attendre("Rien n’a été repris.")
        compose.onNodeWithText("Ce fichier n'est pas un export Trackly (format « suivi-kcal-bundle » attendu)").assertIsDisplayed()
        compose.onNodeWithText("Choisir un autre fichier").assertIsDisplayed()
        compose.onNodeWithText("Fermer").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ImportTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `le calendrier donne acces a la reprise ensuite`() {
        ouvrir()
        compose.onNodeWithText("Lun. 2 nov.").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reprendre un historique").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reprends ton historique").assertIsDisplayed()
        assertFalse(compose.onAllNodesWithText("Novembre 2026").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `une journee estimee reprise affiche l estimation`() {
        val engine = SampleData.emptyEngine()
        val model = ouvrir(engine)
        model.importFrom { SampleData.historyBundle }
        attendre("C’est fait.")
        compose.onNodeWithText("Continuer").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lun. 2 nov.").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Mois précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Mois précédent").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jeudi 3 septembre, suivi").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jeu. 3 sept.").assertIsDisplayed()
        compose.onNodeWithText("ESTIMATION").assertIsDisplayed()
        compose.onNodeWithText("Journée estimée").assertIsDisplayed()
        compose.onNodeWithText("Estimation globale validée (essai), entre ${formatKcal(2400)} et ${formatKcal(2800)} kcal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Protéines : au moins 8 sur 148 grammes").assertIsDisplayed()
    }
}
