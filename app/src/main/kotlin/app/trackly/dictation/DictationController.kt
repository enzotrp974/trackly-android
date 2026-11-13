package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.core.model.TermCorrection
import app.trackly.core.model.TermCorrections
import app.trackly.dictation.audio.AudioSource
import app.trackly.dictation.audio.AudioSourceFactory
import app.trackly.dictation.audio.AudioUnavailableException
import app.trackly.dictation.audio.Pcm
import app.trackly.dictation.audio.PcmBuffer
import app.trackly.dictation.audio.PcmResampler
import app.trackly.dictation.audio.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.roundToLong

/** Ce dont la dictée a besoin ; assemblé par `AppContainer`, remplacé par des faux dans les tests. */
class DictationServices(
    val settings: DictationSettings,
    val permission: MicPermission,
    val audio: AudioSourceFactory,
    val live: LiveTranscriber,
    val file: FileTranscriber,
    /** Noms et raccourcis des produits récurrents (voir [DictationKeywords]). */
    val keywords: suspend () -> List<String> = { emptyList() },
    /** Mots corrigés actifs (« skir » → « Skyr ») : le texte final dicté en est corrigé avant d'arriver dans le chat. */
    val corrections: suspend () -> List<TermCorrection> = { emptyList() },
    val usage: TranscriptionUsage = TranscriptionUsage.None,
    val clock: () -> Instant = Instant::now,
)

/**
 * Chef d'orchestre de la dictée : permission, capture, transcription (direct ou après l'arrêt),
 * erreurs, coût. Il ne touche ni au chat ni aux données : il rend un [DictationResult].
 *
 * Règles :
 * - le micro s'ouvre tout de suite ; en direct, l'audio capté pendant la connexion est gardé et envoyé
 *   dès qu'elle est prête (les premiers mots ne sont pas perdus) ;
 * - tout l'audio reste en mémoire le temps de la dictée (jamais sur le disque) : après une erreur,
 *   « Réessayer » le transcrit en mode fichier, et le texte partiel reste récupérable ;
 * - aucune dictée n'est perdue sans geste de l'utilisateur (« Annuler », « Fermer »).
 */
class DictationController(
    private val services: DictationServices,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val finalTimeoutMs: Long = FINAL_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow(DictationUiState())
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private val _results = Channel<DictationResult>(Channel.UNLIMITED)

    /** Textes dictés, remis une seule fois chacun (mis en attente tant que personne n'écoute). */
    val results: Flow<DictationResult> = _results.receiveAsFlow()

    @Volatile private var session: Session? = null
    private var retained: PcmBuffer? = null

    // ───── Permission ─────

    /** Le bouton micro est touché. */
    fun onMicTapped() {
        val phase = _state.value.phase
        if (phase == DictationPhase.Listening || phase == DictationPhase.Finishing) return
        discard()
        val permission = services.permission
        when {
            permission.isGranted() -> begin()
            // Première demande, ou refus simple : le système accepte encore d'ouvrir sa fenêtre.
            !services.settings.micRequested || permission.shouldShowRationale() -> prompt(PermissionPrompt.Explain)
            // Déjà demandé et le système ne propose plus rien : refus définitif.
            else -> prompt(PermissionPrompt.Blocked)
        }
    }

    /** Résultat de la fenêtre d'autorisation du système. */
    fun onPermissionResult(granted: Boolean) {
        services.settings.micRequested = true
        if (_state.value.phase != DictationPhase.Permission) return
        when {
            granted -> begin()
            services.permission.shouldShowRationale() -> prompt(PermissionPrompt.Denied)
            else -> prompt(PermissionPrompt.Blocked)
        }
    }

    private fun prompt(prompt: PermissionPrompt) {
        _state.value = DictationUiState(phase = DictationPhase.Permission, mode = services.settings.mode.value, permission = prompt)
    }

    // ───── Pendant la dictée ─────

    /** « Envoyer » : arrête l'écoute, attend le texte final, puis l'envoie. */
    fun stopAndSend() = stop(StopAction.Send)

    /** « Relire d'abord » / « Arrêter » : arrête l'écoute et place le texte dans la zone de saisie. */
    fun stopAndEdit() = stop(StopAction.Edit)

    private fun stop(action: StopAction) {
        if (_state.value.phase == DictationPhase.Listening) session?.signals?.trySend(Signal.Stop(action))
    }

    /** « Annuler » : tout est jeté (c'est un geste explicite). */
    fun cancel() {
        session?.abort()
        session = null
        discard()
    }

    /** L'app quitte l'écran (retour, autre appli) : on termine proprement, le texte va dans la zone de saisie. */
    fun onAppStopped() {
        when (_state.value.phase) {
            DictationPhase.Listening -> stopAndEdit()
            DictationPhase.Permission -> discard()
            else -> Unit
        }
    }

    // ───── Après une erreur ─────

    /** « Réessayer » : transcrit l'enregistrement gardé (mode fichier, moins cher), le texte va dans la zone de saisie. */
    fun retry() {
        val audio = retained ?: return
        if (_state.value.phase != DictationPhase.Failed) return
        _state.update { it.copy(phase = DictationPhase.Finishing, failure = null, canRetry = false) }
        val s = Session(DictationMode.AfterStop, audio, initialText = _state.value.text)
        session = s
        s.job = scope.launch { s.transcribeRetained() }
    }

    /** « Garder le texte » : le texte partiel est remis tel quel dans la zone de saisie. */
    fun keepText() {
        val text = _state.value.text.trim()
        discard()
        if (text.isNotEmpty()) scope.launch { _results.trySend(DictationResult(corrected(text), send = false, notice = PARTIAL_NOTICE)) }
    }

    /** Ferme un message (permission, erreur). L'enregistrement gardé est jeté. */
    fun dismiss() = discard()

    /** [text] avec les mots corrigés (mot entier). Le texte tapé, lui, n'est jamais réécrit. */
    private suspend fun corrected(text: String): String =
        TermCorrections.apply(text, runCatching { services.corrections() }.getOrDefault(emptyList()))

    private fun discard() {
        retained?.clear()
        retained = null
        _state.value = DictationUiState()
    }

    private fun begin() {
        val mode = services.settings.mode.value
        _state.value = DictationUiState(phase = DictationPhase.Listening, mode = mode, connecting = mode == DictationMode.Live)
        val s = Session(mode, PcmBuffer())
        session = s
        s.job = scope.launch { s.run() }
    }

    private sealed interface Signal {
        data class Stop(val action: StopAction) : Signal
        data class LiveFailed(val error: AssistantError) : Signal
    }

    /** Une dictée, de l'ouverture du micro au texte rendu. */
    private inner class Session(val mode: DictationMode, val pcm: PcmBuffer, initialText: String = "") {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        var job: Job? = null
        @Volatile private var audio: AudioSource? = null
        @Volatile private var connection: LiveConnection? = null
        @Volatile private var stopping = false
        private val texts = LinkedHashMap<String, String>().apply { if (initialText.isNotBlank()) put("", initialText) }

        fun abort() {
            stopping = true
            audio?.stop()
            connection?.close()
            job?.cancel()
        }

        suspend fun run() {
            try {
                listenThenFinish()
            } finally {
                stopping = true
                audio?.close()
                connection?.close()
            }
        }

        private suspend fun listenThenFinish() = coroutineScope {
            val source = try {
                withContext(io) { services.audio.open() }
            } catch (e: AudioUnavailableException) {
                failed(DictationFailure.MicUnavailable, keepAudio = false)
                return@coroutineScope
            }
            audio = source
            val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
            val ready = CompletableDeferred<LiveConnection?>()
            val completion = CompletableDeferred<LiveEvent.Completed>()
            var sender: Job? = null
            val capture = launch(io) { capture(source, outgoing) }
            if (mode == DictationMode.Live) {
                launch {
                    val connected = try {
                        val keywords = runCatching { services.keywords() }.getOrDefault(emptyList())
                        services.live.connect(LiveConfig(keywords = keywords))
                    } catch (e: AssistantException) {
                        signals.trySend(Signal.LiveFailed(e.error))
                        ready.complete(null)
                        return@launch
                    }
                    connection = connected
                    _state.update { it.copy(connecting = false) }
                    sender = launch { for (chunk in outgoing) connected.sendAudio(chunk) }
                    ready.complete(connected)
                    launch { collect(connected, completion) }
                }
            }

            try {
                val signal = signals.receive()
                stopping = true
                source.stop()
                capture.join()
                when (signal) {
                    is Signal.LiveFailed -> failed(apiFailure(signal.error), keepAudio = true)
                    is Signal.Stop -> {
                        _state.update { it.copy(phase = DictationPhase.Finishing, connecting = false) }
                        if (mode == DictationMode.Live) {
                            finishLive(signal.action, ready, completion) { sender }
                        } else {
                            transcribeFile(pcm, signal.action)
                        }
                    }
                }
            } finally {
                // Ferme la connexion avant de quitter le scope : la lecture des événements s'arrête avec elle.
                connection?.close()
            }
        }

        /** Boucle de lecture du micro : garde l'audio, calcule le niveau, découpe en morceaux de 200 ms. */
        private fun capture(source: AudioSource, outgoing: Channel<ByteArray>) {
            val resampler = if (source.sampleRate == Pcm.RATE) null else PcmResampler(source.sampleRate)
            val read = ShortArray(maxOf(1, source.sampleRate / 10)) // 100 ms
            val pending = ByteArrayOutputStream()

            fun push(samples: ShortArray) {
                if (samples.isEmpty()) return
                val bytes = Pcm.toLittleEndian(samples)
                pcm.append(bytes)
                if (mode == DictationMode.Live) {
                    pending.write(bytes)
                    while (pending.size() >= Pcm.CHUNK_BYTES) {
                        val all = pending.toByteArray()
                        outgoing.trySend(all.copyOf(Pcm.CHUNK_BYTES))
                        pending.reset()
                        pending.write(all, Pcm.CHUNK_BYTES, all.size - Pcm.CHUNK_BYTES)
                    }
                }
                val elapsed = pcm.durationMs
                val level = Pcm.level(samples)
                _state.update { if (it.phase == DictationPhase.Listening) it.copy(level = level, elapsedMs = elapsed) else it }
                if (elapsed >= maxDurationMs && !stopping) {
                    // Plafond de durée (taille du fichier envoyé, mémoire) : on termine, sans rien envoyer tout seul.
                    signals.trySend(Signal.Stop(StopAction.Edit))
                    stopping = true
                    source.stop()
                }
            }

            try {
                while (true) {
                    val n = source.read(read)
                    if (n < 0) break
                    push(resampler?.process(read, n) ?: read.copyOf(n))
                }
                resampler?.finish()?.let(::push)
            } finally {
                if (mode == DictationMode.Live && pending.size() > 0) outgoing.trySend(pending.toByteArray())
                outgoing.close()
                // Le micro s'est arrêté sans qu'on l'ait demandé (appel téléphonique, autre appli) : on garde ce qui a été dit.
                if (!stopping) signals.trySend(Signal.Stop(StopAction.Edit))
            }
        }

        /** Reçoit les événements du serveur : texte provisoire, texte final, erreurs. */
        private suspend fun collect(conn: LiveConnection, completion: CompletableDeferred<LiveEvent.Completed>) {
            conn.events.collect { event ->
                when (event) {
                    is LiveEvent.Delta -> {
                        synchronized(texts) { texts[event.itemId] = texts[event.itemId].orEmpty() + event.text }
                        publishText()
                    }
                    is LiveEvent.Committed -> Unit
                    is LiveEvent.Completed -> {
                        synchronized(texts) { texts[event.itemId] = event.text }
                        publishText()
                        completion.complete(event)
                    }
                    is LiveEvent.Failed -> lost(event.error, completion)
                    LiveEvent.Closed -> lost(AssistantError.NoInternet, completion)
                }
            }
        }

        private fun lost(error: AssistantError, completion: CompletableDeferred<LiveEvent.Completed>) {
            if (completion.isCompleted) return
            completion.completeExceptionally(AssistantException(error))
            signals.trySend(Signal.LiveFailed(error))
        }

        private fun partialText(): String = synchronized(texts) { texts.values.joinToString(" ") }.trim()

        private fun publishText() {
            val text = partialText()
            _state.update { if (it.phase == DictationPhase.Listening || it.phase == DictationPhase.Finishing) it.copy(text = text) else it }
        }

        /** Arrêt en direct : audio restant envoyé, `commit`, attente du texte final (avec repli sur le texte provisoire). */
        private suspend fun finishLive(
            action: StopAction,
            ready: CompletableDeferred<LiveConnection?>,
            completion: CompletableDeferred<LiveEvent.Completed>,
            sender: () -> Job?,
        ) {
            if (pcm.durationMs < MIN_AUDIO_MS) return failed(DictationFailure.NothingHeard, keepAudio = false)
            val conn = ready.await()
            if (conn == null) {
                val error = (signals.tryReceive().getOrNull() as? Signal.LiveFailed)?.error ?: AssistantError.NoInternet
                return failed(apiFailure(error), keepAudio = true)
            }
            sender()?.join()
            if (!conn.commit()) return failed(DictationFailure.Api(AssistantError.NoInternet), keepAudio = true)
            val completed = try {
                withTimeoutOrNull(finalTimeoutMs) { completion.await() }
            } catch (e: AssistantException) {
                return failed(e.toFailure(), keepAudio = true)
            }
            val measured = pcm.durationMs
            if (completed == null) {
                // Le texte final n'est pas arrivé : on garde le texte vu à l'écran, à relire avant tout envoi.
                charge(mode.model, measured, null)
                val partial = partialText()
                return if (partial.isNotEmpty()) {
                    deliver(DictationResult(partial, send = false, notice = UNCONFIRMED_NOTICE))
                } else {
                    failed(DictationFailure.Api(AssistantError.Timeout), keepAudio = true)
                }
            }
            charge(mode.model, measured, completed.seconds)
            val text = completed.text.trim()
            if (text.isEmpty()) failed(DictationFailure.NothingHeard, keepAudio = false)
            else deliver(DictationResult(text, send = action == StopAction.Send))
        }

        /** Mode « après l'arrêt » (et repli après une erreur) : l'enregistrement part en un seul fichier. */
        suspend fun transcribeFile(buffer: PcmBuffer, action: StopAction = StopAction.Edit) {
            if (buffer.durationMs < MIN_AUDIO_MS) return failed(DictationFailure.NothingHeard, keepAudio = false)
            val keywords = runCatching { services.keywords() }.getOrDefault(emptyList())
            val result = try {
                services.file.transcribe(Wav.wrap(buffer.toByteArray()), FileTranscriptionConfig(keywords = keywords))
            } catch (e: AssistantException) {
                return failed(e.toFailure(), keepAudio = true)
            }
            charge(DictationMode.AfterStop.model, buffer.durationMs, result.seconds)
            if (result.text.isBlank()) failed(DictationFailure.NothingHeard, keepAudio = false)
            else deliver(DictationResult(result.text.trim(), send = action == StopAction.Send))
        }

        suspend fun transcribeRetained() {
            try {
                transcribeFile(pcm)
            } finally {
                stopping = true
            }
        }

        /** Enregistre le coût : la durée de la réponse (`usage.type = "duration"`) sinon la durée mesurée par l'app. */
        private suspend fun charge(model: String, measuredMs: Long, seconds: Double?) {
            val audioMs = seconds?.let { (it * 1000).roundToLong() } ?: measuredMs
            runCatching { services.usage.record(model, audioMs, services.clock()) }
        }

        private suspend fun deliver(result: DictationResult) {
            _results.trySend(result.copy(text = corrected(result.text)))
            pcm.clear()
            retained = null
            session = null
            _state.value = DictationUiState()
        }

        private fun failed(failure: DictationFailure, keepAudio: Boolean) {
            val keep = keepAudio && pcm.size > 0
            retained = if (keep) pcm else { pcm.clear(); null }
            session = null
            _state.value = DictationUiState(
                phase = DictationPhase.Failed,
                mode = mode,
                text = partialText(),
                failure = failure,
                canRetry = keep && failure.notice().retryable,
                elapsedMs = pcm.durationMs,
            )
        }
    }

    companion object {
        /** Durée maximale : au-delà, un WAV 24 kHz dépasserait la limite de 25 Mo du mode fichier. */
        const val MAX_DURATION_MS = 8 * 60_000L

        /** Attente du texte final après l'arrêt (la note d'OpenAI conseille 10 à 15 s). */
        const val FINAL_TIMEOUT_MS = 12_000L

        /** En dessous, ce n'est pas une phrase (appui accidentel) : pas d'appel, donc pas de coût. */
        const val MIN_AUDIO_MS = 300L

        const val UNCONFIRMED_NOTICE = "La transcription finale n’est pas arrivée : relis ce texte avant de l’envoyer."
        const val PARTIAL_NOTICE = "Texte partiel de la dictée : relis-le avant de l’envoyer."
    }
}
