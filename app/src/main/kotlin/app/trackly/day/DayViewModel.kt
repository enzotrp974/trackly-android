package app.trackly.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeleteConsumptions
import app.trackly.core.action.Undo
import app.trackly.core.model.ActionId
import app.trackly.core.model.EntryId
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Moment
import app.trackly.core.overview.DayOverviews
import app.trackly.core.time.TrackingDay
import app.trackly.core.store.ActionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Jours affichés : du premier jour enregistré (au moins la veille) jusqu'au jour en cours. */
data class DayUiState(val today: LocalDate?, val days: List<DayView>) {
    val loading: Boolean get() = today == null

    companion object {
        val Loading = DayUiState(today = null, days = emptyList())
    }
}

/** Suppression d'un repas encore annulable : bandeau « [label] · Annuler ». */
data class UndoOffer(val actionId: ActionId, val label: String)

/**
 * Écran du jour sur les vraies données : lit les bilans du moteur en continu et lui
 * transmet les actions (panneau de développement, plus tard l'assistant).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val engine: TracklyEngine,
    /** Intervalle de relecture du jour en cours (le jour de suivi change à 5 h). */
    private val tickMillis: Long = 60_000,
    /** Durée du bandeau « Annuler » : celle d'Android si le réglage d'accessibilité « Délai avant disparition » l'allonge. */
    private val undoTimeout: RecommendedTimeout = RecommendedTimeout.Default,
) : ViewModel() {
    private val today = flow {
        while (true) {
            emit(engine.today())
            delay(tickMillis)
        }
    }.distinctUntilChanged()

    val state: StateFlow<DayUiState> = combine(today, engine.observeFirstDay()) { today, first -> today to first }
        .flatMapLatest { (today, first) ->
            val from = minOf(first ?: today, today.minusDays(1))
            engine.observeDays(from, today).map { summaries ->
                // Le trait de moyenne et le jaune ne concernent que le jour en cours ; un jour passé ne garde que le rouge.
                DayUiState(today, summaries.map { DayView.from(it, today, DayOverviews.of(it, summaries, today)) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState.Loading)

    val recentActions: StateFlow<List<ActionRecord>> = engine.observeRecentActions(RECENT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastOutcome = MutableStateFlow<ActionOutcome?>(null)

    /** Résultat de la dernière action demandée depuis l'écran. */
    val lastOutcome: StateFlow<ActionOutcome?> = _lastOutcome.asStateFlow()

    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)

    /** Dernière suppression de repas, tant qu'elle peut être annulée depuis le bandeau. */
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()

    /** « Supprimer » sur la feuille d'un repas : action du moteur (nouvelle version `deleted`), annulable. */
    fun deleteMeal(entryIds: List<String>, label: String) {
        if (entryIds.isEmpty()) return
        viewModelScope.launch {
            val outcome = engine.execute(DeleteConsumptions(entryIds.map(::EntryId)))
            _lastOutcome.value = outcome
            val offer = (outcome as? ActionOutcome.Applied)?.let { UndoOffer(it.result.id, label) }
            _undoOffer.value = offer
            if (offer != null) {
                // Le bandeau « Annuler » disparaît seul ; l'annulation reste possible plus tard (action du moteur).
                delay(undoTimeout.forControls(UNDO_OFFER_MILLIS))
                if (_undoOffer.value == offer) _undoOffer.value = null
            }
        }
    }

    /** « Annuler » du bandeau : annule la suppression, sans réseau. */
    fun undoDelete() {
        val offer = _undoOffer.value ?: return
        _undoOffer.value = null
        viewModelScope.launch { _lastOutcome.value = engine.execute(Undo(offer.actionId)) }
    }

    /** Moment proposé par défaut selon l'heure de l'horloge du moteur. */
    fun suggestedMoment(): Moment = Moment.guessFor(TrackingDay.localTime(engine.now()))

    fun perform(action: Action) {
        viewModelScope.launch { _lastOutcome.value = engine.execute(action) }
    }

    companion object {
        const val RECENT = 6

        /** Durée d'affichage du bandeau « Annuler » après une suppression (10 s, plus si Android le recommande). */
        const val UNDO_OFFER_MILLIS = 10_000L

        fun factory(engine: TracklyEngine, undoTimeout: RecommendedTimeout = RecommendedTimeout.Default): ViewModelProvider.Factory = viewModelFactory {
            initializer { DayViewModel(engine, undoTimeout = undoTimeout) }
        }
    }
}
