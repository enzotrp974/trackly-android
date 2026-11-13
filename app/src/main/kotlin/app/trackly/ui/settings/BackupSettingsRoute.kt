package app.trackly.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.backup.BackupController
import app.trackly.backup.DocumentExportTarget
import app.trackly.backup.drive.GoogleDriveAuthorizer
import java.time.Clock
import java.time.ZoneId

/**
 * Actions de la section « Sauvegarde » branchées sur le [controller] : écrans Google
 * (consentement), choix d'un fichier, permission de notification (Android 13+, demandée au moment
 * de connecter Drive). À appeler une fois dans l'écran qui affiche [BackupSettingsSection].
 */
@Composable
fun rememberBackupActions(controller: BackupController): BackupActions {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        controller.onConsentResult(granted, if (granted) null else GoogleDriveAuthorizer.consentFailure(context, result.data))
    }
    LaunchedEffect(state.consent) {
        val pending = state.consent ?: return@LaunchedEffect
        consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Accordée ou non, la connexion continue : l'état reste visible dans les réglages.
        controller.connect()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) controller.pickFile(displayName(context, uri)) { context.contentResolver.openInputStream(uri) }
    }

    // Le sélecteur de fichiers crée le document ; l'export l'écrit ensuite, puis le relit.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        controller.onExportDestination(uri?.let { DocumentExportTarget(context.contentResolver, it) })
    }
    LaunchedEffect(state.exportName) {
        val name = state.exportName ?: return@LaunchedEffect
        controller.exportPickerOpened()
        try {
            exporter.launch(name)
        } catch (e: android.content.ActivityNotFoundException) {
            controller.exportUnavailable()
        }
    }

    return BackupActions(
        connect = {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else controller.connect()
        },
        backupNow = controller::backupNow,
        disconnect = controller::disconnect,
        openRestore = controller::openRestore,
        closeRestore = controller::closeRestore,
        choose = controller::choose,
        cancelChoice = controller::cancelChoice,
        confirmRestore = controller::confirmRestore,
        pickFile = { picker.launch(arrayOf("*/*")) },
        exportToFile = controller::startExport,
        dismissNotice = controller::dismissNotice,
        dismissHiddenCopies = controller::dismissHiddenCopies,
    )
}

/** Section « Sauvegarde » des Réglages, branchée sur le [controller]. */
@Composable
fun BackupSettingsRoute(controller: BackupController, clock: Clock, zone: ZoneId) {
    val state by controller.state.collectAsStateWithLifecycle()
    BackupSettingsSection(state, rememberBackupActions(controller), clock.instant(), zone)
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
