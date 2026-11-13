package app.trackly.call

import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.chat.asModelContext
import app.trackly.call.live.LaunchStep
import app.trackly.call.live.LiveTransport
import app.trackly.call.live.SessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.trackly.call.live.AudioLevels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Sortie audio de l'appel (Android : `AudioRouter`). */
interface CallAudio {
    /**
     * Mode communication et focus audio ; rend la sortie choisie (haut-parleur, sauf écouteurs branchés), ou `null` si le
     * téléphone refuse le focus (un vrai appel en cours…) : rien n'est alors laissé en place, et aucun appel ne part.
     */
    fun begin(): AudioRoute?

    fun setSpeaker(on: Boolean): AudioRoute

    fun end()

    object None : CallAudio {
        override fun begin() = AudioRoute.Speaker
        override fun setSpeaker(on: Boolean) = if (on) AudioRoute.Speaker else AudioRoute.Earpiece
        override fun end() = Unit
    }
}

/** Ce que le téléphone fait autour de l'appel : service de premier plan (micro) et vibration courte. */
interface CallPlatform {
    fun startForeground()

    fun stopForeground()

    fun vibrate()

    /** Une réflexion tourne encore après le raccroché : garder l'app en vie jusqu'à [CallServices.settling] faux. */
    fun holdUntilSettled() {}

    object None : CallPlatform {
        override fun startForeground() = Unit
        override fun stopForeground() = Unit
        override fun vibrate() = Unit
    }
}

/**
 * Ce que l'appel garde après coup : coût, transcription du dernier appel, mesures. Le chat du jour, lui, reçoit seulement ce
 * que la réflexion a noté (cartes et récap, [AgentCallThinker]) ; jamais la transcription.
 */
interface CallSink {
    suspend fun recordCost(model: String, sessionMs: Long, at: Instant)

    fun saveTranscript(lines: List<CallLine>, at: Instant)

    fun saveMetrics(metrics: CallMetrics)

    object None : CallSink {
        override suspend fun recordCost(model: String, sessionMs: Long, at: Instant) = Unit
        override fun saveTranscript(lines: List<CallLine>, at: Instant) = Unit
        override fun saveMetrics(metrics: CallMetrics) = Unit
    }
}

/**
 * L'appel dans l'app : préparation gratuite avant le toucher, lancement, puis ce qui reste après coup.
 *
 * - [prepare] tourne quand l'accueil est visible : clé déchiffrée hors du fil principal, transport préparé (fabrique,
 *   offre, collecte ICE), connexion TLS préchauffée. Rien de facturé, micro fermé. [release] libère tout quand l'app
 *   passe en arrière-plan.
 * - [start] lance l'appel au toucher. Le contexte vient du **fil du jour** ([dayThread]), jamais du fil du bilan.
 * - Seule la réflexion ([CallThinker]) note un repas, dans le chat du jour, annulable comme le reste.
 * - [afterCall] reçoit, une fois la dernière réflexion finie, ce qui reste à reprendre : questions sans réponse, paroles
 *   non traitées, et ce qui a été noté. En mémoire seulement.
 * - [review] relit l'appel une fois après coup : il en garde le nécessaire dès le raccroché, pour finir même si
 *   Android tue l'app ; l'app reste en vie jusqu'à sa fin ([settling]), et [resumeReview] reprend à l'ouverture.
 */
class CallServices(
    private val scope: CoroutineScope,
    val settings: CallSettings,
    private val readKey: suspend () -> String?,
    private val dayThread: DayThread,
    thinker: CallThinker,
    private val newTransport: (CallEcho) -> LiveTransport,
    private val audio: CallAudio = CallAudio.None,
    private val platform: CallPlatform = CallPlatform.None,
    private val sink: CallSink = CallSink.None,
    /** Préchauffage TLS vers OpenAI (requête `HEAD` sans clé, gratuite). */
    private val prewarm: () -> Unit = {},
    now: () -> Long,
    wallClock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Réseau par défaut du moment : une offre préparée sur un autre réseau ne sert plus (ses adresses ont changé). */
    private val network: () -> Any? = { null },
    /** Émet à chaque changement de la clé (enregistrée, remplacée, supprimée) : la préparation est refaite. */
    keyChanges: Flow<Any?> = emptyFlow(),
    /** Repas de l'appel à reprendre après coup ; rien par défaut. */
    private val afterCall: (CallMealReport) -> Unit = {},
    /** Relecture après l'appel ; aucune par défaut (aperçus, tests de l'appel seul). */
    private val review: CallReview? = null,
    /** Fabrique du contrôleur (tests : délais raccourcis). */
    controllerFactory: ((onListening: () -> Unit, onFinished: (CallSummary) -> Unit, onMeals: (CallMealReport) -> Unit) -> CallController)? = null,
) {
    val controller: CallController = controllerFactory?.invoke(platform::vibrate, ::onFinished, ::onMeals)
        ?: CallController(
            scope, now, wallClock, thinker,
            onListening = platform::vibrate, onFinished = ::onFinished, onMeals = ::onMeals, onProgress = ::onProgress,
        )

    /** Pendant l'appel : de quoi le relire est gardé, au cas où Android tuerait l'app avant le raccroché. */
    private fun onProgress(startedAt: Instant, transcript: List<CallLine>, reflection: CallReflection) {
        if (!controller.state.value.free) runCatching { review?.onCallProgress(startedAt, transcript, reflection) }
    }

    private val _lastMeals = MutableStateFlow<CallMealReport?>(null)

    /** Repas du dernier appel, une fois sa dernière réflexion finie (en mémoire seulement). */
    val lastMeals: StateFlow<CallMealReport?> = _lastMeals.asStateFlow()

    private val _settling = MutableStateFlow(false)

    /**
     * Un appel est fini, mais sa dernière réflexion ou sa relecture tourne encore (l'app est gardée en vie jusqu'à leur
     * fin, micro fermé).
     */
    val settling: StateFlow<Boolean> = _settling.asStateFlow()

    /** La dernière réflexion de l'appel fini n'a pas encore rendu son rapport. */
    private var callSettling = false

    /** Appels de démonstration (transport gratuit) : aucune relecture. */
    private val freeCalls = mutableSetOf<Instant>()

    /** Réglage de réflexion de l'appel en cours (la relecture le garde). */
    private var callReflection = CallReflection.Default

    private fun refreshSettling() {
        _settling.value = callSettling || review?.running?.value == true
    }

    private fun onMeals(report: CallMealReport) {
        callSettling = false
        _lastMeals.value = report
        // Une démo gratuite (aperçus) n'ouvre jamais de relecture payante ; celles laissées pour l'appel reprennent.
        if (report.startedAt !in freeCalls) runCatching { review?.onReport(report) } else runCatching { review?.resume() }
        refreshSettling()
        runCatching { afterCall(report) }
    }

    /**
     * Ouverture de l'app : une relecture restée en attente (échec réseau, app tuée juste après l'appel) est reprise, hors
     * appel. Sans rien en attente, rien ne se passe (lecture d'un seul fichier, sans réseau).
     */
    fun resumeReview(hold: Boolean = true) {
        val r = review ?: return
        if (controller.isActive || callSettling || _screenOpen.value) return
        if (r.resume()) {
            refreshSettling()
            if (hold && _settling.value) platform.holdUntilSettled()
        }
    }

    private val _screenOpen = MutableStateFlow(false)

    /** L'écran d'appel est ouvert (du toucher jusqu'à ce que l'utilisateur le referme). */
    val screenOpen: StateFlow<Boolean> = _screenOpen.asStateFlow()

    private val current = MutableStateFlow<LiveTransport?>(null)

    /** Niveaux audio de l'appel en cours (vagues) ; silence hors appel. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val levels: StateFlow<AudioLevels> = current
        .flatMapLatest { it?.levels ?: flowOf(AudioLevels()) }
        .stateIn(scope, SharingStarted.Eagerly, AudioLevels())

    private var prepared: LiveTransport? = null
    private var preparedEcho: CallEcho? = null
    private var preparedNetwork: Any? = null
    private var preparedSteps = mutableListOf<Pair<LaunchStep, Long>>()
    private var preparedKey: String? = null
    private var prepareJob: Job? = null
    private var startJob: Job? = null
    private var refreshJob: Job? = null
    private val stamp = now

    init {
        review?.let { r ->
            // La relecture partage le verrou de la réflexion : elle ne part jamais pendant un appel.
            r.callInProgress = { controller.isActive || startJob?.isActive == true }
            scope.launch { r.running.collect { refreshSettling() } }
        }
        // Clé changée dans les Réglages : la clé gardée par la préparation n'est plus la bonne.
        scope.launch {
            keyChanges.drop(1).collect {
                if (!controller.isActive && !_screenOpen.value) {
                    prepareJob?.cancel()
                    refreshJob?.cancel()
                    discardPrepared()
                    prepare()
                }
            }
        }
    }

    /** Comme `runCatching`, mais une annulation remonte toujours (sinon un appel annulé partirait quand même). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Préparation gratuite, avant le toucher (accueil visible). Sans effet pendant un appel ou si déjà faite. */
    fun prepare() {
        if (controller.isActive || _screenOpen.value) return
        if (prepareJob?.isActive == true) return
        val echo = settings.echo.value
        val current = prepared
        if (current != null && usable(current, echo)) return
        discardPrepared()
        prepareJob = scope.launch {
            val steps = mutableListOf<Pair<LaunchStep, Long>>()
            val t0 = stamp()
            val net = network()
            val key = attempt { withContext(io) { readKey() } }
            steps += LaunchStep.Key to (stamp() - t0)
            // Sans clé, rien à préparer : le toucher dira qu'il faut la clé.
            if (key == null) return@launch
            prewarm()
            val transport = newTransport(echo)
            try {
                transport.prepare { step, ms -> steps += step to ms }
            } catch (e: CancellationException) {
                transport.close()
                throw e
            } catch (e: Exception) {
                // La préparation n'est qu'un gain de temps : le toucher refera tout.
                transport.close()
                return@launch
            }
            prepared = transport
            preparedEcho = echo
            preparedNetwork = net
            preparedKey = key
            preparedSteps = steps
            // Une offre ne sert pas plus d'une minute (réutilisation non documentée) : refaite avant, tant que l'accueil reste visible.
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(REFRESH_AFTER_MS)
                if (prepared === transport && !controller.isActive && !_screenOpen.value) {
                    discardPrepared()
                    prepare()
                }
            }
        }
    }

    /** L'app passe en arrière-plan sans appel : tout ce qui a été préparé est libéré. */
    fun release() {
        if (controller.isActive || _screenOpen.value) return
        prepareJob?.cancel()
        refreshJob?.cancel()
        discardPrepared()
    }

    /**
     * Toucher du bouton d'appel : l'écran s'ouvre tout de suite, puis l'appel part. [micAllowed] : la permission du
     * micro est accordée (l'écran la demande avant). Clé absente ou micro refusé : un message clair, rien de facturé.
     */
    fun start(micAllowed: Boolean) {
        if (controller.isActive || startJob?.isActive == true) return
        _screenOpen.value = true
        _lastMeals.value = null
        // Chaque toucher part d'un écran neuf (Muet compris) ; ce que l'utilisateur touche ensuite est gardé jusqu'au départ.
        controller.dismiss()
        // Une relecture d'un appel précédent libère la réflexion tout de suite ; elle reprend après ce nouvel appel.
        review?.yieldToCall()
        if (!micAllowed) {
            controller.refuse(EndReason.NoPermission)
            return
        }
        startJob = scope.launch {
            // La préparation en cours finit d'abord : elle est déjà payée en temps.
            prepareJob?.join()
            refreshJob?.cancel()
            val echo = settings.echo.value
            val steps = mutableListOf<Pair<LaunchStep, Long>>()
            var key = preparedKey
            val transport = prepared?.takeIf { usable(it, echo) }
            if (transport != null) {
                steps += preparedSteps
                prepared = null
            } else {
                discardPrepared()
            }
            preparedKey = null
            // Tant que le contrôleur n'a pas pris le transport, c'est ici qu'on le ferme (écran refermé, annulation).
            var handedOver = false
            try {
                if (key == null) {
                    val t0 = stamp()
                    key = attempt { withContext(io) { readKey() } }
                    steps += LaunchStep.Key to (stamp() - t0)
                }
                if (key == null) {
                    controller.refuse(EndReason.MissingKey)
                    return@launch
                }
                val history = attempt { dayThread.last(SessionRequest.MAX_HISTORY).asModelContext() } ?: emptyList()
                val session = SessionRequest.session(CallInstructions.VOICE, history)
                // Jamais d'appel sans écran : refermé entre-temps, rien ne part.
                ensureActive()
                if (!_screenOpen.value) return@launch
                // Sans focus audio (vrai appel téléphonique en cours…), la session payante ne part pas.
                val route = audio.begin()
                if (route == null) {
                    controller.refuse(EndReason.AudioBusy)
                    return@launch
                }
                platform.startForeground()
                callReflection = settings.reflection.value
                val chosen = transport ?: newTransport(echo)
                current.value = chosen
                handedOver = true
                controller.start(
                    transport = chosen,
                    apiKey = key,
                    session = session,
                    reflection = callReflection,
                    route = route,
                    preparedSteps = steps,
                    // Muet touché pendant la préparation : gardé, appliqué avant que le micro ne s'ouvre.
                    micMuted = controller.state.value.micMuted,
                )
            } finally {
                if (!handedOver) transport?.close()
            }
        }
    }

    /** Vibration courte du toucher. */
    fun platformVibrate() = platform.vibrate()

    fun hangUp() {
        if (controller.isActive) controller.hangUp() else close()
    }

    fun toggleMute() = controller.setMicMuted(!controller.state.value.micMuted)

    fun toggleSpeaker() {
        val speaker = controller.state.value.route == AudioRoute.Speaker
        controller.setRoute(audio.setSpeaker(!speaker))
    }

    /** L'écran d'appel se referme (appel fini) : retour à l'accueil, et nouvelle préparation pour le prochain. */
    fun close() {
        if (controller.isActive) return
        startJob?.cancel()
        _screenOpen.value = false
        current.value = null
        controller.dismiss()
        prepare()
    }

    /** L'app est fermée pour de bon, ou le téléphone prend le son : l'appel s'arrête tout de suite. */
    fun abort(reason: EndReason) {
        startJob?.cancel()
        controller.abort(reason)
    }

    private fun onFinished(summary: CallSummary) {
        audio.end()
        platform.stopForeground()
        // De quoi relire l'appel est gardé tout de suite : la relecture se fera même si Android tue l'app avant le rapport.
        if (summary.free) freeCalls += summary.startedAt else runCatching { review?.onCallEnded(summary.startedAt, summary.transcript, callReflection) }
        // Le rapport part juste après si aucune réflexion ne tourne ; sinon l'app reste en vie jusqu'à lui, puis jusqu'à la
        // fin de la relecture (micro fermé).
        callSettling = true
        refreshSettling()
        scope.launch { if (_settling.value) platform.holdUntilSettled() }
        scope.launch {
            // Aucune session créée (refus, échec avant l'envoi) : rien n'est facturé, rien n'est compté.
            if (!summary.free && summary.billedMs > 0) runCatching { sink.recordCost(SessionRequest.VOICE_MODEL, summary.billedMs, summary.startedAt) }
            withContext(io) {
                runCatching { sink.saveTranscript(summary.transcript, summary.startedAt) }
                runCatching { sink.saveMetrics(summary.metrics()) }
            }
        }
    }

    private fun discardPrepared() {
        prepared?.close()
        prepared = null
        preparedEcho = null
        preparedNetwork = null
        preparedKey = null
        preparedSteps = mutableListOf()
    }

    private companion object {
        /** Avant la limite d'une minute de l'offre préparée. */
        const val REFRESH_AFTER_MS = 50_000L
    }

    /** Préparation encore bonne : même réglage d'écho, même réseau, offre de moins d'une minute. */
    private fun usable(transport: LiveTransport, echo: CallEcho): Boolean =
        preparedEcho == echo && !transport.isStale && preparedNetwork == network()
}
