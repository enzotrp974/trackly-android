package app.trackly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.assistant.chat.DayThread
import app.trackly.ui.chat.PillButton
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Étiquettes de test de la section « Discussion ». */
object DiscussionTags {
    const val SECTION = "reglages-discussion"
    const val CLEAR = "reglages-discussion-effacer"
    const val CONFIRM = "reglages-discussion-confirmer"
    const val KEEP = "reglages-discussion-garder"
    const val DONE = "reglages-discussion-fait"
}

/** Section « Discussion » des Réglages, branchée sur le fil du jour. */
@Composable
fun DiscussionSettingsRoute(thread: DayThread) {
    val scope = rememberCoroutineScope()
    DiscussionSettingsSection(
        onClear = { report ->
            scope.launch {
                // Une panne de la base ne ferme jamais l'app : elle est dite, et rien n'a été changé (un seul UPDATE).
                val hidden = try {
                    thread.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                report(hidden)
            }
        },
    )
}

/**
 * « Effacer la discussion » : masque le fil du jour du chat, après une confirmation. Les repas ne sont pas touchés
 * et aucun message n'est supprimé de la base. [onClear] lance l'effacement et reçoit le nombre de messages masqués, ou
 * `null` si l'effacement a échoué.
 */
@Composable
fun DiscussionSettingsSection(onClear: ((Int?) -> Unit) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    // Résultat du dernier effacement : `Done(n)` ou `Failed`, `null` tant qu'il n'y en a pas.
    var result by remember { mutableStateOf<ClearResult?>(null) }
    Column(Modifier.fillMaxWidth().testTag(DiscussionTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        BasicText("DISCUSSION", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        BasicText(
            "Le chat repart de zéro chaque jour à 5 h. Tu peux aussi effacer la discussion du jour quand tu veux.",
            style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2),
        )
        Spacer(Modifier.height(12.dp))
        if (asking) {
            BasicText(
                "Effacer la discussion d’aujourd’hui ? Tes repas ne sont pas touchés.",
                style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.testTag(DiscussionTags.CONFIRM)) {
                    PillButton("Effacer", primary = true) {
                        asking = false
                        onClear { result = if (it == null) ClearResult.Failed else ClearResult.Done(it) }
                    }
                }
                Box(Modifier.testTag(DiscussionTags.KEEP)) { PillButton("Garder", primary = false) { asking = false } }
            }
        } else {
            Box(Modifier.testTag(DiscussionTags.CLEAR)) {
                PillButton("Effacer la discussion", primary = false) {
                    result = null
                    asking = true
                }
            }
        }
        result?.let { outcome ->
            Spacer(Modifier.height(10.dp))
            BasicText(
                when {
                    outcome is ClearResult.Failed -> "Impossible d’effacer la discussion pour l’instant. Rien n’a été changé."
                    (outcome as ClearResult.Done).count > 0 -> "Discussion effacée. Tes repas n’ont pas été touchés."
                    else -> "Il n’y avait rien à effacer."
                },
                style = TracklyType.Notice.copy(color = TracklyColors.Ink),
                modifier = Modifier.testTag(DiscussionTags.DONE).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private sealed interface ClearResult {
    data class Done(val count: Int) : ClearResult
    data object Failed : ClearResult
}
