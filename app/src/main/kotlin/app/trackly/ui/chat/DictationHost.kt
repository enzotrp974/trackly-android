package app.trackly.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trackly.dictation.DictationReceiver
import app.trackly.dictation.DictationServices
import app.trackly.dictation.DictationViewModel

/**
 * Branche la dictée sur le système et sur le chat : fenêtre d'autorisation du micro, chemin vers les
 * paramètres de l'app, arrêt propre quand l'app quitte l'écran, et remise du texte dicté au chat
 * (envoyé, ou placé dans la zone de saisie).
 */
@Composable
fun rememberDictation(
    services: DictationServices,
    receiver: DictationReceiver,
    /** Une dictée par conversation : le chat de saisie (clé par défaut) et le bilan ont chacune la leur. */
    key: String? = null,
): DictationBinding {
    val model: DictationViewModel = viewModel(key = key, factory = DictationViewModel.factory(services))
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val controller = model.controller

    LaunchedEffect(model, receiver) {
        model.results.collect { receiver.receiveDictation(it.text, sendNow = it.send, notice = it.notice) }
    }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onPermissionResult(granted)
    }
    // Une rotation recrée l'activité : la dictée, portée par le ViewModel, continue.
    val leave = {
        if (context.findActivity()?.isChangingConfigurations != true) controller.onAppStopped()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { leave() }

    val actions = remember(controller, requestMic, context) {
        DictationActions(
            onMic = controller::onMicTapped,
            onSend = controller::stopAndSend,
            onStopEdit = controller::stopAndEdit,
            onCancel = controller::cancel,
            onAllowMic = { requestMic.launch(android.Manifest.permission.RECORD_AUDIO) },
            onOpenAppSettings = { openAppSettings(context) },
            onDismiss = controller::dismiss,
            onRetry = controller::retry,
            onKeepText = controller::keepText,
            onLeave = { if (context.findActivity()?.isChangingConfigurations != true) controller.onAppStopped() },
        )
    }
    return DictationBinding(state, actions)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
