package app.trackly.keyscan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/** Vrai décodage ZXing sur de vraies images de QR code (générées par ZXing), sans appareil photo ni réseau. */
class ZxingQrDecoderTest {
    private val key = "sk-proj-test-FAKEabcdEFGH0123_-FAKEabcdEFGH0123_-FAKEabcdEFGH0123_-FAKEabcdEFGH0123_-end"

    /** Image de luminosité d'un QR code : modules sombres à 0, fond à 255, [padding] octets inutiles en fin de rangée. */
    private fun frameOf(text: String, scale: Int = 6, padding: Int = 0, level: ErrorCorrectionLevel = ErrorCorrectionLevel.L): LumaFrame {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.MARGIN to 4))
        val width = matrix.width * scale
        val height = matrix.height * scale
        val stride = width + padding
        val luma = ByteArray(stride * height) { 0x7F }
        for (y in 0 until height) for (x in 0 until width) luma[y * stride + x] = if (matrix.get(x / scale, y / scale)) 0 else -1
        return LumaFrame(luma, stride, width, height)
    }

    @Test
    fun `lit le QR code d une cle`() {
        assertArrayEquals(key.toCharArray(), ZxingQrDecoder().decode(frameOf(key)))
    }

    @Test
    fun `lit une image dont les rangees sont plus larges que l image (rowStride de l appareil photo)`() {
        assertArrayEquals(key.toCharArray(), ZxingQrDecoder().decode(frameOf(key, padding = 40)))
    }

    @Test
    fun `lit un QR code plus petit a l ecran, comme tenu a 30 cm`() {
        assertArrayEquals(key.toCharArray(), ZxingQrDecoder().decode(frameOf(key, scale = 3)))
    }

    /**
     * Une image de caméra 1920 × 1080 (luminance) où le QR code, marge blanche comprise, n'occupe que [share] de la largeur,
     * posé au centre sur un fond clair, puis flou : flou moyen 3 × 3 repassé [blurPasses] fois (simule une mise au point un peu courte).
     */
    private fun cameraFrameOf(text: String, share: Double, blurPasses: Int): LumaFrame {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4))
        val width = 1920
        val height = 1080
        val side = (width * share).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        var luma = IntArray(width * height) { 235 }
        for (y in 0 until side) for (x in 0 until side) {
            val dark = matrix.get(x * matrix.width / side, y * matrix.height / side)
            luma[(top + y) * width + left + x] = if (dark) 25 else 235
        }
        repeat(blurPasses) {
            val next = luma.copyOf()
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += luma[(y + dy) * width + x + dx]
                next[y * width + x] = sum / 9
            }
            luma = next
        }
        return LumaFrame(ByteArray(width * height) { luma[it].toByte() }, width, width, height)
    }

    @Test
    fun `lit un QR code dense de 166 caracteres qui n occupe que 30 pour cent d une image 1920x1080, legerement floue`() {
        val dense = ("sk-proj-" + "FAKEcleDeTest0123_-".repeat(9)).take(166)
        assertEquals(166, dense.length)
        // 30 % de 1920 px = 576 px pour 61 modules (marge comprise) : un peu moins de 10 px par module avant le flou.
        assertArrayEquals(dense.toCharArray(), ZxingQrDecoder().decode(cameraFrameOf(dense, share = 0.30, blurPasses = 0)))
        assertArrayEquals(dense.toCharArray(), ZxingQrDecoder().decode(cameraFrameOf(dense, share = 0.30, blurPasses = 1)))
    }

    @Test
    fun `le meme lecteur sert pour toutes les images, avec ou sans QR code`() {
        val decoder = ZxingQrDecoder()
        val noise = ByteArray(300 * 300).also { Random(7).nextBytes(it) }
        assertNull(decoder.decode(LumaFrame(noise, 300, 300, 300)))
        assertNull(decoder.decode(LumaFrame(ByteArray(300 * 300) { -1 }, 300, 300, 300)))
        assertNotNull(decoder.decode(frameOf(key)))
    }

    @Test
    fun `une image trop petite pour son contenu est refusee des la creation`() {
        val problem = runCatching { LumaFrame(ByteArray(10), 8, 8, 8) }.exceptionOrNull()
        assertNotNull(problem)
    }
}
