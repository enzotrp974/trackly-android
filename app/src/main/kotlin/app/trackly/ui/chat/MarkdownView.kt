package app.trackly.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.trackly.assistant.markdown.Align
import app.trackly.assistant.markdown.Inline
import app.trackly.assistant.markdown.MdBlock
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/**
 * Réponse libre mise en page, dans le style de la carte d'enregistrement : titres par la graisse,
 * listes à puces discrètes, tableaux à filets (épais sous l'en-tête, fins entre les lignes), chiffres alignés.
 * Un tableau plus large que la bulle défile dans son propre bloc, la page ne bouge pas.
 *
 * [ink] : couleur du texte ; [cancelled] : tout est barré (réponse dont l'action a été annulée).
 */
@Composable
internal fun MarkdownBlocks(blocks: List<MdBlock>, ink: Color = TracklyColors.Ink, cancelled: Boolean = false, fill: Boolean = true) {
    // Une réponse faite de phrases garde la largeur de son texte, comme la bulle d'avant ; une réponse structurée remplit la bulle.
    Column(if (fill) Modifier.fillMaxWidth() else Modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(if (block is MdBlock.Heading) 14.dp else 9.dp))
            MarkdownBlock(block, ink, cancelled)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, ink: Color, cancelled: Boolean) {
    val strike = if (cancelled) TextDecoration.LineThrough else null
    // Couleur secondaire (en-têtes de tableau, numéros, citations) : grisée elle aussi dans une réponse annulée.
    val muted = if (cancelled) TracklyColors.Ink3 else TracklyColors.Ink2
    when (block) {
        is MdBlock.Paragraph -> BasicText(block.inlines.annotated(), style = TracklyType.Bubble.copy(color = ink, textDecoration = strike))
        is MdBlock.Heading -> BasicText(
            block.inlines.annotated(),
            style = (if (block.level <= 2) TracklyType.ReplyHeading else TracklyType.ReplySubheading).copy(color = ink, textDecoration = strike),
            modifier = Modifier.testTag(ChatTags.REPLY_HEADING),
        )
        is MdBlock.ListBlock -> ListView(block, ink, muted, strike)
        is MdBlock.Table -> TableView(block, ink, muted, strike)
        // Filet dessiné, pas mesuré : une citation très longue n'a pas de hauteur fixée à porter (CHAT-06).
        is MdBlock.Quote -> Row(
            Modifier.drawBehind { drawRect(TracklyColors.Line2, size = Size(QuoteBar.roundToPx().toFloat(), size.height)) },
        ) {
            Spacer(Modifier.width(QuoteBar + 11.dp))
            Box(Modifier.weight(1f)) { MarkdownBlocks(block.blocks, muted, cancelled) }
        }
        is MdBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TracklyColors.TrackEmpty)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            BasicText(block.text, style = TracklyType.ReplyCode.copy(color = ink, textDecoration = strike), softWrap = false)
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(TracklySpacing.Hairline).background(TracklyColors.Line))
    }
}

@Composable
private fun ListView(block: MdBlock.ListBlock, ink: Color, muted: Color, strike: TextDecoration?) {
    Column(Modifier.fillMaxWidth().testTag(ChatTags.REPLY_LIST), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (item in block.items) {
            Row(Modifier.padding(start = 16.dp * item.level)) {
                val marker = item.marker ?: if (item.level == 0) "•" else "–"
                BasicText(
                    marker,
                    style = TracklyType.ReplyListMarker.copy(color = if (item.marker == null) TracklyColors.Ink3 else muted),
                    modifier = Modifier.widthIn(min = if (item.marker == null) 16.dp else 22.dp).padding(end = 6.dp),
                )
                BasicText(item.inlines.annotated(), style = TracklyType.Bubble.copy(color = ink, textDecoration = strike), modifier = Modifier.weight(1f))
            }
        }
    }
}

private val QuoteBar = 2.dp

/** Largeur au-delà de laquelle une cellule passe à la ligne : la première colonne (le nom) a droit à plus. */
private val FirstColumnCap = 190.dp
private val ColumnCap = 140.dp
private val WordCap = 260.dp
private val ColumnGap = 14.dp
private val CellPadding = 8.dp
private val HeaderRule = 1.5.dp
private val EdgeFade = 28.dp

/** Part de la colonne coupée qui reste visible au bord d'un tableau large. */
private val PeekWidth = 44.dp

/** Part de la bulle que la première colonne, figée, garde au plus dans un tableau large. */
private const val FROZEN_SHARE = 0.45f

/** Défilement horizontal d'un tableau : [offset] en pixels, de 0 à [max] (calculé à la mise en page). */
private class TableScroll {
    var offset by mutableFloatStateOf(0f)
    var max by mutableIntStateOf(0)

    /** Largeur de la colonne figée, connue après la mise en page (lue au dessin). */
    var frozen = 0
}

/**
 * Tableau à filets. Large, il défile dans son propre bloc : la première colonne (les noms) reste figée et les
 * valeurs glissent dessous, la colonne suivante dépasse au bord, et un rail fin dit où l'on en est.
 */
@Composable
private fun TableView(table: MdBlock.Table, ink: Color, muted: Color, strike: TextDecoration?) {
    val columns = table.header.size
    val rows = listOf(table.header) + table.rows
    val rules = maxOf(1, table.rows.size)
    val scroll = remember { TableScroll() }
    val description = remember(table) { table.spoken() }
    val scrollable = rememberScrollableState { delta ->
        val before = scroll.offset
        scroll.offset = (before - delta).coerceIn(0f, scroll.max.toFloat())
        before - scroll.offset
    }
    Column(Modifier.fillMaxWidth()) {
        Layout(
            contents = listOf(
                {
                    rows.forEachIndexed { r, row ->
                        row.forEachIndexed { c, cell ->
                            val style = when {
                                r == 0 -> TracklyType.ReplyTableHead.copy(color = muted)
                                c == 0 || table.aligns[c] == Align.End -> TracklyType.ReplyTableCell.copy(color = ink, fontWeight = FontWeight(500))
                                else -> TracklyType.ReplyTableCell.copy(color = ink)
                            }
                            BasicText(
                                cell.annotated(),
                                style = style.copy(textAlign = table.aligns[c].textAlign(), textDecoration = strike),
                                modifier = Modifier.padding(start = if (c == 0) 0.dp else ColumnGap, top = CellPadding, bottom = CellPadding),
                            )
                        }
                    }
                },
                {
                    repeat(rules) { index ->
                        Box(Modifier.background(if (index == 0) ink else TracklyColors.Line))
                    }
                },
                // Fond de la colonne figée, une pièce par ligne : il cache ce qui glisse dessous. Une seule pièce sur
                // toute la hauteur dépasserait ce qu'un `Constraints` étroit peut porter (CHAT-06, tableau très long).
                { repeat(rows.size) { Box(Modifier.background(TracklyColors.PaperHi)) } },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatTags.REPLY_TABLE)
                // TalkBack lit une ligne à la fois, chaque valeur avec son en-tête, et sait que le tableau défile.
                .clearAndSetSemantics {
                    contentDescription = description
                    horizontalScrollAxisRange = ScrollAxisRange(value = { scroll.offset }, maxValue = { scroll.max.toFloat() })
                }
                .clipToBounds()
                .scrollable(scrollable, Orientation.Horizontal)
                // Un tableau qui dépasse se devine : ses bords s'estompent tant qu'il reste des colonnes à voir.
                .drawWithContent {
                    drawContent()
                    val fade = EdgeFade.toPx()
                    if (scroll.offset < scroll.max) {
                        drawRect(
                            Brush.horizontalGradient(listOf(TracklyColors.PaperHi.copy(alpha = 0f), TracklyColors.PaperHi), startX = size.width - fade, endX = size.width),
                            topLeft = Offset(size.width - fade, 0f),
                        )
                    }
                    if (scroll.offset > 0f) {
                        val x = scroll.frozen.toFloat()
                        drawRect(
                            Brush.horizontalGradient(listOf(TracklyColors.PaperHi, TracklyColors.PaperHi.copy(alpha = 0f)), startX = x, endX = x + fade),
                            topLeft = Offset(x, 0f),
                            size = Size(fade, size.height),
                        )
                    }
                },
        ) { (cells, lines, backdrops), constraints ->
            fun px(dp: Dp) = dp.roundToPx()
            val viewport = constraints.maxWidth
            val mins = IntArray(columns)
            val widths = IntArray(columns) { c ->
                var max = 0
                var min = 0
                for (r in rows.indices) {
                    val cell = cells[r * columns + c]
                    max = maxOf(max, cell.maxIntrinsicWidth(Constraints.Infinity))
                    min = maxOf(min, cell.minIntrinsicWidth(Constraints.Infinity))
                }
                val limit = px(if (c == 0) FirstColumnCap else ColumnCap) + if (c == 0) 0 else px(ColumnGap)
                mins[c] = minOf(min, px(WordCap))
                minOf(maxOf(min, minOf(max, limit)), px(WordCap))
            }
            if (widths.sum() <= viewport) {
                // Un tableau étroit occupe toute la bulle : la place en trop va à la première colonne (les noms).
                widths[0] += viewport - widths.sum()
            } else if (columns > 1) {
                // La colonne figée laisse de la place aux valeurs, même en grande police.
                widths[0] = minOf(widths[0], maxOf(mins[0], (viewport * FROZEN_SHARE).toInt()))
                // La colonne suivante dépasse toujours au bord, pour qu'on devine que le tableau défile.
                var x = 0
                var cut = 0
                while (cut < columns && x + widths[cut] <= viewport) { x += widths[cut]; cut++ }
                if (cut in 1 until columns) {
                    val missing = minOf(widths[cut], px(ColumnGap) + px(PeekWidth)) - (viewport - x)
                    if (missing > 0) widths[0] -= minOf(missing, widths[0] - mins[0]).coerceAtLeast(0)
                }
            }
            val total = widths.sum()
            scroll.max = (total - viewport).coerceAtLeast(0)
            scroll.frozen = widths[0]
            val placed = rows.indices.map { r ->
                List(columns) { c -> cells[r * columns + c].measure(Constraints.fixedWidth(widths[c])) }
            }
            val heights = placed.map { row -> row.maxOf { it.height } }
            val thick = px(HeaderRule)
            val thin = px(TracklySpacing.Hairline).coerceAtLeast(1)
            val width = minOf(total, viewport)
            val rulePlaceables = lines.mapIndexed { index, line -> line.measure(Constraints.fixed(width, if (index == 0) thick else thin)) }
            val height = heights.sum() + rulePlaceables.sumOf { it.height }
            // Chaque pièce couvre sa ligne et le filet dessous ; jamais une contrainte impossible, même pour une ligne démesurée.
            val spans = rows.indices.map { r -> heights[r] + (rulePlaceables.getOrNull(r)?.height ?: 0) }
            val backdropPlaceables = backdrops.mapIndexed { r, piece ->
                piece.measure(Constraints.fitPrioritizingWidth(widths[0], widths[0], spans[r], spans[r]))
            }
            layout(width, height) {
                if (scroll.max > 0) {
                    var top = 0
                    backdropPlaceables.forEachIndexed { r, piece ->
                        piece.place(0, top, zIndex = 0.5f)
                        top += spans[r]
                    }
                }
                // Lu au placement seulement : un défilement replace les cellules sans tout remesurer.
                val shift = scroll.offset.coerceIn(0f, scroll.max.toFloat()).toInt()
                var y = 0
                placed.forEachIndexed { r, row ->
                    var x = widths[0]
                    for (c in 1 until columns) {
                        row[c].place(x - shift, y)
                        x += widths[c]
                    }
                    row[0].place(0, y, zIndex = 1f)
                    y += heights[r]
                    // Filet épais sous l'en-tête, fins entre les lignes, rien sous la dernière.
                    rulePlaceables.getOrNull(r)?.let { it.place(0, y, zIndex = 2f); y += it.height }
                }
            }
        }
        // Rail de défilement, seulement quand le tableau dépasse : il dit qu'on peut glisser, et où l'on en est.
        if (scroll.max > 0) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .testTag(ChatTags.REPLY_TABLE_TRACK)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(TracklyColors.TrackEmpty, cornerRadius = radius)
                        val thumb = (size.width * size.width / (size.width + scroll.max)).coerceAtLeast(24.dp.toPx())
                        val x = (size.width - thumb) * (scroll.offset / scroll.max).coerceIn(0f, 1f)
                        drawRoundRect(TracklyColors.Ink3, topLeft = Offset(x, 0f), size = Size(thumb, size.height), cornerRadius = radius)
                    },
            )
        }
    }
}

/** « Pâtes thon : kcal 650, P 42 g, G 80 g, L 12 g. » pour chaque ligne ; l'en-tête seul s'il n'y a pas encore de ligne. */
private fun MdBlock.Table.spoken(): String {
    fun List<Inline>.text() = joinToString("") { it.text }.trim()
    val heads = header.map { it.text() }
    if (rows.isEmpty()) return heads.filter { it.isNotEmpty() }.joinToString(", ")
    return rows.joinToString(" ") { row ->
        val name = row.first().text()
        val values = row.drop(1).mapIndexedNotNull { index, cell ->
            cell.text().takeIf { it.isNotEmpty() }?.let { value -> listOf(heads.getOrElse(index + 1) { "" }, value).filter { it.isNotEmpty() }.joinToString(" ") }
        }
        (if (values.isEmpty()) name else "$name : ${values.joinToString(", ")}") + "."
    }
}

private fun Align.textAlign() = when (this) {
    Align.Start -> TextAlign.Start
    Align.Center -> TextAlign.Center
    Align.End -> TextAlign.End
}

private fun List<Inline>.annotated(): AnnotatedString = buildAnnotatedString {
    for (run in this@annotated) {
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight(700) else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            background = if (run.code) TracklyColors.TrackEmpty else Color.Unspecified,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
        )
        withStyle(style) { append(run.text) }
    }
}

