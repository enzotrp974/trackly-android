package app.trackly.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.trackly.assistant.openai.ImageInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** Photo illisible ou vide : [message] est affichable tel quel. */
class PhotoException(override val message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Prépare une photo pour l'assistant : lecture, rotation selon l'orientation EXIF, réduction
 * (côté long ≤ [PhotoLimits.MAX_SIDE_PX]), compression JPEG et encodage en data URL base64.
 * Tout se passe en mémoire, hors du fil principal ; rien n'est écrit sur le disque.
 */
class PhotoPreparer(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Lit [uri] puis, quoi qu'il arrive, supprime [deleteAfterRead] : le fichier temporaire de l'appareil photo
     * ne survit pas à sa lecture. Une photo du sélecteur système (galerie) n'est jamais modifiée.
     */
    suspend fun prepare(uri: Uri, deleteAfterRead: File? = null): PreparedPhoto = withContext(io) {
        try {
            val source = try {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: IOException) {
                throw PhotoException("Impossible de lire la photo.", e)
            } catch (e: SecurityException) {
                throw PhotoException("Impossible de lire la photo.", e)
            } ?: throw PhotoException("Impossible de lire la photo.")
            // La copie en mémoire suffit : le fichier temporaire peut disparaître tout de suite.
            deleteAfterRead?.delete()
            encode(source)
        } finally {
            deleteAfterRead?.delete()
        }
    }

    private fun encode(source: ByteArray): PreparedPhoto {
        if (source.isEmpty()) throw PhotoException("La photo est vide.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoException("Ce fichier n'est pas une image lisible.")
        val options = BitmapFactory.Options().apply { inSampleSize = PhotoLimits.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw PhotoException("Ce fichier n'est pas une image lisible.")
        val oriented = orient(decoded, exifOrientation(source))
        val finalBitmap = flatten(scaleDown(oriented))
        val thumbnail = thumbnailOf(finalBitmap)
        val jpeg = ByteArrayOutputStream().use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, PhotoLimits.JPEG_QUALITY, out)
            out.toByteArray()
        }
        // Image plus petite que la vignette : createScaledBitmap renvoie le même objet, qui ne doit pas être recyclé.
        if (thumbnail !== finalBitmap) finalBitmap.recycle()
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        return PreparedPhoto(UUID.randomUUID().toString(), ImageInput(url), thumbnail, jpeg.size)
    }

    private fun exifOrientation(source: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val (w, h) = PhotoLimits.scaledSize(bitmap.width, bitmap.height)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Le JPEG n'a pas de transparence : une image à fond transparent (capture, PNG) est posée sur du blanc. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return flat
    }

    private fun thumbnailOf(bitmap: Bitmap): Bitmap {
        val (w, h) = PhotoLimits.scaledSize(bitmap.width, bitmap.height, PhotoLimits.THUMBNAIL_SIDE_PX)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
