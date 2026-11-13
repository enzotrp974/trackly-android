package app.trackly.core.goal

import app.trackly.core.Fixture
import app.trackly.core.action.Rejection
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.day
import app.trackly.core.model.ActionId
import app.trackly.core.model.Goal
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.RecordState
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.core.review.WeightHistory
import app.trackly.core.store.ImportedSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Poids de référence mis à jour après chaque bilan : calcul, version datée qui ne change pas le passé, annulation
 * exacte, refus qui dit quoi faire, et croisement avec l'objectif temporaire ([SetTemporaryGoal]).
 *
 * Données fictives : profil à 70 kg, 2 g/kg d'idéal, 1,6 g/kg de minimum, 1 g/kg de lipides, 2 300 kcal.
 */
class ReferenceWeightTest {
    private val base = GoalTargets(kcal = 2300.0, proteinG = 140.0, carbsG = 277.5, fatG = 70.0, proteinMinG = 112.0)
    private val bilanDay = day("2026-11-06")

    private suspend fun fixture(profile: String? = PROFILE): Fixture {
        val f = Fixture("2026-11-06T09:00")
        if (profile != null) f.store.transaction { imports.insert(ImportedSource("profile", "profile", 1, "sha", profile, ActionId(1))) }
        f.apply(SetGoal(base, from = day("2026-10-01")))
        return f
    }

    private suspend fun Fixture.weigh(vararg pairs: Pair<String, Double>) {
        apply(DeclareWeekInfo(day("2026-10-01"), weighIns = pairs.map { (d, kg) -> WeighIn(day(d), kg) }))
    }

    /** Ce qui compte pour l'utilisateur : la suite des objectifs en vigueur et le poids de référence. */
    private suspend fun Fixture.state() = engine.goals().map { it.effectiveFrom to it.targets } to engine.referenceWeight(bilanDay)

    // ───── Calcul pur ─────

    @Test
    fun `pesees - moyenne des 7 derniers jours, sinon derniere pesee de 14 jours au plus`() {
        val history = WeightHistory.of(
            emptyList(),
            listOf(WeighIn(day("2026-10-20"), 66.0), WeighIn(day("2026-10-31"), 65.0), WeighIn(day("2026-11-02"), 65.4), WeighIn(day("2026-11-05"), 65.25)),
        )
        val avg = ReferenceWeights.measured(history, bilanDay)!!
        assertEquals(65.2, avg.kg, 0.0) // (65 + 65,4 + 65,25) / 3 = 65,216… → 65,2 ; le 31 octobre est dans les 7 jours (31 → 6)
        assertEquals(3, avg.count)
        assertTrue(avg.averaged)

        val last = ReferenceWeights.measured(history, day("2026-11-15"))!! // aucune pesée du 9 au 15 : la dernière, du 5 (10 jours)
        assertEquals(65.3, last.kg, 0.0)
        assertEquals(day("2026-11-05"), last.lastDay)
        assertTrue(!last.averaged)

        assertNull(ReferenceWeights.measured(history, day("2026-11-20"))) // dernière pesée de 15 jours : rien d'utilisable
        assertNull(ReferenceWeights.measured(history, day("2026-10-19"))) // aucune pesée connue à cette date
        assertNull(ReferenceWeights.measured(WeightHistory.Empty, bilanDay))
    }

    @Test
    fun `grammes - memes g par kg, calories identiques, macros absentes gardees absentes`() {
        val after = ReferenceWeights.rescaled(base, 70.0, 65.0)
        assertEquals(2300.0, after.kcal, 0.0)
        assertEquals(130.0, after.proteinG!!, 1e-9) // 2 g/kg
        assertEquals(104.0, after.proteinMinG!!, 1e-9) // 1,6 g/kg, pas une autre valeur
        assertEquals(65.0, after.fatG!!, 1e-9) // 1 g/kg
        assertEquals(macroKcal(base), macroKcal(after), 0.02) // les glucides prennent la différence
        val partial = GoalTargets(kcal = 2000.0, proteinG = 140.0)
        assertEquals(GoalTargets(kcal = 2000.0, proteinG = 130.0), ReferenceWeights.rescaled(partial, 70.0, 65.0))
        // Glucides jamais négatifs, même pour une hausse absurde.
        assertEquals(0.0, ReferenceWeights.rescaled(base.copy(carbsG = 10.0), 70.0, 140.0).carbsG!!, 0.0)
    }

    @Test
    fun `une suite de bilans ne fait pas deriver l arrondi - poids qui descend puis remonte`() {
        val weights = (0..40).map { 74.0 - it * 0.3 } + (1..40).map { 62.0 + it * 0.3 } // 74 → 62 → 74, 81 bilans
        var targets = base.copy(proteinG = 148.0, proteinMinG = 118.4, fatG = 74.0, carbsG = (2300.0 - 148 * 4 - 74 * 9) / 4) // à 74 kg
        var previous = 74.0
        for (kg in weights.drop(1)) {
            targets = ReferenceWeights.rescaled(targets, previous, kg)
            previous = kg
            assertEquals("calories strictement constantes", 2300.0, targets.kcal, 0.0)
            assertEquals("idéal 2 g/kg à $kg kg", 2.0, targets.proteinG!! / kg, 0.001)
            assertEquals("minimum 1,6 g/kg à $kg kg", 1.6, targets.proteinMinG!! / kg, 0.001)
            assertEquals("lipides 1 g/kg à $kg kg", 1.0, targets.fatG!! / kg, 0.001)
            assertEquals("calories des macros à $kg kg", 2300.0, macroKcal(targets), 0.5)
        }
        // Revenu à 74 kg : les grammes du départ, au centième près.
        assertEquals(148.0, targets.proteinG!!, 0.05)
        assertEquals(118.4, targets.proteinMinG!!, 0.05)
        assertEquals(74.0, targets.fatG!!, 0.05)
    }

    @Test
    fun `poids du profil - celui de la regle en vigueur, sinon celui du profil`() {
        val profile = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"current_reference_weight_kg": 68.0, "goals": [{"effective_from": "2026-09-01", "reference_weight_kg": 74.0}, {"effective_from": "2026-10-15"}]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(74.0, ReferenceWeights.fromProfile(profile, day("2026-10-01"))!!, 0.0)
        assertEquals(68.0, ReferenceWeights.fromProfile(profile, day("2026-10-15"))!!, 0.0)
        assertEquals(68.0, ReferenceWeights.fromProfile(profile, day("2026-08-01"))!!, 0.0)
        assertNull(ReferenceWeights.fromProfile(null, bilanDay))
    }

    // ───── Action datée, passé intact ─────

    @Test
    fun `mise a jour - nouvelle version datee du jour, le passe ne bouge pas, calories inchangees`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-02" to 65.0, "2026-11-04" to 65.4, "2026-11-06" to 65.2)
        val pastDay = f.engine.daySummary(day("2026-11-05")).goal

        val outcome = f.engine.updateReferenceWeight(bilanDay)
        val change = outcome.plan as ReferenceWeightPlan.Change
        assertEquals(70.0, change.beforeKg, 0.0)
        assertEquals(65.2, change.afterKg, 0.0)
        val result = f.engine.action(outcome.action!!)!!
        assertEquals("Poids de référence : 70 → 65,2 kg, calories inchangées", result.title)

        val goals = f.engine.goals()
        assertEquals(listOf(day("2026-10-01"), bilanDay), goals.map { it.effectiveFrom })
        assertEquals(base, goals[0].targets) // l'objectif d'avant n'est pas réécrit
        val now = goals[1].targets
        assertEquals(2300.0, now.kcal, 0.0)
        assertEquals(130.4, now.proteinG!!, 1e-9)
        assertEquals(104.32, now.proteinMinG!!, 1e-9)
        assertEquals(65.2, now.fatG!!, 1e-9)
        assertEquals(297.9, now.carbsG!!, 1e-9)
        assertEquals(pastDay, f.engine.daySummary(day("2026-11-05")).goal) // la veille garde son objectif
        assertEquals(now, f.engine.daySummary(bilanDay).goal)
        assertEquals(65.2, f.engine.referenceWeight(bilanDay)!!, 0.0)
        assertEquals(65.2, f.engine.referenceWeight(day("2026-12-01"))!!, 0.0)
    }

    @Test
    fun `la mise a jour suivante part du poids enregistre`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 66.0)
        f.engine.updateReferenceWeight(bilanDay)
        f.clock.setLocal("2026-11-13T09:00")
        f.weigh("2026-11-13" to 65.0)
        val second = f.engine.updateReferenceWeight(day("2026-11-13")).plan as ReferenceWeightPlan.Change
        assertEquals(66.0, second.beforeKg, 0.0)
        val targets = f.engine.goals().last().targets
        assertEquals(130.0, targets.proteinG!!, 1e-9) // 2 g/kg à 65 kg, depuis 132 g à 66 kg
        assertEquals(104.0, targets.proteinMinG!!, 1e-9)
    }

    @Test
    fun `objectifs deja prevus - chacun reecrit avec ses propres calories`() = runBlocking {
        val f = fixture()
        val later = base.copy(kcal = 2100.0, carbsG = 227.5)
        f.apply(SetGoal(later, from = day("2026-11-20")))
        f.weigh("2026-11-06" to 63.0)
        f.engine.updateReferenceWeight(bilanDay)
        val goals = f.engine.goals().associate { it.effectiveFrom to it.targets }
        assertEquals(2300.0, goals.getValue(bilanDay).kcal, 0.0)
        assertEquals(2100.0, goals.getValue(day("2026-11-20")).kcal, 0.0)
        assertEquals(126.0, goals.getValue(day("2026-11-20")).proteinG!!, 1e-9)
        assertEquals(macroKcal(later), macroKcal(goals.getValue(day("2026-11-20"))), 0.02)
    }

    @Test
    fun `sans pesee utilisable, meme poids, sans reference - rien ne change`() = runBlocking {
        val f = fixture()
        val before = f.state()
        assertEquals(ReferenceWeightPlan.NoWeighIn(70.0), f.engine.updateReferenceWeight(bilanDay).plan)
        f.weigh("2026-10-20" to 64.0) // 17 jours : trop ancienne
        assertEquals(ReferenceWeightPlan.NoWeighIn(70.0), f.engine.updateReferenceWeight(bilanDay).plan)
        assertTrue(f.reject(UpdateReferenceWeight(bilanDay)) is Rejection.Invalid)
        f.weigh("2026-11-05" to 70.04)
        assertTrue(f.engine.updateReferenceWeight(bilanDay).plan is ReferenceWeightPlan.Unchanged)
        assertTrue(f.reject(UpdateReferenceWeight(bilanDay)) is Rejection.NoChange)
        assertEquals(before, f.state())

        val noProfile = fixture(profile = null)
        noProfile.weigh("2026-11-05" to 65.0)
        assertEquals(ReferenceWeightPlan.NoReference, noProfile.engine.updateReferenceWeight(bilanDay).plan)
    }

    @Test
    fun `derniere pesee de moins de 14 jours - elle sert`() = runBlocking {
        val f = fixture()
        f.weigh("2026-10-27" to 66.0) // 10 jours avant, aucune dans les 7 derniers
        val change = f.engine.updateReferenceWeight(bilanDay).plan as ReferenceWeightPlan.Change
        assertEquals(66.0, change.afterKg, 0.0)
        assertTrue(!change.measured.averaged)
    }

    @Test
    fun `jamais un jour passe`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-05" to 65.0)
        assertTrue(f.reject(UpdateReferenceWeight(day("2026-11-05"))) is Rejection.Invalid)
    }

    @Test
    fun `proteines qui passeraient sous le minimum - refuse, rien n est ecrit`() = runBlocking {
        val f = fixture()
        // Un objectif incohérent (idéal sous le minimum) ne peut venir que d'une base abîmée : il est refusé, pas réécrit.
        f.store.transaction { goals.insert(Goal(day("2026-11-10"), 1, RecordState.Active, base.copy(proteinG = 100.0), Instant.EPOCH, ActionId(99))) }
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val plan = f.engine.updateReferenceWeight(bilanDay).plan
        assertTrue(plan.toString(), plan is ReferenceWeightPlan.Refused)
        assertTrue(f.reject(UpdateReferenceWeight(bilanDay)).message.contains("minimum"))
        assertEquals(before, f.state())
    }

    // ───── Annulation ─────

    @Test
    fun `annuler remet exactement l etat d avant`() = runBlocking {
        val f = fixture()
        f.apply(SetGoal(base.copy(kcal = 2100.0, carbsG = 227.5), from = day("2026-11-20")))
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val undo = f.apply(Undo(id))
        assertEquals("Annulation : Poids de référence : 70 → 65 kg, calories inchangées", undo.title)
        assertEquals(before, f.state())
        assertEquals(70.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
    }

    @Test
    fun `annule par erreur depuis le chat - jamais vise par annule le dernier geste`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        f.engine.updateReferenceWeight(bilanDay)
        f.apply(UndoLast) // vise la pesée, le dernier geste de l'utilisateur
        assertEquals(65.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
    }

    @Test
    fun `objectif ecrit apres la mise a jour - Annuler refuse et dit quoi defaire, puis marche`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val settings = f.apply(SetGoal(f.engine.goals().last().targets.copy(kcal = 2200.0, carbsG = 272.5), from = day("2026-11-10")))
        val refused = f.reject(Undo(id))
        assertTrue(refused.message, refused.message.startsWith("Annule d'abord « ${settings.title} »"))
        f.apply(Undo(settings.id))
        f.apply(Undo(id))
        assertEquals(before, f.state())
    }

    // ───── Croisement avec l'objectif temporaire (vraie action SetTemporaryGoal) ─────

    @Test
    fun `objectif temporaire pose apres - refus qui dit quoi defaire, puis annuler l objectif temporaire et le poids rend l etat d avant le bilan`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0))
        val refused = f.reject(Undo(id))
        assertTrue(refused.message, refused.message.startsWith("Annule d'abord « Objectif temporaire"))
        assertEquals(65.0, f.engine.referenceWeight(bilanDay)!!, 0.0) // rien n'a bougé
        f.apply(Undo(temporary.id))
        f.apply(Undo(id))
        assertEquals(before, f.state())
    }

    @Test
    fun `objectif temporaire qui commence le jour meme - meme parcours`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val temporary = f.apply(SetTemporaryGoal(bilanDay, days = 3, dropKcal = 100.0)) // réécrit l'objectif du jour de la mise à jour
        assertTrue(f.reject(Undo(id)).message.startsWith("Annule d'abord « Objectif temporaire"))
        f.apply(Undo(temporary.id)) // remet le contenu de la mise à jour, dans une version plus récente
        f.apply(Undo(id))
        assertEquals(before, f.state())
    }

    @Test
    fun `objectif temporaire pose avant - recalcule a calories constantes, statut retrouve, Annuler du poids le rend tel quel`() = runBlocking {
        val f = fixture()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0))
        f.weigh("2026-11-06" to 65.0)
        val before = f.state()
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val goals = f.engine.goals().associate { it.effectiveFrom to it.targets }
        assertEquals(2300.0, goals.getValue(bilanDay).kcal, 0.0)
        assertEquals(2200.0, goals.getValue(day("2026-11-07")).kcal, 0.0) // l'objectif temporaire reste temporaire
        assertEquals(2300.0, goals.getValue(day("2026-11-11")).kcal, 0.0) // son retour revient aux calories d'avant
        assertEquals(130.0, goals.getValue(day("2026-11-07")).proteinG!!, 1e-9)
        assertEquals(goals.getValue(bilanDay), goals.getValue(day("2026-11-11"))) // le retour rejoint l'objectif recalculé
        val status = f.engine.temporaryGoal(day("2026-11-07"))!!
        assertEquals(TemporaryPhase.Scheduled, status.phase) // l'objectif temporaire est retrouvé malgré la réécriture
        assertTrue(status.canToggle)
        f.apply(Undo(id))
        assertEquals(before, f.state())
        f.apply(Undo(temporary.id)) // l'objectif temporaire s'annule encore ensuite
        assertEquals(listOf(day("2026-10-01")), f.engine.goals().map { it.effectiveFrom })
    }

    @Test
    fun `objectif temporaire pose avant, annule apres le bilan - Annuler du poids rend l etat d avant, sans faire revenir l objectif temporaire`() = runBlocking {
        val f = fixture()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0))
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        f.apply(Undo(temporary.id)) // accepté malgré la réécriture à calories constantes
        assertEquals(listOf(day("2026-10-01"), bilanDay), f.engine.goals().map { it.effectiveFrom })
        f.apply(Undo(id))
        assertEquals(listOf(day("2026-10-01") to base), f.engine.goals().map { it.effectiveFrom to it.targets })
        assertEquals(70.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
    }

    @Test
    fun `cas rare - premier jour qui portait deja un objectif, annule apres le bilan - il revient avec les macros du nouveau poids`() = runBlocking {
        val f = fixture()
        val planned = base.copy(kcal = 2100.0, carbsG = 227.5)
        f.apply(SetGoal(planned, from = day("2026-11-07")))
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 3, dropKcal = 100.0))
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        f.apply(Undo(temporary.id))
        val back = f.engine.goals().associate { it.effectiveFrom to it.targets }.getValue(day("2026-11-07"))
        assertEquals(ReferenceWeights.rescaled(planned, 70.0, 65.0), back) // pas les macros d'avant la mise à jour
        assertEquals(2100.0, back.kcal, 0.0)
        // Le poids de référence ne s'annule plus : l'objectif du 7 a été réécrit par l'annulation, sur le nouveau poids.
        assertTrue(f.reject(Undo(id)).message.contains("ne peut plus être annulé"))
        assertEquals(65.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
    }

    // ───── Relecture : annulations voisines ─────

    @Test
    fun `apres Annuler du poids, l objectif pose avant s annule encore`() = runBlocking {
        val f = fixture()
        val today = f.apply(SetGoal(base.copy(kcal = 2400.0, carbsG = 302.5), from = bilanDay))
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        f.apply(Undo(id)) // l'objectif du jour revient au contenu de la carte, dans une version plus récente
        f.apply(Undo(today.id))
        assertEquals(listOf(day("2026-10-01") to base), f.engine.goals().map { it.effectiveFrom to it.targets })
    }

    @Test
    fun `un objectif qui ne depend pas du poids, ecrit apres, ne bloque pas Annuler`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        // Calories seules, écrit après la mise à jour (un `SetGoal` reprendrait le minimum, qui, lui, dépend du poids).
        f.store.transaction { goals.insert(Goal(day("2026-11-12"), 1, RecordState.Active, GoalTargets(kcal = 2100.0), Instant.EPOCH, ActionId(9_999))) }
        f.apply(Undo(id))
        assertEquals(70.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
    }

    @Test
    fun `deja mis a jour ce jour-la - la meme action, jamais une seconde`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val first = f.engine.updateReferenceWeight(bilanDay)
        f.weigh("2026-11-05" to 64.0) // nouvelle moyenne 64,5
        val again = f.engine.updateReferenceWeight(bilanDay)
        assertEquals(first.action, again.action)
        assertEquals(65.0, f.engine.referenceWeight(bilanDay)!!, 0.0)
        val change = again.plan as ReferenceWeightPlan.Change
        assertEquals(70.0, change.beforeKg, 0.0)
        assertEquals(65.0, change.afterKg, 0.0)
        assertEquals((first.plan as ReferenceWeightPlan.Change).inForce, change.inForce)
    }

    @Test
    fun `deux bilans - le premier ne s annule qu apres le second`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 66.0)
        val first = f.engine.updateReferenceWeight(bilanDay).action!!
        f.clock.setLocal("2026-11-13T09:00")
        f.weigh("2026-11-13" to 65.0)
        val second = f.engine.updateReferenceWeight(day("2026-11-13")).action!!
        assertTrue(f.reject(Undo(first)).message.contains("Annule d'abord « Poids de référence : 66 → 65 kg"))
        f.apply(Undo(second))
        val past = f.days("2026-11-01", "2026-11-12")
        f.apply(Undo(first))
        assertEquals(70.0, f.engine.referenceWeight(day("2026-11-13"))!!, 0.0)
        // Le premier bilan date du 6, on est le 13 ; les jours du 6 au 12 gardent ses macros, l'objectif d'avant revient le 13.
        assertEquals(past, f.days("2026-11-01", "2026-11-12"))
        assertEquals(base, f.engine.daySummary(day("2026-11-13")).goal)
    }

    // ───── Le passé ne change jamais, même après Annuler et Rétablir ─────

    /** Objectif de chaque jour, du [from] au [to] inclus : ce que voient le suivi et les bilans. */
    private suspend fun Fixture.days(from: String, to: String) =
        generateSequence(day(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(day(to)) }.associateWith { engine.daySummary(it).goal }

    @Test
    fun `annuler apres le jour du bilan - retour des aujourd hui, les jours passes gardent leur objectif`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val updated = ReferenceWeights.rescaled(base, 70.0, 65.0)
        f.clock.setLocal("2026-11-08T09:00")
        val past = f.days("2026-11-01", "2026-11-07")
        assertEquals(updated, past.getValue(bilanDay))

        val undo = f.apply(Undo(id))
        assertEquals(past, f.days("2026-11-01", "2026-11-07")) // le 6 et le 7 gardent les macros du bilan
        assertEquals(base, f.engine.daySummary(day("2026-11-08")).goal) // l'objectif d'avant revient dès aujourd'hui
        assertEquals(base, f.engine.daySummary(day("2026-12-01")).goal)
        assertEquals(70.0, f.engine.referenceWeight(day("2026-11-08"))!!, 0.0)
        assertEquals(
            "Annulation : poids de référence revenu à 70 kg dès aujourd'hui, les jours passés gardent leur objectif",
            undo.title,
        )

        // Rétablir le même jour : la mise à jour revient dès aujourd'hui, le passé ne bouge toujours pas.
        f.apply(Undo(undo.id))
        assertEquals(past, f.days("2026-11-01", "2026-11-07"))
        assertEquals(updated, f.engine.daySummary(day("2026-11-08")).goal)
        assertEquals(65.0, f.engine.referenceWeight(day("2026-11-08"))!!, 0.0)
    }

    @Test
    fun `retablir apres le jour de l annulation - refuse, le passe ne change pas`() = runBlocking {
        val f = fixture()
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        val undo = f.apply(Undo(id)) // le jour même : tout revient comme avant
        f.clock.setLocal("2026-11-08T09:00")
        val past = f.days("2026-11-01", "2026-11-07")
        val before = f.state()
        val refused = f.reject(Undo(undo.id))
        assertEquals(
            "Les jours passés gardent leur objectif : ce poids de référence ne peut plus être rétabli. Le prochain bilan le remettra à jour.",
            refused.message,
        )
        assertEquals(past, f.days("2026-11-01", "2026-11-07"))
        assertEquals(before, f.state())
    }

    @Test
    fun `objectif temporaire en cours, Annuler du poids apres le bilan - le passe garde tout, la suite revient aux macros d avant`() = runBlocking {
        val f = fixture()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0)) // du 7 au 10, retour le 11
        f.weigh("2026-11-06" to 65.0)
        val id = f.engine.updateReferenceWeight(bilanDay).action!!
        f.clock.setLocal("2026-11-08T09:00")
        val past = f.days("2026-11-01", "2026-11-07")

        f.apply(Undo(id))
        assertEquals(past, f.days("2026-11-01", "2026-11-07"))
        val lowered = TemporaryGoals.lowered(base, 100.0)
        assertEquals(lowered, f.engine.daySummary(day("2026-11-08")).goal) // toujours l'objectif temporaire, aux macros d'avant
        assertEquals(lowered, f.engine.daySummary(day("2026-11-10")).goal)
        assertEquals(base, f.engine.daySummary(day("2026-11-11")).goal) // son retour, aux macros d'avant
        val status = f.engine.temporaryGoal(day("2026-11-07"))!!
        assertEquals(TemporaryPhase.Running, status.phase)
        assertTrue(status.canToggle)

        // L'objectif temporaire s'arrête encore ensuite, dès aujourd'hui, avec les macros d'avant.
        f.apply(Undo(temporary.id))
        assertEquals(past, f.days("2026-11-01", "2026-11-07"))
        assertEquals(base, f.engine.daySummary(day("2026-11-08")).goal)
        assertEquals(base, f.engine.daySummary(day("2026-11-11")).goal)
    }

    @Test
    fun `objectif temporaire annule a son premier jour, apres un bilan ce jour-la - macros du nouveau poids, puis Annuler du poids rend l etat d avant`() = runBlocking {
        val f = fixture()
        val before = f.state()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0)) // posé le 6, du 7 au 10
        f.clock.setLocal("2026-11-07T09:00")
        f.weigh("2026-11-07" to 65.0)
        val id = f.engine.updateReferenceWeight(day("2026-11-07")).action!! // le bilan du 7 réécrit le 7 et le retour du 11
        val updated = ReferenceWeights.rescaled(base, 70.0, 65.0)

        val cancel = f.apply(Undo(temporary.id)) // le premier jour : rien n'a encore compté
        assertEquals(updated, f.engine.daySummary(day("2026-11-07")).goal) // macros du poids de référence en vigueur, 65 kg
        assertEquals(updated, f.engine.daySummary(day("2026-11-12")).goal)
        assertEquals(65.0, f.engine.referenceWeight(day("2026-11-07"))!!, 0.0)
        assertEquals(TemporaryPhase.Cancelled, f.engine.temporaryGoal(day("2026-11-07"))!!.phase)

        // Rétablir l'objectif temporaire le même jour : sa baisse revient, aux macros du nouveau poids.
        val redo = f.apply(Undo(cancel.id))
        assertEquals(TemporaryGoals.lowered(updated, 100.0).kcal, f.engine.daySummary(day("2026-11-07")).goal!!.kcal, 0.0)
        assertEquals(updated.proteinMinG, f.engine.daySummary(day("2026-11-07")).goal!!.proteinMinG)
        f.apply(Undo(redo.id)) // seconde annulation : les versions viennent maintenant du rétablissement, pas du bilan
        assertEquals(updated, f.engine.daySummary(day("2026-11-07")).goal)
        assertEquals(updated, f.engine.daySummary(day("2026-11-12")).goal)
        assertEquals(TemporaryPhase.Cancelled, f.engine.temporaryGoal(day("2026-11-07"))!!.phase)

        // Annuler le poids ensuite : l'état d'avant le bilan, objectif temporaire annulé compris.
        f.apply(Undo(id))
        assertEquals(before, f.state())
    }

    @Test
    fun `ordre inverse, Annuler le poids puis l objectif temporaire, puis Retablir le poids - refuse, rien ne bouge`() = runBlocking {
        val f = fixture()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-07"), days = 4, dropKcal = 100.0)) // du 7 au 10
        f.clock.setLocal("2026-11-07T09:00")
        f.weigh("2026-11-07" to 65.0)
        val id = f.engine.updateReferenceWeight(day("2026-11-07")).action!!
        val undoWeight = f.apply(Undo(id))
        f.apply(Undo(temporary.id)) // les objectifs du 7 et du 11 sont retirés
        val before = f.state()
        assertEquals(listOf(day("2026-10-01") to base), before.first)

        val refused = f.reject(Undo(undoWeight.id))
        assertEquals(ReferenceWeightUndo.CHANGED_SINCE, refused.message)
        assertEquals(before, f.state()) // la référence reste à 70 kg, avec l'objectif à 140 g et minimum 112 g
        assertEquals(70.0, f.engine.referenceWeight(day("2026-11-07"))!!, 0.0)
    }

    @Test
    fun `sans bilan, des macros choisies avant le premier jour restent apres l annulation de l objectif temporaire`() = runBlocking {
        val f = fixture()
        val temporary = f.apply(SetTemporaryGoal(day("2026-11-08"), days = 3, dropKcal = 100.0))
        val chosen = base.copy(proteinG = 150.0, carbsG = 267.5) // mêmes calories, autres macros, à partir du 7
        f.apply(SetGoal(chosen, from = day("2026-11-07")))
        f.apply(Undo(temporary.id))
        assertEquals(chosen, f.engine.daySummary(day("2026-11-08")).goal) // jamais écrasé par le retour
        assertEquals(chosen, f.engine.daySummary(day("2026-11-12")).goal)
    }

    private fun macroKcal(t: GoalTargets) = GoalCoherence.macroKcal(t)

    private companion object {
        val PROFILE = """{"current_reference_weight_kg": 70.0, "goals": [{"effective_from": "2026-09-01", "kcal": 2300, "protein_ideal_g_per_kg": 2.0, "protein_min_g_per_kg": 1.6, "fat_g_per_kg": 1.0}]}"""
    }
}

