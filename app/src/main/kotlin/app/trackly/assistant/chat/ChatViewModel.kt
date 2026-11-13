package app.trackly.assistant.chat

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.NoteOutcome
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalNoting
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.cards.frozen
import app.trackly.core.action.Rejection
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Undo
import app.trackly.core.model.ActionId
import app.trackly.dictation.DictationReceiver
import app.trackly.photos.PhotoLimits
import app.trackly.photos.PreparedPhoto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Erreur du dernier envoi, avec le message à renvoyer si l'utilisateur réessaie. */
data class ChatError(
    val notice: ErrorNotice,
    val retryText: String?,
    val retryMessageId: Long?,
    /** Enregistrement visé par « Corriger » quand le message a échoué : le renvoi le garde. */
    val retryFocus: CorrectionFocus? = null,
    /** Libellé de [retryFocus] (« Riz basmati »), gardé avec lui. */
    val retryFocusLabel: String? = null,
    /** « Noter » du tour qui a échoué (carte de proposition, CHAT-02), repris par « Réessayer ». */
    val retryNoting: ProposalNoting? = null,
    /** Niveau de raisonnement du message qui a échoué, repris par « Réessayer ». */
    val retryEffort: ReasoningEffort = ReasoningEffort.Default,
)

private data class Passing(
    val notice: String?,
    val correcting: Correcting?,
    val photos: List<PreparedPhoto>,
    val cardNotices: Map<Long, String>,
)

private data class Surroundings(val online: Boolean, val thumbnails: Map<Long, List<Bitmap>>, val day: LocalDate)

/** État des cartes relu dans le journal du moteur : annulées, et périmées avec le texte à montrer. */
private data class CardStatuses(
    val undone: Set<Long> = emptySet(),
    val superseded: Map<Long, String> = emptyMap(),
    /** Cartes d'enregistrement nées d'une proposition dont tout le repas a été retiré du journal depuis. */
    val retired: Set<Long> = emptySet(),
)

/**
 * Enregistrement que le prochain message corrige : bouton « Corriger » d'une carte du chat, ou d'un repas de
 * l'écran du jour ([ChatViewModel.correctEntries]).
 */
data class Correcting(val focus: CorrectionFocus, val label: String)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** L'assistant traite un message (« Trackly réfléchit »). */
    val thinking: Boolean = false,
    val error: ChatError? = null,
    /** Actions des cartes annulées et pas rétablies (par le bouton, la phrase ou ailleurs). */
    val undone: Set<Long> = emptySet(),
    /**
     * Cartes périmées : une action plus récente a modifié leurs données ; le texte dit quoi
     * (« Ce repas a été corrigé depuis… »). Leurs boutons sont désactivés.
     */
    val superseded: Map<Long, String> = emptyMap(),
    /** Message propre à une carte (refus d'annuler…), affiché sur la carte elle-même. */
    val cardNotices: Map<Long, String> = emptyMap(),
    /** Information passagère (« Seules 6 photos… »). */
    val notice: String? = null,
    /** Carte dont le prochain message est la correction (bouton « Corriger »), ou `null`. */
    val correcting: Correcting? = null,
    /** Photos jointes au prochain message, en mémoire seulement. */
    val photos: List<PreparedPhoto> = emptyList(),
    /** Actions des cartes dont la question n'a pas encore de réponse. */
    val pendingQuestions: Set<Long> = emptySet(),
    /** Réponse en cours (étape, texte en streaming) ou juste terminée, pas encore relue dans l'historique. */
    val live: LiveReply? = null,
    /** Le téléphone n'a pas d'Internet : bannière « Hors ligne » au-dessus de la saisie (idée 8). */
    val offline: Boolean = false,
    /** Vignettes des photos envoyées pendant la session, par message de l'utilisateur (idée 7, mémoire seulement). */
    val thumbnails: Map<Long, List<Bitmap>> = emptyMap(),
    /**
     * L'historique a été lu au moins une fois : [messages] vide veut alors dire « aucun message », et non
     * « pas encore chargé » (valeur initiale de l'état).
     */
    val historyLoaded: Boolean = false,
    /**
     * Cartes de proposition notées : une carte d'enregistrement du fil porte leur identifiant et n'est pas
     * annulée. Leur bouton « Noter » laisse la place à « Noté ».
     */
    val notedProposals: Set<String> = emptySet(),
)

/**
 * Chat de l'assistant : envoie le message, affiche la réponse et les cartes, annule ou rétablit depuis
 * une carte. Toute modification passe par le moteur, via les outils ou l'action [Undo].
 *
 * Le tour lui-même ne vit pas ici mais dans [ChatTurns] (portée de l'application) : recréer l'écran ne
 * relance ni n'interrompt rien, et l'état du tour est retrouvé tel quel.
 *
 * Le brouillon et la correction visée sont gardés dans l'état sauvegardé ([SavedStateHandle], A18) : ils
 * reviennent même si Android ferme l'app en arrière-plan. Les photos jointes, elles, ne vivent qu'en
 * mémoire (jamais sur le disque) : si Android a fermé l'app, un message dit de les joindre à nouveau.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val services: AssistantServices,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel(), DictationReceiver {
    private val engine = services.engine
    private val turns = services.turns
    private val notice = MutableStateFlow<String?>(null)
    private val correcting = MutableStateFlow(restoreCorrection())
    private val cardNotices = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Texte en cours de saisie (gardé dans l'état sauvegardé). */
    val draft: StateFlow<String> = saved.getStateFlow(KEY_DRAFT, "")

    /** Dernier début de message proposé par un « + » ([prefill]) : remplaçable tant qu'il n'a pas été modifié. */
    private var lastPrefill: String? = saved[KEY_PREFILL]

    val keyStatus: StateFlow<ApiKeyStatus> = services.keyStore.status

    /**
     * Niveau de raisonnement choisi pour les messages suivants. Il reste celui qu'on a choisi d'un message à
     * l'autre et après un redémarrage de l'app ([ReasoningSettings]) ; la pastille du sélecteur reste colorée
     * tant qu'il n'est pas « moyen », pour qu'on voie qu'il coûte plus (ou moins) cher.
     */
    val effort: StateFlow<ReasoningEffort> = services.reasoning.effort

    private val messages = services.thread.observe()

    /** Annulations, rétablissements et cartes périmées, relus dans le journal du moteur à chaque nouvelle action. */
    private val statuses = combine(messages, engine.observeRecentActions(UNDO_WATCH)) { list, _ -> list }
        .mapLatest { list -> cardStatuses(list.flatMap { it.cards }) }

    private val photos = MutableStateFlow<List<PreparedPhoto>>(emptyList())

    private val passing = combine(notice, correcting, photos, cardNotices) { n, c, p, cn -> Passing(n, c, p, cn) }

    private val surroundings = combine(services.connectivity.online, services.thumbnails.byMessage, services.thread.days()) { o, t, d -> Surroundings(o, t, d) }

    val state: StateFlow<ChatUiState> = combine(messages, turns.state, passing, statuses, surroundings) { m, t, p, st, env ->
        // Une réponse terminée ou une erreur d'un autre jour n'a pas sa place dans le fil du jour (5 h passées, ou fil effacé).
        val current = t.day == null || t.day == env.day
        ChatUiState(
            messages = m, thinking = t.running, error = t.error.takeIf { current }, undone = st.undone, superseded = st.superseded,
            cardNotices = p.cardNotices, notice = p.notice, correcting = p.correcting, photos = p.photos,
            pendingQuestions = PendingQuestions.of(m), live = t.live?.takeIf { current }?.visibleIn(m), offline = !env.online, thumbnails = env.thumbnails, historyLoaded = true,
            notedProposals = notedProposals(m, st.undone + st.retired),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch { services.keyStore.refresh() }
        // Android a tué le processus pendant un tour : le dernier message resterait sans réponse, sans le dire.
        viewModelScope.launch { turns.recoverInterrupted() }
        // Android a fermé l'app avec des photos jointes : elles n'ont pas été gardées (jamais sur le disque).
        val lost = saved.get<Int>(KEY_PHOTO_COUNT) ?: 0
        if (lost > 0) {
            notice.value = if (lost == 1) {
                "Android a fermé Trackly en arrière-plan : la photo jointe n’a pas été gardée (Trackly ne garde jamais les photos). Joins-la à nouveau."
            } else {
                "Android a fermé Trackly en arrière-plan : les $lost photos jointes n’ont pas été gardées (Trackly ne garde jamais les photos). Joins-les à nouveau."
            }
            saved[KEY_PHOTO_COUNT] = 0
        }
    }

    fun updateDraft(text: String) {
        saved[KEY_DRAFT] = text
    }

    /**
     * Début de message proposé par un « + » (« Ce matin : », « Pour dimanche 1er novembre : », A11). Il ne remplace
     * jamais ce que l'utilisateur a tapé : seulement un brouillon vide, ou le début proposé juste avant, intact.
     * `null` : retire le début proposé s'il est resté tel quel.
     */
    fun prefill(text: String?) {
        val current = draft.value
        val replaceable = current.isBlank() || current == lastPrefill
        if (!replaceable) return
        updateDraft(text.orEmpty())
        lastPrefill = text
        saved[KEY_PREFILL] = text
    }

    /**
     * Texte dicté : envoyé tel quel ([sendNow]), ou remis dans la zone de saisie pour être relu.
     * Ce qui était déjà tapé n'est jamais écrasé ; si le chat répond encore, le texte attend dans la zone de saisie.
     */
    override fun receiveDictation(text: String, sendNow: Boolean, notice: String?) {
        val combined = listOf(draft.value.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (combined.isEmpty()) return
        if (sendNow && !turns.state.value.running) {
            send(combined)
            return
        }
        updateDraft(combined)
        this.notice.value = notice ?: "Trackly répond encore : ton texte t’attend dans la zone de saisie.".takeIf { sendNow }
    }

    fun selectEffort(effort: ReasoningEffort) {
        services.reasoning.setEffort(effort)
    }

    /** Ajoute des photos au prochain message (au plus [PhotoLimits.MAX_PHOTOS]). */
    fun addPhotos(added: List<PreparedPhoto>) {
        val room = PhotoLimits.MAX_PHOTOS - photos.value.size
        if (added.size > room) notice.value = "Seules ${PhotoLimits.MAX_PHOTOS} photos peuvent être jointes à un message."
        setPhotos(photos.value + added.take(room.coerceAtLeast(0)))
    }

    fun removePhoto(photo: PreparedPhoto) {
        setPhotos(photos.value - photo)
    }

    private fun setPhotos(list: List<PreparedPhoto>) {
        photos.value = list
        // Seul leur nombre est sauvegardé (jamais l'image) : de quoi prévenir si Android ferme l'app.
        saved[KEY_PHOTO_COUNT] = list.size
    }

    /** Photo illisible, trop de photos, pas d'appareil photo : affiché comme information passagère. */
    fun photoProblem(message: String) {
        notice.value = message
    }

    /**
     * Envoie un message au niveau [effort] (par défaut, celui du sélecteur), avec les photos jointes. Ignoré
     * pendant qu'un autre est en cours ou s'il est vide (une photo seule suffit). Le sélecteur garde son niveau
     * après l'envoi. Les photos partent au modèle puis disparaissent : l'historique garde « 1 photo envoyée »,
     * et la bulle montre leurs vignettes tant que l'app reste ouverte.
     */
    fun send(text: String = draft.value, effort: ReasoningEffort = this.effort.value) {
        // « Noter » en cours : le message attendrait sinon une carte que le tour ne verrait pas encore.
        if (noting.isNotEmpty()) {
            if (text.isNotBlank()) notice.value = NOTING_NOTICE
            return
        }
        val attached = photos.value
        // La cible de « Corriger » vaut pour ce message seulement.
        val focus = correcting.value?.focus
        val focusLabel = correcting.value?.label
        val thumbnails = attached.mapNotNull(PreparedPhoto::thumbnail)
        val sent = turns.send(text, effort, attached.map(PreparedPhoto::image), focus, focusLabel) { id -> services.thumbnails.put(id, thumbnails) }
        if (!sent) return
        notice.value = null
        cardNotices.value = emptyMap()
        updateDraft("")
        lastPrefill = null
        saved[KEY_PREFILL] = null
        setPhotos(emptyList())
        setCorrecting(null)
    }

    /** Renvoie le message qui a échoué (seulement si rien n'avait été enregistré), au même niveau de raisonnement. */
    fun retry(effort: ReasoningEffort? = null) {
        if (noting.isNotEmpty()) return
        turns.retry(effort)
    }

    fun dismissError() {
        turns.dismissError()
    }

    fun dismissNotice() {
        notice.value = null
    }

    /** Bouton « Annuler » d'une carte : annule son action (ou le rétablissement qui l'a remise en vigueur). */
    fun undo(card: ChatCard) = toggle(card, toInEffect = false)

    /**
     * Bouton « Rétablir » d'une carte annulée : défait l'annulation, sur le téléphone, sans appel à
     * l'assistant. Une nouvelle version, elle-même annulable.
     */
    fun restore(card: ChatCard) = toggle(card, toInEffect = true)

    /** Cartes dont une bascule (annuler / rétablir) est en cours : un second toucher est ignoré (fil principal seulement). */
    private val toggling = mutableSetOf<Long>()

    private fun toggle(card: ChatCard, toInEffect: Boolean) {
        // Double toucher : deux bascules auraient lu le même état, et la seconde afficherait « C’est déjà annulé » à tort.
        if (!toggling.add(card.actionId)) return
        viewModelScope.launch {
            try {
                toggleNow(card, toInEffect)
            } finally {
                toggling.remove(card.actionId)
            }
        }
    }

    private suspend fun toggleNow(card: ChatCard, toInEffect: Boolean) {
        val id = ActionId(card.actionId)
        val status = engine.undoStatuses(listOf(id))[id] ?: return
        // Déjà fait ailleurs (phrase au modèle, autre écran) : rien à faire.
        if (status.inEffect == toInEffect) return
        when (val outcome = engine.execute(Undo(status.head))) {
            is ActionOutcome.Applied -> {
                cardNotices.update { it - card.actionId }
                notice.value = null
            }
            is ActionOutcome.Rejected -> {
                val text = refusal(card, outcome.rejection, toInEffect)
                // Dit sur la carte elle-même (le fil ne saute pas en bas) ; les autres cartes n'ont pas cette place.
                if (card is RecordCard || card is UndoCard) cardNotices.update { it + (card.actionId to text) } else notice.value = text
            }
        }
    }

    /** Propositions dont « Noter » est en cours : un second toucher est ignoré (fil principal seulement). */
    private val noting = mutableSetOf<String>()

    /**
     * Bouton « Noter » d'une carte de proposition : crée la déclaration, annulable, comme un repas dicté.
     * Tous les aliments ont une fiche : le moteur les recalcule et la carte d'enregistrement s'ajoute au fil, sans
     * appel à l'assistant. Un aliment sans fiche : la demande part à l'assistant, qui l'estime comme un repas dicté.
     * Une proposition déjà notée (et pas annulée) ne l'est pas une seconde fois. Une carte dont les fiches ont changé
     * depuis n'est pas notée : elle montre sa nouvelle version (CHAT-03).
     */
    fun note(card: ChatCard) {
        val proposal = card as? ProposalCard ?: return
        if (!noting.add(proposal.id)) return
        viewModelScope.launch {
            try {
                noteNow(proposal)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // CHAT-01 : une base en panne ne ferme pas l'app ; ce qui a été noté reste visible dans le fil.
                notice.value = "« Noter » n’a pas abouti pour « ${proposal.title} ». Regarde ta journée avant de réessayer."
            } finally {
                noting.remove(proposal.id)
            }
        }
    }

    private suspend fun noteNow(proposal: ProposalCard) {
        if (turns.state.value.running) {
            notice.value = "Trackly répond encore : appuie sur « Noter » dans un instant."
            return
        }
        when (val outcome = commitNote(proposal, estimates = null)) {
            NoteOutcome.NeedsEstimate -> noteThroughAssistant(proposal)
            else -> tell(proposal, outcome)
        }
    }

    /** Ce que le chat dit après un toucher sur « Noter », une fois la transaction finie. */
    private suspend fun tell(proposal: ProposalCard, outcome: NoteOutcome) {
        notice.value = when (outcome) {
            NoteOutcome.AlreadyNoted -> null
            // Relu hors de la transaction : la carte affichée correspond à un repas vraiment enregistré.
            is NoteOutcome.Noted -> if (engine.action(outcome.result.id) != null) null else "Rien n’a été noté pour « ${proposal.title} ». Réessaie « Noter »."
            NoteOutcome.Changed -> "« ${proposal.title} » a changé depuis (fiche modifiée) : voici la nouvelle version. Appuie sur « Noter » si elle te va."
            is NoteOutcome.Refused -> "Impossible de noter cette proposition : ${outcome.message}"
            is NoteOutcome.Mismatch -> "Rien n’a été noté : l’estimation ne reprenait pas « ${proposal.title} ». Réessaie « Noter », ou dis-moi ce que tu as mangé."
            NoteOutcome.Failed -> "Rien n’a été noté pour « ${proposal.title} » : la carte n’a pas pu être écrite. Réessaie « Noter »."
            NoteOutcome.NeedsEstimate -> "Rien n’a été noté pour « ${proposal.title} ». Dis-moi ce que tu as mangé, ou réessaie « Noter »."
        }
    }

    /** [noteInTransaction] dans la transaction commune ; une exception l'annule entière, rien n'est compté. */
    private suspend fun commitNote(proposal: ProposalCard, estimates: Map<Int, Food>?): NoteOutcome = try {
        services.sharedTransaction.run { noteInTransaction(proposal, estimates) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        NoteOutcome.Failed
    }

    /**
     * Vérifier, noter et écrire la carte d'enregistrement dans une seule transaction (CHAT-01) : interrompue (Android
     * tue l'app, base en panne), rien n'est compté ; aboutie, le repas et sa carte « Noté » existent ensemble. Un second
     * toucher relit donc toujours ce qui a été noté. Les fiches sont relues ici même (CHAT-03) : une carte qui a changé,
     * même pendant l'estimation de l'assistant, montre sa nouvelle version et rien n'est noté. [estimates] : valeurs
     * estimées des aliments sans fiche, par rang dans la carte (CHAT-02), ou `null` avant l'assistant.
     */
    private suspend fun noteInTransaction(proposal: ProposalCard, estimates: Map<Int, Food>?): NoteOutcome {
        val past = services.thread.last(ChatHistory.DISPLAY_LIMIT)
        val st = cardStatuses(past.flatMap { it.cards })
        if (proposal.id in notedProposals(past, st.undone + st.retired)) return NoteOutcome.AlreadyNoted
        val fresh = proposal.refreshed(engine.allProducts(), engine.allRecipes())
        if (!fresh.sameValuesAs(proposal)) {
            val message = past.lastOrNull { m -> m.cards.any { it is ProposalCard && it.id == proposal.id } }
            if (message != null) {
                val cards = message.cards.map { if (it is ProposalCard && it.id == proposal.id) fresh else it }
                services.history.update(message.id, message.text, cards, message.at)
            }
            return NoteOutcome.Changed
        }
        val declaration = fresh.declaration(engine.now(), estimates.orEmpty()) ?: return NoteOutcome.NeedsEstimate
        return when (val outcome = engine.execute(declaration)) {
            is ActionOutcome.Applied -> {
                val record = RecordCard.from(outcome.result, RecordKind.Added).copy(proposalId = proposal.id).frozen(engine, services.recordAlerts)
                services.history.append(engine.now(), ChatRole.Assistant, "", listOf(record))
                NoteOutcome.Noted(outcome.result)
            }
            is ActionOutcome.Rejected -> NoteOutcome.Refused(outcome.rejection.message)
        }
    }

    /**
     * Un aliment sans fiche : l'assistant reçoit la carte elle-même (CHAT-02) et ne fait que l'estimer. Son appel à
     * `record_consumptions` passe par [ProposalNoting] : l'app vérifie qu'il reprend la carte, avant toute écriture,
     * puis note comme sans l'assistant ([noteInTransaction], fiches et quantités de la carte). Un seul essai.
     */
    private suspend fun noteThroughAssistant(proposal: ProposalCard) {
        val noting = ProposalNoting(proposal) { estimates -> commitNote(proposal, estimates) }
        if (!turns.send(ProposalCard.noteRequest(proposal), noting = noting)) {
            notice.value = "Trackly répond encore : appuie sur « Noter » dans un instant."
            return
        }
        turns.state.first { !it.running }
        val outcome = noting.outcome
        if (outcome == null) {
            // Le tour a échoué (erreur déjà affichée, « Réessayer » reprend la même note) ou le modèle n'a rien tenté.
            if (turns.state.value.error == null) tell(proposal, NoteOutcome.NeedsEstimate)
            return
        }
        tell(proposal, outcome)
    }

    /**
     * Bouton « Corriger » d'une carte : le prochain message corrige l'enregistrement de CETTE carte.
     * L'assistant reçoit ses consommations (identifiants et état actuel) dans le contexte du message.
     */
    fun startCorrection(card: ChatCard) {
        val record = card as? RecordCard ?: return
        if (record.items.isEmpty()) return
        setCorrecting(
            Correcting(
                focus = CorrectionFocus(record.actionId, record.items.map { it.entryId }),
                label = record.items.joinToString(", ") { it.label },
            ),
        )
    }

    /**
     * « Corriger » depuis l'écran du jour (feuille d'un repas, A3) : le prochain message corrige ces
     * consommations ([entryIds]), désignées par [label] (« Riz basmati, Poulet rôti »). Rien n'est modifié
     * tant que l'utilisateur n'a pas envoyé sa correction.
     */
    fun correctEntries(entryIds: List<String>, label: String) {
        if (entryIds.isEmpty()) return
        setCorrecting(Correcting(CorrectionFocus(actionId = null, entryIds = entryIds), label))
    }

    /** « Retirer » sur le rappel de correction : le prochain message est un message ordinaire. */
    fun cancelCorrection() {
        setCorrecting(null)
    }

    private fun setCorrecting(value: Correcting?) {
        correcting.value = value
        saved[KEY_CORRECT_ACTION] = value?.focus?.actionId ?: NO_ACTION
        saved[KEY_CORRECT_ENTRIES] = value?.focus?.entryIds?.let { ArrayList(it) }
        saved[KEY_CORRECT_LABEL] = value?.label
    }

    private fun restoreCorrection(): Correcting? {
        val entries = saved.get<ArrayList<String>>(KEY_CORRECT_ENTRIES)?.takeIf { it.isNotEmpty() } ?: return null
        val label = saved.get<String>(KEY_CORRECT_LABEL) ?: return null
        val action = saved.get<Long>(KEY_CORRECT_ACTION)?.takeIf { it != NO_ACTION }
        return Correcting(CorrectionFocus(action, entries.toList()), label)
    }

    private fun notedProposals(messages: List<ChatMessage>, undone: Set<Long>): Set<String> =
        messages.asSequence().flatMap { it.cards }.filterIsInstance<RecordCard>()
            .filter { it.actionId !in undone }
            .mapNotNull { it.proposalId }
            .toSet()

    private suspend fun cardStatuses(cards: List<ChatCard>): CardStatuses {
        val acting = cards.filter { it.actionId > 0 }.distinctBy { it.actionId }
        if (acting.isEmpty()) return CardStatuses()
        val statuses = engine.undoStatuses(acting.map { ActionId(it.actionId) })
        val undone = statuses.values.filter { it.undone }.map { it.action.value }.toSet()
        val superseded = acting.mapNotNull { card ->
            val status = statuses[ActionId(card.actionId)]?.takeIf { it.superseded } ?: return@mapNotNull null
            card.actionId to supersededText(card, status.inEffect)
        }.toMap()
        // Proposition notée dont tout le repas a été retiré depuis (écran du jour, suppression) : elle redevient notable.
        val retired = acting.filterIsInstance<RecordCard>()
            .filter { it.proposalId != null && it.actionId !in undone && it.items.isNotEmpty() }
            .filter { card -> card.items.all { item -> engine.history(EntryId(item.entryId)).lastOrNull()?.isActive == false } }
            .map { it.actionId }.toSet()
        return CardStatuses(undone, superseded, retired)
    }

    /** Ce qui s'est passé depuis la carte, en clair. */
    private suspend fun supersededText(card: ChatCard, inEffect: Boolean): String {
        if (!inEffect) return "Modifié depuis : cela ne peut plus être rétabli d’ici."
        if (card !is RecordCard) return "Modifié depuis : pour revenir en arrière, annule plutôt la modification la plus récente."
        val gone = card.items.all { item -> engine.history(EntryId(item.entryId)).lastOrNull()?.isActive == false }
        return if (gone) {
            "Retiré du journal depuis."
        } else {
            "Corrigé depuis. Pour revenir en arrière, annule plutôt la correction."
        }
    }

    private fun refusal(card: ChatCard, rejection: Rejection, toInEffect: Boolean): String = when (rejection) {
        is Rejection.UndoConflict -> when {
            toInEffect -> "Modifié depuis : cela ne peut plus être rétabli d’ici."
            card is RecordCard -> "Ce repas a été corrigé depuis. Annule plutôt la correction."
            else -> "Modifié depuis : annule plutôt la modification la plus récente."
        }
        is Rejection.AlreadyUndone -> "C’est déjà annulé."
        else -> rejection.message
    }

    companion object {
        /** Messages précédents renvoyés au modèle (texte et résumés de cartes). */
        const val HISTORY_LIMIT = ChatTurns.HISTORY_LIMIT
        private const val UNDO_WATCH = 50

        private const val KEY_DRAFT = "chat-brouillon"
        private const val KEY_PREFILL = "chat-debut-propose"
        private const val KEY_PHOTO_COUNT = "chat-photos-jointes"
        private const val KEY_CORRECT_ACTION = "chat-correction-action"
        private const val KEY_CORRECT_ENTRIES = "chat-correction-entrees"
        private const val KEY_CORRECT_LABEL = "chat-correction-libelle"
        private const val NO_ACTION = -1L
        private const val NOTING_NOTICE = "Je note ta proposition : ton message t’attend, envoie-le dans un instant."

        fun factory(services: AssistantServices): ViewModelProvider.Factory = viewModelFactory {
            // Sans propriétaire d'état sauvegardé (tests qui créent le modèle à la main) : un état vide.
            initializer { ChatViewModel(services, runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() }) }
        }
    }
}
