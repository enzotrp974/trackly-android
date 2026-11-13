package app.trackly.ui.chat

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.GoalCard
import app.trackly.assistant.cards.GoalValues
import app.trackly.assistant.cards.formatNumber
import app.trackly.assistant.cards.ChatCard
import app.trackly.day.DayLabels
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Odometer
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/**
 * Carte de récapitulatif d'un changement d'objectif : avant, après, date d'effet, Annuler.
 * Mêmes matières que la carte d'enregistrement ; aucun mouvement propre (les chiffres de
 * l'odomètre suivent déjà la réduction des mouvements).
 */
@Composable
internal fun GoalCardView(card: GoalCard, undone: Boolean, animate: Boolean, onUndo: (ChatCard) -> Unit) {
    val date = DayLabels.inSentence(card.date)
    if (undone) {
        val label = "Objectif ${card.after.kcal.roundToInt()} kcal dès $date"
        DashedRow(tag = CardTags.UNDONE, description = "Annulé, $label") {
            BasicText(
                buildAnnotatedString {
                    append("Annulé · ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(label) }
                },
                style = TracklyType.Undone.copy(color = TracklyColors.Ink3),
            )
        }
        return
    }
    val before = card.before
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(CardTags.GOAL)
            .semantics {
                contentDescription = "Objectif modifié dès $date : " +
                    (before?.let { "avant ${spoken(it)} ; " } ?: "aucun objectif avant ; ") + "après ${spoken(card.after)}"
            }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
            }
            Spacer(Modifier.width(7.dp))
            BasicText(
                buildAnnotatedString {
                    append("Objectif · ")
                    withStyle(SpanStyle(color = TracklyColors.Ink)) { append("dès $date") }
                },
                style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt),
            )
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(TracklyColors.Ink, RoundedCornerShape(1.dp)))
        if (before != null) {
            GoalLine("Avant", before, strong = false, animate = animate)
            Box(Modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
        } else {
            BasicText(
                "Avant : aucun objectif",
                style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3),
                modifier = Modifier.padding(top = 9.dp, bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
        }
        GoalLine("Après", card.after, strong = true, animate = animate)
        if (card.macrosChanged) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                GlyphIcon(Glyph.Info, TracklyColors.Ink3, Modifier.padding(top = 2.dp).size(15.dp))
                Spacer(Modifier.width(7.dp))
                BasicText(
                    "Macros ajustées pour rester cohérentes avec les calories.",
                    style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO) { onUndo(card) }
        }
    }
}

@Composable
private fun GoalLine(label: String, values: GoalValues, strong: Boolean, animate: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            BasicText(
                label,
                style = TracklyType.CardItemDetail.copy(color = if (strong) TracklyColors.Ink else TracklyColors.Ink3, fontWeight = FontWeight(if (strong) 700 else 400)),
                modifier = Modifier.width(52.dp).padding(bottom = 4.dp),
            )
            Odometer(
                values.kcal.roundToInt(),
                if (strong) TracklyType.CardTotal else TracklyType.CardItemKcal,
                color = if (strong) TracklyColors.Ink else TracklyColors.Ink2,
                delayMs = if (animate) 200 else 0,
            )
            Spacer(Modifier.width(5.dp))
            BasicText("kcal", style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = if (strong) 3.dp else 2.dp))
        }
        BasicText(
            macros(values),
            style = TracklyType.CardMacros.copy(color = if (strong) TracklyColors.Ink else TracklyColors.Ink2),
            modifier = Modifier.padding(start = 52.dp, top = 2.dp),
        )
    }
}

private fun macros(v: GoalValues): String {
    val parts = listOfNotNull(
        v.proteinG?.let { "P ${formatNumber(it)} g" },
        v.carbsG?.let { "G ${formatNumber(it)} g" },
        v.fatG?.let { "L ${formatNumber(it)} g" },
    )
    return if (parts.isEmpty()) "Pas d’objectif de macros" else parts.joinToString("  ·  ")
}

private fun spoken(v: GoalValues): String {
    val parts = listOfNotNull(
        "${v.kcal.roundToInt()} kilocalories",
        v.proteinG?.let { "protéines ${formatNumber(it)} grammes" },
        v.carbsG?.let { "glucides ${formatNumber(it)} grammes" },
        v.fatG?.let { "lipides ${formatNumber(it)} grammes" },
    )
    return parts.joinToString(", ")
}
