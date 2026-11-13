package app.trackly.keyscan

import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.InMemoryApiKeyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan de la clé : le décodeur est simulé (aucune caméra). On vérifie ce qui est enregistré, ce qui est
 * refusé, et que rien de lu ne reste en mémoire dans les tableaux qu'on contrôle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyScanControllerTest {
    private val key = "sk-test-FAKE-0123456789abcdefghij"

    /** Décodeur simulé : rend les textes dans l'ordre, un par image. */
    private class ScriptedDecoder(vararg texts: String?) : QrDecoder {
        private val pending = ArrayDeque(texts.toList())
        val handedOut = mutableListOf<CharArray>()

        override fun decode(frame: LumaFrame): CharArray? = pending.removeFirstOrNull()?.toCharArray()?.also { handedOut += it }
    }

    private fun frame() = LumaFrame(ByteArray(64) { 7 }, rowStride = 8, width = 8, height = 8)

    private class FailingStore(val inner: ApiKeyStore, var fail: Boolean) : ApiKeyStore by inner {
        override suspend fun save(key: CharArray) {
            if (fail) error("disque plein")
            inner.save(key)
        }
    }

    @Test
    fun `un QR code de cle valide est enregistre dans le stockage chiffre, puis efface`() = runTest {
        val store = InMemoryApiKeyStore()
        val decoder = ScriptedDecoder(key)
        val controller = KeyScanController(decoder, store, this)

        controller.onFrame(frame())
        advanceUntilIdle()

        assertEquals(KeyScanState.Saved, controller.state.value)
        assertEquals(key, store.read())
        assertEquals(ApiKeyStatus.Present(key.takeLast(4)), store.status.value)
        // Le texte lu par le décodeur est effacé après l'enregistrement.
        assertTrue(decoder.handedOut.single().all { it == '\u0000' })
    }

    @Test
    fun `ecran ferme avant le demarrage de l enregistrement - le texte lu est quand meme efface`() {
        // Scope déjà annulé : la coroutine d'enregistrement ne démarre jamais, son finally non plus.
        val closedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() } + kotlinx.coroutines.Dispatchers.Unconfined)
        val decoder = ScriptedDecoder(key)
        val controller = KeyScanController(decoder, InMemoryApiKeyStore(), closedScope)

        controller.onFrame(frame())

        assertTrue(decoder.handedOut.single().all { it == '\u0000' })
    }

    @Test
    fun `l image de l appareil photo est mise a zero des qu elle est analysee`() = runTest {
        val controller = KeyScanController(ScriptedDecoder(null), InMemoryApiKeyStore(), this)
        val frame = frame()

        controller.onFrame(frame)

        assertTrue(frame.luma.all { it == 0.toByte() })
        assertEquals(KeyScanState.Scanning, controller.state.value)
    }

    @Test
    fun `un QR code qui n est pas une cle est refuse, rien n est enregistre, la lecture continue`() = runTest {
        val store = InMemoryApiKeyStore()
        val decoder = ScriptedDecoder("WIFI:T:WPA;S:maison;P:motdepasse;;", key)
        val controller = KeyScanController(decoder, store, this)

        controller.onFrame(frame())
        assertEquals(KeyScanState.Rejected(KeyScanController.NOT_A_KEY), controller.state.value)
        assertNull(store.read())
        assertTrue(decoder.handedOut.single().all { it == '\u0000' })

        controller.onFrame(frame())
        advanceUntilIdle()
        assertEquals(KeyScanState.Saved, controller.state.value)
        assertEquals(key, store.read())
    }

    @Test
    fun `les formes qui ne sont pas des cles OpenAI sont toutes refusees`() = runTest {
        for (bad in listOf("", "sk-court", "pas-une-cle-openai-assez-longue", "sk-avec un espace-0123456789abcdef", "sk-" + "x".repeat(600), "https://exemple.org/sk-0123456789012345")) {
            val store = InMemoryApiKeyStore()
            val controller = KeyScanController(ScriptedDecoder(bad), store, this)
            controller.onFrame(frame())
            advanceUntilIdle()
            assertTrue("accepté à tort : ${bad.take(12)}", controller.state.value is KeyScanState.Rejected)
            assertNull(store.read())
        }
    }

    @Test
    fun `le refus disparait quand on ne voit plus ce QR code`() = runTest {
        var now = 1_000L
        val controller = KeyScanController(ScriptedDecoder("autre QR code a lire", null, null), InMemoryApiKeyStore(), this) { now }

        controller.onFrame(frame())
        assertTrue(controller.state.value is KeyScanState.Rejected)
        now += 1_000
        controller.onFrame(frame()) // image sans QR code, trop tôt pour effacer le message
        assertTrue(controller.state.value is KeyScanState.Rejected)
        now += KeyScanController.REJECTION_SHOWN_MS
        controller.onFrame(frame())
        assertEquals(KeyScanState.Scanning, controller.state.value)
    }

    @Test
    fun `la premiere cle valide gagne, les images suivantes sont ignorees`() = runTest {
        val store = InMemoryApiKeyStore()
        val decoder = ScriptedDecoder(key, "sk-test-AUTRE-0123456789abcdefghi") // gitleaks:allow (fausse clé de test)
        val controller = KeyScanController(decoder, store, this)

        controller.onFrame(frame())
        controller.onFrame(frame())
        advanceUntilIdle()

        assertEquals(key, store.read())
        assertEquals(1, decoder.handedOut.size) // la seconde image n'a même pas été décodée
    }

    @Test
    fun `scan simule - le texte donne est efface apres usage`() = runTest {
        val store = InMemoryApiKeyStore()
        val controller = KeyScanController(ScriptedDecoder(), store, this)
        val scanned = key.toCharArray()

        controller.onScanned(scanned)
        advanceUntilIdle()

        assertEquals(key, store.read())
        assertArrayEquals(CharArray(key.length), scanned)
    }

    @Test
    fun `une fois l ecran ferme, plus rien n est analyse ni enregistre`() = runTest {
        val store = InMemoryApiKeyStore()
        val decoder = ScriptedDecoder(key)
        val controller = KeyScanController(decoder, store, this)

        controller.stop()
        controller.onFrame(frame())
        controller.onScanned(key.toCharArray())
        advanceUntilIdle()

        assertNull(store.read())
        assertTrue(decoder.handedOut.isEmpty())
        assertEquals(KeyScanState.Scanning, controller.state.value)
    }

    @Test
    fun `echec d enregistrement - message sans la cle, puis reessayer`() = runTest {
        val store = FailingStore(InMemoryApiKeyStore(), fail = true)
        val decoder = ScriptedDecoder(key, key)
        val controller = KeyScanController(decoder, store, this)

        controller.onFrame(frame())
        advanceUntilIdle()

        val failed = controller.state.value as KeyScanState.Failed
        assertEquals(KeyScanController.SAVE_FAILED, failed.message)
        assertTrue(key !in failed.message && "disque plein" !in failed.message)
        assertNull(store.read())
        assertTrue(decoder.handedOut.single().all { it == '\u0000' })

        store.fail = false
        controller.retry()
        assertEquals(KeyScanState.Scanning, controller.state.value)
        controller.onFrame(frame())
        advanceUntilIdle()
        assertEquals(KeyScanState.Saved, controller.state.value)
        assertEquals(key, store.read())
    }
}
