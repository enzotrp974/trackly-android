package app.trackly.call

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.call.android.AudioRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** APPEL-04 : sans focus audio (un vrai appel téléphonique en cours…), aucun appel payant ne part. */
@RunWith(AndroidJUnit4::class)
class AudioRouterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val am = context.getSystemService(AudioManager::class.java)

    @Test
    fun `focus audio refuse - aucune sortie rendue, mode du telephone remis`() {
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val router = AudioRouter(context)
        assertNull(router.begin())
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
    }

    @Test
    fun `focus audio accorde - sortie rendue`() {
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val router = AudioRouter(context)
        assertNotNull(router.begin())
        router.end()
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
    }
}
