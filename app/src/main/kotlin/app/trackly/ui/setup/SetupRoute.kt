package app.trackly.ui.setup

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.alerts.NotificationsAllowed
import app.trackly.backup.BackupBusy
import app.trackly.backup.BackupController
import app.trackly.setup.BatteryHelp
import app.trackly.setup.SetupChecklist
import app.trackly.ui.settings.rememberBackupActions
import app.trackly.ui.settings.rememberNotificationRequester

private val DRIVE_STEPS = setOf(BackupBusy.Connecting, BackupBusy.LookingForBackup, BackupBusy.Restarting)

/** Mise en route branchée sur la liste, la permission de notification et la connexion à Drive. */
@Composable
fun SetupRoute(
    checklist: SetupChecklist,
    nav: SetupNav,
    notifications: NotificationsAllowed,
    backup: BackupController,
    onPickHistory: (() -> Unit)? = null,
) {
    val state by checklist.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requester = rememberNotificationRequester(notifications)
    val backupActions = rememberBackupActions(backup)
    val actions = remember(checklist, nav, requester, backupActions, onPickHistory) {
        SetupActions(
            openKey = nav::requestKey,
            pickHistory = onPickHistory,
            allowNotifications = requester.request,
            connectDrive = backupActions.connect,
            openBattery = {
                if (!BatteryHelp.open(context)) {
                    Toast.makeText(context, "Ouvre Réglages › Batterie, puis suis les deux étapes écrites.", Toast.LENGTH_LONG).show()
                }
            },
            setBatteryDone = checklist::markBatteryDone,
            setExportFileDeleted = checklist::markExportFileDeleted,
            finish = {
                checklist.dismiss()
                nav.hide()
            },
            close = nav::leave,
        )
    }
    // Sous la ligne Drive : ce que fait la connexion (recherche, restauration), puis son résultat.
    val driveNotice = backupState.busy?.takeIf { it in DRIVE_STEPS }?.label ?: backupState.notice?.text
    SetupScreen(state, requester.label, actions, driveNotice = driveNotice)
}
