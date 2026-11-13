package app.trackly.assistant.net

import app.trackly.assistant.openai.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ouvre à l'avance la connexion vers OpenAI : au toucher du « + » ou à l'ouverture du chat, le DNS,
 * TCP et TLS sont faits pendant que l'utilisateur parle ou tape ; le premier envoi réutilise la connexion.
 */
fun interface Prewarmer {
    fun warm()

    companion object {
        val None = Prewarmer { }
    }
}

/**
 * Préchauffage par une requête `HEAD` **sans clé ni contenu** (OpenAI répond 401 ou 404, sans frais), envoyée
 * par [client] : il doit partager le pool de connexions du client qui enverra le vrai message (même
 * `OkHttpClient`, ou un client dérivé par `newBuilder()` avec les mêmes protocoles).
 *
 * - Sans clé enregistrée ([ready] faux) ou sans réseau : aucune requête.
 * - Au plus une fois par [minIntervalMs] (une connexion inactive reste ouverte 5 min dans OkHttp).
 * - Toute erreur est ignorée : le préchauffage n'est qu'un gain de temps.
 */
class HttpPrewarmer(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val scope: CoroutineScope,
    /** Clé présente et réseau disponible ; lu à chaque demande. */
    private val ready: suspend () -> Boolean,
    private val clock: Millis = Millis.System,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) : Prewarmer {
    private val last = AtomicLong(Long.MIN_VALUE)
    private val running = AtomicBoolean(false)

    override fun warm() {
        scope.launch { warmNow() }
    }

    /** Préchauffe tout de suite ; vrai si une requête est partie (tests). */
    suspend fun warmNow(): Boolean {
        val now = clock.now()
        val previous = last.get()
        if (previous != Long.MIN_VALUE && now - previous < minIntervalMs) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            if (!runCatching { ready() }.getOrDefault(false)) return false
            last.set(now)
            val request = Request.Builder().url(url).head().build()
            runCatching { client.newCall(request).await().close() }
            return true
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 45_000L
    }
}

/** Plusieurs préchauffages à la fois (assistant, dictée). */
class Prewarmers(private val all: List<Prewarmer>) : Prewarmer {
    override fun warm() = all.forEach { it.warm() }
}
