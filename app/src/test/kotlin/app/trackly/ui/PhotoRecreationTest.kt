package app.trackly.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.photos.CameraTempFiles
import app.trackly.ui.photos.PhotoPickerState
import app.trackly.ui.photos.rememberPhotoPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Si l'activité est recréée pendant que l'appareil photo est ouvert, la capture en cours
 * n'est pas supprimée au démarrage ; les autres captures abandonnées, elles, sont toujours nettoyées.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoRecreationTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val temp = CameraTempFiles(context)
    private fun files(): List<File> = File(context.cacheDir, CameraTempFiles.DIR_NAME).listFiles().orEmpty().toList()

    @Test
    fun `la recreation de l activite pendant l appareil photo garde la capture en cours et purge les autres`() {
        // Trackly déclare la permission CAMERA (scan du QR code) : « Appareil photo » la demande avant d'ouvrir l'appareil.
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
        val abandoned = temp.createFile().also { it.writeText("ancienne capture") }
        val restoration = StateRestorationTester(compose)
        lateinit var picker: PhotoPickerState
        restoration.setContent { picker = rememberPhotoPicker(remaining = 3, onPhotos = {}, onProblem = {}) }
        compose.waitForIdle()
        // Au premier affichage, la capture abandonnée est nettoyée.
        assertFalse(abandoned.exists())

        compose.runOnIdle { picker.takePhoto() }
        val pending = files().single()
        pending.writeText("capture en cours")
        val otherAbandoned = temp.createFile().also { it.writeText("autre capture abandonnée") }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertTrue("La capture en cours doit survivre à la recréation", pending.exists())
        assertEquals("capture en cours", pending.readText())
        assertFalse("Les autres captures abandonnées sont nettoyées", otherAbandoned.exists())
        assertEquals(listOf(pending.name), files().map { it.name })
    }
}
