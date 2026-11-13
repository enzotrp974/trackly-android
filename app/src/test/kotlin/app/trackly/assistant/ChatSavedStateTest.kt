package app.trackly.assistant

import android.graphics.Bitmap
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ImageInput
import app.trackly.photos.PreparedPhoto
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Brouillon, correction visée et photos jointes quand Android ferme l'app en arrière-plan : le texte et la
 * correction reviennent par l'état sauvegardé ; les photos, jamais gardées, sont signalées. Vignettes en mémoire.
 */
@RunWith(AndroidJUnit4::class)
class ChatSavedStateTest {
    private val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    /** Ce qu'Android garderait de l'état sauvegardé : une copie des valeurs (jamais l'objet lui-même). */
    private fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    private fun photo(id: String) = PreparedPhoto(id, ImageInput("data:image/jpeg;base64,AAAA"), Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 4)

    @Test
    fun `brouillon et correction visee reviennent apres la mort du processus, les photos sont signalees`() {
        val services = AssistantServices.inMemory(engine, keyStore = key)
        val handle = SavedStateHandle()
        val before = ChatViewModel(services, handle)
        before.updateDraft("brouillon en cours")
        before.startCorrection(RecordCard(7, RecordKind.Added, listOf(CardItem("e1", "Barre protéinée", moment = "snack", day = "2026-11-02", kcal = 220.0))))
        before.addPhotos(listOf(photo("a"), photo("b")))

        val after = ChatViewModel(services, handle.afterProcessDeath())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { after.state.collect { } }
        idleUntil { after.state.value.correcting != null }

        assertEquals("brouillon en cours", after.draft.value)
        assertEquals("Barre protéinée", after.state.value.correcting!!.label)
        assertEquals(7L, after.state.value.correcting!!.focus.actionId)
        assertEquals(listOf("e1"), after.state.value.correcting!!.focus.entryIds)
        // Les photos ne sont jamais gardées : seul leur nombre l'est, pour le dire.
        assertTrue(after.state.value.photos.isEmpty())
        assertEquals(
            "Android a fermé Trackly en arrière-plan : les 2 photos jointes n’ont pas été gardées (Trackly ne garde jamais les photos). Joins-les à nouveau.",
            after.state.value.notice,
        )
        assertTrue(handle.keys().none { handle.get<Any?>(it) is Bitmap || handle.get<Any?>(it) is ImageInput })
        watch.cancel()
    }

    @Test
    fun `le debut propose par un plus ne remplace jamais un texte tape`() {
        val model = ChatViewModel(AssistantServices.inMemory(engine, keyStore = key), SavedStateHandle())
        model.prefill("Ce matin : ")
        assertEquals("Ce matin : ", model.draft.value)
        // Un autre « + » remplace le début proposé resté intact…
        model.prefill("Ce soir : ")
        assertEquals("Ce soir : ", model.draft.value)
        // … et le « + » du bas l'enlève.
        model.prefill(null)
        assertEquals("", model.draft.value)
        // Mais un texte tapé reste.
        model.updateDraft("une pomme")
        model.prefill("À midi : ")
        assertEquals("une pomme", model.draft.value)
    }

    @Test
    fun `vignettes des photos envoyees gardees en memoire pour la bulle, rien dans l historique`() {
        val transport = ScriptedTransport.replies("question-reponse.json")
        val services = AssistantServices.inMemory(engine, transport, key)
        val model = ChatViewModel(services, SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        model.addPhotos(listOf(photo("a")))
        model.send("c'est combien ?")
        idleUntil { model.state.value.messages.size == 2 && !model.state.value.thinking }

        val userMessage = model.state.value.messages.first()
        assertEquals(1, model.state.value.thumbnails[userMessage.id]?.size)
        assertEquals(1, userMessage.photoCount)
        assertEquals("c'est combien ?\n[1 photo envoyée]", userMessage.text)
        assertNull(model.state.value.notice)
        watch.cancel()
    }
}
