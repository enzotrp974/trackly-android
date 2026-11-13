package app.trackly.ui.day

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.trackly.ui.theme.TracklyColors

/** Épaisseur du trait de moyenne. */
internal val AverageMarkWidth = 1.5.dp

/** Dépassement du trait au-dessus et au-dessous d'une barre de macro. */
internal val MacroMarkOvershoot = 4.dp

/** Dépassement du trait au-dessus et au-dessous du tube de la jauge. */
internal val GaugeMarkOvershoot = 6.dp

/** Encre à 30 % : le trait hors du remplissage, discret, pas tape-à-l'œil. */
internal val AverageMarkOutside: Color = TracklyColors.Ink.copy(alpha = 0.30f)

/** Blanc à 78 % : le trait sur le remplissage, où l'encre à 30 % serait invisible sur le cobalt. */
internal val AverageMarkInside: Color = Color.White.copy(alpha = 0.78f)

/**
 * Position du trait (0 à 1) d'une barre, pour les tests uniquement. Propriété personnalisée : TalkBack ne lit que les propriétés
 * standard, donc le trait reste muet. Le choix est voulu : c'est un repère visuel que l'utilisateur regarde s'il en a envie,
 * qu'on n'annonce ni ne commente. La description de la barre ne le dit pas non plus.
 */
internal val AverageMarkKey = SemanticsPropertyKey<Float>("AverageMark")
internal var SemanticsPropertyReceiver.averageMark: Float by AverageMarkKey

/**
 * Trait pâle de la moyenne, à dessiner dans une barre dont la taille est celle du [DrawScope].
 *
 * @param mark position du trait, de 0 à 1 de la largeur de la barre.
 * @param fill part remplie de la barre, de 0 à 1 : sur le remplissage le trait est blanc, ailleurs encre. Les parties qui
 *   dépassent de la barre sont toujours sur le fond, donc toujours en encre.
 * @param overshoot dépassement au-dessus et au-dessous de la barre.
 */
internal fun DrawScope.drawAverageMark(mark: Float, fill: Float, overshoot: Dp) {
    val width = AverageMarkWidth.toPx()
    val left = (mark.coerceIn(0f, 1f) * size.width - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
    val over = overshoot.toPx()
    drawRect(AverageMarkOutside, Offset(left, -over), Size(width, over))
    drawRect(AverageMarkOutside, Offset(left, size.height), Size(width, over))
    // Dans la barre, le trait change de couleur à la frontière du remplissage, même s'il la traverse.
    val edge = (fill.coerceIn(0f, 1f) * size.width).coerceIn(left, left + width)
    if (edge > left) drawRect(AverageMarkInside, Offset(left, 0f), Size(edge - left, size.height))
    if (left + width > edge) drawRect(AverageMarkOutside, Offset(edge, 0f), Size(left + width - edge, size.height))
}
