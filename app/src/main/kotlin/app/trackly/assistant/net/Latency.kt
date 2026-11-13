package app.trackly.assistant.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Mesure locale de la latence (objectif : « une carte en moins de 10 s ») : **seulement des noms
 * d'étapes et des durées**, jamais de texte, d'adresse complète, d'en-tête ni de clé. En debug, les lignes
 * vont au journal Android (`TracklyLatence`, voir `src/debug`) ; en release, nulle part.
 */
fun interface LatencyLog {
    fun report(line: LatencyLine)

    companion object {
        val None = LatencyLog { }
    }
}

/** Une mesure : quoi (« tour », « requête », « préchauffage ») et ses étapes en millisecondes. */
data class LatencyLine(val what: String, val steps: List<Pair<String, Long>>, val note: String? = null) {
    /** « tour : carte 3420 ms, texte 5100 ms, fin 5800 ms ». */
    fun format(): String = what + " : " + steps.joinToString(", ") { "${it.first} ${it.second} ms" } + note?.let { " ($it)" }.orEmpty()
}

/** Horloge monotone en millisecondes (remplaçable dans les tests). */
fun interface Millis {
    fun now(): Long

    companion object {
        val System = Millis { java.lang.System.nanoTime() / 1_000_000 }
    }
}

/**
 * Chronomètre d'un tour du chat : du toucher d'« Envoyer » à la première carte, au premier texte et à la fin.
 * Une étape n'est notée qu'une fois.
 */
class TurnStopwatch(private val clock: Millis, private val log: LatencyLog) {
    private val start = clock.now()
    private val steps = linkedMapOf<String, Long>()

    fun mark(step: String) {
        if (step !in steps) steps[step] = clock.now() - start
    }

    fun finish(outcome: String) {
        mark(END)
        log.report(LatencyLine(TURN, steps.toList(), outcome))
    }

    companion object {
        const val TURN = "tour"
        const val CARD = "carte"
        const val TEXT = "texte"
        const val END = "fin"
    }
}

/**
 * Temps de chaque requête HTTP vers OpenAI, vu par OkHttp : connexion réutilisée ou neuve (DNS, TCP, TLS),
 * premier octet de la réponse, fin. De quoi vérifier sur le téléphone que le préchauffage sert.
 */
class LatencyEvents(private val log: LatencyLog, private val clock: Millis = Millis.System) : EventListener.Factory {
    override fun create(call: Call): EventListener = object : EventListener() {
        private val start = clock.now()
        private val steps = linkedMapOf<String, Long>()
        private var reused = true
        private var dnsStart = 0L
        private var tlsStart = 0L
        private var connectStart = 0L

        private fun at() = clock.now() - start

        override fun dnsStart(call: Call, domainName: String) {
            reused = false
            dnsStart = clock.now()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            steps["dns"] = clock.now() - dnsStart
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            reused = false
            connectStart = clock.now()
        }

        override fun secureConnectStart(call: Call) {
            tlsStart = clock.now()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            steps["tls"] = clock.now() - tlsStart
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            steps["connexion"] = clock.now() - connectStart
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            steps["prête"] = at()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            steps["premier octet"] = at()
        }

        override fun callEnd(call: Call) = done(null)

        override fun callFailed(call: Call, ioe: IOException) = done("échec")

        override fun canceled(call: Call) {
            steps["annulée"] = at()
        }

        private fun done(failure: String?) {
            steps[TurnStopwatch.END] = at()
            val what = if (call.request().method == "HEAD") "préchauffage" else "requête"
            val note = listOfNotNull(if (reused) "connexion réutilisée" else "connexion neuve", failure).joinToString(", ")
            runCatching { log.report(LatencyLine(what, steps.toList(), note)) }
        }
    }
}
