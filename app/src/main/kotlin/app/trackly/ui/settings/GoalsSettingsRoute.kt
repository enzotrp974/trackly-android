package app.trackly.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trackly.core.engine.TracklyEngine
import app.trackly.goals.GoalsViewModel

/** Section « Objectifs » des Réglages, branchée sur le moteur (même action que l'assistant). */
@Composable
fun GoalsSettingsRoute(engine: TracklyEngine) {
    val model: GoalsViewModel = viewModel(factory = GoalsViewModel.factory(engine))
    val state by model.state.collectAsStateWithLifecycle()
    val actions = remember(model) { GoalsActions(save = model::save, undo = model::undo, dismissNotice = model::dismissNotice) }
    // Un message d'enregistrement ne doit pas réapparaître à la prochaine ouverture des Réglages.
    DisposableEffect(model) { onDispose { model.dismissNotice() } }
    GoalsSettingsSection(state, actions)
}
