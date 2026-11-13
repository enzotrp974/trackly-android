package app.trackly.ui.dishes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trackly.core.engine.TracklyEngine
import app.trackly.dishes.DishesViewModel

/** « Mes aliments » branché sur le moteur : mêmes actions que l'assistant. */
@Composable
fun DishesRoute(engine: TracklyEngine, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val model: DishesViewModel = viewModel(factory = DishesViewModel.factory(engine))
    val state by model.state.collectAsStateWithLifecycle()
    val actions = remember(model) {
        DishesActions(
            startNew = model::startNew,
            startVariant = model::startVariant,
            startEdit = model::startEdit,
            change = model::change,
            save = model::save,
            closeForm = model::close,
            searchProducts = model::searchProducts,
            matching = model::matching,
            addProduct = model::addProduct,
            setDefault = model::setDefault,
            setPaused = model::setPaused,
            delete = model::delete,
            renameVersion = model::renameVersion,
            addProvision = model::addProvision,
            removeProvision = model::removeProvision,
            forgetMemory = model::forget,
            renameProduct = model::renameProduct,
            addShortcut = model::addShortcut,
            removeShortcut = model::removeShortcut,
            makeRecurring = model::makeRecurring,
            undo = model::undo,
            dismissNotice = model::dismissNotice,
        )
    }
    // Un message ou un formulaire à moitié rempli ne doit pas réapparaître à la prochaine ouverture.
    DisposableEffect(model) {
        onDispose {
            model.dismissNotice()
            model.close()
        }
    }
    DishesScreen(state, actions, onClose, modifier)
}
