package app.trackly.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.DishCard
import app.trackly.assistant.cards.ProvisionsCard
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Carte d'une opération sur un plat récurrent (créé, variante, défaut, pause…), avec Annuler. */
@Composable
internal fun DishCardView(card: DishCard, undone: Boolean, onUndo: (ChatCard) -> Unit) {
    if (undone) {
        UndoneLine("${card.status} : ${card.name}", "Annulé, ${card.status}, ${card.name}")
        return
    }
    CardFrame(CardTags.DISH, "${card.status}, ${card.name}", card.status) {
        BasicText(card.name, style = TracklyType.CardItem.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 9.dp))
        card.detail?.let { BasicText(it, style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(top = 3.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO) { onUndo(card) }
        }
    }
}

/** Carte des provisions retenues : « j'ai », « plus de », avec Annuler. Pas une consommation. */
@Composable
internal fun ProvisionsCardView(card: ProvisionsCard, undone: Boolean, onUndo: (ChatCard) -> Unit) {
    val summary = listOfNotNull(
        card.have.takeIf { it.isNotEmpty() }?.let { "J’ai : ${it.joinToString(", ")}" },
        card.out.takeIf { it.isNotEmpty() }?.let { "Plus de : ${it.joinToString(", ")}" },
    ).joinToString(". ")
    if (undone) {
        UndoneLine("Provisions : $summary", "Annulé, provisions, $summary")
        return
    }
    CardFrame(CardTags.PROVISIONS, "Provisions, $summary", "Provisions retenues") {
        if (card.have.isNotEmpty()) {
            BasicText("J’ai", style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3), modifier = Modifier.padding(top = 9.dp))
            BasicText(card.have.joinToString(", "), style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
        }
        if (card.out.isNotEmpty()) {
            BasicText("Plus de", style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3), modifier = Modifier.padding(top = 9.dp))
            BasicText(card.out.joinToString(", "), style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
        }
        if (card.affectedDishes.isNotEmpty()) {
            BasicText(
                "Utilisé dans : ${card.affectedDishes.joinToString(", ")}.",
                style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        BasicText("Ce n’est pas compté comme un repas.", style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink3), modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO) { onUndo(card) }
        }
    }
}

@Composable
private fun CardFrame(tag: String, description: String, status: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(tag)
            .semantics { contentDescription = description }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
            }
            Spacer(Modifier.width(7.dp))
            BasicText(status, style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt))
        }
        content()
    }
}

@Composable
private fun UndoneLine(text: String, description: String) {
    DashedRow(tag = CardTags.UNDONE, description = description) {
        BasicText(
            buildAnnotatedString {
                append("Annulé · ")
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text) }
            },
            style = TracklyType.Undone.copy(color = TracklyColors.Ink3),
        )
    }
}
