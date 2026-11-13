package app.trackly.photos

import android.graphics.Bitmap
import app.trackly.assistant.openai.ImageInput

/**
 * Photo prête à partir : réduite et compressée **sur le téléphone**, déjà encodée en base64
 * pour la requête. Elle ne vit qu'en mémoire : ni la base, ni le disque ne la gardent.
 *
 * [thumbnail] : vignette pour l'aperçu avant l'envoi. [bytes] : taille du JPEG envoyé.
 */
class PreparedPhoto(
    val id: String,
    val image: ImageInput,
    val thumbnail: Bitmap?,
    val bytes: Int,
) {
    override fun equals(other: Any?) = other is PreparedPhoto && other.id == id
    override fun hashCode() = id.hashCode()
}

/** Réglages de la préparation des photos. Un seul endroit à ajuster. */
object PhotoLimits {
    /**
     * Côté le plus long envoyé au modèle, en pixels. La note OpenAI (images) : `high` tient dans 2 048 x 2 048
     * et 2 500 patches ; 1 600 px lit encore une étiquette tout en restant sous ce plafond (environ 2 500 jetons).
     */
    const val MAX_SIDE_PX = 1600

    /** Qualité JPEG (0 à 100) : lisible pour une étiquette, de l'ordre de 300 à 600 Ko par photo. */
    const val JPEG_QUALITY = 82

    /** Côté de la vignette d'aperçu. */
    const val THUMBNAIL_SIDE_PX = 192

    /** Photos jointes à un même message. */
    const val MAX_PHOTOS = 6

    /** Dimensions après réduction : le côté le plus long ne dépasse pas [maxSide], les proportions sont gardées. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val ratio = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * ratio).toInt()) to maxOf(1, Math.round(height * ratio).toInt())
    }

    /** Facteur de sous-échantillonnage (puissance de 2) pour décoder sans charger la photo en pleine définition. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
