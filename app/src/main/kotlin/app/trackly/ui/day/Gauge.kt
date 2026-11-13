package app.trackly.ui.day

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarState
import app.trackly.day.GaugeScale
import app.trackly.day.formatKcal
import app.trackly.ui.components.Odometer
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Jauge graduée de 0 à l'objectif : remplissage cobalt, reflet, graduations qui s'allument. */
@Composable
fun KcalGauge(
    consumedKcal: Int,
    goalKcal: Int,
    delayMs: Int,
    modifier: Modifier = Modifier,
    /** Le total n'est qu'une valeur basse (borne minimale) : l'étiquette dit « au moins 1 550 consommées ». */
    atLeast: Boolean = false,
    /** Position du trait de moyenne (0 à 1 de la jauge), ou `null` : pas de trait. Muet pour TalkBack. */
    averageMark: Float? = null,
    /** État de la jauge : seul le remplissage du tube change de couleur. Dit par TalkBack ; normal : rien de plus. */
    state: BarState = BarState.Normal,
) {
    // Sans objectif calorique, pas de jauge (l'écran garde les autres totaux).
    if (goalKcal <= 0) return
    val motion = LocalMotion.current
    val scale = remember(goalKcal) { GaugeScale.of(goalKcal) }
    val fill = GaugeScale.fill(consumedKcal, goalKcal)
    val lit = scale.litTicks(consumedKcal).toFloat()

    val fillAnim = remember { Animatable(if (motion.reduced) fill else 0f) }
    val litAnim = remember { Animatable(if (motion.reduced) lit else 0f) }
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(fill, lit) {
        launch { fillAnim.animateTo(fill, motion.tween(TracklyDurations.GAUGE_FILL, delayMs + 80)) }
        launch { litAnim.animateTo(lit, motion.tween(TracklyDurations.TICKS_SWEEP, delayMs + 160, LinearEasing)) }
        if (!motion.reduced) {
            sheen.snapTo(0f)
            delay(motion.delayMs(delayMs + 280))
            sheen.animateTo(1f, motion.tween(TracklyDurations.GAUGE_SHEEN, easing = EaseOut))
        }
    }

    val tubeColor by animatedBarColor(state.fill(TracklyColors.Cobalt))
    val spokenState = state.spoken(Bar.Kcal)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(
                if (averageMark != null || spokenState != null) {
                    // Avec un état, la jauge se lit d'une traite, chiffre compris, comme les barres de macros.
                    Modifier.semantics(mergeDescendants = spokenState != null) {
                        if (averageMark != null) this.averageMark = averageMark
                        if (spokenState != null) {
                            this.barState = state
                            val known = if (atLeast) "au moins $consumedKcal" else "$consumedKcal"
                            contentDescription = "Calories : $known consommées sur $goalKcal, $spokenState"
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val width = constraints.maxWidth
        Column {
            // Étiquette « 1 460 consommées » et repère, qui suivent le remplissage.
            // Hauteurs « au moins » (et non fixes) : à 200 % de police, le texte grandit au lieu d'être coupé.
            var captionWidth by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxWidth().heightIn(min = 16.dp)) {
                Row(
                    Modifier
                        .onSizeChanged { captionWidth = it.width }
                        .offset {
                            val x = fillAnim.value * width
                            IntOffset((x - captionWidth).coerceIn(0f, (width - captionWidth).toFloat().coerceAtLeast(0f)).roundToInt(), 0)
                        }
                        .heightIn(min = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (atLeast) {
                        BasicText("au moins ", style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink2), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                    }
                    Odometer(consumedKcal, TracklyType.GaugeNumber, Modifier.alignByBaseline(), delayMs = delayMs + 120)
                    BasicText(" consommées", style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink2), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                }
            }
            // Repère : une graduation de 8 dp sous l'étiquette, au bout du remplissage.
            Box(Modifier.fillMaxWidth().height(11.dp)) {
                Box(
                    Modifier
                        .offset {
                            val x = (fillAnim.value * width).coerceIn(0f, width - 1.dp.toPx())
                            IntOffset(x.roundToInt(), 1.dp.roundToPx())
                        }
                        .size(1.dp, 8.dp)
                        .background(TracklyColors.Cobalt),
                )
            }
            Tube(fill = { fillAnim.value }, sheen = { sheen.value }, mark = averageMark, color = { tubeColor })
            Spacer(Modifier.height(5.dp))
            Ticks(scale, lit = { litAnim.value })
            Spacer(Modifier.height(3.dp))
            ScaleLabels(scale)
        }
    }
}

private val TubeShape = RoundedCornerShape(1.dp)

/** Hauteur du tube : 14 dp. */
internal val GaugeTubeHeight = 14.dp

/**
 * Tube de la jauge :
 * - couleur d'alerte : seul le remplissage passe de [TracklyColors.Cobalt] à `Warn` ou `Bad` ; graduations, repère
 *   et étiquettes gardent le cobalt ;
 * - trait de moyenne : un trait de 1,5 dp dessiné ici ([drawAverageMark]), qui dépasse de 6 dp au-dessus et
 *   au-dessous du tube (la rangée du repère lui laisse la place au-dessus ; au-dessous, il effleure le haut des graduations).
 *   Le tube est rogné aux coins : le trait est dessiné par la boîte qui l'entoure, hors du rognage.
 */
@Composable
private fun Tube(fill: () -> Float, sheen: () -> Float, mark: Float?, color: () -> Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(GaugeTubeHeight)
            .drawWithContent {
                drawContent()
                if (mark != null) drawAverageMark(mark, fill(), GaugeMarkOvershoot)
            },
    ) {
        TubeBody(fill, sheen, color)
    }
}

@Composable
private fun TubeBody(fill: () -> Float, sheen: () -> Float, color: () -> Color) {
    Box(
        Modifier
            .fillMaxSize()
            .border(TracklySpacing.Hairline, TracklyColors.Line2, TubeShape)
            .clip(TubeShape)
            .drawBehind {
                val w = size.width * fill().coerceAtLeast(0f)
                drawRect(color(), size = Size(w, size.height))
                val s = sheen()
                if (s < 1f && w > 0f) {
                    clipRect(right = w) {
                        // Bande claire inclinée (-18°) qui traverse le remplissage une fois.
                        val band = 0.46f * w
                        val left = -0.6f * w + s * 1.9f * w
                        val skew = size.height * 0.325f
                        val path = Path().apply {
                            moveTo(left + skew, 0f)
                            lineTo(left + band + skew, 0f)
                            lineTo(left + band - skew, size.height)
                            lineTo(left - skew, size.height)
                            close()
                        }
                        drawPath(
                            path,
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.32f),
                                1f to Color.Transparent,
                                startX = left - skew,
                                endX = left + band + skew,
                            ),
                        )
                    }
                }
            },
    )
}

@Composable
private fun Ticks(scale: GaugeScale, lit: () -> Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val stroke = 1.dp.toPx()
        val usable = size.width - stroke
        scale.ticks.forEachIndexed { i, tick ->
            val on = (lit() - i).coerceIn(0f, 1f)
            val off = if (tick.major) TracklyColors.TickMajorOff else TracklyColors.TickOff
            val h = (if (tick.major) 9.dp else 5.dp).toPx()
            drawRect(lerp(off, TracklyColors.Cobalt, on), topLeft = Offset(tick.fraction * usable, 0f), size = Size(stroke, h))
        }
    }
}

/** « 0 · 1 000 · 2 000 … sur 2 600 », chaque repère centré sur sa position. */
@Composable
private fun ScaleLabels(scale: GaugeScale) {
    val labelStyle = TracklyType.Scale.copy(color = TracklyColors.Ink3)
    Layout(
        modifier = Modifier.fillMaxWidth().heightIn(min = 12.dp),
        content = {
            scale.labels.forEach { BasicText(formatKcal(it.kcal), style = labelStyle) }
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TracklyColors.Ink2)) { append("sur ") }
                    withStyle(TracklyType.ScaleEnd.toSpanStyle().copy(color = TracklyColors.Ink)) { append(formatKcal(scale.goalKcal)) }
                },
                style = labelStyle,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val end = placeables.last()
        val endLeft = width - end.width
        val minGap = 8.dp.roundToPx()
        layout(width, constraints.minHeight.coerceAtLeast(placeables.maxOf { it.height })) {
            scale.labels.forEachIndexed { i, label ->
                val p = placeables[i]
                val x = if (i == 0) 0 else (label.fraction * width - p.width / 2f).roundToInt()
                // Un repère qui toucherait « sur N » est omis (mesuré en pixels, pas en fraction).
                if (x + p.width + minGap <= endLeft) p.place(x, 0)
            }
            end.place(endLeft, 0)
        }
    }
}
