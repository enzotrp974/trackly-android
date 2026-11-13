package app.trackly.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.key.AndroidSecretClipboard
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.SecretClipboard
import app.trackly.keyscan.KeyScanController
import app.trackly.keyscan.KeyScanState
import app.trackly.keyscan.LumaFrame
import app.trackly.keyscan.QrDecoder
import app.trackly.ui.settings.CameraAccess
import app.trackly.ui.settings.KeyScanRoute
import app.trackly.ui.settings.KeyScanScreen
import app.trackly.ui.settings.KeyScanTags
import app.trackly.ui.settings.KeyTools
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.settings.SettingsTags
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Réglages › Clé OpenAI › Scanner un QR code, et collage de la clé. Caméra et décodeur simulés : aucune
 * vraie clé, aucun réseau. Les captures montrent les écrans du scan à relire à l'œil.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class KeyScanUiTest {
    @get:Rule val compose = createComposeRule()

    private val key = "sk-test-FAKE-0123456789abcdefghij"
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val capturedOut = ByteArrayOutputStream()
    private lateinit var realOut: PrintStream
    private lateinit var realErr: PrintStream

    @Before
    fun listenToLogsAndConsole() {
        ShadowLog.clear()
        realOut = System.out
        realErr = System.err
        System.setOut(PrintStream(capturedOut, true))
        System.setErr(PrintStream(capturedOut, true))
    }

    @After
    fun restoreConsole() {
        System.setOut(realOut)
        System.setErr(realErr)
    }

    /** Ni journal Android ni console : la clé (ni même un morceau) n'y figure. */
    private fun assertKeyNeverLogged() {
        val everything = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" } + capturedOut.toString()
        assertFalse("clé dans les journaux", everything.contains(key))
        assertFalse("morceau de clé dans les journaux", everything.contains(key.drop(8)))
    }

    private class FakeDecoder(private val text: String) : QrDecoder {
        override fun decode(frame: LumaFrame): CharArray = text.toCharArray()
    }

    /** Appareil photo simulé : une image dès que le viseur apparaît. */
    @Composable
    private fun BoxScope.FakeCamera(controller: KeyScanController) {
        Box(Modifier.fillMaxSize().background(Color(0xFF334))) {}
        LaunchedEffect(controller) { controller.onFrame(LumaFrame(ByteArray(64), 8, 8, 8)) }
    }

    private class RecordingClipboard : SecretClipboard {
        var cleared = 0
        override fun clear() {
            cleared++
        }
    }

    private fun settings(keyStore: InMemoryApiKeyStore, decoder: QrDecoder?, clipboard: SecretClipboard = SecretClipboard.None) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                SettingsScreen(
                    keyStore = keyStore,
                    onClose = {},
                    keyTools = KeyTools(
                        clipboard = clipboard,
                        scanner = decoder?.let { d -> { onClose -> KeyScanRoute(keyStore, onClose, d) { FakeCamera(it) } } },
                    ),
                    startPage = SettingsPage.Key,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `le bouton Scanner un QR code ouvre le scan, la cle lue est enregistree et l ecran se referme`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val store = InMemoryApiKeyStore()
        settings(store, FakeDecoder(key))

        compose.onNodeWithTag(SettingsTags.KEY_SCAN).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(KeyScanTags.DONE)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Clé enregistrée.").assertIsDisplayed()
        assertEquals(key, runBlocking { store.read() })

        // L'écran de scan se referme seul ; les Réglages montrent la clé enregistrée.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(KeyScanTags.SCREEN)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(SettingsTags.KEY_STATUS).assertIsDisplayed()
        compose.onNodeWithText("Clé enregistrée, finit par « ghij ».").assertIsDisplayed()
        assertKeyNeverLogged()
    }

    @Test
    fun `un QR code qui n est pas une cle n enregistre rien`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val store = InMemoryApiKeyStore()
        settings(store, FakeDecoder("https://exemple.org/pas-une-cle-du-tout"))

        compose.onNodeWithTag(SettingsTags.KEY_SCAN).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(KeyScanTags.STATUS)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(KeyScanController.NOT_A_KEY).assertIsDisplayed()
        assertNull(runBlocking { store.read() })
        assertKeyNeverLogged()
    }

    @Test
    fun `sans scanner fourni, pas de bouton Scanner un QR code`() {
        settings(InMemoryApiKeyStore(), decoder = null)
        compose.onNodeWithTag(SettingsTags.KEY_INPUT).assertExists()
        compose.onNodeWithTag(SettingsTags.KEY_SCAN).assertDoesNotExist()
    }

    @Test
    fun `sans permission, l ecran explique d abord pourquoi l appareil photo sert`() {
        val store = InMemoryApiKeyStore()
        settings(store, FakeDecoder(key))

        compose.onNodeWithTag(SettingsTags.KEY_SCAN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("L’appareil photo sert à lire le QR code").assertIsDisplayed()
        compose.onNodeWithTag(KeyScanTags.ALLOW).assertIsDisplayed()
        compose.onNodeWithTag(KeyScanTags.VIEWFINDER).assertDoesNotExist()
        assertNull(runBlocking { store.read() })

        compose.onNodeWithTag(KeyScanTags.LATER).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(KeyScanTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `coller la cle - enregistree, puis le presse-papiers est vide`() {
        val store = InMemoryApiKeyStore()
        val clipboard = RecordingClipboard()
        settings(store, FakeDecoder(key), clipboard)

        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput(key)
        compose.onNodeWithText("Enregistrer la clé").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(key, runBlocking { store.read() })
        assertEquals(1, clipboard.cleared)
        compose.onNodeWithText("Clé enregistrée.").assertIsDisplayed()
        assertKeyNeverLogged()
    }

    @Test
    fun `une cle refusee ne vide pas le presse-papiers`() {
        val clipboard = RecordingClipboard()
        settings(InMemoryApiKeyStore(), FakeDecoder(key), clipboard)

        compose.onNodeWithTag(SettingsTags.KEY_INPUT).performTextInput("pas-une-cle-openai-assez-longue")
        compose.onNodeWithText("Enregistrer la clé").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(0, clipboard.cleared)
        compose.onNodeWithText("Une clé OpenAI commence par « sk- ».").assertIsDisplayed()
    }

    @Test
    fun `le presse-papiers d Android est vraiment vide`() {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("clé", key))
        assertTrue(manager.hasPrimaryClip())

        AndroidSecretClipboard(app).clear()

        assertFalse(manager.hasPrimaryClip() && manager.primaryClip?.getItemAt(0)?.text?.contains("sk-") == true)
    }

    @Test
    fun `le presse-papiers d Android se lit, puis est vide apres clear`() {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboard = AndroidSecretClipboard(app)
        manager.setPrimaryClip(ClipData.newPlainText("clé", key))
        assertEquals(key, clipboard.read())

        clipboard.clear()

        assertTrue(clipboard.read().isNullOrBlank() || clipboard.read()?.contains("sk-") == false)
    }

    @Test
    fun `pendant la recherche, Coller la cle reste visible et ramene aux Reglages`() {
        var closes = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                KeyScanScreen(
                    access = CameraAccess.Granted,
                    scan = KeyScanState.Scanning,
                    onAllow = {},
                    onOpenAppSettings = {},
                    onRetry = {},
                    onClose = { closes++ },
                    viewfinder = { Box(Modifier.fillMaxSize().background(Color(0xFF2B3A55))) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Vise le QR code de ton ordinateur.").assertIsDisplayed()
        // Plus de commande à recopier dans l'écran de scan.
        compose.onNodeWithText("trackly-key-qr", substring = true).assertDoesNotExist()
        compose.onNodeWithText("uv run", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(KeyScanTags.LATER).assertIsDisplayed()
        compose.onNodeWithText("Coller la clé").assertIsDisplayed()
        compose.onNodeWithTag(KeyScanTags.LATER).performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `aucun journal Android n est ecrit autour de la cle`() {
        // Le scan complet (lecture, validation, enregistrement) n'a rien écrit dans le journal.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val store = InMemoryApiKeyStore()
        settings(store, FakeDecoder(key))
        compose.onNodeWithTag(SettingsTags.KEY_SCAN).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(KeyScanTags.DONE)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("sk-") || it.msg.contains(key.drop(8)) })
        Log.d("test", "témoin") // le journal fonctionne bien dans ce test
        assertTrue(ShadowLog.getLogs().any { it.msg == "témoin" })
    }

    // ───── Captures de référence ─────

    private fun screen(access: CameraAccess, scan: KeyScanState = KeyScanState.Scanning) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                KeyScanScreen(
                    access = access,
                    scan = scan,
                    onAllow = {},
                    onOpenAppSettings = {},
                    onRetry = {},
                    onClose = {},
                    viewfinder = { Box(Modifier.fillMaxSize().background(Color(0xFF2B3A55))) },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun captureExplication() {
        screen(CameraAccess.Unasked)
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-explication.png")
    }

    @Test
    fun captureRefuse() {
        screen(CameraAccess.Denied)
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-refuse.png")
    }

    @Test
    fun captureBloque() {
        screen(CameraAccess.Blocked)
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-bloque.png")
    }

    @Test
    fun captureLecture() {
        screen(CameraAccess.Granted)
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-lecture.png")
    }

    @Test
    fun capturePasUneCle() {
        screen(CameraAccess.Granted, KeyScanState.Rejected(KeyScanController.NOT_A_KEY))
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-refus.png")
    }

    @Test
    fun captureEnregistree() {
        screen(CameraAccess.Granted, KeyScanState.Saved)
        compose.onRoot().captureRoboImage("src/test/screenshots/scan-cle-enregistree.png")
    }

    @Test
    fun captureReglagesAvecScan() {
        settings(InMemoryApiKeyStore(), FakeDecoder(key))
        compose.onRoot().captureRoboImage("src/test/screenshots/reglages-cle-scan.png")
    }
}
