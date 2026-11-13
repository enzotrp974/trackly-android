package app.trackly.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.AssistantError
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.dictation.DictationFailure
import app.trackly.dictation.DictationMode
import app.trackly.dictation.DictationPhase
import app.trackly.dictation.DictationServices
import app.trackly.dictation.DictationUiState
import app.trackly.dictation.FakeAudioSource
import app.trackly.dictation.FakeFile
import app.trackly.dictation.FakeLive
import app.trackly.dictation.FakeMic
import app.trackly.dictation.FixedMicPermission
import app.trackly.dictation.InMemoryDictationSettings
import app.trackly.dictation.LiveEvent
import app.trackly.dictation.PermissionPrompt
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.chat.ChatTags
import app.trackly.ui.chat.DictationActions
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.chat.MicTags
import app.trackly.ui.settings.DictationSettingsSection
import app.trackly.ui.settings.DictationTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Dictée dans le chat : bouton micro, écran en direct, bandeau, permission, erreurs, et le parcours complet. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DictationUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = DictationActions(
        onMic = { calls += "micro" },
        onSend = { calls += "envoyer" },
        onStopEdit = { calls += "relire" },
        onCancel = { calls += "annuler" },
        onAllowMic = { calls += "autoriser" },
        onOpenAppSettings = { calls += "parametres" },
        onDismiss = { calls += "fermer" },
        onRetry = { calls += "reessayer" },
        onKeepText = { calls += "garder" },
    )

    private fun show(state: DictationUiState, onOpenSettings: () -> Unit = { calls += "reglages" }) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                ChatShell(
                    dateLabel = "Mardi 3 novembre",
                    state = ChatUiState(),
                    onBack = {},
                    dictation = DictationBinding(state, actions),
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        compose.waitForIdle()
    }

    private val listening = DictationUiState(
        phase = DictationPhase.Listening,
        mode = DictationMode.Live,
        text = "Comme snack j’ai pris une barre Nuvo",
        level = 0.4f,
        elapsedMs = 7_000,
    )

    @Test
    fun `le bouton micro est dans la ligne de saisie et lance la dictee`() {
        show(DictationUiState())
        compose.onNodeWithTag(MicTags.MIC).assertIsDisplayed().performClick()
        assertEquals(listOf("micro"), calls)
        compose.onNodeWithTag(ChatTags.SEND).assertIsDisplayed()
    }

    @Test
    fun `sans dictee branchee, pas de bouton micro`() {
        compose.setContent { ChatShell(dateLabel = "Mardi 3 novembre", state = ChatUiState(), onBack = {}) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(MicTags.MIC)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `en direct - le texte dicte, la duree et les trois gestes sont affiches`() {
        show(listening)
        compose.onNodeWithTag(MicTags.PANEL).assertIsDisplayed()
        compose.onNodeWithText("Comme snack j’ai pris une barre Nuvo").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.TIMER).assertIsDisplayed()
        compose.onNodeWithText("0:07").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.STATUS).assertIsDisplayed()

        compose.onNodeWithTag(MicTags.SEND).performClick()
        compose.onNodeWithTag(MicTags.EDIT).performClick()
        compose.onNodeWithTag(MicTags.CANCEL).performClick()
        assertEquals(listOf("envoyer", "relire", "annuler"), calls)
    }

    @Test
    fun `en direct - pendant la connexion, un mot rassure`() {
        show(listening.copy(text = "", connecting = true, elapsedMs = 1_000))
        compose.onNodeWithText("Parle déjà : je garde tout, rien n’est perdu.").assertIsDisplayed()
    }

    @Test
    fun `en direct - arret demande - le panneau attend le texte final`() {
        show(listening.copy(phase = DictationPhase.Finishing))
        compose.onNodeWithText("Je termine le texte…").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag(MicTags.SEND)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `erreur en direct - le texte partiel est garde, les gestes utiles sont proposes`() {
        show(
            listening.copy(
                phase = DictationPhase.Failed,
                failure = DictationFailure.Api(AssistantError.NoInternet),
                canRetry = true,
            ),
        )
        compose.onNodeWithText("Pas de connexion Internet").assertIsDisplayed()
        compose.onNodeWithText("Comme snack j’ai pris une barre Nuvo").assertIsDisplayed()
        compose.onNodeWithText("Le texte déjà dicté est gardé.").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.RETRY).performClick()
        compose.onNodeWithTag(MicTags.KEEP_TEXT).performClick()
        compose.onNodeWithTag(MicTags.CLOSE).performClick()
        assertEquals(listOf("reessayer", "garder", "fermer"), calls)
    }

    @Test
    fun `cle refusee - meme message que le chat, et le bouton ouvre les reglages`() {
        show(listening.copy(phase = DictationPhase.Failed, failure = DictationFailure.Api(AssistantError.KeyRejected)))
        compose.onNodeWithText("Clé OpenAI refusée").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.KEY).performClick()
        assertEquals(listOf("reglages"), calls)
    }

    @Test
    fun `credit epuise - message distinct`() {
        show(listening.copy(phase = DictationPhase.Failed, failure = DictationFailure.Api(AssistantError.CreditExhausted)))
        compose.onNodeWithText("Crédit OpenAI épuisé").assertIsDisplayed()
    }

    @Test
    fun `apres l arret - le chat reste tel quel, un bandeau montre l enregistrement`() {
        show(listening.copy(mode = DictationMode.AfterStop, text = "", elapsedMs = 12_000))
        assertTrue(compose.onAllNodes(hasTestTag(MicTags.PANEL)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
        compose.onNodeWithTag(MicTags.STRIP).assertIsDisplayed()
        compose.onNodeWithText("0:12").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.STOP).performClick()
        compose.onNodeWithTag(MicTags.CANCEL).performClick()
        assertEquals(listOf("relire", "annuler"), calls)
    }

    @Test
    fun `apres l arret - transcription en cours`() {
        show(listening.copy(mode = DictationMode.AfterStop, phase = DictationPhase.Finishing, text = ""))
        compose.onNodeWithText("Transcription…").assertIsDisplayed()
    }

    @Test
    fun `permission - premiere fois - explication, puis la fenetre du systeme`() {
        show(DictationUiState(phase = DictationPhase.Permission, permission = PermissionPrompt.Explain))
        compose.onNodeWithText("Autoriser le micro ?").assertIsDisplayed()
        compose.onNodeWithText("Trackly écoute ta voix", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(MicTags.ALLOW).performClick()
        compose.onNodeWithTag(MicTags.CLOSE).performClick()
        assertEquals(listOf("autoriser", "fermer"), calls)
    }

    @Test
    fun `permission refusee - on peut reessayer ou ecrire`() {
        show(DictationUiState(phase = DictationPhase.Permission, permission = PermissionPrompt.Denied))
        compose.onNodeWithText("Micro refusé").assertIsDisplayed()
        compose.onNodeWithTag(MicTags.RETRY).performClick()
        assertEquals(listOf("micro"), calls)
        compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
    }

    @Test
    fun `permission refusee pour de bon - chemin vers les parametres de l app`() {
        show(DictationUiState(phase = DictationPhase.Permission, permission = PermissionPrompt.Blocked))
        compose.onNodeWithText("Le micro est bloqué").assertIsDisplayed()
        compose.onNodeWithText("Ouvre les paramètres de Trackly", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(MicTags.APP_SETTINGS).performClick()
        compose.onNodeWithTag(MicTags.RETRY).performClick()
        assertEquals(listOf("parametres", "micro"), calls)
    }

    @Test
    fun `le bouton micro est bloque pendant une dictee`() {
        show(listening)
        compose.onNodeWithTag(MicTags.MIC).performClick()
        assertTrue("le micro ne se relance pas pendant une dictée", calls.isEmpty())
    }

    // ───── Réglages › Dictée ─────

    @Test
    fun `reglages - le mode en direct est coche par defaut, le choix change le mode`() {
        val settings = InMemoryDictationSettings()
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                val mode by settings.mode.collectAsState()
                DictationSettingsSection(mode, settings::setMode)
            }
        }
        compose.onNodeWithText("En direct").assertIsDisplayed()
        compose.onNodeWithText("Environ 1,7 centimes la minute").assertIsDisplayed()
        compose.onNodeWithText("Environ 0,45 centime la minute").assertIsDisplayed()
        assertEquals(DictationMode.Live, settings.mode.value)

        compose.onNodeWithTag(DictationTags.option(DictationMode.AfterStop)).performClick()
        compose.waitForIdle()
        assertEquals(DictationMode.AfterStop, settings.mode.value)
        compose.onNodeWithTag(DictationTags.option(DictationMode.Live)).performClick()
        compose.waitForIdle()
        assertEquals(DictationMode.Live, settings.mode.value)
    }

    // ───── Parcours complet ─────

    private val monday = LocalDate.parse("2026-11-02")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Attend que les 2 s de parole factice aient été captées (la durée affichée passe à 0:02). Arrêter avant, c'est risquer
     * moins de 0,3 s d'audio, donc « rien entendu » : l'arrêt dépend de la capture, pas de l'horloge.
     */
    private fun waitForSpeech() {
        compose.waitUntil(8_000) { compose.onAllNodesWithText("0:02").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openChat(engine: TracklyEngine, services: DictationServices, assistant: AssistantServices) {
        compose.setContent {
            TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant, dictation = services)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Écrire à Trackly").performClick()
        compose.waitForIdle()
    }

    private fun services(
        settings: InMemoryDictationSettings = InMemoryDictationSettings(),
        permission: FixedMicPermission = FixedMicPermission(),
        live: FakeLive = FakeLive(),
        file: FakeFile = FakeFile(),
        source: FakeAudioSource = FakeAudioSource().speak(2.0),
    ) = DictationServices(settings, permission, FakeMic(source), live, file)

    @Test
    fun `parcours - micro, parler, Envoyer - le texte devient le message et la consommation est enregistree`() {
        val engine = engine()
        val live = FakeLive().apply {
            script = { c ->
                c.emit(LiveEvent.Completed("item_1", "comme snack j'ai pris une barre Nuvo et une pomme, 150 g", seconds = 2.0))
            }
        }
        val assistant = AssistantServices.inMemory(
            engine,
            ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"),
            InMemoryApiKeyStore("sk-test-cle-factice-0000000000"),
        )
        openChat(engine, services(live = live), assistant)

        compose.onNodeWithTag(MicTags.MIC).performClick()
        waitFor(MicTags.PANEL)
        waitForSpeech()
        compose.onNodeWithTag(MicTags.SEND).performClick()

        waitFor(CardTags.RECORD)
        compose.onNodeWithTag(ChatTags.BUBBLE).assertIsDisplayed()
        compose.onNodeWithText("comme snack j'ai pris une barre Nuvo et une pomme, 150 g").assertIsDisplayed()
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)
        assertTrue("le panneau s'est refermé", compose.onAllNodes(hasTestTag(MicTags.PANEL)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `parcours - gros micro de l accueil - la dictee demarre dans le chat, le texte devient le message`() {
        val engine = engine()
        val live = FakeLive().apply {
            script = { c ->
                c.emit(LiveEvent.Completed("item_1", "comme snack j'ai pris une barre Nuvo et une pomme, 150 g", seconds = 2.0))
            }
        }
        val assistant = AssistantServices.inMemory(
            engine,
            ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json"),
            InMemoryApiKeyStore("sk-test-cle-factice-0000000000"),
        )
        compose.setContent {
            TracklyRoot(engine = engine, motion = MotionPolicy(reduced = true), assistant = assistant, dictation = services(live = live))
        }
        compose.waitForIdle()

        // Un seul geste depuis l'accueil : le chat s'ouvre et la dictée écoute déjà.
        compose.onNodeWithTag(app.trackly.ui.day.DayTags.MIC).performClick()
        waitFor(MicTags.PANEL)
        waitForSpeech()
        compose.onNodeWithTag(MicTags.SEND).performClick()

        waitFor(CardTags.RECORD)
        compose.onNodeWithText("comme snack j'ai pris une barre Nuvo et une pomme, 150 g").assertIsDisplayed()
        assertEquals(300.0, runBlocking { engine.daySummary(monday).totals.kcal }, 0.001)
    }

    @Test
    fun `gros micro apres une ouverture par le champ - la dictee demarre sans ouvrir le clavier`() {
        val engine = engine()
        val assistant = AssistantServices.inMemory(engine, ScriptedTransport(), InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        openChat(engine, services(), assistant)
        compose.onNodeWithTag(app.trackly.ui.chat.ChatTags.INPUT).assertIsFocused()
        compose.onNodeWithContentDescription("Retour au journal").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(app.trackly.ui.day.DayTags.MIC).performClick()
        waitFor(MicTags.PANEL)
        // La demande de clavier de l'ouverture précédente ne doit pas être rejouée.
        compose.onNodeWithTag(app.trackly.ui.chat.ChatTags.INPUT).assertIsNotFocused()
    }

    @Test
    fun `parcours - relire d abord - le texte arrive dans la zone de saisie, rien n est envoye`() {
        val engine = engine()
        val transport = ScriptedTransport()
        val assistant = AssistantServices.inMemory(engine, transport, InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        openChat(engine, services(), assistant)

        compose.onNodeWithTag(MicTags.MIC).performClick()
        waitFor(MicTags.PANEL)
        waitForSpeech()
        compose.onNodeWithTag(MicTags.EDIT).performClick()

        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag(MicTags.PANEL)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Comme snack j’ai pris une barre Nuvo").assertIsDisplayed()
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `parcours - mode apres l arret - le chat reste, le texte arrive dans la saisie`() {
        val engine = engine()
        val assistant = AssistantServices.inMemory(engine, ScriptedTransport(), InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        val settings = InMemoryDictationSettings(DictationMode.AfterStop)
        openChat(engine, services(settings = settings), assistant)

        compose.onNodeWithTag(MicTags.MIC).performClick()
        waitFor(MicTags.STRIP)
        assertTrue(compose.onAllNodes(hasTestTag(MicTags.PANEL)).fetchSemanticsNodes().isEmpty())
        waitForSpeech()
        compose.onNodeWithTag(MicTags.STOP).performClick()

        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag(MicTags.STRIP)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Une barre Nuvo et une pomme").assertIsDisplayed()
    }

    @Test
    fun `parcours - permission jamais accordee - explication, rien ne s enregistre`() {
        val engine = engine()
        val assistant = AssistantServices.inMemory(engine, ScriptedTransport(), InMemoryApiKeyStore("sk-test-cle-factice-0000000000"))
        val permission = FixedMicPermission(granted = false)
        val mic = FakeMic()
        val services = DictationServices(InMemoryDictationSettings(), permission, mic, FakeLive(), FakeFile())
        openChat(engine, services, assistant)

        compose.onNodeWithTag(MicTags.MIC).performClick()
        waitFor(MicTags.CARD)
        compose.onNodeWithText("Autoriser le micro ?").assertIsDisplayed()
        assertEquals(0, mic.opened)
        compose.onNodeWithTag(MicTags.CLOSE).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(MicTags.CARD)).fetchSemanticsNodes().isEmpty())
    }
}
