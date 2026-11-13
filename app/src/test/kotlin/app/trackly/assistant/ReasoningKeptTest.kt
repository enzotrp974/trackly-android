package app.trackly.assistant

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.model.SharedReasoningSettings
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.backup.PortableSettings
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Le niveau de raisonnement choisi reste choisi : d'un message à l'autre, et après un redémarrage de l'app.
 * Le niveau enregistré dans le compteur de coût est celui qui est parti dans la requête.
 */
@RunWith(AndroidJUnit4::class)
class ReasoningKeptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = SampleData.engine(SampleData.clock("2026-11-02T16:10"))
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")
    private val usage = mutableListOf<UsageEvent>()

    @After
    fun clean() {
        context.getSharedPreferences(SharedReasoningSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    private fun effortOf(request: JsonObject) = request["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content

    /** Un « démarrage » de l'app : des préférences relues, un assistant et un écran de chat neufs. */
    private fun startApp(transport: ScriptedTransport = ScriptedTransport.replies("question-reponse.json")): ChatViewModel {
        val services = AssistantServices.inMemory(
            engine, transport, key, usageSink = UsageSink { usage += it }, reasoning = SharedReasoningSettings(context),
        )
        return ChatViewModel(services, SavedStateHandle())
    }

    private fun ChatViewModel.sendAndWait(text: String, answered: Int) {
        send(text)
        idleUntil { state.value.messages.size == answered * 2 && !state.value.thinking }
    }

    @Test
    fun `par defaut le niveau est moyen`() {
        assertEquals(ReasoningEffort.Medium, startApp().effort.value)
    }

    @Test
    fun `envois successifs - le niveau choisi part a chaque message et dans le compteur de cout`() {
        val transport = ScriptedTransport.replies("question-reponse.json", "question-reponse.json", "question-reponse.json")
        val model = startApp(transport)
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }

        model.selectEffort(ReasoningEffort.High)
        model.sendAndWait("combien de calories si je mange une pizza ?", 1)
        assertEquals(ReasoningEffort.High, model.effort.value)
        model.sendAndWait("et une pomme ?", 2)
        model.sendAndWait("et une poire ?", 3)

        assertEquals(listOf("high", "high", "high"), transport.requests.map(::effortOf))
        assertEquals(listOf(ReasoningEffort.High, ReasoningEffort.High, ReasoningEffort.High), usage.map { it.effort })

        // Un nouveau choix vaut aussitôt, puis reste à son tour.
        model.selectEffort(ReasoningEffort.Low)
        assertEquals(ReasoningEffort.Low, model.effort.value)
        watch.cancel()
    }

    @Test
    fun `apres un redemarrage de l app le niveau choisi est retrouve et part dans la requete`() {
        val before = startApp()
        before.selectEffort(ReasoningEffort.XHigh)

        val transport = ScriptedTransport.replies("question-reponse.json")
        val after = startApp(transport)
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { after.state.collect { } }

        assertEquals(ReasoningEffort.XHigh, after.effort.value)
        after.sendAndWait("combien de calories si je mange une pizza ?", 1)
        assertEquals(listOf("xhigh"), transport.requests.map(::effortOf))
        assertEquals(listOf(ReasoningEffort.XHigh), usage.map { it.effort })
        watch.cancel()
    }

    @Test
    fun `le niveau choisi voyage dans la copie Drive et revient apres restauration`() {
        SharedReasoningSettings(context).setEffort(ReasoningEffort.Max)
        val prefsOf = PortableSettings.prefsOf(context)
        val copied = PortableSettings.read(prefsOf)
        assertEquals(listOf("raisonnement"), copied.filter { it.file == "trackly-assistant" }.map { it.key })

        // Appareil neuf : préférences vides, puis réglages de la copie remis au démarrage.
        context.getSharedPreferences(SharedReasoningSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ReasoningEffort.Medium, SharedReasoningSettings(context).effort.value)
        PortableSettings.write(prefsOf, copied)

        assertEquals(ReasoningEffort.Max, SharedReasoningSettings(context).effort.value)
    }

    @Test
    fun `un niveau inconnu dans les preferences donne moyen`() {
        context.getSharedPreferences(SharedReasoningSettings.FILE, Context.MODE_PRIVATE)
            .edit().putString(SharedReasoningSettings.KEY, "niveau-du-futur").commit()

        assertEquals(ReasoningEffort.Medium, startApp().effort.value)
    }
}
