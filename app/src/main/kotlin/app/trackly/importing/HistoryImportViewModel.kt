package app.trackly.importing

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Écran de reprise affiché par-dessus le journal ; `null` = masqué. */
sealed interface ImportScreenState {
    /** Ouverture depuis le calendrier. */
    data object Invite : ImportScreenState

    /** Lecture et vérification du fichier. */
    data object Working : ImportScreenState

    data class Result(val outcome: ImportOutcome) : ImportScreenState
}

/**
 * Reprise de l'historique depuis l'écran (ligne « Historique » de la mise en route, ou calendrier) :
 * lecture du fichier choisi, puis résumé. L'écriture passe par l'action du moteur ([HistoryImport]).
 */
class HistoryImportViewModel(
    private val engine: TracklyEngine,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _screen = MutableStateFlow<ImportScreenState?>(null)
    val screen: StateFlow<ImportScreenState?> = _screen.asStateFlow()

    /** Ouvre l'écran de reprise (calendrier). */
    fun open() {
        if (_screen.value != ImportScreenState.Working) _screen.value = ImportScreenState.Invite
    }

    /** Ferme l'écran. */
    fun close() {
        if (_screen.value == ImportScreenState.Working) return
        _screen.value = null
    }

    /** Reprend le fichier chargé par [load] (lecture hors du fil principal). */
    fun importFrom(load: suspend () -> String) {
        if (_screen.value == ImportScreenState.Working) return
        _screen.value = ImportScreenState.Working
        viewModelScope.launch {
            val outcome = try {
                val text = withContext(io) { load() }
                withContext(io) { HistoryImport.run(engine, text) }
            } catch (e: Exception) {
                ImportOutcome.Refused("Fichier illisible", listOfNotNull(e.message))
            }
            _screen.value = ImportScreenState.Result(outcome)
        }
    }

    companion object {
        fun factory(engine: TracklyEngine): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryImportViewModel(engine) }
        }
    }
}

/** Lecture d'un document choisi par le sélecteur système (accès limité à ce seul fichier). */
object DocumentReader {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun readText(resolver: ContentResolver, uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("Impossible d'ouvrir le fichier")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw IOException("Fichier trop volumineux (plus de 64 Mo)")
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }
}
