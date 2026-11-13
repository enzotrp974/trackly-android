package app.trackly.keyscan

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Une image de l'appareil photo, réduite à sa luminosité (plan Y de l'image YUV) : tout ce que
 * ZXing lit. Rien n'est conservé ; [wipe] met les octets à zéro dès que l'image est analysée.
 */
class LumaFrame(val luma: ByteArray, val rowStride: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && rowStride >= width) { "Image invalide" }
        require(luma.size >= rowStride * (height - 1) + width) { "Image tronquée" }
    }

    fun wipe() = luma.fill(0)
}

/**
 * Lecteur de QR code. Interface pour que le contrôleur de scan se teste sans appareil photo
 * (faux lecteur) ; la vraie implémentation est [ZxingQrDecoder].
 */
fun interface QrDecoder {
    /**
     * Texte du QR code visible sur [frame], en caractères que l'appelant efface après usage,
     * ou `null` s'il n'y en a pas (ou s'il est illisible).
     */
    fun decode(frame: LumaFrame): CharArray?
}

/** Décodage par ZXing core, embarqué dans l'app : tout se passe sur le téléphone, sans réseau. */
class ZxingQrDecoder : QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    override fun decode(frame: LumaFrame): CharArray? {
        val source = PlanarYUVLuminanceSource(frame.luma, frame.rowStride, frame.height, 0, 0, frame.width, frame.height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text.toCharArray()
        } catch (_: NotFoundException) {
            null // pas de QR code dans cette image : le cas courant
        } catch (_: ChecksumException) {
            null // QR code abîmé ou flou : l'image suivante fera mieux
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }
}
