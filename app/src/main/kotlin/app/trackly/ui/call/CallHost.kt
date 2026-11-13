package app.trackly.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.call.CallPhase
import app.trackly.call.CallServices
import app.trackly.call.EndReason
import kotlinx.coroutines.delay

/** Fins ordinaires : l'écran se referme seul peu après. Les autres (erreur, clé, micro) attendent « Fermer ». */
private val AUTO_CLOSE = setOf(EndReason.User, EndReason.Goodbye, EndReason.Silence, EndReason.MaxDuration, EndReason.Server)
private const val AUTO_CLOSE_MS = 1_500L

/**
 * Écran d'appel posé par-dessus toute l'app, ouvert dès le toucher du bouton d'appel. Le retour pendant l'appel met
 * l'app en arrière-plan (l'appel continue, raccrocher reste un geste explicite) ; une fois fini, il referme l'écran.
 */
@Composable
fun CallHost(call: CallServices, onBackground: () -> Unit) {
    val open by call.screenOpen.collectAsStateWithLifecycle()
    if (!open) return
    val ui by call.controller.state.collectAsStateWithLifecycle()
    val levels = call.levels.collectAsStateWithLifecycle()
    BackHandler { if (call.controller.isActive) onBackground() else call.close() }
    LaunchedEffect(ui.phase, ui.endReason) {
        if (ui.phase == CallPhase.Ended && ui.endReason in AUTO_CLOSE) {
            delay(AUTO_CLOSE_MS)
            call.close()
        }
    }
    CallScreen(
        ui = ui,
        levels = levels,
        onMute = call::toggleMute,
        onSpeaker = call::toggleSpeaker,
        onHangUp = call::hangUp,
        onClose = call::close,
        // Rien ne traverse vers l'app dessous.
        modifier = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    )
}
