package app.trackly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.backup.BackupBusy
import app.trackly.backup.BackupEntry
import app.trackly.backup.BackupNotice
import app.trackly.backup.BackupStatus
import app.trackly.backup.BackupUiState
import app.trackly.backup.RestoreChoice
import app.trackly.core.time.TrackingDay
import app.trackly.testing.SampleData
import app.trackly.ui.settings.BackupActions
import app.trackly.ui.settings.BackupSettingsSection
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.dev.DevTags
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.settings.BackupTags
import app.trackly.ui.theme.MotionPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Réglages › Connexion Drive (sauvegarde) : navigation, boutons, et captures de référence. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private val zone = TrackingDay.ZONE

    private val connected = BackupStatus(
        connected = true,
        accountEmail = "prenom.nom@gmail.com",
        lastSuccessAt = Instant.parse("2026-11-03T02:12:05Z"),
        lastSuccessName = "trackly-2026-11-03_03-12-05.db",
    )

    private fun choice(day: String, time: String, label: String) = RestoreChoice(
        key = day,
        title = label,
        detail = "Drive · 48 Ko",
        source = RestoreChoice.Source.Drive(BackupEntry(day, "trackly-${day}_$time.db", Instant.parse("${day}T02:00:00Z"), 48_000, null, null, 2)),
    )

    /** L'écran Réglages réel, ouvert sur la page « Connexion Drive » qui porte la section Sauvegarde. */
    @Composable
    private fun Backup(state: BackupUiState, actions: BackupActions = BackupActions()) =
        SettingsScreen(
            InMemoryApiKeyStore(),
            onClose = {},
            groups = SettingsGroups(drive = { BackupSettingsSection(state, actions, now, zone) }),
            startPage = SettingsPage.Drive,
        )

    private fun root(settings: SettingsSlot, key: String? = "sk-proj-cle-factice-test-1234abcd") {
        val engine = SampleData.engine()
        compose.setContent {
            TracklyRoot(
                engine = engine,
                motion = MotionPolicy(reduced = true),
                assistant = AssistantServices.inMemory(engine, keyStore = InMemoryApiKeyStore(key)),
                settings = settings,
            )
        }
        compose.waitForIdle()
    }

    private val driveGroups get() = SettingsGroups(drive = { BackupSettingsSection(BackupUiState(status = connected), BackupActions(), now, zone) })

    private val backupSlot get() = SettingsSlot(groups = driveGroups)

    /** La page Drive est ouverte sans défilement : sa section Sauvegarde commence en haut. */
    private fun showBackupSection() {
        compose.waitForIdle()
        compose.onNodeWithText("CONNEXION").assertIsDisplayed()
    }

    /** Les Réglages s'ouvrent sur l'accueil : trois lignes, ni clé ni sauvegarde. Chaque ligne mène à son contenu, et le retour ramène à l'accueil. */
    private fun assertReglagesUnifies() {
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assertDoesNotExist()
        compose.onNodeWithTag(BackupTags.SECTION).assertDoesNotExist()

        compose.onNodeWithTag(SettingsPage.Key.tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assertExists()
        compose.onNodeWithContentDescription("Retour aux réglages").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SettingsPage.Drive.tag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BackupTags.SECTION).assertExists()
        compose.onNodeWithText("Dernière sauvegarde : Mardi 3 novembre, 03:12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour aux réglages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsPage.Drive.tag).assertIsDisplayed()
    }

    @Test
    fun `toucher Trackly ouvre les Reglages, cle et sauvegarde sur leurs pages`() {
        root(backupSlot)
        compose.onNodeWithText("Trackly").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertReglagesUnifies()

        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `l icone du chat ouvre le meme ecran Reglages, avec la sauvegarde`() {
        root(backupSlot)
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.waitForIdle()
        assertReglagesUnifies()
    }

    @Test
    fun `sans cle, l icone Reglages du chat ouvre directement la page Modele sur la cle`() {
        root(backupSlot, key = null)
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(SettingsPage.Key.tag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Fermer les réglages").assertIsDisplayed()
    }

    @Test
    fun `appui long sur Trackly ouvre toujours le panneau de developpement`() {
        root(backupSlot)
        compose.onNodeWithText("Trackly").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag(DevTags.PANEL).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `une demande venue d une notification ouvre directement la page Drive`() {
        var request by mutableIntStateOf(0)
        val engine = SampleData.engine()
        compose.setContent {
            TracklyRoot(
                engine = engine,
                motion = MotionPolicy(reduced = true),
                settings = SettingsSlot(request, groups = driveGroups),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        request = 1
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.SECTION).assertIsDisplayed()
        compose.onNodeWithTag(SettingsPage.Drive.tag).assertDoesNotExist()

        // Ouverte directement, la page ferme les Réglages au retour (pas d'accueil entre les deux).
        compose.onNodeWithContentDescription("Fermer les réglages").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
        // Fermés, les Réglages ne se rouvrent pas tout seuls ; une nouvelle demande les rouvre sur Drive.
        request = 2
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.SECTION).assertIsDisplayed()
    }

    @Test
    fun `non connecte - un seul bouton pour connecter Drive`() {
        var connects = 0
        compose.setContent { Backup(BackupUiState(), BackupActions(connect = { connects++ })) }
        showBackupSection()
        compose.onNodeWithText("Connecter Google Drive").performClick()
        assertEquals(1, connects)
        compose.onNodeWithText("Sauvegarder maintenant").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/sauvegarde-non-connectee.png")
    }

    @Test
    fun `connecte - sauvegarder maintenant et choisir une date`() {
        var backups = 0
        var chosen: RestoreChoice? = null
        val choices = listOf(choice("2026-11-03", "03-12-05", "Mardi 3 novembre, 03:12"), choice("2026-11-02", "03-04-41", "Lundi 2 novembre, 03:04"))
        compose.setContent {
            var state by remember { mutableStateOf(BackupUiState(status = connected, choices = choices)) }
            Backup(state, BackupActions(backupNow = { backups++ }, choose = { chosen = it; state = state.copy(confirming = it) }))
        }
        showBackupSection()
        compose.onNodeWithText("Sauvegarder maintenant").performClick()
        assertEquals(1, backups)
        compose.onRoot().captureRoboImage("src/test/screenshots/sauvegarde-connectee.png")

        compose.onNodeWithContentDescription("Restaurer : Lundi 2 novembre, 03:04, Drive · 48 Ko").performScrollTo().performClick()
        assertEquals("2026-11-02", chosen?.key)
        compose.onNodeWithText("Restaurer « Lundi 2 novembre, 03:04 » ?").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `exporter dans un fichier - disponible meme sans Google, avec le resultat a cote du bouton`() {
        var exports = 0
        compose.setContent {
            var state by remember { mutableStateOf(BackupUiState()) }
            Backup(
                state,
                BackupActions(
                    exportToFile = {
                        exports++
                        state = state.copy(
                            notice = BackupNotice(
                                "Fichier exporté et vérifié : trackly-2026-11-03_08-00-00.db (48 Ko). Pour le reprendre : Restaurer › Depuis un fichier.",
                                nearExport = true,
                            ),
                        )
                    },
                ),
            )
        }
        showBackupSection()
        compose.onNodeWithText("Sauvegarder maintenant").assertDoesNotExist()
        compose.onNodeWithText("Ta clé OpenAI n'y est pas", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exporter dans un fichier").performScrollTo().performClick()
        assertEquals(1, exports)
        compose.onNodeWithText("Fichier exporté et vérifié", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Depuis un fichier").assertExists()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/sauvegarde-export.png")
    }

    @Test
    fun `export en cours - bouton grise et progression a cote`() {
        compose.setContent { Backup(BackupUiState(status = connected, busy = BackupBusy.Exporting)) }
        compose.onNodeWithText("Export en cours…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exporter dans un fichier").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `rien a exporter - le message est a cote du bouton`() {
        compose.setContent {
            Backup(
                BackupUiState(
                    status = connected,
                    notice = BackupNotice("Rien à exporter : l’app ne contient encore aucune donnée.", nearExport = true),
                ),
            )
        }
        compose.onNodeWithText("Rien à exporter", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `confirmation avant de remplacer les donnees`() {
        var confirms = 0
        var cancels = 0
        val c = choice("2026-11-02", "03-04-41", "Lundi 2 novembre, 03:04")
        compose.setContent {
            Backup(BackupUiState(status = connected, confirming = c), BackupActions(confirmRestore = { confirms++ }, cancelChoice = { cancels++ }))
        }
        compose.onNodeWithText("Annuler").performScrollTo()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/sauvegarde-confirmation.png")
        compose.onNodeWithText("Restaurer").performScrollTo().performClick()
        compose.onNodeWithText("Annuler").performScrollTo().performClick()
        assertEquals(1, confirms)
        assertEquals(1, cancels)
    }

    @Test
    fun `reconnexion demandee - bouton reconnecter visible`() {
        compose.setContent {
            Backup(
                BackupUiState(
                    status = connected.copy(
                        lastSuccessAt = Instant.parse("2026-11-02T02:10:00Z"),
                        needsReconnect = true,
                        lastFailureAt = Instant.parse("2026-11-03T02:05:00Z"),
                        lastFailureMessage = "Google demande de reconnecter Drive",
                    ),
                ),
            )
        }
        compose.onNodeWithText("Reconnecter Google Drive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dernier essai (Mardi 3 novembre, 03:05) : Google demande de reconnecter Drive.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `copie refusee par le garde-fou - le refus se lit dans l etat de la sauvegarde`() {
        compose.setContent {
            Backup(
                BackupUiState(
                    status = connected.copy(
                        lastRefusalAt = Instant.parse("2026-11-03T07:40:00Z"),
                        lastRefusalMessage = "Rien envoyé : aucune consommation dans l'app, la copie Drive reste la bonne",
                    ),
                ),
            )
        }
        compose.onNodeWithText(
            "Copie refusée (Mardi 3 novembre, 08:40) : Rien envoyé : aucune consommation dans l'app, la copie Drive reste la bonne.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copie quelques minutes après chaque changement", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `dossier introuvable apres reinstallation - l utilisateur est guide`() {
        compose.setContent { Backup(BackupUiState(status = connected, choices = emptyList(), folderMissing = true)) }
        compose.onNodeWithText("Aucune sauvegarde trouvée.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dossier « Trackly » introuvable", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("choisis-y Drive directement", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** Le guide vers les anciennes copies reste affiché jusqu'à une restauration ou « Fermer ce message ». */
    @Test
    fun `anciennes copies invisibles - le guide reste dans Restaurer et se ferme a la main`() {
        var dismissed = 0
        compose.setContent {
            Backup(
                BackupUiState(status = connected.copy(hiddenCopiesAt = Instant.parse("2026-11-03T08:40:00Z"))),
                BackupActions(dismissHiddenCopies = { dismissed++ }),
            )
        }
        compose.onNodeWithText("Si tu avais des sauvegardes Trackly avant le mardi 3 novembre, 09:40", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Drive dans le sélecteur", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sors-le de la corbeille", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fermer ce message").performScrollTo().performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `sans anciennes copies invisibles - aucun guide`() {
        compose.setContent { Backup(BackupUiState(status = connected)) }
        compose.onNodeWithText("Fermer ce message").assertDoesNotExist()
    }
}
