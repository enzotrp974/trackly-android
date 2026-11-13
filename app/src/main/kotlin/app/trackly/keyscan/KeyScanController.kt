package app.trackly.keyscan

import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.validateApiKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ce que l'écran de scan montre pendant la lecture. */
sealed interface KeyScanState {
    /** L'appareil photo cherche un QR code. */
    data object Scanning : KeyScanState

    /** Un QR code a été lu, mais ce n'est pas une clé OpenAI : on continue de chercher. */
    data class Rejected(val message: String) : KeyScanState

    /** Une clé valide a été lue ; elle est en train d'être chiffrée et enregistrée. */
    data object Saving : KeyScanState

    data object Saved : KeyScanState

    /** La clé était bonne mais n'a pas pu être enregistrée. */
    data class Failed(val message: String) : KeyScanState
}

/**
 * Lecture de la clé OpenAI par QR code (Réglages › Clé OpenAI › Scanner un QR code).
 *
 * Reçoit les images de l'appareil photo ([onFrame], fil d'analyse), les confie à un [QrDecoder],
 * vérifie que le texte lu a la forme d'une clé OpenAI, l'enregistre dans le stockage chiffré
 * (Keystore) puis efface les caractères. **Rien de ce qui est lu n'est écrit ailleurs** : ni journal,
 * ni état, ni message d'erreur ; les images sont mises à zéro dès qu'elles sont analysées.
 *
 * La clé lue par ZXing existe d'abord comme `String` interne à la bibliothèque, que la JVM ne permet
 * pas d'effacer : on la convertit aussitôt en caractères, effacés après l'enregistrement.
 *
 * La première clé valide gagne ; ensuite plus aucune image n'est analysée ([stop] ou [retry]).
 */
class KeyScanController(
    private val decoder: QrDecoder,
    private val keyStore: ApiKeyStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<KeyScanState>(KeyScanState.Scanning)
    val state: StateFlow<KeyScanState> = _state.asStateFlow()

    @Volatile
    private var accepting = true
    private var rejectedAt = 0L

    /** Une image de l'appareil photo (fil d'analyse). L'image est effacée avant le retour. */
    fun onFrame(frame: LumaFrame) {
        if (!accepting) {
            frame.wipe()
            return
        }
        val text = try {
            decoder.decode(frame)
        } finally {
            frame.wipe()
        }
        if (text == null) forgetOldRejection() else onScanned(text)
    }

    /**
     * Un texte de QR code a été lu. [text] est effacé avant le retour (ou après l'enregistrement).
     * Public pour les tests (scan simulé) ; l'appareil photo passe par [onFrame].
     */
    fun onScanned(text: CharArray) {
        if (!accepting) {
            text.fill(NUL)
            return
        }
        if (validateApiKey(text) != null) {
            text.fill(NUL)
            rejectedAt = clock()
            _state.value = KeyScanState.Rejected(NOT_A_KEY)
            return
        }
        accepting = false
        _state.value = KeyScanState.Saving
        val job = scope.launch {
            try {
                // Une fois la clé lue, l'écriture va au bout même si l'écran se ferme.
                withContext(NonCancellable) { keyStore.save(text) }
                _state.value = KeyScanState.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pas de détail : l'exception pourrait citer des données de la clé.
                _state.value = KeyScanState.Failed(SAVE_FAILED)
            } finally {
                text.fill(NUL)
            }
        }
        // Si l'écran est annulé avant que la coroutine démarre, son `finally` ne s'exécute jamais :
        // l'effacement est garanti ici, à la fin du job quelle qu'elle soit.
        job.invokeOnCompletion { text.fill(NUL) }
    }

    /** Reprend la lecture après un échec d'enregistrement. */
    fun retry() {
        if (_state.value is KeyScanState.Failed) {
            _state.value = KeyScanState.Scanning
            accepting = true
        }
    }

    /** L'écran se ferme : plus aucune image n'est analysée. */
    fun stop() {
        accepting = false
    }

    private fun forgetOldRejection() {
        val current = _state.value
        if (current is KeyScanState.Rejected && clock() - rejectedAt > REJECTION_SHOWN_MS) {
            _state.value = KeyScanState.Scanning
        }
    }

    companion object {
        const val NOT_A_KEY = "Ce QR code n’est pas une clé OpenAI."
        const val SAVE_FAILED = "Clé lue, mais impossible de l’enregistrer sur ce téléphone. Réessaie, ou colle-la."

        /** Durée pendant laquelle « ce n'est pas une clé » reste affiché après la dernière lecture. */
        const val REJECTION_SHOWN_MS = 2_500L

        private const val NUL = '\u0000'
    }
}
