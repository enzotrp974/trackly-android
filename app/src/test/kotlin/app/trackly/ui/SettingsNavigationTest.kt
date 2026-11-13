package app.trackly.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.setup.SetupState
import app.trackly.setup.SetupStep
import app.trackly.testing.SampleData
import app.trackly.ui.settings.AlertsTags
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsPage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trackly.ui.components.pressable
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.settings.SetupSettingsSection
import app.trackly.ui.setup.SetupActions
import app.trackly.ui.setup.SetupNav
import app.trackly.ui.setup.SetupScreen
import app.trackly.ui.setup.SetupSlot
import app.trackly.ui.setup.SetupTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Navigation des Réglages : l'accueil à trois lignes, les pages, les deux façons de revenir (bouton et retour système),
 * et le lien avec la mise en route (elle remplace vraiment les Réglages à l'écran, puis les rend).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val fakeKey = "sk-proj-cle-factice-test-1234abcd" // gitleaks:allow (fausse clé de test)

    private fun marker(tag: String): @Composable ColumnScope.() -> Unit = { BasicText("contenu $tag", Modifier.testTag(tag)) }

    private val groups = SettingsGroups(top = marker("haut"), model = marker("modele"), drive = marker("drive"), goals = marker("objectifs"))

    private val contents = mapOf(SettingsPage.Model to "modele", SettingsPage.Drive to "drive", SettingsPage.Goals to "objectifs")

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertHome() {
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag("haut").assertIsDisplayed()
        SettingsPage.entries.forEach { compose.onNodeWithTag(it.tag).assertIsDisplayed() }
        contents.values.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription("Fermer les réglages").assertIsDisplayed()
    }

    private fun assertOnlyPage(page: SettingsPage) {
        contents.forEach { (p, tag) ->
            if (p == page) compose.onNodeWithTag(tag).assertIsDisplayed() else compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.onNodeWithTag("haut").assertDoesNotExist()
        SettingsPage.entries.forEach { compose.onNodeWithTag(it.tag).assertDoesNotExist() }
        compose.onNodeWithText(page.title).assertIsDisplayed()
    }

    private fun home(onClose: () -> Unit = {}) {
        compose.setContent { SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = onClose, groups = groups) }
        compose.waitForIdle()
    }

    @Test
    fun `accueil - l en-tete puis les themes, sans section Cle`() {
        home()
        assertHome()
        compose.onNodeWithText("Réglages").assertIsDisplayed()
        // Rien que des thèmes : un titre par ligne, sans contenu ni explication.
        SettingsPage.entries.forEach { compose.onNodeWithText(it.title).assertIsDisplayed() }
    }

    @Test
    fun `capture de l accueil avec la ligne Mise en route`() {
        val state = setupState(ApiKeyStatus.Present("abcd"), KeyVerificationState.Valid)
        compose.setContent {
            SettingsScreen(
                InMemoryApiKeyStore(fakeKey),
                onClose = {},
                groups = SettingsGroups(top = { SetupSettingsSection(state, onOpen = {}) }),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertIsDisplayed()
        SettingsPage.entries.forEach { compose.onNodeWithTag(it.tag).assertIsDisplayed() }
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-accueil.png")
    }

    @Test
    fun `chaque ligne ouvre sa page, et le bouton Retour ramene a l accueil`() {
        var closes = 0
        home { closes++ }
        SettingsPage.entries.forEach { page ->
            compose.onNodeWithTag(page.tag).performClick()
            compose.waitForIdle()
            assertOnlyPage(page)
            compose.onNodeWithContentDescription("Fermer les réglages").assertDoesNotExist()
            compose.onNodeWithContentDescription("Retour aux réglages").performClick()
            compose.waitForIdle()
            assertHome()
        }
        assertEquals("revenir d'une page à l'accueil ne ferme pas les Réglages", 0, closes)
        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `le retour systeme ramene a l accueil, puis ferme les Reglages`() {
        var closes = 0
        home { closes++ }
        SettingsPage.entries.forEach { page ->
            compose.onNodeWithTag(page.tag).performClick()
            compose.waitForIdle()
            assertOnlyPage(page)
            systemBack()
            assertHome()
        }
        assertEquals(0, closes)
        systemBack()
        assertEquals(1, closes)
    }

    @Test
    fun `la section Cle n existe que sur sa page, et rien d autre n y est`() {
        home()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(SettingsPage.Drive.tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription("Retour aux réglages").performClick()
        compose.onNodeWithTag(SettingsPage.Goals.tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription("Retour aux réglages").performClick()
        compose.onNodeWithTag(SettingsPage.Key.tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assertExists()
        compose.onNodeWithTag("modele").assertDoesNotExist()
    }

    @Test
    fun `scrollToKey ouvre directement la page Cle, le retour ferme les Reglages`() {
        var closes = 0
        compose.setContent {
            SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = { closes++ }, groups = groups, scrollToKey = true)
        }
        compose.waitForIdle()
        assertOnlyPage(SettingsPage.Key)
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour aux réglages").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `une demande arrivee Reglages ouverts amene la page demandee`() {
        var start by mutableStateOf<SettingsPage?>(null)
        compose.setContent { SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = {}, groups = SettingsGroups(drive = marker("drive")), startPage = start) }
        compose.waitForIdle()
        start = SettingsPage.Drive
        compose.waitForIdle()
        compose.onNodeWithTag("drive").assertIsDisplayed()
    }

    @Test
    fun `startPage ouvre directement la page, le retour systeme ferme les Reglages`() {
        var closes = 0
        compose.setContent {
            SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = { closes++ }, groups = groups, startPage = SettingsPage.Goals)
        }
        compose.waitForIdle()
        assertOnlyPage(SettingsPage.Goals)
        compose.onNodeWithContentDescription("Retour aux réglages").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fermer les réglages").assertIsDisplayed()
        systemBack()
        assertEquals(1, closes)
    }

    // ───── Avec la vraie racine : la mise en route et les Réglages ─────

    private fun setupState(key: ApiKeyStatus = ApiKeyStatus.Missing, check: KeyVerificationState = KeyVerificationState.Unchecked) =
        SetupState.of(key, check, notificationsAllowed = false, driveConnected = false, batteryDone = false, importDone = false, exportFileDeleted = false, dismissed = false)

    private fun root(nav: SetupNav, key: String? = null) {
        val engine = SampleData.engine()
        val state = setupState()
        compose.setContent {
            TracklyRoot(
                engine = engine,
                motion = MotionPolicy(reduced = true),
                assistant = AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore(key)),
                settings = SettingsSlot(
                    setup = SetupSlot(nav) { n, _ -> SetupScreen(state, "Autoriser", SetupActions(openKey = n::requestKey, close = n::hide)) },
                    groups = SettingsGroups(
                        top = { SetupSettingsSection(state, onOpen = nav::show) },
                        model = marker("modele"),
                    ),
                ),
            )
        }
        compose.waitForIdle()
    }

    private fun openSettings() {
        compose.onNodeWithText("Trackly").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun `depuis les Reglages, Mise en route affiche vraiment la liste et les Reglages ne sont plus dessines, le retour les ramene`() {
        val nav = SetupNav()
        root(nav)
        openSettings()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertIsDisplayed().performClick()
        compose.waitForIdle()

        // La liste est à l'écran, et les Réglages ne sont plus composés : rien ne peut être dessiné par-dessus ni dessous.
        assertTrue(nav.open)
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertDoesNotExist()
        SettingsPage.entries.forEach { compose.onNodeWithTag(it.tag).assertDoesNotExist() }
        compose.onRoot().captureRoboImage("src/test/screenshots/mise-en-route-depuis-reglages.png")

        // Retour : la liste se ferme et les Réglages reviennent.
        compose.onNodeWithTag(SetupTags.BACK).performClick()
        compose.waitForIdle()
        assertFalse(nav.open)
        compose.onNodeWithTag(SetupTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertIsDisplayed()
    }

    @Test
    fun `Plus tard de la liste ramene aussi aux Reglages`() {
        val nav = SetupNav()
        root(nav)
        openSettings()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SetupTags.LATER).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SetupTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun `le retour systeme de la liste ramene aux Reglages`() {
        val nav = SetupNav()
        root(nav)
        openSettings()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()
        systemBack()
        assertFalse(nav.open)
        compose.onNodeWithTag(SetupTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertIsDisplayed()
    }

    @Test
    fun `Mise en route, Regler la cle - page Cle seule, puis le retour ramene la liste`() {
        val nav = SetupNav()
        root(nav)
        openSettings()
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()

        compose.onNodeWithTag(SetupTags.action(SetupStep.Key)).performClick()
        compose.waitForIdle()
        assertFalse(nav.open)
        compose.onNodeWithTag(SetupTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(SettingsPage.Key.title).assertIsDisplayed()
        compose.onNodeWithTag("modele").assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assertExists()
        // Ouverte directement, la page n'a pas d'accueil derrière elle.
        compose.onNodeWithContentDescription("Retour aux réglages").assertDoesNotExist()

        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()
        assertTrue(nav.open)
    }
}
