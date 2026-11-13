package app.trackly.call

import app.trackly.call.live.LaunchStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CallRecordsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val at = Instant.parse("2026-11-06T12:00:00Z")

    @Test
    fun `transcription du dernier appel, ecrasee par le suivant`() {
        val file = File(tmp.root, "appel/derniere-transcription.txt")
        val t = LastCallTranscript(file)
        assertNull(t.read())
        t.write(listOf(CallLine(Speaker.User, "Une pomme."), CallLine(Speaker.Voice, "Quelle taille ?")), at)
        assertTrue(t.read()!!.contains("Toi : Une pomme."))
        assertTrue(t.read()!!.contains("Trackly : Quelle taille ?"))
        t.write(listOf(CallLine(Speaker.User, "Un yaourt.")), at.plusSeconds(60))
        assertFalse(t.read()!!.contains("pomme"))
        assertTrue(t.read()!!.contains("Un yaourt."))
        // Un appel vide (clé refusée) ne remplace pas la transcription précédente.
        t.write(emptyList(), at.plusSeconds(120))
        assertTrue(t.read()!!.contains("Un yaourt."))
        assertEquals(listOf("derniere-transcription.txt"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `mesures gardees sans texte, vingt au plus`() {
        val store = CallMetricsStore(File(tmp.root, "appel/mesures.json"))
        repeat(25) { i ->
            store.add(
                CallMetrics(
                    at.plusSeconds(i * 60L), prepared = i % 2 == 0,
                    steps = listOf(LaunchStep.Key to 3L, LaunchStep.Http to 420L, LaunchStep.Started to 780L),
                    beforeTap = setOf(LaunchStep.Key),
                    listenMs = 780L + i,
                    reflections = listOf(ReflectionTiming(CallReflection.Medium, 2_400, true)),
                    endReason = EndReason.Goodbye.code, seconds = 95,
                ),
            )
        }
        val all = store.all()
        assertEquals(20, all.size)
        assertEquals(804L, all.last().listenMs)
        assertEquals(setOf(LaunchStep.Key), all.last().beforeTap)
        val text = CallMetricsStore.text(all)
        assertTrue(text.contains("requête de session 420 ms"))
        assertTrue(text.contains("Moyenne 2400 ms"))
    }
}
