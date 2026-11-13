package app.trackly.dictation.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Le micro ne peut pas être ouvert (refusé, déjà pris par une autre appli, aucun format accepté). */
class AudioUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Source d'audio PCM 16 bits mono. Une interface pour que les tests n'aient besoin ni du micro
 * ni d'Android : la capture réelle est [AudioRecordSource].
 */
interface AudioSource : AutoCloseable {
    /** Fréquence d'échantillonnage réelle du flux lu. */
    val sampleRate: Int

    /** Remplit [buffer] ; renvoie le nombre d'échantillons lus, ou -1 quand la source est arrêtée. Bloquant. */
    fun read(buffer: ShortArray): Int

    /** Arrête la capture : la lecture en cours se termine, les suivantes renvoient -1. */
    fun stop()
}

/** Ouvre la source (démarre le micro). */
fun interface AudioSourceFactory {
    @Throws(AudioUnavailableException::class)
    fun open(): AudioSource
}

/**
 * Capture réelle (`AudioRecord`). On tente 24 kHz, la fréquence attendue par OpenAI ; sinon la
 * suivante parmi 48 000, 44 100 (la seule garantie par Android) et 16 000 Hz, et
 * [PcmResampler] ramène le flux à 24 kHz.
 */
class AudioRecordSource private constructor(
    private val record: AudioRecord,
    override val sampleRate: Int,
) : AudioSource {
    @Volatile private var stopped = false

    override fun read(buffer: ShortArray): Int {
        if (stopped) return -1
        val n = record.read(buffer, 0, buffer.size)
        return if (n < 0 || stopped) -1 else n
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { record.stop() }
    }

    override fun close() {
        stop()
        runCatching { record.release() }
    }

    companion object {
        /** Fréquences essayées, dans l'ordre. */
        val RATES = listOf(24_000, 48_000, 44_100, 16_000)

        @SuppressLint("MissingPermission") // La permission est vérifiée par l'appelant ; un refus devient AudioUnavailableException.
        fun open(): AudioRecordSource {
            var last: Throwable? = null
            // VOICE_RECOGNITION : réglé pour la voix proche, sans traitement musical.
            for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                for (rate in RATES) {
                    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) continue
                    // Au moins une demi-seconde de tampon : la lecture se fait par morceaux de 100 ms.
                    val bufferBytes = maxOf(minimum, rate)
                    val record = try {
                        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                    } catch (e: SecurityException) {
                        throw AudioUnavailableException("Micro refusé", e)
                    } catch (e: IllegalArgumentException) {
                        last = e
                        continue
                    }
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        record.release()
                        continue
                    }
                    try {
                        record.startRecording()
                    } catch (e: IllegalStateException) {
                        record.release()
                        last = e
                        continue
                    }
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        record.release()
                        continue
                    }
                    return AudioRecordSource(record, rate)
                }
            }
            throw AudioUnavailableException("Aucun format audio accepté par le micro", last)
        }
    }
}
