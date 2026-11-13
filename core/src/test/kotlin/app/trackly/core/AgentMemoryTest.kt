package app.trackly.core

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ForgetFact
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.Rejection
import app.trackly.core.action.RememberFact
import app.trackly.core.action.SeedKnowledge
import app.trackly.core.action.SeedMemory
import app.trackly.core.action.SeedTerm
import app.trackly.core.action.Undo
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.Provisions
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.KnowledgeSeedReader
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.TermCorrections
import app.trackly.core.store.InMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AgentMemoryTest {
    private val engine = TracklyEngine(InMemoryStore(), Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"), ZoneOffset.UTC))

    private suspend fun notes() = AgentMemory.notes(engine.activePreferences())

    private fun applied(outcome: ActionOutcome) = outcome as ActionOutcome.Applied

    @Test
    fun `retenir, mettre a jour, oublier, annuler`() = runBlocking {
        applied(engine.execute(RememberFact("Thon", "N'aime pas le thon en boîte.")))
        val id = notes().single().preferenceId

        applied(engine.execute(RememberFact("Thon", "Thon en boîte seulement en salade.", id)))
        assertEquals("Thon en boîte seulement en salade.", notes().single().rule)

        val forgotten = applied(engine.execute(ForgetFact(id)))
        assertTrue(notes().isEmpty())

        applied(engine.execute(Undo(forgotten.result.id)))
        assertEquals("Thon en boîte seulement en salade.", notes().single().rule)
    }

    @Test
    fun `la meme note ne cree rien et une note introuvable est refusee`() = runBlocking {
        applied(engine.execute(RememberFact("Thon", "Non.")))
        val id = notes().single().preferenceId
        val same = engine.execute(RememberFact("Thon", "Non.", id)) as ActionOutcome.Rejected
        assertEquals(Rejection.NoChange, same.rejection)
        assertTrue(engine.execute(ForgetFact("inconnue")) is ActionOutcome.Rejected)
    }

    @Test
    fun `les notes ne sont ni des mots corriges ni melangees aux regles`() = runBlocking {
        applied(engine.execute(RememberFact("skir", "Une note.")))
        assertTrue(TermCorrections.active(engine.activePreferences()).isEmpty())
    }

    @Test
    fun `connaissances de depart - lues du fichier, une seule fois, sans reecrire ce qui a change`() = runBlocking {
        val seed = KnowledgeSeedReader.read(
            """{"memories":[{"id":"mem-matin","topic":"Matin","text":"Œufs."}],"terms":[{"heard":"skir","correct":"Skyr"}],"stock":["Œufs","Bananes"]}""",
        )
        assertEquals(SeedKnowledge(listOf(SeedMemory("mem-matin", "Matin", "Œufs.")), listOf(SeedTerm("skir", "Skyr")), listOf("Œufs", "Bananes")), seed)
        applied(engine.execute(seed))
        assertEquals("Skyr", TermCorrections.active(engine.activePreferences()).single().correct)
        assertEquals(2, Provisions.held(engine.allProvisions()).size)

        // Changements de l'utilisateur : ils restent, même si les connaissances repassent.
        applied(engine.execute(RememberFact("Matin", "Plus d'œufs le lundi.", "mem-matin")))
        applied(engine.execute(UpdateProvisions(out = listOf("Bananes"))))
        val again = engine.execute(seed) as ActionOutcome.Rejected
        assertEquals(Rejection.NoChange, again.rejection)
        assertEquals("Plus d'œufs le lundi.", notes().single().rule)
        assertEquals(listOf("Œufs"), Provisions.held(engine.allProvisions()).map { it.name })
    }

    @Test
    fun `connaissances avec des doublons - ecrites une seule fois, sans erreur`() = runBlocking {
        applied(
            engine.execute(
                SeedKnowledge(
                    listOf(SeedMemory("m", "A", "a"), SeedMemory("m", "A", "b")),
                    listOf(SeedTerm("skir", "Skyr"), SeedTerm("Skir", "Skyr")),
                    listOf("Œufs", "œufs"),
                ),
            ),
        )
        assertEquals(1, notes().size)
        assertEquals(1, TermCorrections.active(engine.activePreferences()).size)
        assertEquals(1, Provisions.held(engine.allProvisions()).size)
    }

    @Test
    fun `un aliment du stock sorti revient d un mot, sans rien reecrire`() = runBlocking {
        applied(engine.execute(UpdateProvisions(have = listOf(ProvisionInput("Thon")))))
        applied(engine.execute(UpdateProvisions(out = listOf("Thon"))))
        assertTrue(Provisions.held(engine.allProvisions()).isEmpty())
        assertEquals("Thon", engine.allProvisions().single().name)

        applied(engine.execute(UpdateProvisions(have = listOf(ProvisionInput("Thon")))))
        assertEquals(1, engine.allProvisions().size)
        assertEquals("Thon", Provisions.held(engine.allProvisions()).single().name)
    }
}
