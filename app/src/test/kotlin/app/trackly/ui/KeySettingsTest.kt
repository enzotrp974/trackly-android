package app.trackly.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.InMemoryKeyVerificationPrefs
import app.trackly.assistant.key.KeyChecker
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.assistant.key.KeyVerifier
import app.trackly.assistant.key.SecretClipboard
import app.trackly.assistant.key.StaticKeyVerification
import app.trackly.assistant.key.VerifyingApiKeyStore
import app.trackly.ui.settings.KeyTools
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Réglages › Clé OpenAI : la section passe en premier sans clé, « Saisir ma clé » y fait défiler, et chaque
 * enregistrement lance la vérification gratuite (`GET /v1/models`). Le serveur est local (MockWebServer) : jamais
 * d'appel réel, jamais de vraie clé.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class KeySettingsTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakeKey = "sk-proj-cle-factice-test-1234abcd" // gitleaks:allow (fausse clé de test)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun checker() = KeyChecker(OkHttpClient(), server.url("/v1/"), isOnline = { true }, io = Dispatchers.IO)

    private fun otherSection(): @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        BasicText("Autre réglage", Modifier.testTag("autre-section"))
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `la cle a sa propre page, sans autre reglage`() {
        compose.setContent { SettingsScreen(InMemoryApiKeyStore(), onClose = {}, groups = SettingsGroups(model = otherSection()), startPage = SettingsPage.Key) }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_SECTION).assertIsDisplayed()
        compose.onNodeWithTag("autre-section").assertDoesNotExist()
    }

    @Test
    fun `Saisir ma cle ouvre la page de la cle et le dit a l appelant`() {
        var scroll by mutableStateOf(false)
        var done = 0
        compose.setContent {
            SettingsScreen(
                InMemoryApiKeyStore(),
                onClose = {},
                scrollToKey = scroll,
                onScrolledToKey = {
                    done++
                    scroll = false
                },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertDoesNotExist()

        scroll = true
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertIsDisplayed()
        assertEquals(1, done)
        assertFalse(scroll)
    }

    @Test
    fun `touche du clavier Terminer - enregistre la cle, comme le bouton`() {
        val store = InMemoryApiKeyStore()
        compose.setContent { SettingsScreen(store, onClose = {}, startPage = SettingsPage.Key) }
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assert(hasImeAction(ImeAction.Done))
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput(fakeKey)
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performImeAction()
        compose.waitForIdle()
        assertEquals(ApiKeyStatus.Present("abcd"), store.status.value)
    }

    // ───── Bouton « Coller » ─────

    /** Presse-papiers de test : garde ce qu'on y a mis et compte les effacements. */
    private class FakeClipboard(var text: String?) : SecretClipboard {
        var cleared = 0

        override fun clear() {
            cleared++
            text = null
        }

        override fun read(): String? = text
    }

    private fun pasteScreen(store: InMemoryApiKeyStore, clipboard: FakeClipboard) {
        compose.setContent { SettingsScreen(store, onClose = {}, keyTools = KeyTools(clipboard = clipboard), startPage = SettingsPage.Key) }
        compose.waitForIdle()
    }

    @Test
    fun `Coller - une cle valide est enregistree puis le presse-papiers est vide`() {
        val store = InMemoryApiKeyStore()
        val clipboard = FakeClipboard(" $fakeKey\n")
        pasteScreen(store, clipboard)

        compose.onNodeWithTag(SettingsTags.KEY_PASTE).performClick()
        compose.waitForIdle()

        assertEquals(ApiKeyStatus.Present("abcd"), store.status.value)
        assertEquals(1, clipboard.cleared)
        assertEquals(null, clipboard.text)
        compose.onNodeWithTag(SettingsTags.KEY_MESSAGE).assertTextEquals("Clé enregistrée.")
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertTextEquals("Clé enregistrée, finit par « abcd ».")
    }

    @Test
    fun `Coller - un texte qui n est pas une cle est refuse, sans enregistrer ni effacer`() {
        val store = InMemoryApiKeyStore()
        val clipboard = FakeClipboard("rendez-vous demain à 15 h")
        pasteScreen(store, clipboard)

        compose.onNodeWithTag(SettingsTags.KEY_PASTE).performClick()
        compose.waitForIdle()

        assertEquals(ApiKeyStatus.Missing, store.status.value)
        assertEquals(0, clipboard.cleared)
        assertEquals("rendez-vous demain à 15 h", clipboard.text)
        compose.onNodeWithTag(SettingsTags.KEY_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Clé enregistrée.").assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertTextEquals("Aucune clé.")
    }

    private fun assertPasteSaysEmpty(content: String?) {
        val store = InMemoryApiKeyStore()
        val clipboard = FakeClipboard(content)
        pasteScreen(store, clipboard)

        compose.onNodeWithTag(SettingsTags.KEY_PASTE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SettingsTags.KEY_MESSAGE).assertTextEquals("Presse-papiers vide : copie d’abord ta clé.")
        assertEquals(ApiKeyStatus.Missing, store.status.value)
        assertEquals(0, clipboard.cleared)
    }

    @Test
    fun `Coller - presse-papiers vide, un message le dit`() = assertPasteSaysEmpty(null)

    @Test
    fun `Coller - presse-papiers blanc, meme message`() = assertPasteSaysEmpty("   \n")

    // ───── Vérification gratuite ─────

    private fun verifying(store: InMemoryApiKeyStore): Pair<VerifyingApiKeyStore, KeyVerifier> {
        val verifier = KeyVerifier(store, checker(), scope, InMemoryKeyVerificationPrefs())
        return VerifyingApiKeyStore(store, verifier) to verifier
    }

    private fun waitForCheck(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.KEY_CHECK) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `enregistrer une cle valide - Cle valide s affiche, un seul GET models gratuit`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())
        val store = InMemoryApiKeyStore()
        val (keys, verifier) = verifying(store)
        compose.setContent { SettingsScreen(keys, onClose = {}, keyTools = KeyTools(verification = verifier), startPage = SettingsPage.Key) }

        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput(fakeKey)
        compose.onNodeWithText("Enregistrer la clé").performClick()
        waitForCheck("Clé valide")

        compose.onNodeWithTag(SettingsTags.KEY_VERIFY).assertDoesNotExist()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer $fakeKey", request.headers["Authorization"])
        assertEquals(1, server.requestCount)
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-cle-verifiee.png")
    }

    @Test
    fun `cle refusee par OpenAI - Cle refusee, aussitot`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"invalid_api_key"}}""").build())
        val store = InMemoryApiKeyStore()
        val (keys, verifier) = verifying(store)
        compose.setContent { SettingsScreen(keys, onClose = {}, keyTools = KeyTools(verification = verifier), startPage = SettingsPage.Key) }

        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput(fakeKey)
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performImeAction()
        waitForCheck("Clé refusée")
        compose.onNodeWithText("Clé refusée").assertIsDisplayed()
        compose.onNodeWithText("Clé enregistrée.").assertDoesNotExist()
    }

    @Test
    fun `OpenAI en panne - verifiee plus tard, puis Verifier la cle maintenant`() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val store = InMemoryApiKeyStore()
        val (keys, verifier) = verifying(store)
        compose.setContent { SettingsScreen(keys, onClose = {}, keyTools = KeyTools(verification = verifier), startPage = SettingsPage.Key) }

        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput(fakeKey)
        compose.onNodeWithText("Enregistrer la clé").performClick()
        waitForCheck("OpenAI ne répond pas, vérifiée plus tard")
        compose.onNodeWithTag(SettingsTags.KEY_VERIFY).assertIsDisplayed()

        compose.onNodeWithText("Vérifier la clé maintenant").performClick()
        waitForCheck("Clé valide")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sans verification branchee, aucune ligne de controle`() {
        compose.setContent { SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = {}, startPage = SettingsPage.Key) }
        assertEquals(0, compose.onAllNodesWithTag(SettingsTags.KEY_CHECK).fetchSemanticsNodes().size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `etat de la verification - chaque texte, sans cle aucune ligne`() {
        val state = StaticKeyVerification(KeyVerificationState.Valid)
        compose.setContent { SettingsScreen(InMemoryApiKeyStore(fakeKey), onClose = {}, keyTools = KeyTools(verification = state), startPage = SettingsPage.Key) }
        compose.onNodeWithText("Clé valide").assertIsDisplayed()
        state.set(KeyVerificationState.Pending(noInternet = true))
        compose.waitForIdle()
        compose.onNodeWithText("Pas d’Internet, vérifiée plus tard").assertIsDisplayed()
        state.set(KeyVerificationState.Checking)
        compose.waitForIdle()
        compose.onNodeWithText("Vérification de la clé…").assertIsDisplayed()
    }
}
