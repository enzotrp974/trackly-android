package app.trackly.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.trackly.review.ReportFormat
import app.trackly.review.page.ChartKind
import app.trackly.review.page.PointChart
import app.trackly.review.page.WeightChart
import app.trackly.review.page.XY
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyType
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texte des graphiques : il suit la police du système jusqu'à 130 %, pas au-delà. Les étiquettes se posent sur un dessin à
 * l'échelle fixe ; au-delà elles se chevaucheraient. Les valeurs restent lues par le lecteur d'écran (description du graphique).
 */
@Composable
internal fun chartLabelStyle(): TextStyle {
    val scale = LocalDensity.current.fontScale
    val base = TracklyType.ReportChartLabel
    return base.copy(fontSize = base.fontSize * (minOf(scale, MAX_CHART_FONT_SCALE) / scale))
}

private const val MAX_CHART_FONT_SCALE = 1.3f

object ReportChartTags {
    const val WEIGHT = "bilan-courbe-poids"
    const val READOUT = "bilan-courbe-lecture"
    const val MINI = "bilan-petit-graphique"
}

/** Ce qu'un toucher sur la courbe du poids affiche : la date et les valeurs de ce jour-là. */
internal object WeightReadout {
    data class Lines(val date: String, val values: List<String>)

    fun at(chart: WeightChart, day: Long): Lines {
        val values = buildList {
            value(chart.you, day, maxGapDays = 9)?.let { add("toi ${ReportFormat.one(it)} kg") }
            chart.weighIns.firstOrNull { it.day == day }?.let { add("pesée ${ReportFormat.one(it.kg)} kg") }
            value(chart.planned, day, maxGapDays = 9)?.let { add("prévu ${ReportFormat.one(it)} kg") }
            if (chart.projection.isNotEmpty()) value(chart.projection, day, maxGapDays = 9)?.let { add("à ce rythme ${ReportFormat.one(it)} kg") }
        }
        return Lines(ReportFormat.dayMonthYear(LocalDate.ofEpochDay(day)), values)
    }

    /** Valeur de la courbe ce jour-là (interpolée entre deux points), ou `null` hors de la courbe. */
    fun value(points: List<XY>, day: Long, maxGapDays: Int): Double? {
        if (points.isEmpty()) return null
        points.firstOrNull { it.day == day }?.let { return it.kg }
        val before = points.lastOrNull { it.day < day } ?: return null
        val after = points.firstOrNull { it.day > day } ?: return null
        if (after.day - before.day > 3 * maxGapDays) return null
        return before.kg + (after.kg - before.kg) * (day - before.day) / (after.day - before.day)
    }
}

private const val DRAG_SLOP = 6f

/** Courbe du poids : toi, pesées, prévu, « à ce rythme », objectif, arrivée. Un toucher affiche la date et les valeurs. */
@Composable
fun WeightChartView(chart: WeightChart, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var selected by remember { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableStateOf(0f) }
    val xs = chart.you.map { it.day } + chart.weighIns.map { it.day } + chart.planned.map { it.day } + chart.projection.map { it.day } + chart.targetDay + chart.today
    val x0 = xs.min()
    val x1 = (xs.max() + 8).coerceAtLeast(x0 + 14)
    val ys = (chart.you + chart.weighIns + chart.planned + chart.projection).map { it.kg } + chart.targetKg
    val step = if (ys.max() - ys.min() >= 4.0) 2.0 else 1.0
    val yMin = floor((ys.min() - 0.3) / step) * step
    val yMax = ceil((ys.max() + 0.3) / step) * step
    val description = buildString {
        append("Poids : ")
        chart.currentKg?.let { append("toi ${ReportFormat.one(it)} kg, ") }
        append("objectif ${ReportFormat.one(chart.targetKg)} kg le ${ReportFormat.dayMonthYear(LocalDate.ofEpochDay(chart.targetDay))}")
        chart.arrival?.let { append(", arrivée prévue le ${ReportFormat.dayMonthYear(LocalDate.ofEpochDay(it.day))}") }
    }
    val labelStyle = chartLabelStyle()
    val heightDp = 206.dp
    Box(modifier.fillMaxWidth().height(heightDp).semantics { contentDescription = description }.testTag(ReportChartTags.WEIGHT)) {
        val left = with(density) { 26.dp.toPx() }
        val right = with(density) { 10.dp.toPx() }
        val top = with(density) { 20.dp.toPx() }
        val bottom = with(density) { 22.dp.toPx() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(chart) {
                    detectTapGestures { offset -> selected = dayAt(offset.x, size.width.toFloat(), left, right, x0, x1) }
                }
                .pointerInput(chart) {
                    detectDragGestures(onDragStart = { selected = dayAt(it.x, size.width.toFloat(), left, right, x0, x1) }) { change, _ ->
                        selected = dayAt(change.position.x, size.width.toFloat(), left, right, x0, x1)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val plotL = left
            val plotR = w - right
            val plotT = top
            val plotB = h - bottom
            fun x(day: Long) = plotL + (plotR - plotL) * (day - x0).toFloat() / (x1 - x0).toFloat()
            fun y(kg: Double) = plotT + (plotB - plotT) * ((yMax - kg) / (yMax - yMin)).toFloat()
            val hair = 1.dp.toPx()
            val axis = labelStyle.copy(color = TracklyColors.Ink3)

            // Grille et graduations en kg, tous les 2 kg.
            var v = yMin
            while (v <= yMax + 1e-6) {
                val goalLine = abs(v - chart.targetKg) < 1e-6
                drawLine(if (goalLine) TracklyColors.Line2 else TracklyColors.Line, Offset(plotL, y(v)), Offset(plotR, y(v)), hair)
                label(measurer, kgTick(v), Offset(plotL - 6.dp.toPx(), y(v)), axis, anchor = Anchor.End, centerY = true)
                v += step
            }
            // Mois en bas.
            drawMonths(measurer, axis, ::x, x0, x1, plotB)
            drawLine(TracklyColors.Line2, Offset(plotL, plotB), Offset(plotR, plotB), hair)
            // Aujourd'hui.
            drawLine(TracklyColors.Line2, Offset(x(chart.today), plotT - 6.dp.toPx()), Offset(x(chart.today), plotB), hair)
            label(measurer, "auj.", Offset(x(chart.today), plotT - 9.dp.toPx()), axis, anchor = Anchor.Center, centerY = true)

            // Prévu : le chemin qui arrive pile à la date visée.
            polyline(chart.planned, ::x, ::y, TracklyColors.Ink3.copy(alpha = 0.55f), 1.5.dp.toPx())
            // Pesées en points pâles.
            for (p in chart.weighIns) drawCircle(TracklyColors.Cobalt.copy(alpha = 0.28f), 2.dp.toPx(), Offset(x(p.day), y(p.kg)))
            // À ce rythme : pointillé cobalt, jaune si l'arrivée dépasse la date visée.
            val projectionColor = if (chart.late) TracklyColors.Warn else TracklyColors.Cobalt
            polyline(chart.projection, ::x, ::y, projectionColor, 1.5.dp.toPx(), dashed = true)
            // Toi.
            polyline(chart.you, ::x, ::y, TracklyColors.Cobalt, 2.dp.toPx())
            val last = chart.you.lastOrNull()
            if (last != null && last.day == chart.today) {
                drawCircle(TracklyColors.Cobalt, 4.dp.toPx(), Offset(x(last.day), y(last.kg)))
                val text = "toi · ${ReportFormat.one(last.kg)} kg"
                val anchor = if (x(last.day) > plotR - 80.dp.toPx()) Anchor.End else Anchor.Start
                label(
                    measurer, text, Offset(x(last.day) + if (anchor == Anchor.Start) 7.dp.toPx() else -7.dp.toPx(), y(last.kg) - 9.dp.toPx()),
                    axis.copy(color = TracklyColors.Ink, fontWeight = FontWeight.Bold), anchor = anchor, centerY = true,
                )
            }
            // Objectif : anneau ; arrivée : point plein.
            val gx = x(chart.targetDay)
            val gy = y(chart.targetKg)
            drawCircle(TracklyColors.Paper, 4.2.dp.toPx(), Offset(gx, gy))
            drawCircle(TracklyColors.Ink, 4.2.dp.toPx(), Offset(gx, gy), style = Stroke(1.6.dp.toPx()))
            val arrival = chart.arrival
            if (arrival != null) {
                val ax = x(arrival.day)
                drawCircle(projectionColor, 4.6.dp.toPx(), Offset(ax, gy))
                val near = ax > plotR - 30.dp.toPx()
                label(
                    measurer, ReportFormat.dayMonth(LocalDate.ofEpochDay(arrival.day)),
                    Offset(ax + if (near) 4.dp.toPx() else 0f, gy - 12.dp.toPx()),
                    axis.copy(color = TracklyColors.Ink, fontWeight = FontWeight.Bold), anchor = if (near) Anchor.End else Anchor.Center, centerY = true,
                )
            }
            val goalLabel = ReportFormat.dayMonth(LocalDate.ofEpochDay(chart.targetDay))
            val goalNear = gx > plotR - 26.dp.toPx()
            label(
                measurer, goalLabel, Offset(gx + if (goalNear) 4.dp.toPx() else 0f, gy + 16.dp.toPx()),
                axis.copy(color = TracklyColors.Ink2), anchor = if (goalNear) Anchor.End else Anchor.Center, centerY = true,
            )
            label(measurer, "objectif", Offset(plotL + 4.dp.toPx(), gy - 8.dp.toPx()), axis, anchor = Anchor.Start, centerY = true)

            // Lecture au toucher.
            selected?.let { day ->
                val sx = x(day.coerceIn(x0, x1))
                drawLine(TracklyColors.Ink.copy(alpha = 0.5f), Offset(sx, plotT), Offset(sx, plotB), hair)
            }
        }
        selected?.let { day ->
            val lines = WeightReadout.at(chart, day)
            val tipWidth = 150.dp
            Box(
                Modifier
                    .testTag(ReportChartTags.READOUT)
                    .offset { IntOffset(tipOffset(day, x0, x1, left, right, tipWidth.toPx(), widthPx), 2.dp.roundToPx()) }
                    .widthIn(max = tipWidth)
                    .background(TracklyColors.PaperHi, RoundedCornerShape(8.dp))
                    .border(1.dp, TracklyColors.Line2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Column {
                    BasicText(lines.date, style = labelStyle.copy(color = TracklyColors.Ink, fontWeight = FontWeight.Bold))
                    for (line in lines.values) BasicText(line, style = labelStyle.copy(color = TracklyColors.Ink2))
                }
            }
        }
    }
}

private fun dayAt(px: Float, width: Float, left: Float, right: Float, x0: Long, x1: Long): Long {
    val t = ((px - left) / (width - left - right)).coerceIn(0f, 1f)
    return x0 + ((x1 - x0) * t).roundToInt()
}

private fun tipOffset(day: Long, x0: Long, x1: Long, left: Float, right: Float, tipWidthPx: Float, widthPx: Float): Int {
    // La bulle suit le doigt sans sortir de l'écran : à gauche du trait dans la moitié droite.
    val ratio = (day - x0).toFloat() / (x1 - x0).toFloat()
    val x = left + (widthPx - left - right) * ratio
    return (if (ratio > 0.55f) x - tipWidthPx - 8f else x + 8f).roundToInt().coerceAtLeast(0)
}

private fun kgTick(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else ReportFormat.one(v)

/**
 * Plus bas où peut se trouver un point du graphique en points : sa valeur, écrite [below] sous lui (demi-hauteur [halfText]),
 * reste à [gap] au-dessus de l'axe des jours [axisY]. Une valeur basse ne recouvre plus le libellé du jour.
 */
internal fun dotsFloor(axisY: Float, below: Float, halfText: Float, gap: Float): Float = axisY - below - halfText - gap

private enum class Anchor { Start, Center, End }

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, anchor: Anchor, centerY: Boolean) {
    val layout: TextLayoutResult = measurer.measure(text, style, maxLines = 1)
    val x = when (anchor) {
        Anchor.Start -> at.x
        Anchor.Center -> at.x - layout.size.width / 2f
        Anchor.End -> at.x - layout.size.width
    }
    val y = if (centerY) at.y - layout.size.height / 2f else at.y
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.polyline(points: List<XY>, x: (Long) -> Float, y: (Double) -> Float, color: Color, width: Float, dashed: Boolean = false) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.day), y(p.kg)) else path.lineTo(x(p.day), y(p.kg)) }
    drawPath(
        path, color,
        style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null),
    )
}

private val MONTHS_SHORT = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

private fun DrawScope.drawMonths(measurer: TextMeasurer, style: TextStyle, x: (Long) -> Float, x0: Long, x1: Long, baseline: Float) {
    var day = LocalDate.ofEpochDay(x0).withDayOfMonth(1).plusMonths(1)
    var lastEnd = -1f
    while (day.toEpochDay() < x1 - 4) {
        val px = x(day.toEpochDay())
        drawLine(TracklyColors.Line2, Offset(px, baseline), Offset(px, baseline + 4.dp.toPx()), 1.dp.toPx())
        val text = MONTHS_SHORT[day.monthValue - 1]
        val layout = measurer.measure(text, style, maxLines = 1)
        val start = px - layout.size.width / 2f
        if (start > lastEnd + 4.dp.toPx()) {
            drawText(layout, topLeft = Offset(start, baseline + 6.dp.toPx()))
            lastEnd = start + layout.size.width
        }
        day = day.plusMonths(1)
    }
}

// ───── Petits graphiques ─────

/** Petit graphique d'un point qui coince : barres, écarts, points ou perte par semaine. Valeurs écrites seulement sur les cas notables. */
@Composable
fun PointChartView(chart: PointChart, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = chartLabelStyle()
    val heightDp = if (chart.kind == ChartKind.Dots) 124.dp else 118.dp
    val description = chart.title + " : " + chart.values.indices.joinToString(", ") { i ->
        "${chart.labels.getOrElse(i) { "" }.ifBlank { "n°${i + 1}" }} ${chart.values[i]?.let { if (chart.kind == ChartKind.Weekly) ReportFormat.two(it) else ReportFormat.int(it) } ?: "inconnu"}"
    }
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(ReportChartTags.MINI)) {
        BasicText(chart.title, style = TracklyType.ReportPointName.copy(color = TracklyColors.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 6.dp))
        Canvas(Modifier.fillMaxWidth().height(heightDp)) {
            val label = labelStyle.copy(color = TracklyColors.Ink3)
            val strong = labelStyle.copy(color = TracklyColors.Ink, fontWeight = FontWeight.Bold)
            val l = 4.dp.toPx()
            // La marge de droite porte les étiquettes des repères : elle prend la largeur du plus long.
            val refWidth = chart.refs.maxOfOrNull { measurer.measure(it.label, label, maxLines = 1).size.width } ?: 0
            val r = size.width - (if (chart.refs.isNotEmpty()) refWidth + 14.dp.toPx() else 4.dp.toPx())
            val t = 16.dp.toPx()
            val b = size.height - 18.dp.toPx()
            val n = max(chart.values.size, 1)
            val slot = (r - l) / n
            val bar = min(20.dp.toPx(), slot * 0.52f)
            val hair = 1.dp.toPx()
            drawLine(TracklyColors.Line2, Offset(l, b), Offset(r + 4.dp.toPx(), b), hair)
            for (i in chart.values.indices) {
                val cx = l + slot * (i + 0.5f)
                label(measurer, chart.labels.getOrElse(i) { "" }, Offset(cx, b + 11.dp.toPx()), label, Anchor.Center, centerY = true)
            }
            when (chart.kind) {
                ChartKind.Bars, ChartKind.Weekly -> {
                    val refMax = chart.refs.maxOfOrNull { it.value } ?: 0.0
                    val ymax = max((chart.values.filterNotNull().maxOrNull() ?: 0.0), refMax) * 1.1
                    if (ymax <= 0) return@Canvas
                    fun y(v: Double) = b - (b - t) * (v / ymax).toFloat()
                    val color = if (chart.kind == ChartKind.Weekly) TracklyColors.Cobalt else TracklyColors.Carbs
                    chart.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val cx = l + slot * (i + 0.5f)
                        drawRoundRect(color, Offset(cx - bar / 2, y(v)), Size(bar, b - y(v)), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        chart.valueLabels.getOrNull(i)?.let { label(measurer, it, Offset(cx, y(v) - 7.dp.toPx()), strong, Anchor.Center, centerY = true) }
                    }
                    refLines(measurer, chart, ::y, l, r, label, dashed = chart.kind == ChartKind.Weekly)
                }

                ChartKind.Gap -> {
                    val mid = t + (b - t) * 0.62f
                    val extent = max(chart.values.filterNotNull().maxOfOrNull { abs(it) } ?: 1.0, 320.0)
                    val scale = (mid - t) / extent.toFloat()
                    chart.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val cx = l + slot * (i + 0.5f)
                        val hgt = max(abs(v).toFloat() * scale, 1.5.dp.toPx())
                        val top = if (v > 0) mid - hgt else mid
                        drawRoundRect(if (chart.flagged.getOrElse(i) { false }) TracklyColors.Warn else TracklyColors.Carbs, Offset(cx - bar / 2, top), Size(bar, hgt), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        chart.valueLabels.getOrNull(i)?.let { label(measurer, it, Offset(cx, top - 7.dp.toPx()), strong, Anchor.Center, centerY = true) }
                    }
                    drawLine(TracklyColors.Ink, Offset(l, mid), Offset(r + 4.dp.toPx(), mid), hair)
                    chart.refs.firstOrNull()?.let { label(measurer, it.label, Offset(r + 8.dp.toPx(), mid), label, Anchor.Start, centerY = true) }
                }

                ChartKind.Dots -> {
                    val all = chart.values.filterNotNull() + chart.refs.map { it.value }
                    if (all.isEmpty()) return@Canvas
                    val lo = floor((all.min() - 6) / 5) * 5
                    val hi = ceil((all.max() + 6) / 5) * 5
                    // La valeur s'écrit sous son point : le point le plus bas garde la place de l'écrire au-dessus de l'axe des jours.
                    val half = measurer.measure("0", strong, maxLines = 1).size.height / 2f
                    val floor = dotsFloor(b, below = 15.dp.toPx(), halfText = half, gap = 2.dp.toPx())
                    fun y(v: Double) = floor - (floor - t) * ((v - lo) / (hi - lo)).toFloat()
                    refLines(measurer, chart, ::y, l, r, label, dashed = false)
                    val minRef = chart.refs.firstOrNull()?.value ?: all.min()
                    chart.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        val cx = l + slot * (i + 0.5f)
                        val flagged = chart.flagged.getOrElse(i) { false }
                        val color = if (flagged) TracklyColors.Warn else TracklyColors.Ink
                        drawLine(color, Offset(cx, y(minRef)), Offset(cx, y(v)), 1.5.dp.toPx())
                        drawCircle(color, 4.5.dp.toPx(), Offset(cx, y(v)))
                        chart.valueLabels.getOrNull(i)?.let { label(measurer, it, Offset(cx, y(v) + 15.dp.toPx()), strong, Anchor.Center, centerY = true) }
                    }
                }
            }
        }
        if (chart.legend.isNotEmpty()) {
            BasicText(chart.legend.joinToString("   ·   "), style = labelStyle.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun DrawScope.refLines(measurer: TextMeasurer, chart: PointChart, y: (Double) -> Float, l: Float, r: Float, style: TextStyle, dashed: Boolean) {
    chart.refs.forEachIndexed { index, ref ->
        val yy = y(ref.value)
        val effect = if (dashed || index > 0) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
        drawLine(if (index == 0) TracklyColors.Ink else TracklyColors.Ink3, Offset(l, yy), Offset(r + 4.dp.toPx(), yy), 1.dp.toPx(), pathEffect = effect)
        label(measurer, ref.label, Offset(r + 8.dp.toPx(), yy), style, Anchor.Start, centerY = true)
    }
}
