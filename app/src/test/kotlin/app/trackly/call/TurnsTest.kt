package app.trackly.call

import app.trackly.call.live.AudioLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodbyeTest {
    @Test
    fun `formules nettes en fin de phrase`() {
        for (s in listOf("Au revoir !", "bon ben merci, au revoir", "Allez, à plus.", "à plus tard Trackly", "Bonne soirée, merci beaucoup", "ok bye")) {
            assertTrue(s, Goodbye.isGoodbye(s))
        }
    }

    @Test
    fun `c est tout seulement dans une phrase courte`() {
        assertTrue(Goodbye.isGoodbye("Non, c'est tout."))
        assertTrue(Goodbye.isGoodbye("C'est bon, merci !"))
        assertTrue(Goodbye.isGoodbye("j'ai fini"))
        assertFalse(Goodbye.isGoodbye("Ce midi poulet riz et brocolis, c'est tout."))
        assertFalse(Goodbye.isGoodbye("c'est bon le poulet"))
        // « C'est bon » seul ou « oui c'est bon » répondent souvent à une question de la voix.
        assertFalse(Goodbye.isGoodbye("C'est bon."))
        assertFalse(Goodbye.isGoodbye("Oui c'est bon"))
    }

    @Test
    fun `ni salut seul, ni au revoir au milieu d une phrase`() {
        assertFalse(Goodbye.isGoodbye("Salut !"))
        assertFalse(Goodbye.isGoodbye("au revoir les frites, j'ai pris une salade"))
        assertFalse(Goodbye.isGoodbye("j'ai mangé à plus de 200 g"))
        assertFalse(Goodbye.isGoodbye("Il n'y en a plus."))
        assertFalse(Goodbye.isGoodbye("on a passé une bonne journée"))
        assertFalse(Goodbye.isGoodbye(""))
    }
}

class BargeInGuardTest {
    @Test
    fun `voix forte pendant 300 ms sur la voix de l IA, apres traitement`() {
        val g = BargeInGuard()
        val loud = AudioLevels(mic = 0.3f, ai = 0.2f, micProcessed = 0.15f)
        assertNull(g.onLevels(0, loud, lastAiTranscriptAt = 0))
        assertNull(g.onLevels(250, loud, lastAiTranscriptAt = 0))
        assertEquals(BargeInGuard.Action.Mute, g.onLevels(300, loud, lastAiTranscriptAt = 0))
        assertTrue(g.muted)
        // La voix parle encore (transcription récente) : le son reste coupé.
        assertNull(g.onLevels(900, AudioLevels(), lastAiTranscriptAt = 800))
        assertEquals(BargeInGuard.Action.Unmute, g.onLevels(1_700, AudioLevels(), lastAiTranscriptAt = 800))
        assertEquals(1, g.count)
    }

    @Test
    fun `jamais sans voix de l IA, ni sur un bruit bref`() {
        val g = BargeInGuard()
        assertNull(g.onLevels(0, AudioLevels(mic = 0.5f, ai = 0f, micProcessed = 0.5f), null))
        assertNull(g.onLevels(500, AudioLevels(mic = 0.5f, ai = 0f, micProcessed = 0.5f), null))
        assertNull(g.onLevels(600, AudioLevels(mic = 0.5f, ai = 0.2f, micProcessed = 0.5f), null))
        assertNull(g.onLevels(700, AudioLevels(mic = 0.01f, ai = 0.2f, micProcessed = 0.01f), null))
        assertNull(g.onLevels(950, AudioLevels(mic = 0.5f, ai = 0.2f, micProcessed = 0.5f), null))
        assertFalse(g.muted)
    }

    @Test
    fun `micro brut en echo WebRTC, il doit depasser l echo`() {
        val g = BargeInGuard(echoGateOnRaw = true)
        // Écho : le micro brut suit la voix jouée, sans la dépasser de 1,5 fois.
        for (t in 0L..2_000L step 100) assertNull(g.onLevels(t, AudioLevels(mic = 0.25f, ai = 0.2f), null))
        // L'utilisateur parle fort par-dessus.
        assertNull(g.onLevels(2_100, AudioLevels(mic = 0.5f, ai = 0.2f), null))
        assertEquals(BargeInGuard.Action.Mute, g.onLevels(2_400, AudioLevels(mic = 0.5f, ai = 0.2f), null))
    }

    @Test
    fun `son rendu au plus tard apres 6 s`() {
        val g = BargeInGuard()
        val loud = AudioLevels(mic = 0.3f, ai = 0.2f, micProcessed = 0.2f)
        g.onLevels(0, loud, 0)
        assertEquals(BargeInGuard.Action.Mute, g.onLevels(300, loud, 0))
        assertNull(g.onLevels(6_000, AudioLevels(), lastAiTranscriptAt = 5_900))
        assertEquals(BargeInGuard.Action.Unmute, g.onLevels(6_300, AudioLevels(), lastAiTranscriptAt = 6_250))
    }
}

class LocalTurnDetectorTest {
    @Test
    fun `pause quand personne ne parle depuis assez longtemps`() {
        val d = LocalTurnDetector()
        assertTrue(d.isPause(0, 700))
        d.onLevels(0, mic = 0.2f, ai = 0f)
        assertTrue(d.userSpeaking)
        assertFalse(d.isPause(100, 700))
        d.onLevels(400, mic = 0f, ai = 0f)
        assertFalse(d.userSpeaking)
        assertFalse(d.isPause(500, 700))
        assertTrue(d.isPause(700, 700))
    }

    @Test
    fun `l echo pendant que l IA parle n est pas l utilisateur`() {
        val d = LocalTurnDetector()
        d.onLevels(0, mic = 0.2f, ai = 0.1f)
        assertTrue(d.aiSpeaking)
        assertFalse(d.userSpeaking)
        assertNull(d.userLastLoudAt)
    }
}
