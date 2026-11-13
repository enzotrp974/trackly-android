package app.trackly.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.chat.SharedTurnMarkerStore
import app.trackly.assistant.chat.TurnMarker
import app.trackly.assistant.context.CorrectionFocus
import app.trackly.assistant.openai.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Le repère « tour en cours » survit à un nouveau processus (un nouveau magasin relit le même fichier). */
@RunWith(AndroidJUnit4::class)
class TurnMarkerStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = Instant.parse("2026-11-02T15:10:00Z")

    @Test
    fun `repere avec cible de correction - relu a l identique par un nouveau magasin`() {
        val marker = TurnMarker(42, at, CorrectionFocus(actionId = 7, entryIds = listOf("e-1", "e-2")), "Riz basmati, Poulet rôti")
        SharedTurnMarkerStore(context).write(marker)
        assertEquals(marker, SharedTurnMarkerStore(context).read())
    }

    @Test
    fun `repere sans cible - relu sans cible, et une cible sans action est gardee`() {
        val plain = TurnMarker(1, at)
        SharedTurnMarkerStore(context).write(plain)
        assertEquals(plain, SharedTurnMarkerStore(context).read())

        val fromDay = TurnMarker(2, at, CorrectionFocus(actionId = null, entryIds = listOf("e-9")), "Pomme")
        SharedTurnMarkerStore(context).write(fromDay)
        assertEquals(fromDay, SharedTurnMarkerStore(context).read())
    }

    @Test
    fun `niveau de raisonnement - relu a l identique, et Moyen si la valeur est inconnue`() {
        val low = TurnMarker(5, at, effort = ReasoningEffort.Low)
        SharedTurnMarkerStore(context).write(low)
        assertEquals(low, SharedTurnMarkerStore(context).read())
        val max = TurnMarker(6, at, effort = ReasoningEffort.Max)
        SharedTurnMarkerStore(context).write(max)
        assertEquals(ReasoningEffort.Max, SharedTurnMarkerStore(context).read()!!.effort)

        context.getSharedPreferences("trackly-tour-en-cours", Context.MODE_PRIVATE).edit().putString("effort", "inconnu").commit()
        assertEquals(ReasoningEffort.Medium, SharedTurnMarkerStore(context).read()!!.effort)
    }

    @Test
    fun `effacer - plus aucun repere, et rien d autre que des identifiants n est ecrit`() {
        val store = SharedTurnMarkerStore(context)
        assertNull(store.read())
        store.write(TurnMarker(3, at, CorrectionFocus(null, listOf("e-1")), "Riz"))
        val raw = context.getSharedPreferences("trackly-tour-en-cours", Context.MODE_PRIVATE).all
        // Pas de texte de message ni de photo : identifiant, heure, cible et libellé seulement.
        assertTrue(raw.keys.toString(), raw.keys.all { it in setOf("message", "debut", "action", "consommations", "libelle", "effort") })
        store.clear()
        assertNull(SharedTurnMarkerStore(context).read())
    }
}
