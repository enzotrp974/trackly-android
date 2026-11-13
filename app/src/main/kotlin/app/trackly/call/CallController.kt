package app.trackly.call

import app.trackly.call.live.ClientEvents
import app.trackly.call.live.LaunchStep
import app.trackly.call.live.LiveEvent
import app.trackly.call.live.LiveEvents
import app.trackly.call.live.LiveTransport
import app.trackly.call.live.Redact
import app.trackly.call.live.TransportEvent
import app.trackly.call.live.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant

enum class CallPhase { Idle, Connecting, Live, Ending, Ended }

/** Par où sort le son pendant l'appel. */
enum class AudioRoute(val label: String, val isHeadset: Boolean) {
    Speaker("Haut-parleur", false),
    Earpiece("Écouteur du téléphone", true),
    Wired("Écouteurs filaires", true),
    Bluetooth("Écouteurs Bluetooth", true),
}

enum class EndReason(val code: String, val label: String) {
    User("raccroche", "Appel terminé"),
    Goodbye("au-revoir", "Appel terminé"),
    Silence("silence", "Raccroché après 30 s de silence"),
    MaxDuration("duree-max", "Appel arrêté à 10 minutes"),
    Server("serveur", "Appel fermé par le serveur"),
    Lost("perdu", "Connexion perdue"),
    Failed("echec", "L’appel n’a pas pu démarrer"),
    Interrupted("interrompu", "Appel coupé par le téléphone"),
    /** Erreur du serveur qui ne vise aucune commande de l'app : rien ne prouve que la session repart, on la ferme. */
    SessionError("erreur-session", "Appel arrêté après une erreur d’OpenAI"),
    AudioBusy("son-occupe", "Le téléphone utilise déjà le son (un autre appel ?). Réessaie quand il est libre."),
    ClosedApp("app-fermee", "Appel coupé : l’app a été fermée"),
    MissingKey("cle-absente", "Ajoute ta clé OpenAI dans Réglages › Clé OpenAI pour appeler."),
    NoPermission("micro-refuse", "Trackly a besoin du micro pour l’appel. Autorise-le dans les paramètres du téléphone."),
}

/** Ce que l'écran d'appel affiche. Le texte de la conversation n'y est pas : l'écran reste calme. */
data class CallUi(
    val phase: CallPhase = CallPhase.Idle,
    val free: Boolean = false,
    /** Temps écoulé depuis « je t'écoute ». */
    val elapsedMs: Long = 0,
    /** La réflexion travaille : la voix continue, ce n'est pas un silence. */
    val thinking: Boolean = false,
    val micMuted: Boolean = false,
    val route: AudioRoute = AudioRoute.Speaker,
    /** Phrase d'état ou d'erreur, déjà sans clé. */
    val status: String? = null,
    val endReason: EndReason? = null,
)

/** Bilan d'un appel, pour le coût, les mesures et la transcription du dernier appel. */
data class CallSummary(
    val startedAt: Instant,
    val free: Boolean,
    val prepared: Boolean,
    val steps: List<Pair<LaunchStep, Long>>,
    val beforeTap: Set<LaunchStep>,
    /** Du toucher à `session.started`. */
    val listenMs: Long?,
    val liveMs: Long,
    /** Durée de session à facturer (0 quand on sait qu'aucune session n'a été créée). */
    val billedMs: Long,
    val reflections: List<ReflectionTiming>,
    val bargeIns: Int,
    val nudges: Int,
    val endReason: EndReason,
    val lastError: String?,
    val transcript: List<CallLine>,
) {
    fun metrics(): CallMetrics = CallMetrics(startedAt, prepared, steps, beforeTap, listenMs, reflections, endReason.code, liveMs / 1000)
}

/**
 * Pilote un appel : ouverture par le transport, « je t'écoute », réflexions (délégation `client`), coupure gérée par
 * l'app, relance après 15 s de silence et raccroché à 30 s (la réflexion ne compte pas comme un silence), au revoir
 * de l'utilisateur, 10 minutes au plus. Tout passe par [LiveTransport] et [CallThinker] : testable sans réseau, en temps virtuel.
 *
 * Repas ([CallMeals]) : seule la réflexion note. Elle part quand la voix la demande, quand l'utilisateur a répondu à la
 * dernière question, ou après une pause sur une parole que la voix n'a pas transmise ; une seule à la fois. La voix n'attend
 * jamais : elle pose les questions de la réflexion une à la fois, dans une pause. Une réflexion encore en cours au raccroché
 * finit quand même (ce qu'elle note reste dans le chat), puis [onMeals] reçoit ce qui reste à reprendre.
 */
class CallController(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val wallClock: () -> Instant = Instant::now,
    private val thinker: CallThinker = CallThinker.None,
    private val tickMs: Long = 50,
    private val nudgeAfterMs: Long = 15_000,
    private val hangUpAfterMs: Long = 30_000,
    private val maxCallMs: Long = 10 * 60_000L,
    private val startTimeoutMs: Long = 15_000,
    /** Après une demande de fin : temps laissé à la voix pour finir sa phrase. */
    private val voiceFinishMaxMs: Long = 7_000,
    /** La voix a fini sa phrase quand elle se tait depuis autant. */
    private val voiceQuietMs: Long = 800,
    private val closeWaitMs: Long = 5_000,
    private val maxReflectionMs: Long = 90_000,
    /** Une réflexion est dite dans une pause d'au moins autant… */
    private val deliverQuietMs: Long = 700,
    /** … ou au plus tard après autant d'attente. */
    private val deliverMaxWaitMs: Long = 8_000,
    /** L'utilisateur a fini sa phrase (pour juger un au revoir) quand plus rien n'arrive de lui depuis autant. */
    private val utteranceEndMs: Long = 900,
    /** Une réflexion demandée part quand la transcription de l'utilisateur est retombée depuis autant… */
    private val thinkSettleMs: Long = 300,
    /** … ou au plus tard après autant (ses derniers mots arrivent parfois juste après la demande). */
    private val thinkSettleMaxMs: Long = 1_000,
    /** Filet de l'app : une parole de l'utilisateur que la voix n'a pas transmise est réfléchie après une pause d'autant. */
    private val pauseThinkMs: Long = 2_500,
    /** Une question suit la phrase précédente envoyée à la voix d'au moins autant (elle a le temps de la dire). */
    private val questionGapMs: Long = 2_000,
    /** Au-delà, une réflexion est arrêtée : la voix continue, ce que l'utilisateur a dit va à la relecture après l'appel. */
    private val reflectionTimeoutMs: Long = 120_000,
    private val bargeIn: () -> BargeInGuard = { BargeInGuard() },
    /** Signal « je t'écoute » (vibration courte). */
    private val onListening: () -> Unit = {},
    private val onFinished: (CallSummary) -> Unit = {},
    /** Repas de l'appel, une fois l'appel fini et sa dernière réflexion terminée. Appelé une fois par appel. */
    private val onMeals: (CallMealReport) -> Unit = {},
    /**
     * Pendant l'appel, à chaque phrase finie de l'utilisateur et avant chaque réflexion : la transcription jusque-là, pour que la
     * relecture la reprenne si Android tue l'app avant le raccroché. Doit rendre la main tout de suite.
     */
    private val onProgress: (startedAt: Instant, transcript: List<CallLine>, reflection: CallReflection) -> Unit = { _, _, _ -> },
) {
    private val _state = MutableStateFlow(CallUi())
    val state: StateFlow<CallUi> = _state.asStateFlow()

    private var job: Job? = null
    private var transport: LiveTransport? = null
    private var events = Channel<Any>(Channel.UNLIMITED)

    private object Tick
    private class ThinkDone(val result: ThinkResult)
    private class Delivery(val delegationIds: List<String>, val result: ThinkResult, val queuedAt: Long)

    /** Repas d'un appel ; une réflexion qui finit après le raccroché y retrouve le sien. */
    private class MealsRun {
        val meals = CallMeals()
        var ended = false
        var reported = false
        var transcript: List<CallLine> = emptyList()
        var startedAt: Instant = Instant.EPOCH
        var reflection: CallReflection = CallReflection.Default
    }

    // État de l'appel en cours (remis à zéro à chaque start).
    private var reflection = CallReflection.Default
    private var local = LocalTurnDetector()
    private var guard = BargeInGuard()
    private var startedAt: Instant = Instant.EPOCH
    private var tapAt = 0L
    private var prepared = false
    private val steps = mutableListOf<Pair<LaunchStep, Long>>()
    private var beforeTap = emptySet<LaunchStep>()
    private var liveAt: Long? = null
    private var lastActivityAt = 0L
    private var nudged = false
    private var nudges = 0
    private var run = MealsRun()
    private val meals: CallMeals get() = run.meals
    private val deliveries = mutableListOf<Delivery>()
    private var lastSentAt: Long? = null
    private val reflections = mutableListOf<ReflectionTiming>()
    private val lines = mutableListOf<CallLine>()
    private var lastInputAt: Long? = null
    private var utteranceJudged = true
    private var lastAiTranscriptAt: Long? = null
    private var lastError: String? = null
    private var serverSeconds: Double? = null
    private var finalServerSeconds: Double? = null
    private var noSession = false
    private var endRequested: EndReason? = null
    private var endRequestedAt = 0L
    private var waitForVoice = false
    private var closeSentAt: Long? = null
    private var serverClosed = false
    private var lastProgress: List<CallLine> = emptyList()

    val isActive: Boolean get() = _state.value.phase in ACTIVE

    /**
     * Lance un appel avec un transport déjà préparé ou non. [session] : configuration de la session (voix, consignes,
     * délégation `client`, fil du jour). [preparedSteps] : étapes faites avant le toucher, chronométrées. [micMuted] : Muet
     * touché avant le départ, appliqué avant que le micro ne s'ouvre.
     */
    fun start(
        transport: LiveTransport,
        apiKey: String,
        session: JsonObject,
        reflection: CallReflection,
        route: AudioRoute = AudioRoute.Speaker,
        preparedSteps: List<Pair<LaunchStep, Long>> = emptyList(),
        micMuted: Boolean = false,
    ) {
        if (isActive) return
        reset(transport, reflection, preparedSteps)
        if (micMuted) transport.setMicMuted(true)
        _state.value = CallUi(phase = CallPhase.Connecting, free = transport.isFree, micMuted = micMuted, route = route, status = "Connexion…")
        val inbox = events // propre à cet appel : un ancien transport ne peut pas écrire dans le suivant
        job = scope.launch {
            try {
                try {
                    transport.open(session, apiKey, onEvent = { inbox.trySend(it) }, onStep = { step, ms -> inbox.trySend(StepDone(step, ms)) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    if (e.noSession) noSession = true
                    drainSteps(inbox)
                    fail(e.message ?: "Échec de connexion")
                    return@launch
                } catch (e: Exception) {
                    drainSteps(inbox)
                    fail("Échec de connexion (${e.javaClass.simpleName})")
                    return@launch
                }
                loop(inbox)
            } finally {
                // Quoi qu'il arrive (annulation, écran fermé), le transport ne reste jamais ouvert.
                transport.close()
            }
        }
    }

    /** Appel refusé avant tout réseau (clé absente, micro refusé) : l'écran montre la raison, rien n'est facturé. */
    fun refuse(reason: EndReason) {
        if (isActive) return
        _state.value = CallUi(phase = CallPhase.Ended, endReason = reason, status = reason.label)
    }

    fun hangUp() {
        when (_state.value.phase) {
            CallPhase.Connecting -> {
                // Session peut-être déjà créée côté serveur : on demande sa fermeture si le canal est ouvert.
                transport?.send(ClientEvents.close())
                job?.cancel()
                finish(EndReason.User)
            }
            CallPhase.Live -> requestEnd(EndReason.User, voice = false)
            CallPhase.Ending -> if (closeSentAt == null) {
                // Deuxième toucher pendant l'au revoir : on n'attend plus la voix.
                waitForVoice = false
            }
            else -> Unit
        }
    }

    /**
     * Fermeture immédiate, sans attendre le serveur : l'écran disparaît (app fermée, tâche balayée) ou le téléphone
     * sonne. Envoie `session.close`, ferme le transport et rend le bilan.
     */
    fun abort(reason: EndReason) {
        if (!isActive) return
        transport?.send(ClientEvents.close())
        job?.cancel()
        finish(endRequested ?: reason)
    }

    fun setRoute(route: AudioRoute) = _state.update { it.copy(route = route) }

    fun setMicMuted(muted: Boolean) {
        transport?.setMicMuted(muted)
        _state.update { it.copy(micMuted = muted) }
    }

    /** L'écran d'appel a été vu et fermé : l'état revient au repos. */
    fun dismiss() {
        if (!isActive) _state.value = CallUi()
    }

    private class StepDone(val step: LaunchStep, val ms: Long)

    private fun drainSteps(inbox: Channel<Any>) {
        while (true) {
            val e = inbox.tryReceive().getOrNull() ?: break
            if (e is StepDone) steps += e.step to e.ms
        }
    }

    private fun reset(transport: LiveTransport, reflection: CallReflection, preparedSteps: List<Pair<LaunchStep, Long>>) {
        this.transport = transport
        this.reflection = reflection
        events = Channel(Channel.UNLIMITED)
        local = LocalTurnDetector()
        guard = bargeIn()
        startedAt = wallClock()
        tapAt = now()
        prepared = preparedSteps.any { it.first == LaunchStep.Offer }
        steps.clear()
        steps += preparedSteps
        beforeTap = preparedSteps.map { it.first }.toSet()
        liveAt = null
        lastActivityAt = tapAt
        nudged = false
        nudges = 0
        run = MealsRun()
        deliveries.clear()
        lastSentAt = null
        reflections.clear()
        lines.clear()
        lastInputAt = null
        utteranceJudged = true
        lastAiTranscriptAt = null
        lastError = null
        serverSeconds = null
        finalServerSeconds = null
        noSession = false
        endRequested = null
        waitForVoice = false
        closeSentAt = null
        serverClosed = false
        lastProgress = emptyList()
    }

    /**
     * Une seule file pour les événements du transport, les réflexions finies et les battements du minuteur : rien ne
     * se perd, et l'état de l'appel n'est touché que depuis cette boucle.
     */
    private suspend fun loop(inbox: Channel<Any>) = coroutineScope {
        val ticker = launch {
            while (true) {
                delay(tickMs)
                inbox.trySend(Tick)
            }
        }
        for (ev in inbox) {
            when (ev) {
                is TransportEvent -> handle(ev)
                is StepDone -> steps += ev.step to ev.ms
                is ThinkDone -> onThinkDone(ev)
                Tick -> tick()
            }
            if (_state.value.phase == CallPhase.Ended) break
        }
        ticker.cancel()
    }

    private fun handle(ev: TransportEvent) {
        when (ev) {
            TransportEvent.ChannelOpen -> Unit
            is TransportEvent.Closed -> {
                serverClosed = true
                if (endRequested == null) {
                    lastError = ev.cause?.let(Redact::secrets) ?: lastError
                    endRequested = EndReason.Lost
                    endRequestedAt = now()
                }
            }
            is TransportEvent.Message -> LiveEvents.parse(ev.json)?.let(::handleLive)
        }
    }

    private fun handleLive(e: LiveEvent) {
        val t = now()
        when (e) {
            // Démarrage arrivé après une demande de fin (raccroché pendant la connexion) : on n'y répond plus.
            is LiveEvent.Started -> if (liveAt == null && endRequested == null) {
                liveAt = t
                lastActivityAt = t
                steps += LaunchStep.Started to (t - tapAt)
                _state.update { it.copy(phase = CallPhase.Live, status = null) }
                onListening()
                // L'utilisateur parle déjà : on ne lui coupe pas la parole avec « je t'écoute ».
                if (!local.userSpeaking && lastInputAt == null) transport?.send(ClientEvents.commentary(CallInstructions.LISTENING))
            }
            is LiveEvent.InputTranscript -> {
                lastActivityAt = t
                nudged = false
                lastInputAt = t
                utteranceJudged = false
                meals.onUser(e.delta, newLine = lines.lastOrNull()?.speaker != Speaker.User)
                appendLine(Speaker.User, e.delta)
            }
            is LiveEvent.OutputTranscript -> {
                if (!nudged) lastActivityAt = t
                lastAiTranscriptAt = t
                appendLine(Speaker.Voice, e.delta)
            }
            is LiveEvent.Usage -> serverSeconds = e.seconds
            is LiveEvent.DelegationCreated -> if (endRequested == null) {
                lastActivityAt = t
                meals.request(ThinkTrigger.Voice, t, e.id)
            }
            is LiveEvent.Closed -> {
                serverClosed = true
                e.seconds?.let {
                    serverSeconds = it
                    finalServerSeconds = it
                }
                if (endRequested == null) {
                    endRequested = EndReason.Server
                    endRequestedAt = t
                }
            }
            is LiveEvent.Error -> {
                lastError = Redact.secrets(listOfNotNull(e.code, e.message).joinToString(" : ")).take(200)
                // Doc GPT-Live : une erreur qui porte `client_event_id` vise la commande de l'app refusée, la session continue ;
                // pendant la fermeture, les ajouts en attente reviennent en erreur. Pour toute autre, la doc ne dit pas que la
                // session repart : on la ferme, plutôt que de la laisser facturée sans rien faire.
                if (e.clientEventId == null) requestEnd(EndReason.SessionError, voice = false)
            }
            is LiveEvent.Ack, is LiveEvent.Other -> Unit
        }
        _state.update { it.copy(thinking = meals.thinking) }
    }

    /**
     * Lance la réflexion demandée dès que la transcription de l'utilisateur est retombée (ou au plus tard après 1 s), et seulement
     * quand la précédente a fini : une à la fois, pour qu'un ajout de l'utilisateur corrige au lieu de noter deux fois.
     */
    private fun startThinking(t: Long) {
        if (endRequested != null) return
        val settled = t - (lastInputAt ?: Long.MIN_VALUE / 2) >= thinkSettleMs
        val started = meals.startIfReady(t) { settled || t - it.askedAt >= thinkSettleMaxMs } ?: return
        think(started)
    }

    /**
     * Réflexion lancée hors de la boucle ; son résultat revient par la file. Elle n'est pas annulée au raccroché : ce
     * qu'elle note s'écrit dans le chat du jour, et le rapport de l'appel l'attend.
     */
    private fun think(started: CallMeals.Running) {
        progress()
        val mine = run
        val request = ThinkRequest(
            started.ids.toList(), lines.toList(), reflection, mine.meals.notedEntries, mine.meals.askedQuestions, started.trigger,
            onNoted = { mine.meals.remember(it) },
        )
        val inbox = events
        scope.launch {
            val result = try {
                withTimeoutOrNull(reflectionTimeoutMs) { thinker.think(request) } ?: ThinkResult.Failed("délai dépassé")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ThinkResult.Failed(e.javaClass.simpleName)
            }
            if (mine.ended) {
                // L'appel est fini : on garde ce qui a été noté, puis le rapport part.
                mine.meals.finish(result)
                report(mine)
            } else {
                inbox.trySend(ThinkDone(result))
            }
        }
    }

    private fun onThinkDone(done: ThinkDone) {
        val t = now()
        val finished = meals.finish(done.result) ?: return
        reflections += ReflectionTiming(reflection, t - finished.startedAt, done.result !is ThinkResult.Failed)
        lastActivityAt = t
        if (endRequested == null) deliveries += Delivery(finished.ids.toList(), done.result, t)
        _state.update { it.copy(thinking = meals.thinking) }
    }

    /** Rapport de l'appel (repas à reprendre) : une seule fois, quand l'appel est fini et qu'aucune réflexion ne tourne plus. */
    private fun report(mine: MealsRun) {
        if (!mine.ended || mine.reported || mine.meals.running != null) return
        mine.reported = true
        runCatching { onMeals(mine.meals.report(mine.transcript, mine.startedAt, mine.reflection)) }
    }

    private fun tick() {
        val t = now()
        val levels = transport?.levels?.value
        if (levels != null) {
            local.onLevels(t, levels.mic, levels.ai)
            if (local.aiSpeaking && !nudged) lastActivityAt = t
            // L'utilisateur parle (micro) même si sa transcription n'est pas encore arrivée.
            if (local.userSpeaking && !_state.value.micMuted) {
                lastActivityAt = t
                nudged = false
            }
            // Micro coupé : l'utilisateur parle à quelqu'un d'autre, la voix n'a pas à se taire.
            if (liveAt != null && endRequested == null && !_state.value.micMuted) {
                when (guard.onLevels(t, levels, lastAiTranscriptAt)) {
                    BargeInGuard.Action.Mute -> {
                        transport?.setSpeakerMuted(true)
                        transport?.send(ClientEvents.instructions(CallInstructions.BARGE_IN))
                    }
                    BargeInGuard.Action.Unmute -> transport?.setSpeakerMuted(false)
                    null -> Unit
                }
            }
        }
        startThinking(t)
        // Une réflexion trop longue ne compte plus comme une activité (une fin perdue ne bloque pas le silence).
        meals.abandonIfLonger(t, maxReflectionMs)
        if (meals.thinking) lastActivityAt = t
        deliver(t)

        val live = liveAt
        val silence = if (live != null) t - lastActivityAt else 0L
        _state.update { it.copy(elapsedMs = if (live != null) t - live else 0L, thinking = meals.thinking) }

        if (_state.value.phase == CallPhase.Connecting && live == null && endRequested == null && t - tapAt > startTimeoutMs) {
            lastError = "Le serveur n’a pas démarré la session à temps."
            requestEnd(EndReason.Failed, voice = false)
        } else if (_state.value.phase == CallPhase.Live && endRequested == null && live != null) {
            when {
                t - live >= maxCallMs -> requestEnd(EndReason.MaxDuration, voice = true, say = CallInstructions.GOODBYE)
                silence >= hangUpAfterMs -> requestEnd(EndReason.Silence, voice = true, say = CallInstructions.GOODBYE)
                silence >= nudgeAfterMs && !nudged -> {
                    nudged = true
                    nudges++
                    transport?.send(ClientEvents.commentary(CallInstructions.NUDGE))
                }
                else -> {
                    judgeUtterance(t)
                    pauseReflection(t)
                }
            }
        }
        closeIfReady(t)
    }

    /**
     * L'utilisateur a fini sa phrase : est-ce un au revoir ? Sinon, a-t-il répondu à la dernière question de la réflexion ? L'app la
     * relance alors avec ses réponses.
     */
    private fun judgeUtterance(t: Long) {
        val last = lastInputAt ?: return
        if (utteranceJudged || local.userSpeaking || t - last < utteranceEndMs) return
        utteranceJudged = true
        progress()
        val said = lines.lastOrNull { it.speaker == Speaker.User }?.text ?: return
        if (Goodbye.isGoodbye(said)) {
            requestEnd(EndReason.Goodbye, voice = true)
            return
        }
        if (meals.onUtteranceEnded()) meals.request(ThinkTrigger.Answer, t)
    }

    /** Filet de l'app : la voix n'a pas transmis une parole de l'utilisateur qui ressemble à un repas fini. */
    private fun pauseReflection(t: Long) {
        val last = lastInputAt ?: return
        if (t - last < pauseThinkMs || !local.isPause(t, pauseThinkMs) || deliveries.isNotEmpty()) return
        if (meals.needsPauseReflection(lines.lastOrNull())) meals.request(ThinkTrigger.Pause, t)
    }

    /**
     * Dit les réflexions finies dans une pause (ou au plus tard après [deliverMaxWaitMs]), puis les questions restantes,
     * une à la fois : la suivante seulement quand l'utilisateur a répondu, dans une pause, et après la phrase précédente.
     */
    private fun deliver(t: Long) {
        if (liveAt == null || endRequested != null) return
        val quiet = local.isPause(t, deliverQuietMs) && t - (lastInputAt ?: Long.MIN_VALUE / 2) >= deliverQuietMs
        val iterator = deliveries.iterator()
        while (iterator.hasNext()) {
            val d = iterator.next()
            if (!quiet && t - d.queuedAt < deliverMaxWaitMs) continue
            send(t, d)
            iterator.remove()
        }
        if (deliveries.isEmpty() && quiet && t - (lastSentAt ?: Long.MIN_VALUE / 2) >= questionGapMs) {
            meals.takeQuestion()?.let { question ->
                transport?.send(ClientEvents.commentary(CallInstructions.ask(question)))
                lastSentAt = t
                lastActivityAt = t
            }
        }
    }

    /** Réponse d'une réflexion à la voix : sa correction ou sa réponse, sinon sa première question, sinon « c'est noté ». */
    private fun send(t: Long, d: Delivery) {
        val main = d.delegationIds.lastOrNull()
        // Plusieurs demandes de la voix regroupées : chacune reçoit une réponse, la dernière porte le contenu.
        d.delegationIds.dropLast(1).forEach { transport?.send(ClientEvents.thinking(CallInstructions.THINKING_MERGED, it)) }
        val json = when (val r = d.result) {
            is ThinkResult.Failed -> ClientEvents.thinking(CallInstructions.THINKING_FAILED, main)
            is ThinkResult.Done -> when {
                r.say != null -> ClientEvents.commentary(CallInstructions.say(r.say), main)
                else -> {
                    val question = meals.takeQuestion()
                    when {
                        question != null -> ClientEvents.commentary(CallInstructions.ask(question), main)
                        meals.outstanding != null || meals.queuedQuestions.isNotEmpty() -> ClientEvents.thinking(CallInstructions.THINKING_WAIT, main)
                        r.noted.any { !it.deleted } -> ClientEvents.commentary(CallInstructions.NOTED, main)
                        else -> ClientEvents.thinking(CallInstructions.THINKING_NOTHING, main)
                    }
                }
            }
        }
        transport?.send(json)
        lastSentAt = t
        lastActivityAt = t
    }

    /**
     * Fin demandée : [voice] laisse la voix finir sa phrase (au revoir de l'utilisateur, ou [say] qu'elle doit dire) avant
     * `session.close` ; puis on attend `session.closed`, au plus [closeWaitMs].
     */
    private fun requestEnd(reason: EndReason, voice: Boolean, say: String? = null) {
        if (endRequested != null) return
        endRequested = reason
        endRequestedAt = now()
        waitForVoice = voice && liveAt != null
        deliveries.clear()
        meals.dropPending()
        if (waitForVoice && say != null) transport?.send(ClientEvents.commentary(say))
        _state.update { it.copy(phase = CallPhase.Ending, status = if (reason == EndReason.Failed) lastError else it.status) }
    }

    private fun closeIfReady(t: Long) {
        val reason = endRequested ?: return
        if (_state.value.phase != CallPhase.Ending) _state.update { it.copy(phase = CallPhase.Ending) }
        if (serverClosed) {
            finish(reason)
            return
        }
        val sentAt = closeSentAt
        if (sentAt == null) {
            if (!waitForVoice || voiceDone(t)) {
                transport?.send(ClientEvents.close())
                closeSentAt = t
            }
        } else if (t - sentAt >= closeWaitMs) {
            finish(reason)
        }
    }

    /** La voix a parlé depuis la demande de fin et se tait depuis [voiceQuietMs], ou on a assez attendu. */
    private fun voiceDone(t: Long): Boolean {
        if (t - endRequestedAt >= voiceFinishMaxMs) return true
        val lastVoice = maxOf(local.aiLastLoudAt ?: Long.MIN_VALUE, lastAiTranscriptAt ?: Long.MIN_VALUE)
        return lastVoice > endRequestedAt && !local.aiSpeaking && t - lastVoice >= voiceQuietMs
    }

    private fun fail(rawMessage: String) {
        lastError = Redact.secrets(rawMessage)
        endRequested = EndReason.Failed
        finish(EndReason.Failed)
    }

    private fun finish(reason: EndReason) {
        if (_state.value.phase == CallPhase.Ended) return
        // Les réflexions en cours ne sont pas annulées : ce qu'elles notent finit dans le chat du jour (voir [think]).
        meals.dropPending()
        // Un résultat posté dans la file mais pas encore lu (boucle arrêtée, appel coupé) n'est pas perdu.
        while (true) {
            val pending = events.tryReceive().getOrNull() ?: break
            if (pending is ThinkDone) meals.finish(pending.result)
        }
        transport?.close()
        val t = now()
        val live = liveAt
        val sessionMs = finalServerSeconds?.let { (it * 1000).toLong() }
            ?: maxOf(((serverSeconds ?: 0.0) * 1000).toLong(), t - tapAt)
        val summary = CallSummary(
            startedAt = startedAt,
            free = _state.value.free,
            prepared = prepared,
            steps = steps.toList(),
            beforeTap = beforeTap,
            listenMs = live?.let { it - tapAt },
            liveMs = if (live != null) t - live else 0L,
            // Aucune session (refus) ou requête jamais envoyée (raccroché avant) : rien n'est facturé.
            billedMs = if (noSession || transport?.requestSent == false) 0L else sessionMs,
            reflections = reflections.toList(),
            bargeIns = guard.count,
            nudges = nudges,
            endReason = reason,
            lastError = lastError,
            transcript = lines.toList(),
        )
        _state.update {
            it.copy(
                phase = CallPhase.Ended,
                endReason = reason,
                thinking = false,
                status = when (reason) {
                    EndReason.Failed -> lastError ?: reason.label
                    EndReason.SessionError -> lastError?.let { "${reason.label} ($it)" } ?: reason.label
                    else -> reason.label
                },
            )
        }
        onFinished(summary)
        val mine = run
        mine.transcript = lines.toList()
        mine.startedAt = startedAt
        mine.reflection = reflection
        mine.ended = true
        report(mine)
    }

    /** Transcription gardée pour une reprise après un arrêt d'Android, seulement si elle a changé. */
    private fun progress() {
        if (lines == lastProgress) return
        lastProgress = lines.toList()
        runCatching { onProgress(startedAt, lastProgress, reflection) }
    }

    private fun appendLine(speaker: Speaker, delta: String) {
        val last = lines.lastOrNull()
        if (last != null && last.speaker == speaker) {
            lines[lines.lastIndex] = last.copy(text = last.text + delta)
        } else {
            lines += CallLine(speaker, delta)
        }
    }

    private companion object {
        val ACTIVE = setOf(CallPhase.Connecting, CallPhase.Live, CallPhase.Ending)
    }
}
