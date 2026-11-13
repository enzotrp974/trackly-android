package app.trackly.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.alerts.AlertKind
import app.trackly.alerts.AlertLine
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.setup.SetupState
import app.trackly.setup.SetupStep
import app.trackly.testing.SampleData
import app.trackly.ui.settings.AlertsTags
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsAlertsSection
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.settings.SetupSettingsSection
import app.trackly.ui.setup.SetupActions
import app.trackly.ui.setup.SetupNav
import app.trackly.ui.setup.SetupScreen
import app.trackly.ui.setup.SetupSlot
import app.trackly.ui.setup.SetupTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mise en route, alertes discrètes des Réglages (sauvegarde de plus de 36 h) : parcours dans l'interface. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class SetupUiTest {
    @get:Rule val compose = createComposeRule()

    private fun state(
        key: ApiKeyStatus = ApiKeyStatus.Missing,
        check: KeyVerificationState = KeyVerificationState.Unchecked,
        notifications: Boolean = false,
        drive: Boolean = false,
        battery: Boolean = false,
        imported: Boolean = true,
        exportDeleted: Boolean = false,
    ) = SetupState.of(key, check, notifications, drive, battery, imported, exportDeleted, dismissed = false)

    /** Toutes les lignes, y compris « Fichier d'export », et aucune cochée. */
    private fun allToDo() = state().let { it.copy(items = it.items.map { item -> item.copy(done = false) }) }

    private fun screen(
        state: SetupState,
        actions: SetupActions = SetupActions(),
        label: String = "Autoriser les notifications",
        driveNotice: String? = null,
    ) {
        compose.setContent { SetupScreen(state, label, actions, driveNotice = driveNotice) }
        compose.waitForIdle()
    }

    /** App neuve : Google Drive d'abord, avec ce qu'il apporte ; la recherche s'affiche sous la ligne. */
    @Test
    fun `app neuve - la connexion Google en premier, la recherche de la sauvegarde visible`() {
        var connected = 0
        val offer = SetupState.of(
            ApiKeyStatus.Missing, KeyVerificationState.Unchecked, false, false, false, false, false, dismissed = false,
            restoreOffer = true,
        )
        screen(offer, SetupActions(connectDrive = { connected++ }), driveNotice = "Recherche de ta sauvegarde sur Drive…")
        compose.onRoot().captureRoboImage("src/test/screenshots/mise-en-route-app-neuve.png")
        assertEquals(SetupStep.Drive, offer.items.first().step)
        compose.onNodeWithText(SetupState.RESTORE_OFFER_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.NOTICE).assertTextEquals("Recherche de ta sauvegarde sur Drive…")
        compose.onNodeWithTag(SetupTags.action(SetupStep.Drive)).performClick()
        assertEquals(1, connected)
    }

    @Test
    fun `au depart, les etapes a faire dans l ordre, avec une action chacune`() {
        assertEquals(
            listOf(SetupStep.Key, SetupStep.History, SetupStep.Drive, SetupStep.Notifications, SetupStep.Battery, SetupStep.ExportFile),
            SetupStep.entries,
        )
        screen(state(imported = false), SetupActions(pickHistory = {}))
        compose.onRoot().captureRoboImage("src/test/screenshots/mise-en-route.png")
        compose.onNodeWithTag(SetupTags.PROGRESS).assertTextEquals("0 sur 5")
        (SetupStep.entries - SetupStep.ExportFile).forEach { compose.onNodeWithTag(SetupTags.row(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(SetupTags.action(SetupStep.Key)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.History)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Drive)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Notifications)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Battery)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Terminer sans tout cocher").assertExists()
    }

    @Test
    fun `les boutons portent les noms courts`() {
        screen(allToDo(), SetupActions(pickHistory = {}))
        listOf("Régler", "Choisir le fichier", "Connecter", "Autoriser les notifications", "Ouvrir", "C’est fait", "C’est supprimé").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `apres la reprise, Historique est coche et la ligne Fichier d export s ajoute`() {
        screen(state(imported = true), SetupActions(pickHistory = {}))
        compose.onNodeWithTag(SetupTags.PROGRESS).assertTextEquals("1 sur 6")
        compose.onNode(hasContentDescription("Historique") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fait"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.History)).assertDoesNotExist()
        compose.onNodeWithTag(SetupTags.markDone(SetupStep.ExportFile)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Historique pas faite - le bouton n existe que si le choix du fichier est branche, et il l appelle`() {
        screen(state(imported = false))
        compose.onNodeWithTag(SetupTags.row(SetupStep.History)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Historique") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "À faire"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.action(SetupStep.History)).assertDoesNotExist()
        compose.onNodeWithText("Choisir le fichier").assertDoesNotExist()
    }

    @Test
    fun `Historique pas faite - Choisir le fichier appelle le selecteur`() {
        var picks = 0
        screen(state(imported = false), SetupActions(pickHistory = { picks++ }))
        compose.onNodeWithTag(SetupTags.action(SetupStep.History)).performScrollTo().performClick()
        assertEquals(1, picks)
    }

    @Test
    fun `message de Drive affiche sous la ligne Drive, jamais ailleurs`() {
        screen(state(), driveNotice = "Projet Google Cloud de Trackly pas prêt (identifiant OAuth Android, package ou empreinte SHA-1).")
        compose.onNodeWithTag(SetupTags.NOTICE).performScrollTo().assertIsDisplayed()
            .assertTextContains("Projet Google Cloud de Trackly pas prêt", substring = true)
    }

    @Test
    fun `sans message de Drive, aucune ligne de message`() {
        screen(state())
        compose.onNodeWithTag(SetupTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun `chaque ligne declenche son action`() {
        val calls = mutableListOf<String>()
        screen(
            allToDo(),
            SetupActions(
                openKey = { calls += "cle" },
                pickHistory = { calls += "historique" },
                allowNotifications = { calls += "notifications" },
                connectDrive = { calls += "drive" },
                openBattery = { calls += "batterie" },
                setBatteryDone = { calls += "batterie-faite=$it" },
                setExportFileDeleted = { calls += "export=$it" },
                finish = { calls += "terminer" },
                close = { calls += "fermer" },
            ),
        )
        listOf(
            SetupTags.action(SetupStep.Key), SetupTags.action(SetupStep.History), SetupTags.action(SetupStep.Drive),
            SetupTags.action(SetupStep.Notifications), SetupTags.action(SetupStep.Battery), SetupTags.markDone(SetupStep.Battery), SetupTags.markDone(SetupStep.ExportFile),
            SetupTags.FINISH, SetupTags.LATER, SetupTags.BACK,
        ).forEach { tag ->
            // Le retour est dans l'en-tête fixe, les autres dans la colonne défilante.
            compose.onNodeWithTag(tag).let { if (tag == SetupTags.BACK) it else it.performScrollTo() }.performClick()
        }
        assertEquals(
            listOf("cle", "historique", "drive", "notifications", "batterie", "batterie-faite=true", "export=true", "terminer", "fermer", "fermer"),
            calls,
        )
    }

    @Test
    fun `batterie Samsung - la consigne tient en une ligne`() {
        screen(state())
        compose.onNodeWithText("Sans restriction, et jamais en veille.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `details vides sauf probleme - export et batterie gardent leur consigne courte`() {
        screen(allToDo())
        compose.onNodeWithText("Supprime-le du téléphone et de Drive.").performScrollTo().assertIsDisplayed()
        // Les lignes sans problème ne disent rien de plus que leur titre.
        assertEquals("", allToDo().item(SetupStep.History)?.detail)
        assertEquals("", allToDo().item(SetupStep.Drive)?.detail)
        assertEquals("", allToDo().item(SetupStep.Notifications)?.detail)
    }

    @Test
    fun `sans reprise d historique, pas de ligne fichier d export`() {
        screen(state(imported = false))
        assertEquals(0, compose.onAllNodesWithTag(SetupTags.row(SetupStep.ExportFile)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(SetupTags.PROGRESS).assertTextEquals("0 sur 5")
    }

    @Test
    fun `tout est fait - Terminer, et les etapes cochees perdent leur bouton`() {
        screen(
            state(ApiKeyStatus.Present("abcd"), KeyVerificationState.Valid, notifications = true, drive = true, battery = true, exportDeleted = true),
        )
        compose.onNodeWithTag(SetupTags.PROGRESS).assertTextEquals("6 sur 6")
        compose.onNodeWithText("Terminer").assertExists()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Key)).assertDoesNotExist()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Notifications)).assertDoesNotExist()
        // Les deux étapes manuelles se décochent.
        compose.onNodeWithTag(SetupTags.undo(SetupStep.Battery)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.undo(SetupStep.ExportFile)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `cle enregistree mais refusee - la ligne reste a faire et le dit`() {
        screen(state(ApiKeyStatus.Present("abcd"), KeyVerificationState.Refused))
        compose.onNodeWithText("OpenAI refuse cette clé.").assertExists()
        compose.onNodeWithTag(SetupTags.action(SetupStep.Key)).assertExists()
    }

    @Test
    fun `permission deja demandee - le bouton des notifications mene aux reglages d Android`() {
        screen(state(), label = "Ouvrir les réglages de notification")
        compose.onNodeWithTag(SetupTags.action(SetupStep.Notifications)).assertExists()
        compose.onNodeWithText("Ouvrir les réglages de notification").assertExists()
    }

    // ───── Entrée « Mise en route » des Réglages ─────

    @Test
    fun `les Reglages proposent la liste tant qu il reste une etape, et plus ensuite`() {
        var opened = 0
        val pending = state(ApiKeyStatus.Present("abcd"), KeyVerificationState.Valid, notifications = true)
        compose.setContent { SetupSettingsSection(pending) { opened++ } }
        compose.onNodeWithTag(AlertsTags.SETUP).assertIsDisplayed()
        // Une seule ligne : titre, progression et flèche.
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).assertIsDisplayed()
            .assertTextContains("Mise en route", substring = true)
            .assertTextContains("3 sur 6", substring = true)
        compose.onNodeWithTag(AlertsTags.SETUP_OPEN).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `liste complete - l entree des Reglages disparait`() {
        val done = state(ApiKeyStatus.Present("abcd"), KeyVerificationState.Valid, notifications = true, drive = true, battery = true, exportDeleted = true)
        compose.setContent { SetupSettingsSection(done) {} }
        compose.onNodeWithTag(AlertsTags.SETUP).assertDoesNotExist()
    }

    // ───── Alertes ─────

    @Test
    fun `alertes - sauvegarde de plus de 36 h et notifications bloquees, avec leur action`() {
        val taps = mutableListOf<AlertKind>()
        val lines = listOf(
            AlertLine(AlertKind.StaleBackup, "La dernière sauvegarde Drive date de plus de 36 heures.", "Sauvegarder maintenant"),
            AlertLine(AlertKind.NotificationsOff, "Active les notifications pour l’invitation du lundi.", "Autoriser les notifications"),
        )
        compose.setContent { SettingsAlertsSection(lines) { taps += it } }
        compose.onNodeWithText("plus de 36 heures", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.action(AlertKind.StaleBackup)).performClick()
        compose.onNodeWithTag(AlertsTags.action(AlertKind.NotificationsOff)).performClick()
        assertEquals(listOf(AlertKind.StaleBackup, AlertKind.NotificationsOff), taps)
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-alertes.png")
    }

    @Test
    fun `aucune alerte - rien n est affiche`() {
        compose.setContent { SettingsAlertsSection(emptyList()) {} }
        compose.onNodeWithTag(AlertsTags.SECTION).assertDoesNotExist()
    }

    // ───── Navigation ─────

    @Test
    fun `la ligne Cle de la mise en route ouvre les Reglages, deja defiles jusqu a la section Cle`() {
        val nav = SetupNav()
        val engine = SampleData.engine()
        compose.setContent {
            TracklyRoot(
                engine = engine,
                motion = MotionPolicy(reduced = true),
                assistant = AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore("sk-proj-cle-factice-test-1234abcd")),
                settings = SettingsSlot(
                    setup = SetupSlot(nav) { n, _ -> SetupScreen(state(), "Autoriser", SetupActions(openKey = n::requestKey, close = n::hide)) },
                    groups = SettingsGroups(
                        model = {
                            BasicText("Réglages avant la clé")
                            Spacer(Modifier.height(2_400.dp))
                        },
                    ),
                ),
            )
        }
        compose.waitForIdle()
        nav.show()
        compose.waitForIdle()
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()

        compose.onNodeWithTag(SetupTags.action(SetupStep.Key)).performClick()
        compose.waitForIdle()
        assertFalse(nav.open)
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertIsDisplayed()
        compose.onNodeWithContentDescription("Fermer la mise en route").assertDoesNotExist()

        // Ouverts pour la clé, les Réglages rouvrent la liste en se fermant.
        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(SetupTags.SCREEN).assertIsDisplayed()
        assertTrue(nav.open)
    }

    @Test
    fun `la mise en route s ouvre d elle-meme une fois, Plus tard la referme`() {
        var due = true
        val nav = SetupNav(autoOpen = { due.also { due = false } })
        nav.openIfDue()
        assertTrue(nav.open)
        nav.hide()
        nav.openIfDue()
        assertFalse(nav.open)
    }

    @Test
    fun `titre de la progression lisible`() {
        screen(state(ApiKeyStatus.Present("abcd"), KeyVerificationState.Valid, notifications = true, drive = true))
        compose.onNodeWithTag(SetupTags.PROGRESS).assertTextEquals("4 sur 6")
    }
}
