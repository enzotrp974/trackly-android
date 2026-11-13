package app.trackly.assistant

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ImportOutcome
import app.trackly.testing.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les préférences importées sont lues par l'assistant. Seules les règles actives
 * figurent au contexte, dans la partie stable (avant « Maintenant »), pour ne pas casser le cache d'entrée.
 */
class PreferencesContextTest {
    private val rice = "Le riz est pesé cuit sauf mention contraire."
    private val yogurt = "Un yaourt fait 125 g."

    @Test
    fun `une preference importee et active est transmise a l assistant avec son declencheur`() = runBlocking {
        val engine = SampleData.emptyEngine()
        assertTrue(HistoryImport.run(engine, SampleData.historyBundle) is ImportOutcome.Imported)

        val text = ContextBuilder.default().build(ContextEnv(engine, engine.now()))

        assertTrue(text, text.contains("## Préférences de l'utilisateur"))
        assertTrue(text, text.contains("riz"))
        assertTrue(text, text.contains(rice))
        // Règle désactivée : jamais transmise.
        assertFalse(text, text.contains(yogurt))
        // Partie stable du contexte : avant l'heure et la journée en cours.
        assertTrue(text.indexOf(rice) < text.indexOf("## Maintenant"))
    }

    @Test
    fun `sans preference, aucune section`() = runBlocking {
        val engine = SampleData.emptyEngine()
        val text = ContextBuilder.default().build(ContextEnv(engine, engine.now()))
        assertFalse(text, text.contains("Préférences"))
    }

    @Test
    fun `le contexte des preferences est identique d un message a l autre`() = runBlocking {
        val engine = SampleData.emptyEngine()
        HistoryImport.run(engine, SampleData.historyBundle)
        val provider = ContextBuilder.default().providers.single { it.id == "preferences" }
        val first = provider.render(ContextEnv(engine, engine.now()))
        val later = provider.render(ContextEnv(engine, engine.now().plusSeconds(3600)))
        assertEquals(first, later)
    }
}
