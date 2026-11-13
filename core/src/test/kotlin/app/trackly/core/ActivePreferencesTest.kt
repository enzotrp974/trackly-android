package app.trackly.core

import app.trackly.core.importing.HistoryImport
import app.trackly.core.importing.ParityBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le moteur expose les préférences courantes actives (les règles désactivées restent en base). */
class ActivePreferencesTest {
    @Test
    fun `seules les preferences actives sont exposees`() = runBlocking {
        val f = Fixture()
        HistoryImport.run(f.engine, ParityBundle.text)

        val active = f.engine.activePreferences()

        assertEquals(listOf("riz"), active.map { it.trigger })
        assertEquals("Le riz est pesé cuit sauf mention contraire.", active.single().rule)
    }

    @Test
    fun `aucune preference sans reprise`() = runBlocking {
        assertEquals(emptyList<Any>(), Fixture().engine.activePreferences())
    }
}
