package app.trackly.dictation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** La dictée, rattachée à l'écran : survit à la rotation, s'arrête avec l'activité. */
class DictationViewModel(services: DictationServices) : ViewModel() {
    val controller = DictationController(services, viewModelScope)

    val state get() = controller.state
    val results get() = controller.results

    override fun onCleared() {
        controller.cancel()
    }

    companion object {
        fun factory(services: DictationServices): ViewModelProvider.Factory = viewModelFactory {
            initializer { DictationViewModel(services) }
        }
    }
}
