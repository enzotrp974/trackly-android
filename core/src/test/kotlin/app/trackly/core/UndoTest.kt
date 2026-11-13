package app.trackly.core

import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.action.Rejection
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.action.UndoStatus
import app.trackly.core.model.ActionId
import app.trackly.core.model.Moment
import app.trackly.core.model.RecordState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Annuler » rétablit l'état précédent par une nouvelle version, sans effacer l'historique. */
class UndoTest {
    private val f = Fixture()

    @Test
    fun `annuler un ajout le retire du bilan par une version deleted`() = runTest {
        f.add("Riz", 400.0)
        val pomme = f.add("Pomme", 80.0)

        val result = f.apply(UndoLast)

        assertEquals(400.0, f.summary().totals.kcal, 0.0)
        assertEquals(listOf(RecordState.Active, RecordState.Deleted), f.engine.history(pomme).map { it.state })
        assertEquals("Annulation : Ajout : Pomme (80 kcal)", result.title)
    }

    @Test
    fun `annuler une correction retablit le contenu precedent en version 3`() = runTest {
        val id = f.add("Barre protéinée", 220.0, Moment.Snack)
        f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0), moment = Moment.Midi))

        f.apply(UndoLast)

        val history = f.engine.history(id)
        assertEquals(listOf(1, 2, 3), history.map { it.version })
        assertEquals(history[0].food, history[2].food)
        assertEquals(Moment.Snack, history[2].moment)
        assertEquals(220.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `annuler une suppression rend la consommation active`() = runTest {
        val id = f.add("Pomme", 80.0)
        f.apply(DeleteConsumption(id))

        f.apply(UndoLast)

        assertEquals(80.0, f.summary().totals.kcal, 0.0)
        assertEquals(listOf(RecordState.Active, RecordState.Deleted, RecordState.Active), f.engine.history(id).map { it.state })
    }

    @Test
    fun `une declaration de plusieurs aliments s annule d un seul coup`() = runTest {
        f.add("Riz", 400.0)
        f.apply(
            app.trackly.core.action.AddConsumptions(
                listOf(
                    app.trackly.core.action.NewConsumption(food("Barre protéinée", 220.0), Moment.Snack),
                    app.trackly.core.action.NewConsumption(food("Pomme", 80.0), Moment.Snack),
                ),
            ),
        )
        f.apply(UndoLast)
        assertEquals(listOf("Riz"), f.summary().consumptions.map { it.food.label })
    }

    @Test
    fun `annuler la derniere action remonte l historique pas a pas`() = runTest {
        f.add("Riz", 400.0)
        f.add("Pomme", 80.0)
        f.add("Yaourt", 120.0)

        f.apply(UndoLast)
        f.apply(UndoLast)

        assertEquals(listOf("Riz"), f.summary().consumptions.map { it.food.label })
    }

    @Test
    fun `annuler une action precise, plus ancienne`() = runTest {
        f.add("Riz", 400.0)
        val first = f.engine.observeRecentActions(1).first().single().id
        f.add("Pomme", 80.0)

        f.apply(Undo(first))

        assertEquals(listOf("Pomme"), f.summary().consumptions.map { it.food.label })
    }

    @Test
    fun `annuler une annulation retablit l action d origine`() = runTest {
        f.add("Pomme", 80.0)
        val undo = f.apply(UndoLast)

        f.apply(Undo(undo.id))
        assertEquals(80.0, f.summary().totals.kcal, 0.0)

        // Le rétablissement est la dernière modification : « Annuler » le défait.
        f.apply(UndoLast)
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
    }

    @Test
    fun `apres annuler puis retablir, annuler remonte ensuite aux actions plus anciennes`() = runTest {
        f.add("Riz", 400.0)
        f.add("Pomme", 80.0)
        val undo = f.apply(UndoLast)
        f.apply(Undo(undo.id))

        f.apply(UndoLast) // défait le rétablissement : la pomme repart
        assertEquals(listOf("Riz"), f.summary().consumptions.map { it.food.label })
        f.apply(UndoLast) // puis le riz
        assertEquals(emptyList<String>(), f.summary().consumptions.map { it.food.label })
        assertEquals(Rejection.NothingToUndo, f.reject(UndoLast))
    }

    @Test
    fun `une action deja annulee ne s annule pas deux fois`() = runTest {
        f.add("Pomme", 80.0)
        val undo = f.apply(UndoLast)
        val target = undo.undoes!!
        assertTrue(f.reject(Undo(target)) is Rejection.AlreadyUndone)
        assertEquals(Rejection.NothingToUndo, f.reject(UndoLast))
    }

    @Test
    fun `rien a annuler au depart`() = runTest {
        assertEquals(Rejection.NothingToUndo, f.reject(UndoLast))
    }

    @Test
    fun `annuler un ajout modifie depuis est refuse, rien n est ecrase`() = runTest {
        val id = f.add("Barre protéinée", 220.0)
        val add = f.engine.observeRecentActions(1).first().single().id
        f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))

        assertTrue(f.reject(Undo(add)) is Rejection.UndoConflict)
        assertEquals(2, f.engine.history(id).size)
        assertEquals(240.0, f.summary().totals.kcal, 0.0)
    }

    /**
     * La correction a été annulée, la consommation a retrouvé exactement ce qu'avait écrit l'ajout. La carte
     * de l'ajout n'est plus « corrigée depuis » : « Annuler » (bouton ou « annule » au chat) la retire, sans conflit.
     */
    @Test
    fun `correction annulee - l ajout n est plus perime et s annule`() = runTest {
        val id = f.add("Barre protéinée", 220.0)
        val add = f.engine.observeRecentActions(1).first().single().id
        val correction = f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))
        f.apply(Undo(correction.id))

        assertEquals(false, f.engine.undoStatuses(listOf(add)).getValue(add).superseded)
        val undo = f.apply(UndoLast)

        assertEquals(add, undo.undoes)
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
        assertEquals(RecordState.Deleted, f.engine.history(id).last().state)
    }

    /** Même règle quand une seconde correction remet exactement le contenu de l'ajout : rien n'est écrasé en annulant l'ajout. */
    @Test
    fun `correction remise a l identique par une autre correction - l ajout s annule`() = runTest {
        val id = f.add("Barre protéinée", 220.0)
        val add = f.engine.observeRecentActions(1).first().single().id
        f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))
        f.apply(CorrectConsumption(id, food = f.engine.history(id).first().food))

        assertEquals(false, f.engine.undoStatuses(listOf(add)).getValue(add).superseded)
        f.apply(Undo(add))
        assertEquals(RecordState.Deleted, f.engine.history(id).last().state)
    }

    @Test
    fun `le resultat d annulation decrit les changements pour la carte`() = runTest {
        val id = f.add("Pomme", 80.0)
        val result = f.apply(UndoLast)
        val change = result.consumptionChanges.single()
        assertEquals(id, change.after.entryId)
        assertEquals(RecordState.Active, change.before!!.state)
        assertEquals(RecordState.Deleted, change.after.state)
        assertEquals(result.id, change.after.actionId)
    }

    @Test
    fun `annuler puis retablir puis annuler, des titres lisibles sans annulation imbriquee`() = runTest {
        f.add("Pomme", 80.0)
        val undo = f.apply(UndoLast)
        val redo = f.apply(Undo(undo.id))
        val again = f.apply(Undo(redo.id))

        assertEquals("Annulation : Ajout : Pomme (80 kcal)", undo.title)
        assertEquals("Rétablissement : Ajout : Pomme (80 kcal)", redo.title)
        assertEquals("Annulation : Ajout : Pomme (80 kcal)", again.title)
    }

    @Test
    fun `statut d annulation - en vigueur, annulee, retablie, avec le maillon a annuler`() = runTest {
        f.add("Pomme", 80.0)
        val add = f.engine.observeRecentActions(1).first().single().id
        assertEquals(UndoStatus(add, inEffect = true, head = add, superseded = false), f.engine.undoStatuses(listOf(add))[add])

        val undo = f.apply(Undo(add))
        assertEquals(UndoStatus(add, inEffect = false, head = undo.id, superseded = false), f.engine.undoStatuses(listOf(add))[add])

        // « Rétablir » : annuler le maillon en tête de chaîne.
        val redo = f.apply(Undo(f.engine.undoStatuses(listOf(add)).getValue(add).head))
        assertEquals(80.0, f.summary().totals.kcal, 0.0)
        assertEquals(UndoStatus(add, inEffect = true, head = redo.id, superseded = false), f.engine.undoStatuses(listOf(add))[add])

        // « Annuler » à nouveau, depuis la même carte : encore le maillon en tête.
        f.apply(Undo(f.engine.undoStatuses(listOf(add)).getValue(add).head))
        assertEquals(0.0, f.summary().totals.kcal, 0.0)
        assertTrue(f.engine.undoStatuses(listOf(add)).getValue(add).undone)
    }

    @Test
    fun `statut d annulation - une correction plus recente rend la carte perimee`() = runTest {
        val id = f.add("Barre protéinée", 220.0)
        val add = f.engine.observeRecentActions(1).first().single().id
        val correction = f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))

        val statuses = f.engine.undoStatuses(listOf(add, correction.id, ActionId(999)))

        assertTrue(statuses.getValue(add).superseded)
        assertTrue(statuses.getValue(add).inEffect)
        assertEquals(false, statuses.getValue(correction.id).superseded)
        assertEquals(setOf(add, correction.id), statuses.keys)
    }

    @Test
    fun `statut d annulation - une correction annulee puis corrigee autrement ne peut plus etre retablie`() = runTest {
        val id = f.add("Barre protéinée", 220.0)
        val first = f.apply(CorrectConsumption(id, food = food("Barre protéinée chocolat", 240.0)))
        f.apply(Undo(first.id))
        f.apply(CorrectConsumption(id, food = food("Barre protéinée vanille", 230.0)))

        val status = f.engine.undoStatuses(listOf(first.id)).getValue(first.id)
        assertTrue(status.undone)
        assertTrue(status.superseded)
        assertTrue(f.reject(Undo(status.head)) is Rejection.UndoConflict)
    }
}
