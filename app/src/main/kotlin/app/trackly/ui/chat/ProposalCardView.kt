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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.day.QuantityText
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Odometer
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/**
 * Carte de proposition, de la famille de la carte d'enregistrement : même papier, mêmes filets d'étiquette
 * nutritionnelle, mêmes macros. Ce qui la distingue : la pastille est vide (rien n'est compté), le nom de l'idée
 * vient en tête, et un seul bouton, « Noter ». Une valeur inconnue s'affiche « ? » et se dit sous les macros ;
 * le total devient une borne basse (« ≥ »).
 */
@Composable
internal fun ProposalCardView(card: ProposalCard, noted: Boolean, animate: Boolean, onNote: (ChatCard) -> Unit) {
    val play = animate && !LocalMotion.current.reduced
    val total = card.totalKcal.roundToInt()
    val atLeast = !card.kcalComplete
    // Aucune valeur connue : « ? kcal », plutôt qu'un « ≥ 0 » trompeur.
    val none = card.items.none { it.kcal != null }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(CardTags.PROPOSAL)
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Part(0, play) {
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "Proposition, ${card.momentLabel}, ${card.title}, " +
                        (if (none) "kilocalories inconnues" else (if (atLeast) "au moins " else "") + "$total kilocalories") + (if (noted) ", notée" else ", pas encore comptée")
                },
            ) {
                // Grande police : « Proposition » garde sa ligne entière, le total passe dessous (jamais de mot coupé).
                val stacked = LocalDensity.current.fontScale >= 1.5f
                val status = @Composable {
                    Row(Modifier.padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Pastille vide : une idée, pas un enregistrement.
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, TracklyColors.Cobalt, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlyphIcon(Glyph.Bowl, TracklyColors.Cobalt, Modifier.size(12.dp), stroke = 2f)
                        }
                        Spacer(Modifier.width(7.dp))
                        BasicText(
                            buildAnnotatedString {
                                append("Proposition · ")
                                withStyle(SpanStyle(color = TracklyColors.Ink)) { append(card.momentLabel) }
                            },
                            style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt),
                        )
                    }
                }
                val totalRow = @Composable {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (atLeast && !none) {
                            BasicText("≥", style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink3), modifier = Modifier.padding(bottom = 3.dp, end = 5.dp))
                        }
                        if (none) {
                            BasicText("?", style = TracklyType.CardTotal.copy(color = TracklyColors.Ink3))
                        } else {
                            Odometer(total, TracklyType.CardTotal, delayMs = if (play) 500 else 0)
                        }
                        Spacer(Modifier.width(5.dp))
                        BasicText("kcal", style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                if (stacked) {
                    status()
                    totalRow()
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) { status() }
                        totalRow()
                    }
                }
                BasicText(card.title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 2.dp))
            }
        }
        Rule(5.dp, play, delayMs = 350)
        card.items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
            Part(2 + i, play) {
                val kcal = item.kcal
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp, bottom = 8.dp)
                        .clearAndSetSemantics {
                            contentDescription = item.label + item.quantity?.let { ", $it" }.orEmpty() +
                                (if (kcal != null) ", ${kcal.roundToInt()} kilocalories" else ", valeur inconnue")
                        },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicText(
                        buildAnnotatedString {
                            append(item.label)
                            item.quantity?.let(QuantityText::agree)?.let { q ->
                                withStyle(SpanStyle(color = TracklyColors.Ink3, fontWeight = TracklyType.CardItemDetail.fontWeight)) { append("  $q") }
                            }
                        },
                        style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                    )
                    if (kcal != null) {
                        Odometer(kcal.roundToInt(), TracklyType.CardItemKcal, delayMs = if (play) 950 + i * 150 else 0)
                    } else {
                        BasicText("?", style = TracklyType.CardItemKcal.copy(color = TracklyColors.Ink3))
                    }
                }
            }
        }
        Rule(2.dp, play, delayMs = 900)
        Part(2 + card.items.size, play) { MacroRow(card.protein, card.carbs, card.fat) }
        val unknown = card.unknownItems
        if (unknown.isNotEmpty()) {
            Part(3 + card.items.size, play) { UnknownValues(card, unknown.map { it.label + it.unknown?.let { why -> " ($why)" }.orEmpty() }) }
        }
        Part(4 + card.items.size, play) {
            if (noted) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 40.dp)
                        .testTag(CardTags.PROPOSAL_NOTED)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(11.dp), stroke = 2.6f)
                    }
                    Spacer(Modifier.width(7.dp))
                    BasicText("Noté dans ta journée", style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt))
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    CardButton("Noter", Glyph.Check, ghost = false, tag = CardTags.PROPOSAL_NOTE, description = "Noter la proposition ${card.title}") { onNote(card) }
                }
            }
        }
    }
}

/** Valeurs inconnues, dites en clair : rien n'est inventé, le total est une borne basse. */
@Composable
private fun UnknownValues(card: ProposalCard, lines: List<String>) {
    val how = if (card.notableLocally) "" else " Trackly l’estimera quand tu la noteras."
    Row(
        Modifier
            .fillMaxWidth()
            .drawTopLine()
            .padding(top = 9.dp, bottom = 2.dp)
            .testTag(CardTags.PROPOSAL_UNKNOWN),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(Glyph.Info, TracklyColors.Ink3, Modifier.padding(top = 2.dp).size(15.dp))
        Spacer(Modifier.width(7.dp))
        BasicText(
            "Valeur inconnue : " + lines.joinToString(", ") + "." + how,
            style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2),
        )
    }
}
