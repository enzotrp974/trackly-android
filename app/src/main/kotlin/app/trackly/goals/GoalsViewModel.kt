package app.trackly.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.day.DayLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Section Objectifs des Réglages. L'enregistrement passe par [SetGoal], la même action que
 * l'assistant : un objectif changé dans le chat apparaît ici aussitôt, et l'inverse.
 */
class GoalsViewModel(private val engine: TracklyEngine) : ViewModel() {
    private val notice = MutableStateFlow<GoalsNotice?>(null)

    val state: StateFlow<GoalsUiState> = combine(engine.observeGoals(), notice) { goals, notice ->
        GoalsUiState.of(goals, engine.today(), notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState(engine.today()))

    fun save(action: SetGoal) {
        viewModelScope.launch {
            notice.value = when (val outcome = engine.execute(action)) {
                is ActionOutcome.Applied -> GoalsNotice(
                    "Objectif enregistré : ${action.targets.kcal.roundToInt()} kcal dès ${DayLabels.inSentence(outcome.result.goalChanges.last().after.effectiveFrom)}.",
                    undoActionId = outcome.result.id.value,
                )
                is ActionOutcome.Rejected -> GoalsNotice(outcome.rejection.message, error = true)
            }
        }
    }

    fun undo(actionId: Long) {
        viewModelScope.launch {
            notice.value = when (val outcome = engine.execute(Undo(ActionId(actionId)))) {
                is ActionOutcome.Applied -> GoalsNotice("Changement annulé.")
                is ActionOutcome.Rejected -> GoalsNotice(outcome.rejection.message, error = true)
            }
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    companion object {
        fun factory(engine: TracklyEngine): ViewModelProvider.Factory = viewModelFactory {
            initializer { GoalsViewModel(engine) }
        }
    }
}
