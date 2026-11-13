package app.trackly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.roundToInt
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import app.trackly.day.formatKcal
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import kotlinx.coroutines.launch

/**
 * Chiffres en odomètre : chaque chiffre est un rouleau 0–9 qui tourne jusqu'à sa valeur,
 * de gauche à droite (85 ms d'écart), avec un léger dépassement.
 * Quand le nombre de chiffres change, les rouleaux repartent de 0, comme dans le prototype.
 * Mouvements réduits : la valeur est posée directement.
 */
@Composable
fun Odometer(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = TracklyColors.Ink,
    delayMs: Int = 0,
    groupGap: TextUnit = 0.25.em,
) {
    val motion = LocalMotion.current
    val text = formatKcal(value)
    val digits = text.filter(Char::isDigit).map { it - '0' }
    val reels = remember(digits.size) {
        List(digits.size) { i -> Animatable(if (motion.reduced) digits[i].toFloat() else 0f) }
    }
    LaunchedEffect(text, reels) {
        reels.forEachIndexed { i, reel ->
            launch {
                val target = digits[i].toFloat()
                if (motion.reduced) {
                    reel.snapTo(target)
                } else {
                    reel.animateTo(
                        target,
                        motion.tween(
                            durationMs = TracklyDurations.ODOMETER,
                            delayMs = delayMs + i * TracklyDurations.ODOMETER_STAGGER,
                            easing = TracklyEasing.Spring,
                        ),
                    )
                }
            }
        }
    }

    val density = LocalDensity.current
    // Hauteur de rouleau en pixels entiers : sinon l'arrondi s'accumule d'un chiffre à l'autre
    // et un éclat du chiffre voisin dépasse.
    val cellPx = with(density) { style.fontSize.toPx() }.roundToInt()
    val gap = with(density) { (style.fontSize.toPx() * groupGap.value).toDp() }
    val measurer = rememberTextMeasurer()
    val baseline = remember(style, density) { measurer.measure("0", style).firstBaseline.roundToInt() }
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = text }
            // Expose la ligne de base des chiffres : « kcal restantes » s'aligne dessus.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height, mapOf(FirstBaseline to baseline, LastBaseline to baseline)) { p.place(0, 0) }
            },
    ) {
        var r = 0
        text.forEach { ch ->
            if (ch.isDigit()) Reel(reels[r++], cellPx, style, color) else Spacer(Modifier.width(gap))
        }
    }
}

@Composable
private fun Reel(position: Animatable<Float, AnimationVector1D>, cellPx: Int, style: TextStyle, color: Color) {
    val cell = with(LocalDensity.current) { cellPx.toDp() }
    Box(
        Modifier
            .height(cell)
            // Coupe en hauteur seulement, comme `clip-path: inset(0 -.05em)`.
            .drawWithContent {
                val bleed = size.width * 0.2f
                clipRect(left = -bleed, top = 0f, right = size.width + bleed, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        Column(
            Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -position.value * cellPx },
        ) {
            for (d in 0..9) {
                BasicText("$d", style = style.copy(color = color), softWrap = false, maxLines = 1, modifier = Modifier.height(cell))
            }
        }
    }
}
