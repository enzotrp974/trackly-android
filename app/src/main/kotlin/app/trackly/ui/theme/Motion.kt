package app.trackly.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Courbes et durées des animations de l'app. */
object TracklyEasing {
    /** Léger dépassement : odomètre, jauge, entrées. */
    val Spring: Easing = CubicBezierEasing(0.22f, 1.14f, 0.36f, 1f)
    val Soft: Easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)

    /** Ouverture circulaire du chat. */
    val Reveal: Easing = CubicBezierEasing(0.6f, 0f, 0.15f, 1f)
    val DayOut: Easing = CubicBezierEasing(0.5f, 0f, 0.9f, 0.4f)
    val DayIn: Easing = CubicBezierEasing(0.22f, 1.1f, 0.36f, 1f)
}

object TracklyDurations {
    const val ODOMETER = 1250
    const val ODOMETER_STAGGER = 85
    const val GAUGE_FILL = 1350
    const val GAUGE_SHEEN = 1300
    const val TICKS_SWEEP = 1100
    const val MACRO_BAR = 1200
    const val ROW_IN = 800
    const val ROW_STAGGER = 70
    const val HEAD_IN = 800
    const val DOCK_IN = 900
    const val DAY_OUT = 260
    const val DAY_IN = 560
    const val DAY_LABEL = 500
    const val CHAT_OPEN = 850
    const val CHAT_CLOSE = 800
    const val PRESS = 250
    /** Une dérive de la lumière (ouverture ou transition), après laquelle elle s'arrête. */
    const val DRIFT = 10_000
}

/**
 * Politique de mouvement. Android n'a pas de réglage « réduire les animations » distinct :
 * « Supprimer les animations » (Samsung : Accessibilité › Améliorations de la visibilité)
 * met l'échelle de durée des animations à 0. On pose alors les valeurs finales sans animer,
 * et les mouvements décoratifs (lumière, halo) s'arrêtent.
 */
@Immutable
data class MotionPolicy(val reduced: Boolean) {
    fun <T> tween(durationMs: Int, delayMs: Int = 0, easing: Easing = TracklyEasing.Spring): AnimationSpec<T> =
        if (reduced) snap() else androidx.compose.animation.core.tween(durationMs, delayMs, easing)

    fun delayMs(ms: Int): Long = if (reduced) 0L else ms.toLong()

    companion object {
        fun fromAnimatorScale(scale: Float) = MotionPolicy(reduced = scale == 0f)

        fun fromSystem(context: Context): MotionPolicy = fromAnimatorScale(
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
        )
    }
}

val LocalMotion = staticCompositionLocalOf { MotionPolicy(reduced = false) }
