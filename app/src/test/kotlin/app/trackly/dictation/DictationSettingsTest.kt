package app.trackly.dictation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Réglage du mode de dictée : « en direct » par défaut, mémorisé d'un lancement à l'autre. */
@RunWith(AndroidJUnit4::class)
class DictationSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        context.getSharedPreferences("trackly-dictee", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `par defaut - en direct`() {
        assertEquals(DictationMode.Live, SharedDictationSettings(context).mode.value)
        assertEquals("gpt-live-transcribe", DictationMode.Live.model)
    }

    @Test
    fun `le mode apres l arret est memorise`() {
        SharedDictationSettings(context).setMode(DictationMode.AfterStop)
        val reopened = SharedDictationSettings(context)
        assertEquals(DictationMode.AfterStop, reopened.mode.value)
        assertEquals("gpt-transcribe", reopened.mode.value.model)
        reopened.setMode(DictationMode.Live)
        assertEquals(DictationMode.Live, SharedDictationSettings(context).mode.value)
    }

    @Test
    fun `un code inconnu (version plus recente, fichier abime) donne le mode par defaut`() {
        context.getSharedPreferences("trackly-dictee", Context.MODE_PRIVATE).edit().putString("mode", "avenir").commit()
        assertEquals(DictationMode.Live, SharedDictationSettings(context).mode.value)
        assertEquals(DictationMode.Live, DictationMode.fromCode(null))
    }

    @Test
    fun `la demande de permission du micro est memorisee`() {
        val settings = SharedDictationSettings(context)
        assertFalse(settings.micRequested)
        settings.micRequested = true
        assertTrue(SharedDictationSettings(context).micRequested)
    }

    @Test
    fun `chaque mode a son modele et ses tarifs dans la table de prix`() {
        for (mode in DictationMode.entries) {
            assertTrue("${mode.model} sans tarif", app.trackly.usage.PriceTable.transcriptionRate(mode.model) != null)
        }
    }
}
