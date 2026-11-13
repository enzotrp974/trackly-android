package app.trackly.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test du sélecteur de raisonnement. */
object EffortTags {
    const val CHIP = "chat-raisonnement"
    const val STRIP = "chat-raisonnement-choix"

    /** Une option du sélecteur ouvert, par code du niveau (`low`, `medium`, `high`, `xhigh`, `max`). */
    fun option(effort: ReasoningEffort) = "chat-raisonnement-${effort.code}"
}

private val PillShape = RoundedCornerShape(999.dp)

/**
 * Niveau de raisonnement du prochain message (bas, moyen, élevé, très élevé, maximum).
 * Fermé, c'est une petite pastille discrète au-dessus de la saisie ; elle prend la couleur de
 * l'accent quand le niveau n'est pas « moyen », pour qu'on voie qu'il est armé. Un toucher
 * l'ouvre, un second choix la referme : deux touchers pour changer de niveau.
 */
@Composable
fun EffortSelector(effort: ReasoningEffort, onSelect: (ReasoningEffort) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        if (!open) {
            val armed = effort != ReasoningEffort.Default
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .testTag(EffortTags.CHIP)
                    .pressable({ open = true }, pressedScale = 0.96f)
                    .semantics {
                        contentDescription = "Niveau de raisonnement : ${effort.label}. Toucher pour changer."
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    "Raisonnement : ${effort.label}",
                    style = TracklyType.GaugeCaption.copy(color = if (armed) TracklyColors.Cobalt else TracklyColors.Ink2),
                    modifier = Modifier
                        .clip(PillShape)
                        .background(if (armed) TracklyColors.Mist else TracklyColors.PaperHi)
                        .border(TracklySpacing.Hairline, if (armed) TracklyColors.Cobalt else TracklyColors.Line2, PillShape)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().testTag(EffortTags.STRIP).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (level in ReasoningEffort.entries) {
                    val selected = level == effort
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .testTag(EffortTags.option(level))
                            .pressable({
                                onSelect(level)
                                open = false
                            }, pressedScale = 0.95f)
                            .semantics {
                                this.selected = selected
                                contentDescription = "Raisonnement ${level.label.lowercase()}"
                                stateDescription = if (selected) "Choisi" else "Non choisi"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            level.label,
                            style = TracklyType.GaugeCaption.copy(color = if (selected) TracklyColors.PaperHi else TracklyColors.Ink2),
                            modifier = Modifier
                                .clip(PillShape)
                                .background(if (selected) TracklyColors.Cobalt else TracklyColors.PaperHi)
                                .border(TracklySpacing.Hairline, if (selected) TracklyColors.Cobalt else TracklyColors.Line2, PillShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}
