package app.trackly.call.live

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.SystemClock
import app.trackly.call.CallEcho
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Transport réel : WebRTC vers `POST /v1/live/sessions` (doc GPT-Live relue le 6/11/2026).
 *
 * - [prepare] (gratuit, avant le toucher) : fabrique WebRTC avec les essais **WARP** (DTLS 1.3, SNAP, SPED), module
 *   audio en communication vocale, canal de données **pré-négocié** (`id` [SessionRequest.DATA_CHANNEL_ID]), offre
 *   SDP et collecte ICE. Aucun contact avec OpenAI, et le micro n'est pas encore ouvert.
 * - [open] (facturé) : la requête de session, puis la réponse SDP. Plus d'attente fixe de collecte ICE : sans
 *   serveur STUN, seuls les candidats locaux sont collectés, en quelques millisecondes, pendant la préparation.
 *
 * Annulation d'écho selon [echo] : WebRTC seul (logiciel), ou celle du téléphone complétée par WebRTC.
 * La clé ne sert qu'à l'en-tête `Authorization` de la requête : jamais gardée, jamais journalisée.
 */
class WebRtcLiveTransport(
    private val context: Context,
    private val echo: CallEcho,
    private val http: OkHttpClient,
    private val endpoint: String = SessionRequest.ENDPOINT,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : LiveTransport {
    override val isFree = false
    private val _levels = MutableStateFlow(AudioLevels())
    override val levels: StateFlow<AudioLevels> = _levels

    @Volatile private var micLevel = 0f
    @Volatile private var aiLevel = 0f
    @Volatile private var micProcessed: Float? = null
    @Volatile private var closed = false
    @Volatile private var released = false
    @Volatile override var requestSent = false
        private set

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var track: org.webrtc.AudioTrack? = null

    /** Muet demandé avant que la piste existe (préparation faite au toucher) : appliqué à sa création. */
    @Volatile private var micMuted = false
    private var offerSdp: String? = null
    private var preparedAt = 0L

    /** Rappels installés par [open] ; la préparation n'a encore personne à prévenir. */
    @Volatile private var onEvent: (TransportEvent) -> Unit = {}
    private var connectedAt: CompletableDeferred<Long> = CompletableDeferred()
    private var channelOpenAt: CompletableDeferred<Long> = CompletableDeferred()
    private val prepareLock = Mutex()

    /**
     * Un seul fil pour tous les objets WebRTC (création, envoi, fermeture) : une fermeture demandée pendant la
     * connexion ne peut jamais libérer un objet en cours d'usage.
     */
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trackly-appel-rtc") }
    private val rtc = executor.asCoroutineDispatcher()

    private fun ensureOpen() {
        if (closed) throw CancellationException("Transport fermé")
    }

    /** Vrai si l'offre préparée a plus de [OFFER_MAX_AGE_MS] : on n'en réutilise pas une vieille (non documenté). */
    override val isStale: Boolean get() = offerSdp != null && clock() - preparedAt > OFFER_MAX_AGE_MS

    override suspend fun prepare(onStep: (LaunchStep, Long) -> Unit) = prepareLock.withLock {
        if (offerSdp != null) return@withLock
        val gathered = CompletableDeferred<Unit>()
        var t = clock()
        val peer = withContext(rtc) {
            ensureOpen()
            try {
                ensureInitialized(context.applicationContext)
            } catch (e: UnsatisfiedLinkError) {
                // Architecture sans la librairie native (seul arm64 est embarqué) : message clair, aucun plantage.
                throw TransportException("Appel indisponible sur ce téléphone (WebRTC absent)", noSession = true)
            }
            val phoneAec = echo == CallEcho.Phone && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            val module = JavaAudioDeviceModule.builder(context.applicationContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(phoneAec)
                .setUseHardwareNoiseSuppressor(phoneAec && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setSamplesReadyCallback { s -> micLevel = rms(s.data); publish() }
                .setPlaybackSamplesReadyCallback { s -> aiLevel = rms(s.data); publish() }
                .createAudioDeviceModule()
            adm = module
            val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            factory = f
            // Traitements logiciels de WebRTC : toujours actifs (ils complètent ou remplacent le matériel).
            val constraints = MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
                mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
                mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
                mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
            }
            val source = f.createAudioSource(constraints)
            audioSource = source
            val mic = f.createAudioTrack("micro", source)
            mic.setEnabled(!micMuted)
            track = mic
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val peer = f.createPeerConnection(config, observer(gathered))
                ?: throw TransportException("WebRTC indisponible sur ce téléphone", noSession = true)
            pc = peer
            peer.addTrack(mic, listOf("micro"))
            // Canal pré-négocié (WARP) : il n'attend pas l'ouverture annoncée par l'autre côté.
            val init = DataChannel.Init().apply {
                negotiated = true
                id = SessionRequest.DATA_CHANNEL_ID
            }
            val channel = peer.createDataChannel("oai-events", init)
            dc = channel
            channel.registerObserver(channelObserver(channel))
            peer
        }
        onStep(LaunchStep.Factory, clock() - t)
        t = clock()
        val offer = withContext(rtc) { ensureOpen(); createOffer(peer) }
        withContext(rtc) { ensureOpen(); setLocal(peer, offer) }
        onStep(LaunchStep.Offer, clock() - t)
        t = clock()
        // Sans serveur STUN, la collecte ne prend que les candidats locaux : très court. Garde-fou seulement.
        withTimeoutOrNull(ICE_GATHER_GUARD_MS) { gathered.await() }
        offerSdp = withContext(rtc) { ensureOpen(); peer.localDescription?.description }
            ?: throw TransportException("Offre WebRTC introuvable", noSession = true)
        preparedAt = clock()
        onStep(LaunchStep.Ice, clock() - t)
    }

    override suspend fun open(
        session: JsonObject,
        apiKey: String,
        onEvent: (TransportEvent) -> Unit,
        onStep: (LaunchStep, Long) -> Unit,
    ) {
        this.onEvent = onEvent
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { openSteps(session, apiKey, onStep) }
        } catch (e: TimeoutCancellationException) {
            close()
            throw TransportException("Connexion trop longue (plus de ${CONNECT_TIMEOUT_MS / 1000} s)")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private suspend fun openSteps(session: JsonObject, apiKey: String, onStep: (LaunchStep, Long) -> Unit) {
        prepare(onStep)
        val sdp = offerSdp ?: throw TransportException("Offre WebRTC introuvable", noSession = true)
        val peer = pc ?: throw TransportException("Connexion WebRTC fermée", noSession = true)
        var t = clock()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(SessionRequest.body(session, sdp).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val answer = try {
            requestSent = true
            val (code, text) = http.newCall(request).await()
            // Durée de la requête gardée même en cas de refus : c'est le temps réseau vers OpenAI.
            onStep(LaunchStep.Http, clock() - t)
            if (code !in 200..299) throw TransportException(httpMessage(code, text), code)
            runCatching {
                Json.parseToJsonElement(text).jsonObject["transport"]!!.jsonObject["sdp"]!!.jsonPrimitive.content
            }.getOrElse { throw TransportException("Réponse du serveur illisible") }
        } catch (e: java.io.IOException) {
            // La session a pu être créée avant la coupure : on la compte (15 s au moins), par prudence.
            throw TransportException("Réseau indisponible (${e.javaClass.simpleName})")
        }
        t = clock()
        withContext(rtc) { ensureOpen(); setRemote(peer, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        val answered = clock()
        onStep(LaunchStep.Answer, answered - t)
        // ICE/DTLS et canal : chronométrés sans bloquer l'appel (les événements de session arrivent par le canal).
        connectedAt.invokeOnCompletion { if (it == null) onStep(LaunchStep.Connected, connectedAt.getCompleted() - answered) }
        channelOpenAt.invokeOnCompletion { if (it == null) onStep(LaunchStep.Channel, channelOpenAt.getCompleted() - answered) }
    }

    override fun send(json: String): Boolean {
        if (closed) return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        executor.execute {
            val channel = dc
            if (!released && channel != null && channel.state() == DataChannel.State.OPEN) {
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            }
        }
        return true
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
        if (closed) return
        executor.execute { if (!released) track?.setEnabled(!muted) }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        if (closed) return
        executor.execute { if (!released) adm?.setSpeakerMute(muted) }
    }

    /** Ferme : immédiat pour les autres fils, libération faite sur le fil WebRTC, après les envois en attente. */
    override fun close() {
        if (closed) return
        closed = true
        _levels.value = AudioLevels()
        connectedAt.cancel()
        channelOpenAt.cancel()
        runCatching {
            executor.execute {
                released = true
                runCatching { dc?.unregisterObserver(); dc?.close(); dc?.dispose() }
                runCatching { pc?.close(); pc?.dispose() }
                runCatching { track?.dispose() }
                runCatching { audioSource?.dispose() }
                runCatching { factory?.dispose() }
                runCatching { adm?.release() }
                dc = null; pc = null; track = null; audioSource = null; factory = null; adm = null
                _levels.value = AudioLevels()
            }
            executor.shutdown()
        }
    }

    /**
     * Niveau du micro **après** le traitement audio de WebRTC (annulation d'écho comprise) : `audioLevel` de la source
     * audio dans les statistiques, relu toutes les [STATS_EVERY_MS]. C'est un niveau de crête ; multiplié par
     * [PEAK_TO_RMS] pour rester comparable au niveau efficace du micro brut. Absent (ancienne librairie) : `null`.
     */
    private fun startStatsPolling() {
        runCatching {
            executor.scheduleAtFixedRate({
                val peer = pc
                if (released || peer == null) return@scheduleAtFixedRate
                runCatching {
                    peer.getStats { report ->
                        val level = report.statsMap.values
                            .firstOrNull { it.type == "media-source" && it.members["kind"] == "audio" }
                            ?.members?.get("audioLevel") as? Double
                        if (level != null && !closed) {
                            micProcessed = (level * PEAK_TO_RMS).toFloat()
                            publish()
                        }
                    }
                }
            }, 0, STATS_EVERY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun publish() {
        _levels.value = AudioLevels(micLevel, aiLevel, micProcessed)
    }

    private fun observer(gathered: CompletableDeferred<Unit>) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED && !closed) onEvent(TransportEvent.Closed("ICE en échec"))
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    connectedAt.complete(clock())
                    startStatsPolling()
                }
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED ->
                    if (!closed) onEvent(TransportEvent.Closed("connexion ${newState.name.lowercase()}"))
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private fun channelObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            when (channel.state()) {
                DataChannel.State.OPEN -> {
                    channelOpenAt.complete(clock())
                    onEvent(TransportEvent.ChannelOpen)
                }
                DataChannel.State.CLOSED -> if (!closed) onEvent(TransportEvent.Closed("canal fermé"))
                else -> Unit
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            onEvent(TransportEvent.Message(String(bytes, Charsets.UTF_8)))
        }
    }

    private suspend fun createOffer(peer: PeerConnection): SessionDescription {
        val result = CompletableDeferred<SessionDescription>()
        peer.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) result.complete(sdp) else result.completeExceptionally(TransportException("Offre WebRTC vide", noSession = true))
            }
            override fun onCreateFailure(error: String?) {
                result.completeExceptionally(TransportException("Offre WebRTC impossible : $error", noSession = true))
            }
        }, MediaConstraints())
        return result.await()
    }

    private suspend fun setLocal(peer: PeerConnection, sdp: SessionDescription) = setDescription { peer.setLocalDescription(it, sdp) }
    private suspend fun setRemote(peer: PeerConnection, sdp: SessionDescription) = setDescription { peer.setRemoteDescription(it, sdp) }

    private suspend fun setDescription(block: (SdpObserver) -> Unit) {
        val done = CompletableDeferred<Unit>()
        block(object : SdpObserverAdapter() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(error: String?) { done.completeExceptionally(TransportException("SDP refusé : $error")) }
        })
        done.await()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ICE_GATHER_GUARD_MS = 1_000L
        private const val STATS_EVERY_MS = 100L

        /** Rapport niveau efficace / crête d'une voix parlée (environ 0,3). À vérifier à l'essai. */
        private const val PEAK_TO_RMS = 0.3

        /** Une offre préparée depuis plus longtemps est jetée et refaite (réutilisation non documentée). */
        const val OFFER_MAX_AGE_MS = 60_000L

        /**
         * Essais WARP de WebRTC (guide realtime-webrtc-warp) : DTLS 1.3, SNAP et SPED, moins d'allers-retours à la
         * connexion. Posés une fois, avant la première fabrique. Un essai inconnu de la librairie est ignoré par elle.
         */
        const val WARP_FIELD_TRIALS = "WebRTC-ForceDtls13/Enabled/WebRTC-Sctp-Snap/Enabled/WebRTC-IceHandshakeDtls/Enabled/"

        /** Requête OkHttp annulable : l'annulation de la coroutine annule l'appel réseau. */
        private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { it.code to it.body.string() }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

        /** Client de la requête de session : partage le pool du préchauffage (TLS déjà ouvert vers api.openai.com). */
        fun httpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        @Volatile private var initialized = false

        private fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials(WARP_FIELD_TRIALS)
                        .createInitializationOptions(),
                )
                initialized = true
            }
        }

        /** Niveau efficace d'un morceau de PCM 16 bits, de 0 à 1. */
        fun rms(data: ByteArray): Float {
            val n = data.size / 2
            if (n == 0) return 0f
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            for (i in 0 until n) {
                val v = buf.getShort(i * 2) / 32768.0
                sum += v * v
            }
            return sqrt(sum / n).toFloat()
        }

        /** Message d'erreur pour l'écran : code HTTP et message de l'API, jamais la requête ni la clé. */
        fun httpMessage(code: Int, body: String): String {
            val apiMessage = runCatching {
                Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            }.getOrNull()?.take(160)
            val prefix = when (code) {
                401 -> "Clé refusée par OpenAI"
                403 -> "Accès refusé à l’appel vocal"
                404 -> "Appel vocal introuvable chez OpenAI"
                429 -> "Limite ou crédit OpenAI atteint"
                else -> "Erreur du serveur"
            }
            // Clé refusée : le message d'OpenAI (en anglais, avec une clé masquée) n'apporte rien.
            if (code == 401) return "$prefix ($code). Vérifie-la dans Réglages › Clé OpenAI."
            return Redact.secrets("$prefix ($code)" + (apiMessage?.let { " : $it" } ?: ""))
        }
    }
}
