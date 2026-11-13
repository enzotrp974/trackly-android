package app.trackly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.alerts.AndroidNotificationsAllowed
import app.trackly.alerts.NotificationsAllowed
import app.trackly.review.REMINDER_TIME
import app.trackly.review.ReviewSettings
import app.trackly.review.ReviewSettingsState
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import java.time.format.DateTimeFormatter

/** Étiquettes de test de la section « Bilan du lundi ». */
object ReviewSettingsTags {
    const val SECTION = "reglages-bilan"
    const val SWITCH = "reglages-bilan-rappels"
    const val TIME = "reglages-bilan-heure"
    const val EARLIER = "reglages-bilan-plus-tot"
    const val LATER = "reglages-bilan-plus-tard"
    const val ALLOW = "reglages-bilan-autoriser"
    const val START = "reglages-bilan-faire"
    const val REPORTS = "reglages-bilan-liste"
}

/**
 * Section « Bilan du lundi » des Réglages, branchée sur les réglages et la planification. L'interrupteur « Rappels »
 * reflète la réalité : il n'est allumé que si les rappels sont voulus **et** qu'Android laisse notifier. L'autorisation
 * (Android 13+) est demandée ici, avec son explication, si elle ne l'a pas été à la mise en route.
 */
@Composable
fun ReviewSettingsRoute(
    settings: ReviewSettings,
    reschedule: () -> Unit,
    onStart: () -> Unit,
    onReports: () -> Unit,
    notifications: NotificationsAllowed = rememberAndroidNotifications(),
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val allowed by notifications.allowed.collectAsStateWithLifecycle()
    val requester = rememberNotificationRequester(notifications)
    // Au retour des réglages système, l'utilisateur a pu changer la permission.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifications.refresh() }
    ReviewSettingsSection(
        state = state,
        notificationsAllowed = allowed,
        onToggle = { on ->
            settings.setEnabled(on)
            reschedule()
            if (on && !allowed) requester.request()
        },
        onEarlier = {
            settings.setInviteTime(state.inviteTime.minusMinutes(ReviewSettingsState.STEP_MINUTES))
            reschedule()
        },
        onLater = {
            settings.setInviteTime(state.inviteTime.plusMinutes(ReviewSettingsState.STEP_MINUTES))
            reschedule()
        },
        onAllowNotifications = requester.request,
        onStart = onStart,
        onReports = onReports,
        allowLabel = requester.label,
    )
}

@Composable
private fun rememberAndroidNotifications(): NotificationsAllowed {
    val context = LocalContext.current
    return remember(context) { AndroidNotificationsAllowed(context) }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Réglages du bilan du lundi : rappels, heure de l'invitation, accès à la conversation et aux bilans passés. */
@Composable
fun ReviewSettingsSection(
    state: ReviewSettingsState,
    notificationsAllowed: Boolean,
    onToggle: (Boolean) -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onAllowNotifications: () -> Unit,
    onStart: () -> Unit,
    onReports: () -> Unit,
    allowLabel: String = "Autoriser les notifications",
) {
    // L'interrupteur dit ce qui se passera vraiment : rappels voulus ET notifications permises.
    val remindersOn = state.enabled && notificationsAllowed
    Column(Modifier.fillMaxWidth().testTag(ReviewSettingsTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        BasicText("BILAN DU LUNDI", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        SettingSwitch("Rappels du lundi", remindersOn, onToggle)
        if (remindersOn) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    BasicText("Invitation le lundi", style = TracklyType.MealTitle.copy(color = TracklyColors.Ink))
                    BasicText("Heure locale", style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
                }
                Stepper(
                    label = TIME_FORMAT.format(state.inviteTime),
                    earlierEnabled = state.inviteTime > ReviewSettingsState.EARLIEST,
                    laterEnabled = state.inviteTime < ReviewSettingsState.LATEST,
                    onEarlier = onEarlier,
                    onLater = onLater,
                )
            }
            Spacer(Modifier.height(4.dp))
            Body("Relance à ${TIME_FORMAT.format(REMINDER_TIME)}, seulement si le bilan de la semaine n’est pas fait. L’heure exacte dépend d’Android.")
        }
        if (!notificationsAllowed) {
            Spacer(Modifier.height(8.dp))
            Body(
                if (state.enabled) {
                    "Notifications désactivées : pas d’invitation le lundi."
                } else {
                    "Notifications désactivées : pas d’invitation le lundi."
                },
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.testTag(ReviewSettingsTags.ALLOW)) { SettingsButton(allowLabel, primary = true, onClick = onAllowNotifications) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.testTag(ReviewSettingsTags.START)) { SettingsButton("Faire mon bilan", primary = true, onClick = onStart) }
            Box(Modifier.testTag(ReviewSettingsTags.REPORTS)) { SettingsButton("Mes bilans", onClick = onReports) }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ReviewSettingsTags.SWITCH)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 46.dp, height = 28.dp)
                .clip(CircleShape)
                .background(if (checked) TracklyColors.Cobalt else TracklyColors.TrackEmpty)
                .border(TracklySpacing.Hairline, if (checked) TracklyColors.Cobalt else TracklyColors.Line2, CircleShape)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (checked) TracklyColors.PaperHi else TracklyColors.Ink3))
        }
    }
}

@Composable
private fun Stepper(label: String, earlierEnabled: Boolean, laterEnabled: Boolean, onEarlier: () -> Unit, onLater: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Une demi-heure plus tôt", earlierEnabled, ReviewSettingsTags.EARLIER, onEarlier)
        BasicText(
            label,
            style = TracklyType.MealTitle.copy(color = TracklyColors.Ink),
            modifier = Modifier.width(62.dp).testTag(ReviewSettingsTags.TIME).semantics { contentDescription = "Invitation à $label" },
        )
        StepButton("+", "Une demi-heure plus tard", laterEnabled, ReviewSettingsTags.LATER, onLater)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.92f, enabled = enabled)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) TracklyColors.Mist else TracklyColors.TrackEmpty),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(symbol, style = TracklyType.MealTitle.copy(color = if (enabled) TracklyColors.Cobalt else TracklyColors.Ink3))
    }
}

@Composable
private fun Body(text: String) {
    BasicText(text, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
private fun SettingsButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.MealTitle.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.97f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.Mist)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
