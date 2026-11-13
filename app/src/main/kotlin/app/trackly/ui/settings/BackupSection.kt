package app.trackly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.backup.BackupLabels
import app.trackly.backup.BackupStatus
import app.trackly.backup.BackupUiState
import app.trackly.backup.RestoreChoice
import app.trackly.backup.TRASHED_FOLDER
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Étiquettes de test de la sauvegarde. */
object BackupTags {
    const val SECTION = "reglages-sauvegarde"
    const val EXPORT = "reglages-sauvegarde-export"
}

/** Ce que l'écran Sauvegarde peut demander. */
class BackupActions(
    val connect: () -> Unit = {},
    val backupNow: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val openRestore: () -> Unit = {},
    val closeRestore: () -> Unit = {},
    val choose: (RestoreChoice) -> Unit = {},
    val cancelChoice: () -> Unit = {},
    val confirmRestore: () -> Unit = {},
    val pickFile: () -> Unit = {},
    val exportToFile: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val dismissHiddenCopies: () -> Unit = {},
)

/** Des changements attendent depuis plus de 2 jours d'être copiés (une copie a déjà réussi). */
private fun isStale(status: BackupStatus, now: Instant): Boolean =
    status.lastSuccessAt != null && status.behindFor(now, Duration.ofHours(48))

/**
 * Section « Sauvegarde » autonome, placée dans la colonne de l'écran Réglages : copie sur Drive quelques minutes
 * après chaque changement, restauration à une date choisie.
 */
@Composable
fun BackupSettingsSection(state: BackupUiState, actions: BackupActions, now: Instant, zone: ZoneId) {
    val status = state.status
    Column(Modifier.fillMaxWidth().testTag(BackupTags.SECTION)) {
        // ───── Copie sur Drive ─────
        SectionTitle("Connexion")
        Feedback(state, nearRestore = false, nearExport = false, actions)
        if (!status.connected) {
            Spacer(Modifier.height(6.dp))
            SettingsButton("Connecter Google Drive", primary = true, enabled = state.busy == null, onClick = actions.connect)
        } else {
            status.accountEmail?.let { Body("Compte Google : $it") }
            Spacer(Modifier.height(6.dp))
            val last = status.lastSuccessAt
            Line(
                if (last != null) "Dernière sauvegarde : ${BackupLabels.moment(last, zone)}" else "Aucune sauvegarde pour l'instant",
                strong = true,
            )
            if (isStale(status, now)) Body("Des changements attendent depuis plus de 2 jours d’être copiés.")
            if (status.refused) {
                Body("Copie refusée (${BackupLabels.moment(status.lastRefusalAt!!, zone)}) : ${status.lastRefusalMessage}.")
            }
            if (status.failing) {
                Body("Dernier essai (${BackupLabels.moment(status.lastFailureAt!!, zone)}) : ${status.lastFailureMessage}.")
            }
            Body("Copie quelques minutes après chaque changement · les 10 dernières et 1 par jour sur 30 jours gardées.")
            Spacer(Modifier.height(14.dp))
            if (status.needsReconnect) {
                SettingsButton("Reconnecter Google Drive", primary = true, enabled = state.busy == null, onClick = actions.connect)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton("Sauvegarder maintenant", primary = !status.needsReconnect, enabled = state.busy == null, onClick = actions.backupNow)
                TextAction("Déconnecter", enabled = state.busy == null, onClick = actions.disconnect)
            }
        }

        // ───── Fichier : un secours qui ne dépend pas de Google ─────
        Column(Modifier.fillMaxWidth().testTag(BackupTags.EXPORT)) {
            SectionTitle("Fichier")
            Body("Une copie de tes données dans un fichier, à l'endroit que tu choisis : un secours qui ne dépend pas de Google. Ta clé OpenAI n'y est pas.")
            Feedback(state, nearRestore = false, nearExport = true, actions)
            Spacer(Modifier.height(8.dp))
            SettingsButton("Exporter dans un fichier", enabled = state.busy == null, onClick = actions.exportToFile)
        }

        // ───── Restaurer ─────
        SectionTitle("Restaurer")
        status.lastRestoreAt?.let { at ->
            Body("Dernière restauration : ${status.lastRestoreLabel ?: "sauvegarde"} (faite le ${BackupLabels.inSentence(at, zone)}).")
        }
        status.lastRestoreDiscardedAt?.takeIf { at -> status.lastRestoreAt?.isBefore(at) ?: true }?.let { at ->
            Body("Restauration non faite (${BackupLabels.moment(at, zone)}) : ${status.lastRestoreDiscardedMessage}.")
        }
        status.hiddenCopiesAt?.let { at ->
            // Le seul guide quand Trackly ne voit plus ses anciennes copies ; il reste jusqu'à une restauration.
            Body(
                "Si tu avais des sauvegardes Trackly avant le ${BackupLabels.inSentence(at, zone)}, Trackly ne les voit plus : " +
                    "elles restent dans ton Drive (ancien dossier « Trackly »). Pour en reprendre une : « Depuis un fichier », " +
                    "puis Drive dans le sélecteur. $TRASHED_FOLDER",
            )
            TextAction("Fermer ce message", enabled = state.busy == null, onClick = actions.dismissHiddenCopies)
        }
        Feedback(state, nearRestore = true, nearExport = false, actions)
        Spacer(Modifier.height(14.dp))
        val confirming = state.confirming
        val choices = state.choices
        when {
            confirming != null -> Confirm(confirming, state.busy == null, actions)
            choices != null -> Choices(choices, state.folderMissing, status.connected, actions)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton("Choisir une sauvegarde", enabled = state.busy == null, onClick = actions.openRestore)
                TextAction("Depuis un fichier", enabled = state.busy == null, onClick = actions.pickFile)
            }
        }

    }
}

/** Message et opération en cours, à côté des boutons qui les ont lancés. */
@Composable
private fun Feedback(state: BackupUiState, nearRestore: Boolean, nearExport: Boolean, actions: BackupActions) {
    state.notice?.takeIf { it.nearRestore == nearRestore && it.nearExport == nearExport }?.let { notice ->
        Spacer(Modifier.height(2.dp))
        Notice(notice.text, notice.error, actions.dismissNotice)
        Spacer(Modifier.height(8.dp))
    }
    state.busy?.takeIf { it.nearRestore == nearRestore && it.nearExport == nearExport }?.let { busy ->
        Spacer(Modifier.height(2.dp))
        BasicText(
            busy.label,
            style = TracklyType.MealDetail.copy(color = TracklyColors.Cobalt),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Choices(choices: List<RestoreChoice>, folderMissing: Boolean, connected: Boolean, actions: BackupActions) {
    if (folderMissing) {
        Body(
            "Dossier « Trackly » introuvable sur Drive. Après une réinstallation, Trackly peut ne plus voir ses " +
                "anciens fichiers. « Depuis un fichier » ouvre le sélecteur du téléphone : choisis-y Drive directement, " +
                "puis la sauvegarde voulue dans le dossier Trackly. Déconnecter puis Connecter Google Drive recrée un dossier pour les prochaines copies.",
        )
        Spacer(Modifier.height(10.dp))
    } else if (!connected) {
        Body("Google Drive n'est pas connecté : seules les copies gardées sur le téléphone sont proposées.")
        Spacer(Modifier.height(10.dp))
    }
    if (choices.isEmpty()) {
        Line("Aucune sauvegarde trouvée.")
    }
    for (choice in choices) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable({ actions.choose(choice) }, pressedScale = 0.985f)
                .semantics(mergeDescendants = true) { contentDescription = "Restaurer : ${choice.title}, ${choice.detail}" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(choice.title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink))
                BasicText(choice.detail, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
            }
            BasicText("›", style = TracklyType.MealTitle.copy(color = TracklyColors.Ink3))
        }
        Hairline()
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction("Depuis un fichier", onClick = actions.pickFile)
        TextAction("Fermer la liste", onClick = actions.closeRestore)
    }
}

private val CardShape = RoundedCornerShape(14.dp)

@Composable
private fun Confirm(choice: RestoreChoice, enabled: Boolean, actions: BackupActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(16.dp),
    ) {
        BasicText("Restaurer « ${choice.title} » ?", style = TracklyType.MealTitle.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(6.dp))
        Body(
            "Les données actuelles du téléphone seront remplacées par celles de cette sauvegarde. " +
                "Une copie de sécurité de l'état actuel reste sur le téléphone. Trackly va redémarrer.",
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton("Restaurer", primary = true, enabled = enabled, onClick = actions.confirmRestore)
            TextAction("Annuler", enabled = enabled, onClick = actions.cancelChoice)
        }
    }
}

// ───── Éléments communs ─────

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(26.dp))
    BasicText(title.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Body(text: String) {
    BasicText(text, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
private fun Line(text: String, strong: Boolean = false) {
    BasicText(
        text,
        style = (if (strong) TracklyType.MealTitle else TracklyType.MealDetail).copy(color = TracklyColors.Ink),
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun Notice(text: String, error: Boolean, onDismiss: () -> Unit) {
    val label = if (error) "À noter : $text" else text
    BasicText(
        label,
        style = TracklyType.MealDetail.copy(color = if (error) TracklyColors.Ink else TracklyColors.Cobalt),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (error) TracklyColors.Paper else TracklyColors.Mist)
            .border(TracklySpacing.Hairline, if (error) TracklyColors.Line2 else TracklyColors.Mist, CardShape)
            .pressable(onDismiss, pressedScale = 0.99f)
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            .padding(14.dp),
    )
}

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun SettingsButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.MealTitle.copy(
            color = when {
                !enabled -> TracklyColors.Ink3
                primary -> TracklyColors.PaperHi
                else -> TracklyColors.Cobalt
            },
        ),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.97f, enabled = enabled)
            .clip(ButtonShape)
            .background(
                when {
                    !enabled -> TracklyColors.TrackEmpty
                    primary -> TracklyColors.Cobalt
                    else -> TracklyColors.Mist
                },
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

@Composable
private fun TextAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = if (enabled) TracklyColors.Cobalt else TracklyColors.Ink3),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.94f, enabled = enabled)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    )
}
