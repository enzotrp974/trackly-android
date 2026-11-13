package app.trackly.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {
    @Test
    fun `animations supprimees dans les reglages = mouvements reduits`() {
        assertTrue(MotionPolicy.fromAnimatorScale(0f).reduced)
        assertFalse(MotionPolicy.fromAnimatorScale(1f).reduced)
        assertFalse(MotionPolicy.fromAnimatorScale(0.5f).reduced)
    }

    @Test
    fun `mouvements reduits = valeurs posees sans animation`() {
        val spec = MotionPolicy(reduced = true).tween<Float>(durationMs = 1250, delayMs = 300)
        assertTrue(spec is SnapSpec<*>)
        assertTrue(MotionPolicy(reduced = false).tween<Float>(1250, 300) is TweenSpec<*>)
    }

    @Test
    fun `mouvements reduits = aucun delai d attente`() {
        assertTrue(MotionPolicy(reduced = true).delayMs(500) == 0L)
        assertTrue(MotionPolicy(reduced = false).delayMs(500) == 500L)
    }
}
