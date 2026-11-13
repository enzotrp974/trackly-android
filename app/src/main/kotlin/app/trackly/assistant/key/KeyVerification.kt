package app.trackly.assistant.key

import android.content.Context
import app.trackly.assistant.openai.OkHttpResponsesTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Réponse d'OpenAI à la vérification gratuite d'une clé. */
enum class KeyCheck {
    /** Clé reconnue. */
    Valid,

    /** OpenAI refuse la clé (401 ou 403) : fausse, révoquée ou sans droit. */
    Refused,

    /** Pas de réseau (ou réseau injoignable) : la clé n'a pas pu être contrôlée. */
    NoInternet,

    /** OpenAI n'a pas répondu correctement (erreur de leur côté, trop de demandes) : à refaire plus tard. */
    ServerTrouble,
}

/**
 * Vérifie une clé OpenAI sans rien dépenser : `GET /v1/models` ne coûte rien et ne lit aucune donnée de l'utilisateur.
 * La clé n'est jamais journalisée (le client ne porte aucun intercepteur de journal, et aucune ligne du code n'écrit
 * dans le journal Android, ce que `NoLogsInSourcesTest` garde).
 */
class KeyChecker(
    private val client: OkHttpClient = OkHttpResponsesTransport.defaultClient(),
    baseUrl: HttpUrl = OkHttpResponsesTransport.DEFAULT_BASE_URL.toHttpUrl(),
    /** Faux : le téléphone n'a aucun réseau, rien n'est envoyé. */
    private val isOnline: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val endpoint: HttpUrl = baseUrl.newBuilder().addPathSegment("models").build()

    suspend fun check(key: String): KeyCheck {
        if (!isOnline()) return KeyCheck.NoInternet
        val request = try {
            Request.Builder().url(endpoint).header("Authorization", "Bearer $key").get().build()
        } catch (e: IllegalArgumentException) {
            return KeyCheck.Refused // caractère impossible dans un en-tête : ce n'est pas une clé OpenAI
        }
        return withContext(io) {
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyCheck.Valid
                        response.code == 401 || response.code == 403 -> KeyCheck.Refused
                        else -> KeyCheck.ServerTrouble
                    }
                }
            } catch (e: IOException) {
                KeyCheck.NoInternet
            }
        }
    }
}

/** Où en est la vérification de la clé enregistrée. */
sealed interface KeyVerificationState {
    /** Pas de clé, ou clé enregistrée et jamais contrôlée. */
    data object Unchecked : KeyVerificationState

    data object Checking : KeyVerificationState

    data object Valid : KeyVerificationState

    data object Refused : KeyVerificationState

    /** Contrôle impossible pour l'instant ([noInternet] : pas de réseau) ; refait dès que possible. */
    data class Pending(val noInternet: Boolean) : KeyVerificationState
}

/** Textes de la vérification, tels qu'affichés dans les Réglages. */
fun KeyVerificationState.message(): String? = when (this) {
    KeyVerificationState.Unchecked -> null
    KeyVerificationState.Checking -> "Vérification de la clé…"
    KeyVerificationState.Valid -> "Clé valide"
    KeyVerificationState.Refused -> "Clé refusée"
    is KeyVerificationState.Pending -> if (noInternet) "Pas d’Internet, vérifiée plus tard" else "OpenAI ne répond pas, vérifiée plus tard"
}

/**
 * La vérification de la clé, vue des écrans. [verified] émet à chaque fois qu'une clé vient d'être reconnue valide :
 * le chat s'en sert pour proposer « Renvoyer mon dernier message » après une clé corrigée.
 */
interface KeyVerification {
    val state: StateFlow<KeyVerificationState>

    /** Une clé vient d'être reconnue valide par OpenAI. */
    val verified: SharedFlow<Unit>

    /** Refait le contrôle si le dernier n'a pas pu aboutir (réseau revenu, app rouverte). */
    fun retryIfPending()

    /** Contrôle la clé enregistrée maintenant (clé enregistrée avant cette vérification, ou bouton « Vérifier »). */
    fun verify()
}

/** Mémoire de la dernière clé reconnue valide : ses quatre derniers caractères, jamais la clé. */
interface KeyVerificationPrefs {
    var validLastFour: String?
}

class SharedKeyVerificationPrefs(context: Context) : KeyVerificationPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("trackly-cle", Context.MODE_PRIVATE)

    override var validLastFour: String?
        get() = prefs.getString(KEY, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "verifiee_fin"
    }
}

class InMemoryKeyVerificationPrefs(override var validLastFour: String? = null) : KeyVerificationPrefs

/**
 * Contrôle la clé enregistrée à chaque enregistrement (collage ou QR code), en arrière-plan : l'enregistrement
 * n'attend pas la réponse. Seule la clé de [store] est lue, au moment de la requête.
 */
class KeyVerifier(
    private val store: ApiKeyStore,
    private val checker: KeyChecker,
    private val scope: CoroutineScope,
    private val prefs: KeyVerificationPrefs = InMemoryKeyVerificationPrefs(),
) : KeyVerification {
    private val _state = MutableStateFlow<KeyVerificationState>(KeyVerificationState.Unchecked)
    override val state: StateFlow<KeyVerificationState> = _state.asStateFlow()
    private val _verified = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val verified: SharedFlow<Unit> = _verified.asSharedFlow()

    /**
     * Numéro du dernier contrôle demandé (ou de la dernière suppression de clé). Un contrôle dont le numéro n'est
     * plus le bon est périmé : son résultat arrive trop tard et ne doit rien écraser (deux contrôles peuvent se
     * chevaucher, et le plus ancien finir le dernier).
     */
    private val latest = AtomicInteger()

    /**
     * Verrou court autour de « le numéro est-il encore le bon ? » et des écritures qui suivent (fin de clé gardée,
     * état), ainsi que de la prise d'un nouveau numéro avec ses écritures. Sans lui, un enregistrement concurrent
     * pouvait s'intercaler entre le test et les écritures, et le résultat d'une ancienne clé écrasait la nouvelle.
     * Rien de suspendu n'est appelé sous le verrou (pas d'attente réseau) : un `synchronized` suffit, et il sert
     * aussi aux fonctions non suspendantes ([onSaved], [onCleared], [verify]).
     */
    private val lock = Any()

    init {
        // Au démarrage : la clé déjà reconnue reste « valide » sans refaire d'appel — sauf si un contrôle a été
        // demandé (ou la clé supprimée) entre-temps : ce contrôle est plus récent, il décide.
        val ticket = synchronized(lock) { latest.incrementAndGet() }
        scope.launch {
            store.refresh()
            val status = store.status.value
            synchronized(lock) {
                if (ticket == latest.get() && status is ApiKeyStatus.Present && status.lastFour == prefs.validLastFour) {
                    _state.value = KeyVerificationState.Valid
                }
            }
        }
    }

    /** À appeler quand une clé vient d'être enregistrée. */
    fun onSaved() = startCheck(forgetValid = true)

    /** À appeler quand la clé a été supprimée. */
    fun onCleared() {
        synchronized(lock) {
            latest.incrementAndGet() // un contrôle encore en route portait sur la clé supprimée
            prefs.validLastFour = null
            _state.value = KeyVerificationState.Unchecked
        }
    }

    override fun retryIfPending() {
        if (_state.value is KeyVerificationState.Pending) verify()
    }

    override fun verify() = startCheck(forgetValid = false)

    private fun startCheck(forgetValid: Boolean) {
        val ticket = synchronized(lock) {
            val next = latest.incrementAndGet()
            if (forgetValid) prefs.validLastFour = null
            _state.value = KeyVerificationState.Checking
            next
        }
        scope.launch { runCheck(ticket) }
    }

    /** Contrôle la clé enregistrée maintenant et met l'état à jour (sauf si un contrôle plus récent a été demandé). */
    suspend fun verifyNow(): KeyVerificationState {
        val ticket = synchronized(lock) { latest.incrementAndGet() }
        return runCheck(ticket)
    }

    private suspend fun runCheck(ticket: Int): KeyVerificationState {
        val key = store.read()
        if (key == null) {
            return synchronized(lock) {
                if (ticket == latest.get()) _state.value = KeyVerificationState.Unchecked
                _state.value
            }
        }
        val result = when (checker.check(key)) {
            KeyCheck.Valid -> KeyVerificationState.Valid
            KeyCheck.Refused -> KeyVerificationState.Refused
            KeyCheck.NoInternet -> KeyVerificationState.Pending(noInternet = true)
            KeyCheck.ServerTrouble -> KeyVerificationState.Pending(noInternet = false)
        }
        synchronized(lock) {
            // Périmé : une clé plus récente est en cours de contrôle (ou a été supprimée), ce résultat ne la concerne plus.
            if (ticket != latest.get()) return _state.value
            when (result) {
                KeyVerificationState.Valid -> {
                    prefs.validLastFour = key.takeLast(4)
                    _state.value = result
                    _verified.tryEmit(Unit)
                }
                KeyVerificationState.Refused -> {
                    prefs.validLastFour = null
                    _state.value = result
                }
                else -> _state.value = result
            }
        }
        return result
    }
}

/** Clé qui lance le contrôle gratuit dès qu'elle est enregistrée, par collage comme par QR code. */
class VerifyingApiKeyStore(private val delegate: ApiKeyStore, private val verifier: KeyVerifier) : ApiKeyStore by delegate {
    override suspend fun save(key: String) {
        delegate.save(key)
        verifier.onSaved()
    }

    override suspend fun save(key: CharArray) {
        delegate.save(key)
        verifier.onSaved()
    }

    override suspend fun clear() {
        delegate.clear()
        verifier.onCleared()
    }
}

/** Vérification inerte, pour les aperçus et les tests qui n'en ont pas besoin. */
class StaticKeyVerification(initial: KeyVerificationState = KeyVerificationState.Unchecked) : KeyVerification {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<KeyVerificationState> = _state.asStateFlow()
    override val verified: SharedFlow<Unit> = MutableSharedFlow()

    fun set(value: KeyVerificationState) {
        _state.value = value
    }

    override fun retryIfPending() = Unit

    override fun verify() = Unit
}
