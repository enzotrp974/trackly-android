package app.trackly.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.trackly.setup.BatteryHelp
import app.trackly.setup.SetupItem
import app.trackly.setup.SetupState
import app.trackly.setup.SetupStep
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test de la mise en route. */
object SetupTags {
    const val SCREEN = "mise-en-route"
    const val FINISH = "mise-en-route-terminer"
    const val LATER = "mise-en-route-plus-tard"
    const val BACK = "mise-en-route-retour"
    const val PROGRESS = "mise-en-route-progression"
    const val NOTICE = "mise-en-route-message-drive"

    fun row(step: SetupStep) = "mise-en-route-ligne-${step.name}"
    fun action(step: SetupStep) = "mise-en-route-action-${step.name}"
    fun markDone(step: SetupStep) = "mise-en-route-fait-${step.name}"
    fun undo(step: SetupStep) = "mise-en-route-decocher-${step.name}"
}

/** Ce que la liste peut demander ; chaque ligne a son action. */
class SetupActions(
    val openKey: () -> Unit = {},
    /** Sélecteur de fichier de la reprise ; `null` : pas de bouton. */
    val pickHistory: (() -> Unit)? = null,
    val allowNotifications: () -> Unit = {},
    val connectDrive: () -> Unit = {},
    val openBattery: () -> Unit = {},
    val setBatteryDone: (Boolean) -> Unit = {},
    val setExportFileDeleted: (Boolean) -> Unit = {},
    val finish: () -> Unit = {},
    val close: () -> Unit = {},
)

private val CardShape = RoundedCornerShape(TracklySpacing.SheetRadius)
private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * Mise en route : ce qu'il reste à régler une fois (clé, historique, Drive, notifications, batterie Samsung, fichier
 * d'export), en lignes courtes. S'ouvre seule au premier lancement, puis depuis les Réglages tant qu'elle n'est pas finie.
 */
@Composable
fun SetupScreen(
    state: SetupState,
    notificationLabel: String,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    /** Dernier message de la connexion à Drive (échec compris), montré sous sa ligne. */
    driveNotice: String? = null,
) {
    Box(modifier.fillMaxSize().testTag(SetupTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(TracklySpacing.BackSize)
                        .testTag(SetupTags.BACK)
                        .pressable(actions.close, pressedScale = 0.86f)
                        .semantics { contentDescription = "Fermer la mise en route" },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp)) }
                BasicText("Mise en route", style = TracklyType.SettingsTitle.copy(color = TracklyColors.Ink), modifier = Modifier.padding(start = 6.dp).semantics { heading() })
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = TracklySpacing.Gutter, vertical = 8.dp),
            ) {
                BasicText(
                    state.progress,
                    style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
                    modifier = Modifier.testTag(SetupTags.PROGRESS),
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(TracklyColors.Paper)
                        .border(TracklySpacing.Hairline, TracklyColors.Line, CardShape)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    state.items.forEachIndexed { i, item ->
                        if (i > 0) Hairline()
                        ItemRow(item, notificationLabel, actions, if (item.step == SetupStep.Drive) driveNotice else null)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.testTag(SetupTags.FINISH)) {
                        Button(if (state.items.all { it.done }) "Terminer" else "Terminer sans tout cocher", primary = true, onClick = actions.finish)
                    }
                    Box(Modifier.testTag(SetupTags.LATER)) { Button("Plus tard", primary = false, onClick = actions.close) }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(item: SetupItem, notificationLabel: String, actions: SetupActions, notice: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(SetupTags.row(item.step))
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CheckMark(item.done, item.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(item.title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.semantics { heading() })
            if (item.detail.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                BasicText(item.detail, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
            }
            if (item.step == SetupStep.Battery && !item.done) {
                BasicText(BatteryHelp.PATH_NEVER_SLEEPING, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink))
            }
            if (notice != null) {
                Spacer(Modifier.height(3.dp))
                BasicText(notice, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink), modifier = Modifier.testTag(SetupTags.NOTICE).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (!item.done || item.manual) Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (item.step) {
                    SetupStep.Key -> if (!item.done) Box(Modifier.testTag(SetupTags.action(item.step))) { Button("Régler", primary = true, onClick = actions.openKey) }
                    SetupStep.Notifications -> if (!item.done) Box(Modifier.testTag(SetupTags.action(item.step))) { Button(notificationLabel, primary = true, onClick = actions.allowNotifications) }
                    SetupStep.Drive -> if (!item.done) Box(Modifier.testTag(SetupTags.action(item.step))) { Button("Connecter", primary = true, onClick = actions.connectDrive) }
                    SetupStep.History -> if (!item.done && actions.pickHistory != null) Box(Modifier.testTag(SetupTags.action(item.step))) { Button("Choisir le fichier", primary = true, onClick = actions.pickHistory) }
                    SetupStep.Battery -> if (!item.done) {
                        Box(Modifier.testTag(SetupTags.action(item.step))) { Button("Ouvrir", primary = true, onClick = actions.openBattery) }
                        Box(Modifier.testTag(SetupTags.markDone(item.step))) { Button("C’est fait", primary = false) { actions.setBatteryDone(true) } }
                    } else {
                        Box(Modifier.testTag(SetupTags.undo(item.step))) { TextAction("Décocher") { actions.setBatteryDone(false) } }
                    }
                    SetupStep.ExportFile -> if (!item.done) {
                        Box(Modifier.testTag(SetupTags.markDone(item.step))) { Button("C’est supprimé", primary = true) { actions.setExportFileDeleted(true) } }
                    } else {
                        Box(Modifier.testTag(SetupTags.undo(item.step))) { TextAction("Décocher") { actions.setExportFileDeleted(false) } }
                    }
                }
            }
        }
    }
}

/** Case ronde : pleine et cochée quand c'est fait, un simple cercle sinon. */
@Composable
private fun CheckMark(done: Boolean, label: String) {
    Box(
        Modifier
            .padding(top = 1.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(if (done) TracklyColors.Cobalt else TracklyColors.PaperHi)
            .border(1.5.dp, if (done) TracklyColors.Cobalt else TracklyColors.Line2, CircleShape)
            .semantics {
                contentDescription = label
                stateDescription = if (done) "Fait" else "À faire"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (done) GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(14.dp), stroke = 2f)
    }
}

@Composable
private fun Button(text: String, primary: Boolean, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.CardButton.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.97f)
            .semantics { role = Role.Button }
            .clip(ButtonShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.Mist)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.94f)
            .semantics { role = Role.Button }
            .heightIn(min = 44.dp)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}
