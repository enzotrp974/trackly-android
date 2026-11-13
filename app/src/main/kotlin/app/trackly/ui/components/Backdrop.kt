package app.trackly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklySpacing

/**
 * Fond « lumière sur la pierre » : une tache claire qui dérive très lentement,
 * et une trame de points perforés qui s'efface vers le bas.
 *
 * La lumière ne dérive qu'à l'ouverture de l'écran puis à chaque changement de [nudge]
 * (transition : autre jour, écran qui s'ouvre), et s'arrête ensuite : au repos, plus aucune
 * image n'est redessinée (l'ancienne boucle infinie coûtait 376 images en 10 s, soit 55 % d'un cœur).
 */
@Composable
fun PorcelainBackdrop(
    modifier: Modifier = Modifier,
    background: Color = TracklyColors.Paper,
    glow: Boolean = true,
    dotsFadeAt: Float = 0.36f,
    dotsAlpha: Float = 1f,
    /** Change à chaque transition qui doit relancer la dérive (par exemple le jour affiché). */
    nudge: Int = 0,
) {
    val drift = if (glow) rememberGlowDrift(nudge) else null
    Box(modifier.fillMaxSize().background(background)) {
        if (glow) {
            // Dessinée une seule fois ; seule la transformation du calque est animée (pas de redessin).
            Canvas(
                Modifier
                    .fillMaxSize()
                    // Dérive lente : 30 images/s suffisent et ménagent la batterie (Android 15+).
                    .preferredFrameRate(30f)
                    .graphicsLayer {
                        val d = drift?.value
                        if (d != null) {
                            translationX = lerp(-0.03f, 0.04f, d) * GLOW_BOX_W * size.width
                            translationY = lerp(0f, 0.03f, d) * GLOW_BOX_H * size.height
                            val s = lerp(1f, 1.08f, d)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, GLOW_BOX_TOP + GLOW_BOX_H / 2)
                        }
                    },
            ) { drawGlow() }
        }
        Canvas(Modifier.fillMaxSize()) { drawDots(dotsFadeAt, dotsAlpha) }
    }
}

/**
 * Position de la lumière, de 0 à 1 (au repos 0,5). Une seule dérive de [TracklyDurations.DRIFT] ms à l'ouverture,
 * puis une autre à chaque changement de [nudge], avant de s'arrêter. Mouvements réduits : position fixe.
 */
@Composable
internal fun rememberGlowDrift(nudge: Int): State<Float> {
    val motion = LocalMotion.current
    val position = remember { Animatable(0.5f) }
    LaunchedEffect(nudge, motion.reduced) {
        if (motion.reduced) {
            position.snapTo(0.5f)
        } else {
            // Va et vient à chaque déclenchement : 0,5 → 0,8 → 0,2 → 0,8… (même vitesse que l'ancienne boucle).
            val target = if (nudge % 2 == 0) 0.8f else 0.2f
            position.animateTo(target, tween(TracklyDurations.DRIFT, easing = FastOutSlowInEasing))
        }
    }
    return position.asState()
}

// Boîte du ::before : inset -20 % à gauche, à droite et en haut, hauteur 80 %.
private const val GLOW_BOX_W = 1.4f
private const val GLOW_BOX_H = 0.8f
private const val GLOW_BOX_LEFT = -0.2f
private const val GLOW_BOX_TOP = -0.2f

private fun DrawScope.drawGlow() {
    val boxW = GLOW_BOX_W * size.width
    val boxH = GLOW_BOX_H * size.height
    val left = GLOW_BOX_LEFT * size.width
    val top = GLOW_BOX_TOP * size.height
    ellipseGlow(Offset(left + 0.80f * boxW, top + 0.20f * boxH), 0.56f * boxW, 0.46f * boxH, TracklyColors.Glow)
    ellipseGlow(Offset(left + 0.08f * boxW, top + 0.42f * boxH), 0.46f * boxW, 0.40f * boxH, TracklyColors.GlowCool)
}

private fun DrawScope.ellipseGlow(center: Offset, rx: Float, ry: Float, color: Color) {
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(0f to color, 0.7f to color.copy(alpha = 0f), center = center, radius = rx),
            radius = rx,
            center = center,
        )
    }
}

private fun DrawScope.drawDots(fadeAt: Float, alpha: Float) {
    val step = TracklySpacing.DotGrid.toPx()
    val r = TracklySpacing.DotRadius.toPx()
    val fadeEnd = size.height * fadeAt
    var y = step / 2
    while (y < fadeEnd) {
        val a = (1f - y / fadeEnd) * alpha
        var x = step / 2
        while (x < size.width) {
            drawCircle(TracklyColors.Dot, radius = r, center = Offset(x, y), alpha = a)
            x += step
        }
        y += step
    }
}
