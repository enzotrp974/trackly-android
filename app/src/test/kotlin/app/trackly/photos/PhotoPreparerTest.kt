package app.trackly.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/**
 * Préparation des photos : réduction et compression sur le téléphone, rotation EXIF, fond blanc des images
 * transparentes, et suppression du fichier temporaire de l'appareil photo, quoi qu'il arrive.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPreparerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preparer = PhotoPreparer(context.contentResolver, Dispatchers.Unconfined)
    private val temp = CameraTempFiles(context)

    @After
    fun clean() {
        temp.purge()
    }

    private fun jpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(230, 220, 200))
            drawRect(0f, 0f, width / 2f, height / 2f, Paint().apply { color = Color.rgb(20, 60, 160) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun decode(photo: PreparedPhoto): Bitmap {
        val url = photo.image.url
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.getDecoder().decode(url.removePrefix("data:image/jpeg;base64,"))
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "JPEG illisible" }
    }

    @Test
    fun `une grande photo est reduite a 1600 px et envoyee en JPEG base64 high`() = runBlocking<Unit> {
        val file = temp.createFile()
        jpeg(file, 3200, 2400)

        val photo = preparer.prepare(Uri.fromFile(file), deleteAfterRead = file)

        val decoded = decode(photo)
        assertEquals(1600, decoded.width)
        assertEquals(1200, decoded.height)
        assertEquals("high", photo.image.detail)
        assertTrue("JPEG trop lourd : ${photo.bytes}", photo.bytes < 800_000)
        // Vignette d'aperçu petite.
        assertTrue(maxOf(photo.thumbnail!!.width, photo.thumbnail!!.height) <= PhotoLimits.THUMBNAIL_SIDE_PX)
    }

    @Test
    fun `une petite photo n est pas agrandie`() = runBlocking<Unit> {
        val file = temp.createFile()
        jpeg(file, 800, 600)

        val decoded = decode(preparer.prepare(Uri.fromFile(file), deleteAfterRead = file))

        assertEquals(800, decoded.width)
        assertEquals(600, decoded.height)
    }

    @Test
    fun `une image plus petite que la vignette garde une vignette utilisable, non recyclee`() = runBlocking<Unit> {
        val file = temp.createFile()
        jpeg(file, 120, 90)

        val photo = preparer.prepare(Uri.fromFile(file), deleteAfterRead = file)

        val thumbnail = photo.thumbnail!!
        assertFalse("La vignette ne doit pas être recyclée", thumbnail.isRecycled)
        assertEquals(120, thumbnail.width)
        assertEquals(90, thumbnail.height)
        assertEquals(120, decode(photo).width)
    }

    @Test
    fun `le fichier temporaire de l appareil photo est supprime apres lecture`() = runBlocking<Unit> {
        val file = temp.createFile()
        jpeg(file, 1000, 800)
        assertTrue(file.exists())

        preparer.prepare(Uri.fromFile(file), deleteAfterRead = file)

        assertFalse(file.exists())
        assertTrue(File(context.cacheDir, CameraTempFiles.DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `le fichier temporaire est supprime meme si la photo est illisible`() = runBlocking<Unit> {
        val file = temp.createFile()
        file.writeText("ceci n'est pas une image")

        try {
            preparer.prepare(Uri.fromFile(file), deleteAfterRead = file)
            fail("Une PhotoException était attendue")
        } catch (e: PhotoException) {
            assertEquals("Ce fichier n'est pas une image lisible.", e.message)
        }
        assertFalse(file.exists())
    }

    @Test
    fun `une photo du sélecteur systeme n est jamais supprimee ni modifiee`() = runBlocking<Unit> {
        val gallery = File(context.cacheDir, "galerie.jpg")
        jpeg(gallery, 900, 900)
        val before = gallery.readBytes()

        preparer.prepare(Uri.fromFile(gallery))

        assertTrue(gallery.exists())
        assertTrue(before.contentEquals(gallery.readBytes()))
        gallery.delete()
    }

    @Test
    fun `la rotation EXIF est appliquee`() = runBlocking<Unit> {
        val file = temp.createFile()
        jpeg(file, 2400, 1800)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val decoded = decode(preparer.prepare(Uri.fromFile(file), deleteAfterRead = file))

        // Portrait après rotation, côté long à 1600 px.
        assertEquals(1200, decoded.width)
        assertEquals(1600, decoded.height)
    }

    @Test
    fun `une image transparente est posee sur fond blanc`() = runBlocking<Unit> {
        val file = temp.createFile()
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val decoded = decode(preparer.prepare(Uri.fromFile(file), deleteAfterRead = file))

        val pixel = decoded.getPixel(100, 100)
        assertTrue("pixel ${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun `tailles calculees`() {
        assertEquals(1600 to 1200, PhotoLimits.scaledSize(4000, 3000))
        assertEquals(1200 to 1600, PhotoLimits.scaledSize(3000, 4000))
        assertEquals(1000 to 800, PhotoLimits.scaledSize(1000, 800))
        assertEquals(1, PhotoLimits.sampleSize(1600, 1200))
        assertEquals(2, PhotoLimits.sampleSize(4000, 3000))
        assertEquals(4, PhotoLimits.sampleSize(8000, 6000))
    }
}
