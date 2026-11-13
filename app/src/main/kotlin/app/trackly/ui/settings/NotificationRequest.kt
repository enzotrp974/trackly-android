package app.trackly.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.trackly.alerts.NotificationSettingsIntent
import app.trackly.alerts.NotificationsAllowed

/**
 * Demande d'autorisation des notifications. La première fois, la fenêtre d'Android (13 et plus) ; ensuite le système ne
 * la remontre plus, donc on ouvre les réglages de notification de Trackly. [firstTime] choisit le libellé du bouton.
 */
class NotificationRequester(val firstTime: Boolean, val request: () -> Unit) {
    val label: String get() = if (firstTime) "Autoriser les notifications" else "Ouvrir les réglages de notification"
}

@Composable
fun rememberNotificationRequester(notifications: NotificationsAllowed): NotificationRequester {
    val context = LocalContext.current
    var asked by remember(notifications) { mutableStateOf(notifications.asked) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications.refresh() }
    val firstTime = Build.VERSION.SDK_INT >= 33 && !asked
    return NotificationRequester(firstTime) {
        if (firstTime) {
            notifications.asked = true
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { context.startActivity(NotificationSettingsIntent.of(context)) }
        }
    }
}
