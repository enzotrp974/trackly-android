package app.trackly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.ReferenceWeights
import app.trackly.core.model.ActionId
import app.trackly.review.page.ReferenceStatus
import app.trackly.review.page.ReferenceWeight
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.launch

object ReferenceTags {
    const val UNDO = "reference-annuler"
    const val UNDONE = "reference-annule"
    const val NOTICE = "reference-message"
}

/**
 * Bouton Annuler du poids de référence, branché par l'écran : [undone] se lit en direct dans le journal des actions ; [notice]
 * est la réponse d'un refus (« Annule d'abord … »). `null` (aperçu, test sans moteur, rien à annuler) : pas de bouton.
 */
class ReferenceUi(
    val undone: Boolean,
    val notice: String? = null,
    val onUndo: () -> Unit = {},
)

/** État du bouton Annuler du bloc [reference] ; `null` s'il n'y a rien à annuler. */
@Composable
fun rememberReferenceUi(engine: TracklyEngine, reference: ReferenceWeight?): ReferenceUi? {
    val id = reference?.actionId?.takeIf { reference.status == ReferenceStatus.Updated } ?: return null
    val revision by engine.revision.collectAsState()
    var undone by remember(id) { mutableStateOf<Boolean?>(null) }
    var notice by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, revision) {
        undone = engine.undoStatuses(listOf(ActionId(id)))[ActionId(id)]?.undone
        // Une action appliquée ailleurs (l'objectif temporaire annulé) rend le dernier refus périmé.
        notice = null
    }
    val known = undone ?: return null
    return ReferenceUi(known, notice) {
        scope.launch {
            val status = engine.undoStatuses(listOf(ActionId(id)))[ActionId(id)] ?: return@launch
            if (status.undone) return@launch
            notice = when (val outcome = engine.execute(Undo(status.head))) {
                is ActionOutcome.Applied -> null
                is ActionOutcome.Rejected -> outcome.rejection.message
            }
        }
    }
}

private val Shape = RoundedCornerShape(12.dp)

/**
 * Le poids de référence sur la page du bilan : « 72,4 → 71,9 kg », d'où vient le poids, les grammes
 * qui changent et « calories inchangées », puis Annuler. Sans changement (pas de pesée, même poids, garde-fou), le bloc le dit
 * en encre, sans pastille ni jaune : ce n'est jamais une alerte.
 */
@Composable
internal fun ReferenceBlock(r: ReferenceWeight, ui: ReferenceUi?) {
    val updated = r.status == ReferenceStatus.Updated
    Column(
        Modifier.fillMaxWidth().testTag(ReportPageTags.REFERENCE)
            .clip(Shape).background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, Shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(if (updated) "POIDS DE RÉFÉRENCE · MIS À JOUR" else "POIDS DE RÉFÉRENCE", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
        BasicText(
            if (updated) "${ReferenceWeights.label(r.beforeKg)} → ${ReferenceWeights.label(r.afterKg)} kg" else "${ReferenceWeights.label(r.afterKg)} kg · inchangé",
            style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
        )
        if (r.source.isNotBlank()) BasicText(r.source, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
        // Une valeur ne se coupe jamais en fin de ligne (« 105 → 104 g ») : espaces insécables autour de la flèche et avant « g ».
        BasicText(
            r.lines.joinToString(" · ") { it.replace(" → ", "\u00A0→\u00A0").replace(" g", "\u00A0g").replace(" (", "\u00A0(") },
            style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2),
        )
        if (!updated || ui == null) return@Column
        Spacer(Modifier.size(6.dp))
        if (ui.undone) {
            BasicText(
                "Annulé · ton poids de référence reste ${ReferenceWeights.label(r.beforeKg)} kg",
                style = TracklyType.CardButton.copy(color = TracklyColors.Ink2),
                modifier = Modifier.testTag(ReferenceTags.UNDONE),
            )
        } else {
            UndoButton(onClick = ui.onUndo, description = "Annuler la mise à jour du poids de référence, revenir à ${ReferenceWeights.label(r.beforeKg)} kg")
        }
        ui.notice?.let {
            BasicText(it, style = TracklyType.HintBody.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 4.dp).testTag(ReferenceTags.NOTICE))
        }
    }
}

@Composable
private fun UndoButton(onClick: () -> Unit, description: String) {
    Row(
        Modifier.heightIn(min = 40.dp).testTag(ReferenceTags.UNDO)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics { contentDescription = description }
            .clip(Shape)
            .border(2.dp, TracklyColors.Ink, Shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Undo, TracklyColors.Ink, Modifier.size(16.dp), stroke = 1.6f)
        Spacer(Modifier.width(7.dp))
        BasicText("Annuler", style = TracklyType.CardButton.copy(color = TracklyColors.Ink))
    }
}
