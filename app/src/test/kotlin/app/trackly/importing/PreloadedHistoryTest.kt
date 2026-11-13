package app.trackly.importing

import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ImportReport
import app.trackly.setup.InMemorySetupPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadedHistoryTest {
    private val prefs = InMemorySetupPrefs()
    private val imported = mutableListOf<String>()
    private val report = ImportReport(61, null, null, 300, 70, 10, 5, 400, 0, 61, null, emptyList())
    private var outcome: ImportOutcome = ImportOutcome.Imported(report)
    private var embedded: String? = "export"

    private val preload = PreloadedHistory(
        read = { embedded },
        import = {
            imported += it
            outcome
        },
        prefs = prefs,
        baseHasData = { false },
    )

    @Test
    fun `APK sans historique - rien ne se passe`() = runBlocking {
        embedded = null
        assertFalse(preload.runOnce())
        assertTrue(imported.isEmpty())
        assertFalse(prefs.historyPreloaded)
    }

    @Test
    fun `reprise reussie - une seule fois, meme apres un redemarrage`() = runBlocking {
        assertTrue(preload.runOnce())
        assertTrue(prefs.historyPreloaded)
        assertFalse(preload.runOnce())
        assertEquals(listOf("export"), imported)
    }

    @Test
    fun `historique deja la - considere comme repris`() = runBlocking {
        outcome = ImportOutcome.NothingNew(report)
        assertTrue(preload.runOnce())
        assertTrue(prefs.historyPreloaded)
    }

    @Test
    fun `reprise refusee - retentee au lancement suivant`() = runBlocking {
        outcome = ImportOutcome.Refused("Conflit")
        assertFalse(preload.runOnce())
        assertFalse(prefs.historyPreloaded)

        outcome = ImportOutcome.Imported(report)
        assertTrue(preload.runOnce())
        assertEquals(2, imported.size)
    }

    @Test
    fun `fichier embarque abime - pas de plantage, retente plus tard`() = runBlocking {
        val broken = PreloadedHistory(read = { throw IllegalStateException("abîmé") }, import = { outcome }, prefs = prefs, baseHasData = { false })
        assertFalse(broken.runOnce())
        assertFalse(prefs.historyPreloaded)
    }

    @Test
    fun `historique refuse - les connaissances passent quand meme, et la reprise reste a refaire`() = runBlocking {
        var seeded = 0
        outcome = ImportOutcome.Refused("Conflit")
        val refused = PreloadedHistory(read = { embedded }, import = { outcome }, prefs = prefs, baseHasData = { false }, seed = {
            seeded++
            true
        })
        assertFalse(refused.runOnce())
        assertEquals(1, seeded)
        assertFalse(prefs.historyPreloaded)
    }

    @Test
    fun `base illisible - pas de plantage, rien n'est marque ni importe`() = runBlocking {
        val unreadable = PreloadedHistory(read = { embedded }, import = {
            imported += it
            outcome
        }, prefs = prefs, baseHasData = { throw IllegalStateException("base illisible") })
        assertFalse(unreadable.runOnce())
        assertTrue(imported.isEmpty())
        assertFalse(prefs.historyPreloaded)
    }

    @Test
    fun `APK sans historique sur une base remplie - rien n'est marque`() = runBlocking {
        embedded = null
        val filled = PreloadedHistory(read = { embedded }, import = { outcome }, prefs = prefs, baseHasData = { true })
        assertEquals(PreloadDecision.NothingEmbedded, filled.decide())
        assertFalse(filled.runOnce())
        assertFalse(prefs.historyPreloaded)
    }
}
