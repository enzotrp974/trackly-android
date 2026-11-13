package app.trackly.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.ui.components.rememberGlowDrift
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyDurations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lumière de fond ne redessine plus en continu. On compte les images (une recomposition = une
 * valeur nouvelle de la dérive) sur une horloge de test à 60 images par seconde.
 *
 * Mesure de l'audit (émulateur, Android 30) : 376 images en 10 s au repos avec l'ancienne boucle infinie.
 * Ici, même durée simulée : ancienne boucle comparée à la dérive limitée.
 */
@RunWith(AndroidJUnit4::class)
class BackdropDriftTest {
    @get:Rule val compose = createComposeRule()

    private val frameMs = 16L

    /** Avance de [ms] millisecondes, image par image. */
    private fun advance(ms: Long) {
        var t = 0L
        while (t < ms) {
            compose.mainClock.advanceTimeBy(frameMs)
            t += frameMs
        }
    }

    @Test
    fun `la derive s arrete apres l ouverture - plus aucune image au repos`() {
        var frames = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = false)) {
                val drift by rememberGlowDrift(nudge = 0)
                drift.let { SideEffect { frames++ } }
            }
        }
        advance(TracklyDurations.DRIFT + 1_000L)
        val duringOpening = frames
        assertTrue("la lumière doit bouger à l'ouverture ($duringOpening images)", duringOpening > 100)

        // 30 s de repos : aucune image de plus.
        advance(30_000)
        assertEquals("au repos, la lumière ne redessine plus", duringOpening, frames)
    }

    @Test
    fun `une transition relance une derive, qui s arrete aussi`() {
        var frames = 0
        var nudge by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = false)) {
                val drift by rememberGlowDrift(nudge)
                drift.let { SideEffect { frames++ } }
            }
        }
        advance(TracklyDurations.DRIFT + 1_000L)
        val afterOpening = frames

        compose.runOnUiThread {
            nudge = 1 // changement de jour
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        advance(1_000)
        assertTrue("la transition relance la dérive", frames > afterOpening + 20)
        advance(TracklyDurations.DRIFT + 1_000L)
        val afterTransition = frames
        advance(30_000)
        assertEquals("puis elle s'arrête de nouveau", afterTransition, frames)
    }

    @Test
    fun `mouvements reduits - aucune derive`() {
        var frames = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionPolicy(reduced = true)) {
                val drift by rememberGlowDrift(nudge = 0)
                drift.let { SideEffect { frames++ } }
            }
        }
        advance(20_000)
        assertTrue("au plus la composition initiale ($frames)", frames <= 2)
    }

    /** Pour mémoire : l'ancienne boucle infinie, sur la même durée, redessine sans fin. */
    @Test
    fun `avant - la boucle infinie redessinait en continu`() {
        var frames = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val drift by rememberInfiniteTransition(label = "lumiere").animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(24_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "derive",
            )
            drift.let { SideEffect { frames++ } }
        }
        advance(10_000)
        val first10s = frames
        advance(30_000)
        assertTrue("l'ancienne boucle continue de dessiner ($first10s puis ${frames - first10s} images)", frames - first10s > 1_000)
    }
}
