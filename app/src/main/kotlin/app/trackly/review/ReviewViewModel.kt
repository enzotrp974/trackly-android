package app.trackly.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.TemporaryGoalCard
import app.trackly.assistant.cards.WeekInfoCard
import app.trackly.assistant.cards.WeightGoalCard
import app.trackly.assistant.chat.ChatError
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ErrorNotice
import app.trackly.assistant.chat.PhotoMarker
import app.trackly.assistant.chat.afterRecording
import app.trackly.assistant.chat.notice
import app.trackly.assistant.chat.recordedSince
import app.trackly.assistant.chat.unexpectedFault
import app.trackly.dictation.DictationReceiver
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.openai.ImageInput
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Undo
import app.trackly.core.model.ActionId
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.photos.PhotoLimits
import app.trackly.review.page.Advice
import app.trackly.review.page.AdviceActions
import app.trackly.review.page.AdviceState
import app.trackly.photos.PreparedPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewUiState(
    /** Période du bilan : du lendemain du dernier bilan à aujourd'hui. */
    val period: WeekRange,
    val messages: List<ChatMessage> = emptyList(),
    val thinking: Boolean = false,
    val error: ChatError? = null,
    /** Actions des cartes déjà annulées. */
    val undone: Set<Long> = emptySet(),
    val notice: String? = null,
    /** Captures jointes au prochain message (vignettes du sélecteur de photos ; jamais conservées après l'envoi). */
    val photos: List<PreparedPhoto> = emptyList(),
    /** Ce que l'utilisateur a déjà donné pour la période, ou `null`. */
    val declared: WeekInfo? = null,
    /** Dernier rapport produit pour cette période, ou `null`. */
    val latestReport: ReportInfo? = null,
    /** État des boutons des cartes d'objectif temporaire, rangé par `AdviceActions.key`. */
    val advice: Map<String, AdviceState> = emptyMap(),
)

/**
 * Conversation de bilan. Chaque période a son fil (rangé par son premier jour), séparé du fil du jour. Les informations déclarées passent par
 * l'outil `record_week_info` (action du moteur) ; les captures d'écran (images) partent au modèle avec
 * le message et ne sont jamais conservées.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(private val services: ReviewServices, opened: WeekRange) : ViewModel(), DictationReceiver {
    private val engine = services.engine

    /**
     * Période affichée : celle de l'ouverture, recalculée à chaque envoi au même instant que le tour. Un seul
     * fil, un seul contexte et les mêmes outils par envoi, même si 5 h passe pendant que la conversation est ouverte.
     */
    private val period = MutableStateFlow(opened)

    private val thinking = MutableStateFlow(false)
    private val error = MutableStateFlow<ChatError?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val attachments = MutableStateFlow<List<PreparedPhoto>>(emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    val keyStatus: StateFlow<ApiKeyStatus> = services.keyStore.status

    private val messages = period.map { it.start }.distinctUntilChanged().flatMapLatest { services.history.observe(it) }

    private val undone = combine(messages, engine.observeRecentActions(UNDO_WATCH)) { list, _ -> list }
        .mapLatest { list ->
            list.flatMap { it.cards }.filter { it is WeekInfoCard || it is WeightGoalCard }.map(ChatCard::actionId).distinct()
                .filter { id -> engine.action(ActionId(id))?.undoneBy != null }
                .toSet()
        }

    private val declared = combine(messages, engine.observeRecentActions(UNDO_WATCH)) { _, _ -> Unit }
        .mapLatest { engine.weekInfo(period.value.start) }

    private val latestReport = combine(services.reports.observeAll(), period) { all, p -> all.firstOrNull { it.weekStart == p.start } }

    private val adviceActions = AdviceActions(engine)

    private val advice = combine(messages, engine.observeRecentActions(UNDO_WATCH)) { list, _ -> list }
        .mapLatest { list -> adviceActions.states(list.flatMap { it.cards }.filterIsInstance<TemporaryGoalCard>().map { it.advice }) }

    private val passing = combine(error, notice, attachments) { e, n, a -> Triple(e, n, a) }
    private val facts = combine(undone, declared, latestReport) { u, d, r -> Triple(u, d, r) }
    private val shown = combine(period, messages, advice) { p, m, adv -> Triple(p, m, adv) }

    val state: StateFlow<ReviewUiState> = combine(shown, thinking, passing, facts) { (p, m, adv), t, (e, n, a), (u, d, r) ->
        ReviewUiState(p, m, t, e, u, n, a, d, r, adv)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState(opened))

    /** Images dont l'envoi a échoué : renvoyées par « Réessayer ». */
    private var retryImages: List<ImageInput> = emptyList()

    init {
        viewModelScope.launch { services.keyStore.refresh() }
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    /** Sélecteur de photos : ajoute des captures d'écran, déjà réduites, au prochain message. */
    fun attach(photos: List<PreparedPhoto>) {
        if (photos.isEmpty()) return
        attachments.value = (attachments.value + photos).distinct().take(MAX_IMAGES)
    }

    fun removeAttachment(photo: PreparedPhoto) {
        attachments.value = attachments.value - photo
    }

    fun removeAttachments() {
        attachments.value = emptyList()
    }

    /** Photo illisible, trop de photos, pas d'appareil photo : message affiché sous la conversation. */
    fun photoProblem(message: String) {
        notice.value = message
    }

    /**
     * Texte dicté (micro du bilan) : envoyé tel quel, ou placé dans la zone de saisie à la suite de ce qui y est.
     * Si l'assistant répond encore, le texte attend dans la zone de saisie.
     */
    override fun receiveDictation(text: String, sendNow: Boolean, notice: String?) {
        val combined = listOf(_draft.value.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (combined.isEmpty()) return
        if (sendNow && !thinking.value) {
            send(combined)
            return
        }
        _draft.value = combined
        this.notice.value = notice ?: "Trackly répond encore : ton texte t’attend dans la zone de saisie.".takeIf { sendNow }
    }

    /** Envoie le message (et les captures jointes). Ignoré pendant qu'un autre est en cours, ou s'il est vide sans capture. */
    fun send(text: String = _draft.value, effort: ReasoningEffort = ReasoningEffort.Default) {
        val photos = attachments.value
        val images = photos.map { it.image }
        val clean = text.trim().ifEmpty { if (images.isEmpty()) return else "Voici mes captures." }
        if (thinking.value) return
        thinking.value = true
        error.value = null
        notice.value = null
        _draft.value = ""
        attachments.value = emptyList()
        retryImages = emptyList()
        // Repère « [1 photo envoyée] » : l'image elle-même n'est jamais conservée.
        val marked = PhotoMarker.stored(clean, images.size)
        viewModelScope.launch {
            val now = engine.now()
            val current = periodAt(now)
            if (current == null) {
                // Rien n'est parti : le texte et les captures attendent dans le nouveau fil.
                _draft.value = text
                attachments.value = photos
                thinking.value = false
                return@launch
            }
            val history = services.history.last(current.start, HISTORY_LIMIT)
            val id = services.history.append(current.start, now, ChatRole.User, marked)
            runTurn(clean, history, effort, id, images, current, now)
        }
    }

    /** « Produire le bilan » : demande le rapport avec ce qui est noté, informations manquantes comprises. */
    fun produceReport() = send(PRODUCE_TEXT, ReasoningEffort.High)

    fun retry() {
        val failed = error.value ?: return
        val text = failed.retryText ?: return
        if (thinking.value) return
        thinking.value = true
        error.value = null
        viewModelScope.launch {
            val now = engine.now()
            val current = periodAt(now)
            if (current == null) {
                // Le message en échec reste dans l'ancien fil : le texte attend dans le nouveau, à renvoyer (captures à rejoindre).
                _draft.value = text
                retryImages = emptyList()
                thinking.value = false
                return@launch
            }
            val history = services.history.last(current.start, HISTORY_LIMIT + 1).filter { it.id != failed.retryMessageId }.takeLast(HISTORY_LIMIT)
            runTurn(text, history, failed.retryEffort, failed.retryMessageId, retryImages, current, now)
        }
    }

    fun dismissError() {
        error.value = null
    }

    /** Bouton « Annuler » d'une carte d'informations ou d'objectif de poids : action d'annulation du moteur. */
    fun undo(card: ChatCard) {
        viewModelScope.launch {
            when (val outcome = engine.execute(Undo(ActionId(card.actionId)))) {
                is ActionOutcome.Applied -> notice.value = null
                is ActionOutcome.Rejected -> notice.value = outcome.rejection.message
            }
        }
    }

    /** « Appliquer » d'une carte d'objectif temporaire : le seul geste qui change l'objectif. */
    fun applyAdvice(a: Advice) {
        viewModelScope.launch { notice.value = adviceActions.apply(a) }
    }

    /** « Annuler » ou « Rétablir » d'un objectif temporaire : jamais un jour passé ne change. */
    fun toggleAdvice(s: AdviceState) {
        viewModelScope.launch { notice.value = adviceActions.toggle(s) }
    }

    /**
     * Période à l'instant [now], désormais affichée. `null` si elle commence un autre jour que celle affichée
     * jusque-là (5 h est passé après un rapport) : l'envoi n'a pas lieu, l'utilisateur voit le nouveau fil et un mot.
     */
    private suspend fun periodAt(now: java.time.Instant): WeekRange? {
        val shown = period.value
        val current = services.periods.at(now)
        period.value = current
        if (current.start == shown.start) return current
        notice.value = PERIOD_MOVED
        return null
    }

    /** Identifiant du dernier rapport enregistré, pour savoir si le tour en a produit un. */
    private suspend fun lastReportId(): Long? = services.reports.observeAll().first().maxOfOrNull { it.id }

    private suspend fun runTurn(
        text: String,
        history: List<ChatMessage>,
        effort: ReasoningEffort,
        messageId: Long?,
        images: List<ImageInput>,
        period: WeekRange,
        sentAt: java.time.Instant,
    ) {
        val startedAt = sentAt
        var reportsBefore: Long? = null
        // Un rapport enregistré pendant ce tour ne se rejoue pas : « Réessayer » le referait.
        // Seul compte un rapport de cet envoi (enregistré à son instant), pas celui d'un autre écran validé pendant le tour.
        suspend fun reported() = services.reports.observeAll().first().any { it.id > (reportsBefore ?: 0L) && it.generatedAt == sentAt }
        try {
            reportsBefore = lastReportId()
            val outcome = services.interpreter.run(
                TurnInput(text = text, history = history.map(ChatMessage::toHistory), effort = effort, images = images, sentAt = sentAt, period = period),
            )
            when (outcome) {
                is TurnOutcome.Completed -> {
                    if (outcome.reply.isNotBlank() || outcome.cards.isNotEmpty()) {
                        services.history.append(period.start, engine.now(), ChatRole.Assistant, outcome.reply, outcome.cards)
                    }
                }
                is TurnOutcome.Failed -> {
                    if (outcome.cards.isNotEmpty()) services.history.append(period.start, engine.now(), ChatRole.Assistant, "", outcome.cards)
                    val canRetry = outcome.canRetry && !reported()
                    retryImages = if (canRetry) images else emptyList()
                    error.value = ChatError(
                        // Quelque chose est enregistré ou le rapport est produit : le texte n'invite pas à renvoyer.
                        notice = outcome.error.notice().let { if (canRetry) it else it.afterRecording() },
                        retryText = text.takeIf { canRetry },
                        retryMessageId = messageId,
                        retryEffort = effort,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Base de données, outil… : sans cela l'exception fermerait l'app (même traitement que le chat).
            // Si une action a pu être enregistrée, renvoyer le message risquerait de la refaire.
            val recorded = engine.recordedSince(startedAt) || runCatching { reported() }.getOrDefault(true)
            error.value = ChatError(
                notice = ErrorNotice.unexpectedFault(recorded),
                retryText = text.takeIf { !recorded },
                retryMessageId = messageId,
                retryEffort = effort,
            )
            retryImages = if (recorded) emptyList() else images
        } finally {
            thinking.value = false
        }
    }

    companion object {
        const val HISTORY_LIMIT = 12
        const val MAX_IMAGES = PhotoLimits.MAX_PHOTOS
        private const val UNDO_WATCH = 50
        const val PRODUCE_TEXT = "Produis mon bilan maintenant, avec ce que tu as."
        const val PERIOD_MOVED = "Une nouvelle période de bilan a commencé : ton message n’est pas parti, il t’attend ici. Renvoie-le s’il vaut pour cette période."

        fun factory(services: ReviewServices, period: WeekRange): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReviewViewModel(services, period) }
        }
    }
}
