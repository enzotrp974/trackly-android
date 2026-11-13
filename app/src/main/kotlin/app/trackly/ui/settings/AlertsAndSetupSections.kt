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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.alerts.AlertKind
import app.trackly.alerts.AlertLine
import app.trackly.alerts.HomeAlertsSource
import app.trackly.alerts.NotificationsAllowed
import app.trackly.backup.BackupController
import app.trackly.setup.SetupChecklist
import app.trackly.ui.setup.SetupNav
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.setup.SetupState
import app.trackly.ui.components.pressable
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test des alertes et de l'entrée « Mise en route » des Réglages. */
object AlertsTags {
    const val SECTION = "reglages-alertes"
    const val SETUP = "reglages-mise-en-route"
    const val SETUP_OPEN = "reglages-mise-en-route-ouvrir"
    const val NOTIFICATIONS = "reglages-notifications"

    fun line(kind: AlertKind) = "reglages-alerte-${kind.name}"
    fun action(kind: AlertKind) = "reglages-alerte-action-${kind.name}"
}

private val PanelShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * Alertes en tête des Réglages : copie de nuit trop ancienne, notifications bloquées. Rien quand tout va bien.
 * [onAction] reçoit la ligne dont l'utilisateur a touché le bouton.
 */
@Composable
fun SettingsAlertsSection(lines: List<AlertLine>, onAction: (AlertKind) -> Unit) {
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(AlertsTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(AlertsTags.line(line.kind))
                    .clip(PanelShape)
                    .background(TracklyColors.Mist)
                    .border(TracklySpacing.Hairline, TracklyColors.Line2, PanelShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                BasicText(
                    "À noter : ${line.text}",
                    style = TracklyType.MealDetail.copy(color = TracklyColors.Ink),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                line.actionLabel?.let { label ->
                    BasicText(
                        label,
                        style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
                        modifier = Modifier
                            .testTag(AlertsTags.action(line.kind))
                            .pressable({ onAction(line.kind) }, pressedScale = 0.94f)
                            .semantics { role = Role.Button }
                            .heightIn(min = 44.dp)
                            .padding(vertical = 12.dp, horizontal = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** Entrée « Mise en route » des Réglages, montrée tant qu'une étape reste à faire. */
@Composable
fun SetupSettingsSection(state: SetupState, onOpen: () -> Unit) {
    if (state.items.all { it.done }) return
    Column(Modifier.fillMaxWidth().testTag(AlertsTags.SETUP)) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(AlertsTags.SETUP_OPEN)
                .pressable(onOpen, pressedScale = 0.985f)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .clip(ButtonShape)
                .background(TracklyColors.Cobalt)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Mise en route", style = TracklyType.MealTitle.copy(color = TracklyColors.PaperHi), modifier = Modifier.weight(1f))
            BasicText(state.progress, style = TracklyType.MealDetail.copy(color = TracklyColors.PaperHi))
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.ChevronRight, TracklyColors.PaperHi, Modifier.size(17.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** Alertes des Réglages branchées sur l'état : « Autoriser » demande la permission, « Sauvegarder maintenant » ouvre Connexion Drive et sauvegarde. */
@Composable
fun AlertsSettingsRoute(source: HomeAlertsSource, notifications: NotificationsAllowed, backup: BackupController, onOpenDrive: () -> Unit) {
    val alerts by source.alerts.collectAsStateWithLifecycle()
    val requester = rememberNotificationRequester(notifications)
    SettingsAlertsSection(
        lines = alerts.forSettings.filter { it.kind == AlertKind.StaleBackup }.map { if (it.kind == AlertKind.NotificationsOff) it.copy(actionLabel = requester.label) else if (it.kind == AlertKind.StaleBackup) it.copy(actionLabel = "Sauvegarder maintenant") else it },
        onAction = { kind ->
            when (kind) {
                AlertKind.NotificationsOff -> requester.request()
                // Le bilan n'a pas de ligne dans les Réglages (section « Bilan du lundi »).
                AlertKind.ReviewDue -> Unit
                AlertKind.StaleBackup -> {
                    // La page Drive porte l'écran Google et les messages : sans elle, un accès expiré ne montrerait rien.
                    onOpenDrive()
                    backup.backupNow()
                }
            }
        },
    )
}

/** Thème « Notifications » : l'état, et le bouton pour les autoriser quand elles sont bloquées. */
@Composable
fun NotificationsSettingsRoute(notifications: NotificationsAllowed) {
    val allowed by notifications.allowed.collectAsStateWithLifecycle()
    val requester = rememberNotificationRequester(notifications)
    Column(Modifier.fillMaxWidth().testTag(AlertsTags.NOTIFICATIONS)) {
        BasicText(
            if (allowed) "Notifications autorisées." else "Notifications bloquées : pas de rappel du bilan ni d’alerte de sauvegarde.",
            style = TracklyType.MealTitle.copy(color = TracklyColors.Ink),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!allowed) {
            BasicText(
                requester.label,
                style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
                modifier = Modifier
                    .pressable(requester.request, pressedScale = 0.94f)
                    .semantics { role = Role.Button }
                    .heightIn(min = 44.dp)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** Entrée « Mise en route » des Réglages branchée sur la liste. */
@Composable
fun SetupSettingsRoute(checklist: SetupChecklist, nav: SetupNav) {
    val state by checklist.state.collectAsStateWithLifecycle()
    SetupSettingsSection(state, onOpen = nav::show)
}
