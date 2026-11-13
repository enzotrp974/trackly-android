package app.trackly.assistant.chat

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalNoting
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.net.LatencyLog
import app.trackly.assistant.net.Millis
import app.trackly.assistant.net.TurnStopwatch
import app.trackly.assistant.openai.ImageInput
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnListener
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.TurnStatus
import app.trackly.core.action.ForgetFormattingNotes
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Réponse de l'assistant pendant (et juste après) un tour : étape en cours, texte reçu en streaming,
 * et message déjà écrit dans l'historique dès la première carte.
 */
data class LiveReply(
    val status: TurnStatus = TurnStatus.Thinking,
    /** Texte reçu jusqu'ici (la réponse finale, une fois le tour terminé). */
    val text: String = "",
    /** Message de l'assistant déjà dans l'historique (cartes écrites pendant le tour), ou `null`. */
    val messageId: Long? = null,
    /** Tour terminé : le texte final est écrit dans l'historique. */
    val done: Boolean = false,
) {
    /**
     * Ce qu'il reste à montrer à côté de l'historique [messages], ou `null`. Une fois le tour terminé, la
     * réponse reste affichée d'ici jusqu'à ce que l'historique relu la contienne (pas de clignotement).
     */
    fun visibleIn(messages: List<ChatMessage>): LiveReply? {
        if (!done) return this
        if (text.isBlank()) return null
        val written = messageId?.let { id -> messages.firstOrNull { it.id == id } }
        return if (written != null && written.text.isNotBlank()) null else this
    }
}

/** État des tours du chat : un seul à la fois. */
data class TurnsState(
    /** Un tour est en cours (« Trackly réfléchit »). */
    val running: Boolean = false,
    val live: LiveReply? = null,
    val error: ChatError? = null,
    /**
     * Fil du jour où ce résultat (réponse terminée, erreur) a été produit, ou `null` pendant un tour. Le chat ne montre
     * pas, dans le fil d'un autre jour, la réponse ou l'erreur d'un jour passé.
     */
    val day: LocalDate? = null,
)

/** Garde l'app en vie pendant un tour, même écran verrouillé ou app quittée (WorkManager accéléré). */
fun interface TurnKeepAlive {
    fun hold()

    companion object {
        val None = TurnKeepAlive { }
    }
}

/** Signale la fin d'un tour quand l'app n'est plus à l'écran (notification discrète). */
fun interface TurnNotifier {
    fun turnFinished(summary: TurnSummary)

    companion object {
        val None = TurnNotifier { }
    }
}

/**
 * Tours de conversation du chat, **hors du cycle de vie de l'écran** : portée de l'application, une seule
 * instance (dans `AssistantServices`). Un message envoyé donne un seul tour, même si l'écran est recréé ou
 * l'app quittée ; l'état (réflexion, texte en cours, erreur) est retrouvé en revenant dans le chat.
 *
 * Les cartes sont écrites dans l'historique dès que leur action est appliquée (visibles et conservées avant la
 * fin du texte du modèle), puis la réponse est complétée par le texte final. Si l'app n'est plus à l'écran à la
 * fin du tour, [notifier] reçoit un résumé (« Enregistré : barre protéinée, pomme · 287 kcal »).
 */
class ChatTurns(
    private val engine: TracklyEngine,
    private val history: ChatHistory,
    private val interpreter: TurnInterpreter,
    private val scope: CoroutineScope,
    private val keepAlive: TurnKeepAlive = TurnKeepAlive.None,
    private val notifier: TurnNotifier = TurnNotifier.None,
    /** Une activité de l'app est-elle à l'écran ? Sinon, la fin du tour est notifiée. */
    private val inForeground: () -> Boolean = { true },
    /** Mesure locale de la latence (durées seulement). */
    private val latency: LatencyLog = LatencyLog.None,
    private val clock: Millis = Millis.System,
    /** Repère « tour en cours » : seul moyen de savoir qu'Android a tué le processus pendant un tour. */
    private val markers: TurnMarkerStore = InMemoryTurnMarkerStore(),
) {
    private val _state = MutableStateFlow(TurnsState())
    val state: StateFlow<TurnsState> = _state.asStateFlow()

    /** Photos du message qui a échoué, gardées en mémoire pour « Réessayer » ; jamais sur disque. */
    private var retryImages: List<ImageInput> = emptyList()

    /**
     * Envoie un message. Refusé (`false`) si un tour est déjà en cours ou si le message est vide (une photo
     * seule suffit). Le message de l'utilisateur est écrit dans l'historique, puis le tour se joue.
     */
    fun send(
        text: String,
        effort: ReasoningEffort = ReasoningEffort.Default,
        images: List<ImageInput> = emptyList(),
        focus: CorrectionFocus? = null,
        /** Libellé de la cible de correction, gardé avec le repère du tour. */
        focusLabel: String? = null,
        /** « Noter » d'une carte de proposition : sa consigne part au modèle, son outil passe par elle. */
        noting: ProposalNoting? = null,
        /** Reçoit l'identifiant du message de l'utilisateur dès qu'il est écrit (vignettes des photos). */
        onUserMessage: (Long) -> Unit = {},
    ): Boolean {
        val clean = text.trim()
        if ((clean.isEmpty() && images.isEmpty()) || _state.value.running) return false
        start()
        scope.launch {
            var id: Long? = null
            try {
                val past = history.last(today(), HISTORY_LIMIT).asModelContext()
                id = history.append(engine.now(), ChatRole.User, PhotoMarker.stored(clean, images.size))
                runCatching { onUserMessage(id) }
                play(clean, past, effort, id, focus, focusLabel, images, noting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lecture ou écriture de l'historique impossible : sans cela l'exception fermerait l'app et `running` resterait vrai.
                failBeforePlay(e, clean, id, effort, focus, focusLabel, images, noting)
            } finally {
                markers.clear()
            }
        }
        return true
    }

    /** Renvoie le message qui a échoué (seulement si rien n'avait été enregistré), au même niveau de raisonnement par défaut. */
    fun retry(effort: ReasoningEffort? = null): Boolean {
        val failed = _state.value.error ?: return false
        val text = failed.retryText ?: return false
        if (_state.value.running) return false
        val images = retryImages
        start()
        scope.launch {
            var id = failed.retryMessageId
            try {
                val past = history.last(today(), HISTORY_LIMIT + 1).filter { it.id != id }.takeLast(HISTORY_LIMIT).asModelContext()
                // Le message n'avait pas pu être écrit (historique en panne au premier envoi) : il l'est maintenant.
                if (id == null) id = history.append(engine.now(), ChatRole.User, PhotoMarker.stored(text, images.size))
                play(text, past, effort ?: failed.retryEffort, id, failed.retryFocus, failed.retryFocusLabel, images, failed.retryNoting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failBeforePlay(e, text, id, effort ?: failed.retryEffort, failed.retryFocus, failed.retryFocusLabel, images, failed.retryNoting)
            } finally {
                markers.clear()
            }
        }
        return true
    }

    /**
     * Échec avant que le tour ne démarre (lecture ou écriture de l'historique) : rien n'a été envoyé au modèle ni
     * enregistré, donc « Réessayer » est sûr. Comme pour les autres erreurs, le type de l'exception est le seul détail gardé.
     */
    private fun failBeforePlay(
        cause: Exception,
        text: String,
        messageId: Long?,
        effort: ReasoningEffort,
        focus: CorrectionFocus?,
        focusLabel: String?,
        images: List<ImageInput>,
        noting: ProposalNoting?,
    ) {
        retryImages = images
        // Journalisé comme les autres exceptions de tour : le type seulement, jamais le message.
        runCatching { TurnStopwatch(clock, latency).finish("exception ${cause::class.java.name}") }
        _state.value = TurnsState(
            day = today(),
            error = ChatError(
                notice = ErrorNotice.unexpectedFault(recorded = false),
                retryText = text,
                retryMessageId = messageId,
                retryFocus = focus,
                retryFocusLabel = focusLabel,
                retryNoting = noting,
                retryEffort = effort,
            ),
        )
    }

    /** La vérification de reprise ([recoverInterrupted]) n'a lieu qu'une fois par processus. */
    private var recoveryChecked = false

    /**
     * À l'ouverture du chat après que **Android a tué le processus** pendant un tour : le message de l'utilisateur
     * est dans l'historique mais le tour n'existe plus, donc rien n'y répondra. Une erreur « Ton message n’a pas
     * reçu de réponse » avec « Réessayer » est alors posée, comme pour les autres erreurs ([retry] renvoie le même
     * message sans le réécrire dans l'historique).
     *
     * Le seul témoin d'un tour interrompu est le **repère persistant** [TurnMarker], posé au début du tour et effacé
     * à sa fin, quelle qu'elle soit. Un échec ordinaire (hors ligne, délai, clé refusée, crédit épuisé, exception)
     * n'écrit rien dans l'historique mais efface le repère : l'erreur d'origine a été montrée, rien n'est rajouté.
     * Ce que le repère garde aussi, c'est la cible de « Corriger » : « Réessayer » corrige encore le bon repas.
     *
     * Conditions : pas encore vérifié dans ce processus, aucun tour ni erreur en cours, un repère qui date de moins
     * de [INTERRUPTED_WINDOW] et désigne le dernier message de l'historique, qui est celui de l'utilisateur.
     * Le repère reste tant que l'erreur n'est pas écartée ([dismissError]) ou renvoyée ([retry]).
     *
     * - **Pas de double comptage** : une carte enregistrée crée un message de l'assistant juste après celui de
     *   l'utilisateur (dès la première carte), donc le dernier message n'est alors plus le sien et rien n'est
     *   proposé. Reste la fenêtre minuscule entre l'application d'une action et l'écriture de sa carte : si le
     *   journal des actions contient une action postérieure au message, « Réessayer » n'est pas proposé et le texte
     *   demande de regarder le journal.
     * - **Au-delà de 15 minutes, rien n'est affiché** : le message reste visible dans le fil ; le renvoyer plus tard
     *   le ferait dater de maintenant (« ce matin », « je viens de prendre… » n'auraient plus de sens), et l'erreur
     *   ne ferait qu'ajouter du bruit à quelqu'un qui est passé à autre chose.
     * - **Jamais de plantage** : c'est une vérification de confort ; si l'historique ou le journal ne se lisent pas,
     *   rien n'est affiché (le message reste visible dans le fil).
     */
    suspend fun recoverInterrupted() {
        if (recoveryChecked) return
        recoveryChecked = true
        try {
            recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rien à dire à l'utilisateur : on ne sait pas s'il y a quelque chose à reprendre.
        }
    }

    private suspend fun recover() {
        if (isBusy()) return
        val marker = markers.read() ?: return
        val last = history.last(today(), 1).lastOrNull()
        val stale = Duration.between(marker.startedAt, engine.now()) > INTERRUPTED_WINDOW
        if (last == null || last.role != ChatRole.User || last.id != marker.messageId || stale) {
            // Repère périmé (réponse déjà écrite, message plus ancien, trop tard) : il ne servira plus.
            if (!isBusy() && markers.read() == marker) markers.clear()
            return
        }
        val recorded = engine.recordedSince(last.at)
        val photos = last.photoCount
        val text = last.shownText.trim()
        val noting = ProposalCard.isNoteRequest(text)
        val error = ChatError(
            notice = ErrorNotice.interrupted(recorded = recorded, lostPhotos = photos, notingTitle = ProposalCard.noteRequestTitle(text)),
            // Les photos ne sont jamais gardées : le message seul ne dirait pas la même chose.
            // Un « Noter » coupé ne se renvoie pas en simple phrase, sans sa garde : on retouche « Noter ».
            retryText = text.takeIf { it.isNotEmpty() && photos == 0 && !recorded && !noting },
            retryMessageId = last.id,
            retryFocus = marker.focus.takeIf { !recorded && photos == 0 },
            retryFocusLabel = marker.focusLabel.takeIf { !recorded && photos == 0 },
            // Le niveau de raisonnement choisi pour ce message, pas celui par défaut.
            retryEffort = marker.effort,
        )
        // Revérifié au moment d'écrire : un envoi a pu partir pendant la lecture de l'historique.
        _state.update { s -> if (s.running || s.error != null || s.live != null) s else s.copy(error = error, day = today()) }
    }

    /**
     * Date de rattachement du fil en cours : le modèle ne reçoit que lui (fil du jour). Relue à chaque usage, car un tour
     * peut passer 5 h en route ; une réponse écrite après 5 h rejoint alors le nouveau fil ([ChatHistory.update]).
     */
    private fun today(): LocalDate = TrackingDay.of(engine.now())

    private fun isBusy(): Boolean = _state.value.let { it.running || it.error != null || it.live != null }

    /**
     * « Effacer la discussion » : la réponse terminée et l'erreur encore affichées disparaissent avec le fil. Un tour en
     * cours continue, et sa réponse s'écrit dans le fil comme d'habitude.
     */
    fun clearFinished() {
        if (_state.value.running) return
        markers.clear()
        retryImages = emptyList()
        _state.update { if (it.running) it else TurnsState() }
    }

    fun dismissError() {
        // Écartée, l'erreur de reprise ne doit pas revenir au prochain redémarrage (le repère d'un tour en cours reste).
        if (!_state.value.running) markers.clear()
        _state.update { it.copy(error = null) }
        retryImages = emptyList()
    }

    /** Attend la fin du tour en cours (travail WorkManager qui garde l'app en vie). */
    suspend fun awaitIdle() {
        _state.first { !it.running }
    }

    private fun start() {
        // Marqué tout de suite, avant toute suspension : un second envoi est refusé.
        _state.value = TurnsState(running = true, live = LiveReply())
        keepAlive.hold()
    }

    private suspend fun play(
        text: String,
        past: List<HistoryMessage>,
        effort: ReasoningEffort,
        messageId: Long?,
        focus: CorrectionFocus?,
        focusLabel: String?,
        images: List<ImageInput>,
        noting: ProposalNoting? = null,
    ) {
        retryImages = emptyList()
        val startedAt = engine.now()
        // Effacé par l'appelant à la fin du tour : s'il reste, c'est qu'Android a tué le processus entre-temps.
        if (messageId != null) runCatching { markers.write(TurnMarker(messageId, startedAt, focus, focusLabel, effort)) }
        val stopwatch = TurnStopwatch(clock, latency)
        var finished = "erreur"
        val shown = mutableListOf<ChatCard>()
        var replyId: Long? = null
        val listener = object : TurnListener {
            override suspend fun onStatus(status: TurnStatus) = live { it.copy(status = status) }

            override suspend fun onText(text: String) {
                if (text.isNotEmpty()) stopwatch.mark(TurnStopwatch.TEXT)
                live { it.copy(text = text) }
            }

            override suspend fun onCard(card: ChatCard) {
                stopwatch.mark(TurnStopwatch.CARD)
                shown += card
                // Écrite tout de suite : visible (et conservée) avant la fin du texte du modèle.
                val id = replyId
                if (id == null) {
                    replyId = history.append(engine.now(), ChatRole.Assistant, "", shown.toList())
                } else {
                    history.update(id, "", shown.toList(), engine.now())
                }
                live { it.copy(messageId = replyId) }
            }
        }
        var next = TurnsState()
        try {
            val outcome = interpreter.run(
                TurnInput(
                    text = text, history = past, effort = effort,
                    images = images, sentAt = engine.now(), focus = focus,
                    extraContext = noting?.context, noting = noting,
                ),
                listener,
            )
            when (outcome) {
                is TurnOutcome.Completed -> {
                    val id = replyId
                    if (id != null) {
                        history.update(id, outcome.reply, outcome.cards, engine.now())
                    } else if (outcome.reply.isNotBlank() || outcome.cards.isNotEmpty()) {
                        replyId = history.append(engine.now(), ChatRole.Assistant, outcome.reply, outcome.cards)
                    }
                    next = TurnsState(live = LiveReply(text = outcome.reply, messageId = replyId, done = true))
                    finished = if (outcome.cards.isEmpty()) "réponse" else "${outcome.cards.size} carte(s)"
                }
                is TurnOutcome.Failed -> {
                    // Ce qui a été enregistré avant l'erreur reste visible (et annulable) : les cartes sont déjà écrites.
                    if (replyId == null && outcome.cards.isNotEmpty()) history.append(engine.now(), ChatRole.Assistant, "", outcome.cards)
                    if (outcome.canRetry) retryImages = images
                    next = TurnsState(
                        error = ChatError(
                            notice = outcome.error.notice().let { if (outcome.canRetry) it else it.afterRecording() },
                            retryText = text.takeIf { outcome.canRetry },
                            retryMessageId = messageId,
                            retryFocus = focus.takeIf { outcome.canRetry },
                            retryFocusLabel = focusLabel.takeIf { outcome.canRetry },
                            retryNoting = noting.takeIf { outcome.canRetry },
                            retryEffort = effort,
                        ),
                    )
                }
            }
            if (!inForeground()) TurnSummary.of(outcome)?.let { runCatching { notifier.turnFinished(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Base de données, outil… : sans cela l'exception remonterait dans la portée de l'application et fermerait l'app.
            // Si quelque chose a pu être enregistré (carte écrite, action au journal), renvoyer risquerait de compter deux fois.
            val recorded = shown.isNotEmpty() || replyId != null || engine.recordedSince(startedAt)
            // Journalisé par la mesure de tour (debug seulement, voir LatencyLog) : le type de l'exception, jamais son message.
            finished = "exception ${e::class.java.name}"
            next = TurnsState(
                error = ChatError(
                    notice = ErrorNotice.unexpectedFault(recorded),
                    retryText = text.takeIf { !recorded },
                    retryMessageId = messageId,
                    retryFocus = focus.takeIf { !recorded },
                    retryFocusLabel = focusLabel.takeIf { !recorded },
                    retryNoting = noting.takeIf { !recorded },
                    retryEffort = effort,
                ),
            )
            if (!recorded) retryImages = images
        } finally {
            _state.value = next.copy(day = today())
            runCatching { stopwatch.finish(finished) }
        }
    }

    private inline fun live(change: (LiveReply) -> LiveReply) {
        _state.update { s -> if (s.running) s.copy(live = change(s.live ?: LiveReply())) else s }
    }

    companion object {
        /** Messages précédents renvoyés au modèle (texte et résumés de cartes). */
        const val HISTORY_LIMIT = 12

        /** Un message sans réponse n'est signalé après un arrêt d'Android que s'il a moins de 15 minutes. */
        val INTERRUPTED_WINDOW: Duration = Duration.ofMinutes(15)
    }
}

/**
 * Une action a-t-elle été appliquée depuis [at] ? Alors renvoyer le message pourrait compter deux fois. Dans le
 * doute (journal illisible), oui. L'annulation de la coroutine passe toujours.
 */
internal suspend fun TracklyEngine.recordedSince(at: Instant): Boolean = try {
    // Le nettoyage de la mémoire au lancement n'enregistre rien du message : il ne compte pas.
    observeRecentActions(RECENT_ACTIONS).first().any { !it.appliedAt.isBefore(at) && it.type != ForgetFormattingNotes.TYPE }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    true
}

private const val RECENT_ACTIONS = 20

/**
 * Début du marquage d'un message de l'utilisateur resté **sans réponse** (hors ligne, délai, clé refusée…) : rien
 * n'a été enregistré. Les consignes (`RecordingRulesSection`) citent ce début : les deux vont ensemble.
 */
internal const val UNPROCESSED_PREFIX = "[Message non traité"

/** Marquage complet, placé avant le texte du message resté sans réponse. */
internal const val UNPROCESSED_NOTICE =
    "$UNPROCESSED_PREFIX : l'envoi a échoué, rien n'a été enregistré. Ne l'exécute que si l'utilisateur demande " +
        "explicitement de le reprendre ; s'il le répète dans un nouveau message, ne compte qu'une fois.]"

/**
 * Messages renvoyés au modèle comme contexte. Les messages de l'utilisateur restés **sans réponse** de l'assistant
 * et suivis d'un autre message (celui qu'on envoie maintenant, ou un message plus récent) sont gardés mais marqués
 * [UNPROCESSED_NOTICE] :
 * - sans le marquage, le modèle compterait deux fois « riz 200 g » quand l'utilisateur retape « riz 200 g et une pomme » ;
 * - sans le message, « Réessaie mon dernier message » ou « C'était du riz cuit » n'auraient plus rien à quoi se référer.
 * Tous restent affichés dans le fil : seul le contexte envoyé change.
 */
internal fun List<ChatMessage>.asModelContext(): List<HistoryMessage> = mapIndexed { i, m ->
    val unanswered = m.role == ChatRole.User && (i == lastIndex || this[i + 1].role == ChatRole.User)
    m.toHistory().let { if (unanswered) it.copy(text = "$UNPROCESSED_NOTICE\n${it.text}") else it }
}
