package app.trackly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.core.goal.TemporaryPhase
import kotlinx.coroutines.launch
import app.trackly.review.ReportFormat
import app.trackly.review.page.Advice
import app.trackly.review.page.AdviceActions
import app.trackly.review.page.AdviceKind
import app.trackly.review.page.AdviceState
import app.trackly.review.page.AdviceText
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

object AdviceTags {
    const val CARD = "conseil-carte"
    const val APPLY = "conseil-appliquer"
    const val APPLIED = "conseil-applique"
    const val TOGGLE = "conseil-annuler"
    const val STEPS = "conseil-pas"
    const val DATE = "conseil-date"
    const val NOTICE = "conseil-message"
}

/**
 * Boutons des conseils, branchés par l'écran : [states] vient d'[AdviceActions] ; `null` (aperçu, test sans moteur) : les
 * cartes sont montrées sans bouton.
 */
class AdviceUi(
    val states: Map<String, AdviceState>,
    val notice: String? = null,
    val onApply: (Advice) -> Unit = {},
    val onToggle: (AdviceState) -> Unit = {},
)

/** Les conseils de la page ou d'une carte du chat : objectif temporaire en carte, pas et date en phrases. */
@Composable
internal fun AdviceList(advice: List<Advice>, ui: AdviceUi?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (a in advice) {
            val state = ui?.states?.get(AdviceActions.key(a))
            when (a.kind) {
                AdviceKind.Temporary -> TemporaryCard(a, state, ui)
                AdviceKind.Steps -> BasicText(
                    a.title,
                    style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag(AdviceTags.STEPS),
                )
                AdviceKind.Date -> DateShift(a, state, ui)
            }
        }
        ui?.notice?.let {
            BasicText(it, style = TracklyType.HintBody.copy(color = TracklyColors.Ink), modifier = Modifier.testTag(AdviceTags.NOTICE))
        }
    }
}

private val Shape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun TemporaryCard(a: Advice, state: AdviceState?, ui: AdviceUi?) {
    Column(
        Modifier.fillMaxWidth().testTag(AdviceTags.CARD)
            .semantics(mergeDescendants = false) { contentDescription = "${a.kicker} : ${a.title}, ${a.big} ${a.unit}. ${a.rows.joinToString(". ")}" }
            .clip(Shape).background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, Shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(a.kicker, style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt))
                BasicText(a.title, style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(a.big, style = TracklyType.CardTotal.copy(color = TracklyColors.Ink))
                if (a.unit.isNotEmpty()) BasicText(a.unit, style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(start = 3.dp, bottom = 4.dp))
            }
        }
        // Filet encre de 4 dp, comme la carte « Proposition ».
        Box(Modifier.padding(top = 12.dp, bottom = 4.dp).fillMaxWidth().height(4.dp).background(TracklyColors.Ink, RoundedCornerShape(2.dp)))
        for (row in a.rows) BasicText(row, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(vertical = 3.dp))
        if (ui == null || state == null) return@Column
        Spacer(Modifier.height(10.dp))
        when (state) {
            AdviceState.Open -> TonalButton("Appliquer", "Appliquer l’objectif temporaire de ${a.big} kcal") { ui.onApply(a) }
            AdviceState.Expired -> BasicText(
                "Proposition passée : demande-en une nouvelle dans le chat du bilan.",
                style = TracklyType.HintBody.copy(color = TracklyColors.Ink3),
                modifier = Modifier.testTag(AdviceTags.APPLIED),
            )
            is AdviceState.Temporary -> {
                val s = state.status
                val back = "${ReportFormat.int(s.baseKcal)} ${AdviceText.dayName(s.returnOn)}"
                val (text, done, button) = if (!state.matches) {
                    Triple("Un autre objectif temporaire est appliqué : ${ReportFormat.int(s.temporaryKcal)} kcal, retour à $back", s.inEffect, if (s.inEffect) "Annuler" else null)
                } else when (s.phase) {
                    TemporaryPhase.Scheduled, TemporaryPhase.Running -> Triple("Appliqué · retour à $back", true, "Annuler")
                    TemporaryPhase.Finished -> Triple("Terminé · retour à ${ReportFormat.int(s.baseKcal)} le ${AdviceText.dayName(s.returnOn)}", true, null)
                    TemporaryPhase.Cancelled -> Triple("Annulé · ton objectif reste ${ReportFormat.int(s.baseKcal)}", false, "Rétablir")
                    TemporaryPhase.Stopped -> Triple("Arrêté · ${ReportFormat.int(s.baseKcal)} depuis ${AdviceText.dayName(s.stoppedOn!!)}", false, "Rétablir")
                }
                AppliedRow(text, done, button?.takeIf { s.canToggle }) { ui.onToggle(state) }
            }
            is AdviceState.DateSet -> Unit
        }
    }
}

/** « Ou garder ton rythme et viser le 13 mars », avec le bouton secondaire « Changer la date ». */
@Composable
private fun DateShift(a: Advice, state: AdviceState?, ui: AdviceUi?) {
    Column(Modifier.fillMaxWidth().testTag(AdviceTags.DATE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            ui == null || state == null || state == AdviceState.Open -> {
                BasicText(a.title, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
                if (ui != null && state == AdviceState.Open) OutlineButton("Changer la date", Glyph.Calendar, "Changer la date visée : ${a.title}") { ui.onApply(a) }
            }
            state is AdviceState.DateSet -> {
                val date = a.newDate?.let { ReportFormat.dayMonthYear(java.time.LocalDate.parse(it)) }.orEmpty()
                AppliedRow("Objectif : ${ReportFormat.one(a.targetKg ?: 0.0).removeSuffix(",0")} kg au $date", true, "Annuler") { ui.onToggle(state) }
            }
        }
    }
}

@Composable
private fun AppliedRow(text: String, done: Boolean, button: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(AdviceTags.APPLIED), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            if (done) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(11.dp), stroke = 2.6f)
                }
                Spacer(Modifier.width(7.dp))
            }
            BasicText(text, style = TracklyType.CardButton.copy(color = if (done) TracklyColors.Cobalt else TracklyColors.Ink2))
        }
        if (button != null) {
            Spacer(Modifier.width(8.dp))
            OutlineButton(button, Glyph.Undo, button, onClick)
        }
    }
}

@Composable
private fun TonalButton(text: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(AdviceTags.APPLY)
            .pressable(onClick, pressedScale = 0.97f)
            .semantics { contentDescription = description }
            .clip(ButtonShape).background(TracklyColors.Mist)
            .border(TracklySpacing.Hairline, TracklyColors.Cobalt.copy(alpha = 0.22f), ButtonShape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Check, TracklyColors.Cobalt, Modifier.size(18.dp), stroke = 1.8f)
        Spacer(Modifier.width(8.dp))
        BasicText(text, style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt))
    }
}

@Composable
private fun OutlineButton(text: String, glyph: Glyph, description: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).testTag(if (glyph == Glyph.Undo) AdviceTags.TOGGLE else AdviceTags.APPLY)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics { contentDescription = description }
            .clip(ButtonShape)
            .border(2.dp, TracklyColors.Ink, ButtonShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, TracklyColors.Ink, Modifier.size(16.dp), stroke = 1.6f)
        Spacer(Modifier.width(7.dp))
        BasicText(text, style = TracklyType.CardButton.copy(color = TracklyColors.Ink))
    }
}

/**
 * Boutons des conseils d'une page ou d'une carte, branchés sur le moteur : l'état se relit après chaque action (bouton, chat,
 * annulation ailleurs), et un refus du moteur s'affiche sous les conseils.
 */
@Composable
fun rememberAdviceUi(engine: app.trackly.core.engine.TracklyEngine, advice: List<Advice>): AdviceUi? {
    if (advice.none { it.kind != AdviceKind.Steps }) return null
    val actions = androidx.compose.runtime.remember(engine) { AdviceActions(engine) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val notice = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val recent by engine.observeRecentActions(1).collectAsState(initial = emptyList())
    // Le passage à 5 h change l'étape (prévu, en cours, terminé, proposition passée) sans aucune action : on relit chaque minute.
    val tick by androidx.compose.runtime.produceState(0L) { while (true) { kotlinx.coroutines.delay(60_000); value += 1 } }
    val states by androidx.compose.runtime.produceState<Map<String, AdviceState>?>(null, advice, recent, tick) { value = actions.states(advice) }
    val current = states ?: return null
    return AdviceUi(
        states = current,
        notice = notice.value,
        onApply = { a -> scope.launch { notice.value = actions.apply(a) } },
        onToggle = { s -> scope.launch { notice.value = actions.toggle(s) } },
    )
}
