package app.trackly.dictation.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Audio tel qu'OpenAI l'attend : PCM 16 bits signé, mono, petit-boutiste, 24 kHz. */
object Pcm {
    const val RATE = 24_000
    const val BYTES_PER_SAMPLE = 2

    /** Taille d'un morceau envoyé : 200 ms (l'exemple officiel d'OpenAI), toujours un nombre pair d'octets. */
    const val CHUNK_BYTES = 9_600

    fun toLittleEndian(samples: ShortArray, count: Int = samples.size): ByteArray {
        val buffer = ByteBuffer.allocate(count * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        return buffer.array()
    }

    /** Durée en millisecondes de [bytes] octets de PCM 24 kHz. */
    fun durationMs(bytes: Int): Long = bytes.toLong() / BYTES_PER_SAMPLE * 1000L / RATE

    /** Niveau efficace du morceau, de 0 (silence) à 1 (pleine échelle). */
    fun level(samples: ShortArray, count: Int = samples.size): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }
}

/** Audio gardé en mémoire pendant la dictée (jamais écrit sur le disque), pour relancer une transcription après une erreur. */
class PcmBuffer {
    private val bytes = ByteArrayOutputStream()

    val size: Int get() = bytes.size()
    val durationMs: Long get() = Pcm.durationMs(size)

    @Synchronized
    fun append(chunk: ByteArray) = bytes.write(chunk, 0, chunk.size)

    @Synchronized
    fun toByteArray(): ByteArray = bytes.toByteArray()

    @Synchronized
    fun clear() = bytes.reset()
}

/** Emballe du PCM 16 bits mono dans un fichier WAV (en-tête de 44 octets). */
object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int = Pcm.RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // taille du bloc fmt
        header.putShort(1) // PCM entier
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * Pcm.BYTES_PER_SAMPLE) // octets par seconde
        header.putShort(Pcm.BYTES_PER_SAMPLE.toShort()) // alignement d'un bloc
        header.putShort(16) // bits par échantillon
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
