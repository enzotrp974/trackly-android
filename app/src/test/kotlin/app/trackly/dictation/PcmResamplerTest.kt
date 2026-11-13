package app.trackly.dictation

import app.trackly.dictation.audio.Pcm
import app.trackly.dictation.audio.PcmBuffer
import app.trackly.dictation.audio.PcmResampler
import app.trackly.dictation.audio.Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Le rééchantillonneur ramène l'audio de l'appareil à 24 kHz, sans changer la durée. */
class PcmResamplerTest {
    private fun sine(rate: Int, seconds: Double, hz: Double = 440.0, amplitude: Double = 10_000.0) =
        ShortArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun resample(input: ShortArray, rate: Int, chunk: Int = input.size): ShortArray {
        val resampler = PcmResampler(rate)
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            out += resampler.process(input.copyOfRange(i, i + n), n).toList()
            i += n
        }
        out += resampler.finish().toList()
        return out.toShortArray()
    }

    @Test
    fun `24 kHz - passe tel quel`() {
        val input = sine(24_000, 0.5)
        assertArrayEquals(input, resample(input, 24_000, chunk = 777))
    }

    @Test
    fun `48 kHz - exactement 24 000 echantillons pour une seconde`() {
        assertEquals(24_000, resample(sine(48_000, 1.0), 48_000).size)
    }

    @Test
    fun `44,1 kHz - exactement 24 000 echantillons pour une seconde`() {
        assertEquals(24_000, resample(sine(44_100, 1.0), 44_100).size)
    }

    @Test
    fun `16 kHz - monte a 24 kHz sans changer la duree`() {
        assertEquals(24_000, resample(sine(16_000, 1.0), 16_000).size)
    }

    @Test
    fun `duree conservee quel que soit le decoupage de l entree`() {
        val input = sine(44_100, 2.5)
        val whole = resample(input, 44_100)
        for (chunk in listOf(1, 7, 441, 4410, 10_000)) {
            val chunked = resample(input, 44_100, chunk)
            assertEquals("découpage $chunk", whole.size, chunked.size)
            // Même résultat que d'un seul bloc, à l'arrondi près.
            val worst = whole.indices.maxOf { abs(whole[it] - chunked[it]) }
            assertTrue("écart $worst pour le découpage $chunk", worst <= 1)
        }
        assertEquals(60_000, whole.size)
    }

    @Test
    fun `une tonalite de 440 Hz garde sa frequence et son amplitude`() {
        val out = resample(sine(48_000, 1.0), 48_000)
        // Montées par zéro : une par période, donc 440 en une seconde.
        val crossings = (1 until out.size).count { out[it - 1] < 0 && out[it] >= 0 }
        assertTrue("passages par zéro : $crossings", crossings in 439..441)
        val peak = out.drop(100).dropLast(100).maxOf { abs(it.toInt()) }
        assertTrue("amplitude $peak", peak in 9_000..10_100)
    }

    @Test
    fun `un son aigu au dela de 12 kHz est attenue a la descente (pas de repliement)`() {
        val out = resample(sine(48_000, 1.0, hz = 20_000.0), 48_000)
        val peak = out.drop(100).dropLast(100).maxOf { abs(it.toInt()) }
        assertTrue("20 kHz doit être fortement atténué, crête $peak", peak < 4_000)
    }

    @Test
    fun `le silence reste silence`() {
        assertTrue(resample(ShortArray(48_000), 48_000).all { it == 0.toShort() })
    }

    @Test
    fun `entree vide - sortie vide`() {
        assertEquals(0, resample(ShortArray(0), 48_000).size)
    }

    @Test
    fun `PCM - petit-boutiste, deux octets par echantillon`() {
        val bytes = Pcm.toLittleEndian(shortArrayOf(1, -2, 0x1234))
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), bytes)
        assertEquals(1000L, Pcm.durationMs(48_000))
        assertEquals(200L, Pcm.durationMs(Pcm.CHUNK_BYTES))
        assertEquals(0, Pcm.CHUNK_BYTES % 2)
    }

    @Test
    fun `niveau - zero pour le silence, un pour la pleine echelle`() {
        assertEquals(0f, Pcm.level(ShortArray(100)), 0f)
        assertEquals(1f, Pcm.level(ShortArray(100) { Short.MIN_VALUE }), 0.001f)
    }

    @Test
    fun `WAV - en-tete de 44 octets, 24 kHz mono 16 bits, tailles justes`() {
        val pcm = Pcm.toLittleEndian(shortArrayOf(1, 2, 3, 4))
        val wav = Wav.wrap(pcm)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 8, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(24_000, header.getInt(24))
        assertEquals(48_000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(8, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `tampon audio - garde et vide`() {
        val buffer = PcmBuffer()
        buffer.append(ByteArray(48_000))
        buffer.append(ByteArray(48_000))
        assertEquals(2000L, buffer.durationMs)
        buffer.clear()
        assertEquals(0, buffer.size)
    }
}
