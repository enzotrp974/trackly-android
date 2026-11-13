package app.trackly.core

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ForgetFormattingNotes
import app.trackly.core.action.Rejection
import app.trackly.core.action.RememberFact
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.FormattingNotes
import app.trackly.core.model.Preference
import app.trackly.core.model.RecordState
import app.trackly.core.model.TermCorrections
import app.trackly.core.store.InMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Nettoyage unique des notes de mise en forme : une seule action, annulable, qui ne retire que les notes de forme.
 * Jeu d'essai inventé (aucune donnée personnelle), construit sur les types de notes réellement rencontrés.
 */
class FormattingNotesTest {
    private val store = InMemoryStore()
    private val engine = TracklyEngine(store, Clock.fixed(Instant.parse("2026-11-06T18:00:00Z"), ZoneOffset.UTC))

    private fun note(id: String, kind: String, trigger: String, rule: String, active: Boolean = true, state: RecordState = RecordState.Active) =
        Preference(id, 1, state, kind, trigger, rule, active, Instant.EPOCH, null)

    /** Notes de forme, à retirer. */
    private val markdown = note("m-forme", AgentMemory.KIND, "Affichage des macros", "L'application n'affiche pas le Markdown : écrire en texte brut, une macro par ligne.")
    private val tables = note("i-tableaux", "interaction_preference", "propositions", "Ne jamais faire de tableaux, l'écran les casse.")
    private val emoji = note("d-emoji", "display_preference", "réponses", "Pas d'émoji dans les réponses.")

    /** Notes à garder : contenu, goûts, routines, ou autre type de règle. */
    private val keep = listOf(
        note("m-gouts", AgentMemory.KIND, "Goûts", "Chocolat noir et sauces ; déteste le thon en boîte."),
        note("m-fibres", AgentMemory.KIND, "Ce qui compte", "Calories, protéines, glucides et lipides. Pas de fibres dans les réponses."),
        note("d-fibres", "display_preference", "récapitulatif", "Ne plus afficher les fibres dans les propositions et bilans."),
        note("i-bilan", "interaction_preference", "bilan du lundi", "Présenter la semaine du lundi au dimanche, analyse claire et rapide à lire."),
        note("m-gras", AgentMemory.KIND, "Fromage", "Préfère le fromage gras, et les matières grasses en petite quantité."),
        note("m-liste", AgentMemory.KIND, "Courses", "Fait sa liste de courses le dimanche, au supermarché."),
        note("c-riz", "cooking_default", "riz", "Le riz est pesé cuit, comme dans le tableau de l ancien suivi."),
        note("t-md", TermCorrections.KIND, "markdown", "Markdown"),
        note("i-off", "interaction_preference", "ancien", "Tout présenter en tableau Markdown.", active = false),
        // Doute : forme et goût mêlés dans la même note, elle reste entière.
        note("m-mixte", AgentMemory.KIND, "Goûts", "Chocolat noir, déteste le thon ; pas d'émojis."),
        note("d-mixte", "display_preference", "bilan", "Tableau des 7 jours, avec les recettes les plus mangées."),
    )

    private fun seed(vararg notes: Preference) = runBlocking { store.transaction { notes.forEach { preferences.insert(it) } } }

    private suspend fun current(id: String) = store.transaction { preferences.current(id) }!!

    @Test
    fun `le critere ne garde que les mots de mise en forme sans autre sens`() {
        val bold = note("d-gras", "display_preference", "réponses", "Pas de texte en gras dans les réponses.")
        for (n in listOf(markdown, tables, emoji, bold)) assertTrue(n.trigger, FormattingNotes.matches(n))
        for (n in keep) assertFalse(n.preferenceId, FormattingNotes.matches(n))
        assertFalse(FormattingNotes.matches(markdown.copy(state = RecordState.Deleted)))
    }

    @Test
    fun `une preference alimentaire jointe a une consigne de forme reste`() {
        val mixed = listOf(
            note("m-vege", AgentMemory.KIND, "Régime", "Régime : végétarien. Présenter les suggestions en tableaux."),
            note("m-vege2", AgentMemory.KIND, "Suggestions", "Végétarien, présenter les suggestions en tableaux."),
            note("i-halal", "interaction_preference", "propositions", "Uniquement halal ; propositions en tableau."),
            note("d-lactose", "display_preference", "réponses", "Sans lactose. Pas d'émoji."),
            // Une seule phrase, aucun mot de goût connu, mais une préférence quand même.
            note("m-sucre", AgentMemory.KIND, "Suggestions", "Sans sucre et sans sel, réponses en tableaux."),
            note("m-diabete", AgentMemory.KIND, "Réponses", "Toujours en tableau, je suis diabétique."),
            note("i-budget", "interaction_preference", "propositions", "Propositions pas chères, en tableau."),
            // « gras » seul n'est pas « en gras ».
            note("m-gras2", AgentMemory.KIND, "Suggestions", "Sans gras, réponses en tableaux."),
            note("m-page", AgentMemory.KIND, "Réponses", "Une page de recettes, en tableau."),
        )
        for (n in mixed) assertFalse(n.rule, FormattingNotes.matches(n))
    }

    @Test
    fun `une seule action retire toutes les notes de forme, et rien d autre`() = runBlocking {
        seed(markdown, tables, emoji, *keep.toTypedArray())
        val before = keep.associate { it.preferenceId to current(it.preferenceId) }

        val applied = engine.execute(ForgetFormattingNotes) as ActionOutcome.Applied
        assertEquals(3, applied.result.changes.size)
        assertEquals("Mémoire : 3 notes de mise en forme retirées", applied.result.title)
        for (id in listOf("m-forme", "i-tableaux", "d-emoji")) {
            val now = current(id)
            assertEquals(RecordState.Deleted, now.state)
            assertEquals(2, now.version)
        }
        for ((id, version) in before) assertEquals(id, version, current(id))
        assertTrue(engine.activePreferences().none { FormattingNotes.matches(it) })

        // Annulable d'un seul geste : les trois notes reviennent.
        engine.execute(Undo(applied.result.id)) as ActionOutcome.Applied
        for (id in listOf("m-forme", "i-tableaux", "d-emoji")) assertEquals(RecordState.Active, current(id).state)
    }

    @Test
    fun `une seule fois par base, meme annulee, et une note ajoutee ensuite reste`() = runBlocking {
        seed(markdown)
        val applied = engine.execute(ForgetFormattingNotes) as ActionOutcome.Applied
        engine.execute(Undo(applied.result.id)) as ActionOutcome.Applied

        // L'annulation est respectée : le nettoyage ne repasse pas.
        assertEquals(Rejection.NoChange, (engine.execute(ForgetFormattingNotes) as ActionOutcome.Rejected).rejection)
        assertEquals(RecordState.Active, current("m-forme").state)

        engine.execute(RememberFact("Tableaux", "Aime les tableaux pour comparer.")) as ActionOutcome.Applied
        assertTrue(engine.execute(ForgetFormattingNotes) is ActionOutcome.Rejected)
        assertEquals(2, AgentMemory.notes(engine.activePreferences()).size)
    }

    @Test
    fun `rien a retirer - le passage est inscrit sans rien ecrire, et une note retenue ensuite reste`() = runBlocking {
        seed(*keep.toTypedArray())
        val pass = engine.execute(ForgetFormattingNotes) as ActionOutcome.Applied
        assertTrue(pass.result.changes.isEmpty())
        for (n in keep) assertEquals(1, current(n.preferenceId).version)
        // Rien à annuler : « annule » ne le vise pas, et il ne repasse jamais sur cette base (ni sur ses copies).
        assertEquals(Rejection.NothingToUndo, (engine.execute(UndoLast) as ActionOutcome.Rejected).rejection)
        engine.execute(RememberFact("Tableaux", "Aime voir un tableau pour comparer.")) as ActionOutcome.Applied
        assertEquals(Rejection.NoChange, (engine.execute(ForgetFormattingNotes) as ActionOutcome.Rejected).rejection)
        assertTrue(AgentMemory.notes(engine.activePreferences()).any { it.trigger == "Tableaux" })
    }

    @Test
    fun `annule vise le dernier geste de l utilisateur, pas le nettoyage`() = runBlocking {
        val thon = (engine.execute(RememberFact("Thon", "Déteste le thon.")) as ActionOutcome.Applied).result
        seed(markdown)
        val cleanup = engine.execute(ForgetFormattingNotes) as ActionOutcome.Applied

        val undone = engine.execute(UndoLast) as ActionOutcome.Applied
        assertEquals(thon.id, undone.result.undoes)
        assertEquals(RecordState.Deleted, current("m-forme").state)
        // Le nettoyage reste annulable par son identifiant.
        engine.execute(Undo(cleanup.result.id)) as ActionOutcome.Applied
        assertEquals(RecordState.Active, current("m-forme").state)
    }
}
