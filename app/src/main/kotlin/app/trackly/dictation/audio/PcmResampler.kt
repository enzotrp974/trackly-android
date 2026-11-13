package app.trackly.dictation.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rééchantillonneur en flux : ramène un PCM 16 bits mono de [inRate] à [outRate] (24 kHz).
 *
 * Chaque échantillon de sortie est une moyenne des échantillons d'entrée voisins, pondérée en
 * triangle sur une largeur d'au moins un échantillon de sortie : c'est une interpolation linéaire
 * à la montée en fréquence, et un filtre passe-bas simple à la descente (48 000 -> 24 000 Hz),
 * qui évite le repliement des aigus. Suffisant pour de la parole.
 *
 * La durée est conservée : [process] puis [finish] produisent `ceil(entrée / ratio)` échantillons
 * en tout, quel que soit le découpage de l'entrée.
 */
class PcmResampler(private val inRate: Int, private val outRate: Int = Pcm.RATE) {
    init {
        require(inRate > 0 && outRate > 0) { "Fréquence invalide" }
    }

    private val ratio = inRate.toDouble() / outRate
    private val half = max(ratio, 1.0)
    private var buffer = ShortArray(0)
    private var bufferStart = 0L // indice absolu du premier échantillon gardé
    private var total = 0L // échantillons reçus
    private var nextOut = 0L // prochain échantillon de sortie

    /** Ajoute [count] échantillons de [input] ; renvoie les échantillons de sortie devenus calculables. */
    fun process(input: ShortArray, count: Int = input.size): ShortArray {
        if (inRate == outRate) return input.copyOf(count)
        buffer = buffer.copyOf(buffer.size + count).also { System.arraycopy(input, 0, it, buffer.size, count) }
        total += count
        val out = ArrayList<Short>()
        while (true) {
            val t = nextOut * ratio
            val lastNeeded = ceil(t + half).toLong() - 1
            if (lastNeeded > total - 1) break
            out += sampleAt(t)
            nextOut++
        }
        trim()
        return out.toShortArray()
    }

    /** Fin du flux : produit les derniers échantillons (la fin est calculée avec ce qui reste). */
    fun finish(): ShortArray {
        if (inRate == outRate) return ShortArray(0)
        val out = ArrayList<Short>()
        while (nextOut * ratio < total) {
            out += sampleAt(nextOut * ratio)
            nextOut++
        }
        buffer = ShortArray(0)
        bufferStart = total
        return out.toShortArray()
    }

    private fun sampleAt(t: Double): Short {
        val from = max(floor(t - half).toLong() + 1, max(0L, bufferStart))
        val to = minOf(ceil(t + half).toLong() - 1, total - 1)
        var sum = 0.0
        var weights = 0.0
        for (k in from..to) {
            val w = 1.0 - abs(k - t) / half
            if (w <= 0.0) continue
            sum += w * buffer[(k - bufferStart).toInt()]
            weights += w
        }
        if (weights == 0.0) return 0
        return (sum / weights).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Oublie les échantillons que les prochaines sorties n'utilisent plus. */
    private fun trim() {
        val needFrom = max(0L, floor(nextOut * ratio - half).toLong() + 1)
        val drop = (needFrom - bufferStart).coerceIn(0L, buffer.size.toLong()).toInt()
        if (drop > 0) {
            buffer = buffer.copyOfRange(drop, buffer.size)
            bufferStart += drop
        }
    }
}
