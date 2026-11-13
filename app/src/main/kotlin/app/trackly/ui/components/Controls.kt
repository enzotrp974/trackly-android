package app.trackly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklySpacing

/** Bouton sans ondulation : le retour tactile est un léger rétrécissement. */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    pressedScale: Float,
    enabled: Boolean = true,
    /** Libellé de l'action pour les lecteurs d'écran (« Ouvrir le calendrier »). */
    onClickLabel: String? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = LocalMotion.current
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, motion.tween(TracklyDurations.PRESS), label = "pression")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
}

/** Centre de l'élément dans la racine : point de départ de l'ouverture circulaire du chat. */
fun Modifier.trackCenter(onCenter: (Offset) -> Unit): Modifier =
    onGloballyPositioned { onCenter(it.boundsInRoot().center) }

/**
 * Flèche ronde de navigation entre les jours. Zone tactile fixe de 48 dp (largeur et hauteur),
 * cercle dessiné de 30 dp au centre.
 */
@Composable
fun ArrowButton(glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(TracklySpacing.TouchTarget)
            .alpha(if (enabled) 1f else 0.3f)
            .pressable(onClick, pressedScale = 0.88f, enabled = enabled)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(TracklySpacing.ArrowSize).border(TracklySpacing.Hairline, TracklyColors.Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, TracklyColors.Ink, Modifier.size(14.dp))
        }
    }
}

/** Ombre portée à la manière de `box-shadow: 0 y radius spread`. */
fun Modifier.softShadow(
    shape: Shape,
    radius: Dp,
    spread: Dp,
    y: Dp,
    alpha: Float,
    cobalt: Boolean = false,
): Modifier = dropShadow(
    shape,
    Shadow(
        radius = radius,
        color = if (cobalt) TracklyColors.ShadowCobalt else TracklyColors.Shadow,
        spread = spread,
        offset = DpOffset(0.dp, y),
        alpha = alpha,
    ),
)

/** Filet horizontal d'un pixel. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
}
