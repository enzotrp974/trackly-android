package app.trackly.ui.day

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarState
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors

/**
 * Couleur d'alerte d'une barre, calculée par la vue du jour (`:core`). Seul le **remplissage** la prend : chiffres,
 * étiquettes, « +N g », repère cobalt et graduations restent tels quels : seule la barre change de couleur. Les deux
 * teintes gardent un contraste suffisant sur le fond porcelaine (voir `ContrastTest`).
 */
internal fun BarState.fill(normal: Color): Color = when (this) {
    BarState.Normal -> normal
    BarState.Warn -> TracklyColors.Warn
    BarState.Bad -> TracklyColors.Bad
}

/** Fond des hachures des lipides : les lipides gardent leurs hachures dans les trois états. */
internal fun BarState.hatchGround(): Color = when (this) {
    BarState.Normal -> TracklyColors.FatHatchGround
    BarState.Warn -> TracklyColors.WarnHatchGround
    BarState.Bad -> TracklyColors.BadHatchGround
}

/**
 * Ce que TalkBack dit de l'état d'une barre, en un fait sans jugement ; `null` pour une barre normale (rien de plus à dire).
 * Les glucides n'ont jamais d'état.
 */
internal fun BarState.spoken(bar: Bar): String? = when (this) {
    BarState.Normal -> null
    BarState.Warn -> "attention"
    BarState.Bad -> if (bar == Bar.Protein) "minimum non atteint" else "limite dépassée"
}

/**
 * État d'une barre, pour les tests uniquement (comme [AverageMarkKey]) : la description lue par TalkBack passe par
 * `contentDescription`, cette propriété laisse un test vérifier l'état sans lire une couleur de pixel.
 */
internal val BarStateKey = SemanticsPropertyKey<BarState>("BarState")
internal var SemanticsPropertyReceiver.barState: BarState by BarStateKey

/** Couleur d'un remplissage qui glisse d'une teinte à l'autre ; mouvements réduits : elle change d'un coup, même rendu final. */
@Composable
internal fun animatedBarColor(target: Color): State<Color> {
    val reduced = LocalMotion.current.reduced
    return animateColorAsState(target, if (reduced) snap() else tween(BAR_COLOR_MS), label = "couleur de la barre")
}

private const val BAR_COLOR_MS = 400
