package app.trackly.review.page

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.context.review
import app.trackly.assistant.tools.ProduceReportTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.WeighIn
import app.trackly.core.store.ImportedSource
import app.trackly.core.store.InMemoryStore
import app.trackly.core.time.TrackingDay
import app.trackly.review.InMemoryReportRepository
import app.trackly.review.InMemoryReviewHistory
import app.trackly.review.ReviewPeriods
import app.trackly.review.RecordingReviewNotifier
import app.trackly.review.ReportRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Le poids de référence à la production du rapport : mis à jour une seule fois par période, bloc de la page écrit par
 * l'application (seulement ce qui change, toujours « calories inchangées »), rien sans pesée. Données fictives.
 */
class ReferenceWeightReportTest {
    private val now: Instant = LocalDateTime.parse("2026-11-06T20:00").atZone(TrackingDay.ZONE).toInstant()
    private val today = LocalDate.parse("2026-11-06")
    private val store = InMemoryStore()
    private val engine = TracklyEngine(store, Clock.fixed(now, TrackingDay.ZONE))
    private val reports = InMemoryReportRepository()
    private val base = GoalTargets(kcal = 2300.0, proteinG = 140.0, carbsG = 277.5, fatG = 69.2, proteinMinG = 112.0)

    private fun seed(weighIn: Double? = 69.5) = runBlocking {
        store.transaction { imports.insert(ImportedSource("profile", "profile", 1, "sha", PROFILE, ActionId(1))) }
        assertTrue(engine.execute(SetGoal(base, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        if (weighIn != null) assertTrue(engine.execute(DeclareWeekInfo(LocalDate.parse("2026-10-26"), weighIns = listOf(WeighIn(today.minusDays(1), weighIn)))) is ActionOutcome.Applied)
    }

    private suspend fun produce(repo: ReportRepository = reports) =
        ProduceReportTool(ReviewPeriods(repo, InMemoryReviewHistory()), RecordingReviewNotifier()).execute(buildJsonObject { put("analysis", "Une période régulière.") }, ToolContext(engine, now))

    private suspend fun page(id: Long) = ReportContent.decode(reports.get(id)!!.html)!!

    @Test
    fun `premier rapport - mise a jour faite, seulement les valeurs qui changent, calories inchangees, Annuler`() = runBlocking {
        seed(weighIn = 69.5) // 70 → 69,5 kg
        val result = produce()
        val reference = page(result.output["report_id"]!!.jsonPrimitive.content.toLong()).referenceWeight!!
        assertEquals(ReferenceStatus.Updated, reference.status)
        assertEquals(70.0, reference.beforeKg, 0.0)
        assertEquals(69.5, reference.afterKg, 0.0)
        assertEquals(
            listOf("protéines 140 → 139 g", "minimum de protéines 112 → 111 g", "lipides max inchangé (69 g)", "glucides 278 → 280 g", "calories inchangées"),
            reference.lines,
        )
        assertEquals("Ta pesée du 5 novembre, la seule des 7 derniers jours.", reference.source)
        assertEquals(69.5, engine.referenceWeight(today)!!, 0.0)
        assertTrue(reference.actionId != null)
        assertTrue(result.output["reference_weight"]!!.jsonPrimitive.content.startsWith("Mis à jour par l'application : 70 → 69,5 kg"))
    }

    @Test
    fun `second rapport du meme jour - meme bloc, aucune seconde mise a jour, meme apres Annuler`() = runBlocking {
        seed(weighIn = 66.0)
        val first = page(produce().output["report_id"]!!.jsonPrimitive.content.toLong()).referenceWeight!!
        assertTrue(engine.execute(Undo(ActionId(first.actionId!!))) is ActionOutcome.Applied) // l'utilisateur refuse la mise à jour
        assertTrue(engine.execute(DeclareWeekInfo(LocalDate.parse("2026-10-26"), weighIns = listOf(WeighIn(today, 65.0)))) is ActionOutcome.Applied)
        val second = page(produce().output["report_id"]!!.jsonPrimitive.content.toLong()).referenceWeight!!
        assertEquals(first, second) // même bloc, même action : l'annulation tient
        assertEquals(70.0, engine.referenceWeight(today)!!, 0.0)
        assertEquals(1, engine.goals().size) // aucun objectif réécrit
    }

    @Test
    fun `mise a jour du jour restee sans rapport - le rapport suivant la montre avec Annuler, sans seconde mise a jour`() = runBlocking {
        seed(weighIn = 66.0)
        val orphan = engine.updateReferenceWeight(today).action!! // comme si le rapport ne s'était pas enregistré
        val reference = page(produce().output["report_id"]!!.jsonPrimitive.content.toLong()).referenceWeight!!
        assertEquals(ReferenceStatus.Updated, reference.status)
        assertEquals(orphan.value, reference.actionId)
        assertEquals(70.0, reference.beforeKg, 0.0)
        assertTrue(reference.lines.contains("protéines 140 → 132 g"))
    }

    @Test
    fun `sans pesee - la page le dit, rien ne change, aucune alerte`() = runBlocking {
        seed(weighIn = null)
        val reference = page(produce().output["report_id"]!!.jsonPrimitive.content.toLong()).referenceWeight!!
        assertEquals(ReferenceStatus.NoWeighIn, reference.status)
        assertNull(reference.actionId)
        assertEquals(listOf("calories inchangées"), reference.lines)
        assertTrue(reference.source.startsWith("Aucune pesée ces 14 derniers jours"))
        assertEquals(70.0, engine.referenceWeight(today)!!, 0.0)
        assertEquals(1, engine.goals().size)
    }

    @Test
    fun `contexte du chat du jour et du bilan - le poids de reference a jour, et la regle`() = runBlocking {
        seed(weighIn = 66.0)
        produce()
        val env = ContextEnv(engine, now)
        val day = ContextBuilder.default().build(env)
        assertTrue(day, day.contains("Poids de référence : 66 kg (mis à jour par l'application à chaque bilan produit"))
        assertFalse(day, day.contains("Poids de référence : 70 kg"))
        val review = ContextBuilder.review(ReviewPeriods(reports, InMemoryReviewHistory())).build(env)
        assertTrue(review, review.contains("## Poids de référence\nPoids de référence : 66 kg"))
        assertTrue(review, review.contains("tu ne le changes jamais toi-même"))
    }

    @Test
    fun `OBJ-04 - contexte du modele, texte enregistre et points jugent les proteines avec le minimum que le bilan va ecrire`() = runBlocking {
        seed(weighIn = 65.0) // minimum de protéines 112 → 104 g (1,6 g/kg), idéal 140 → 130 g
        val dinner = NewConsumption(Food("Dîner", Nutrition(2300.0, 108.0, 300.0, 60.0)), Moment.Soir)
        assertTrue(engine.execute(AddConsumptions(dinner)) is ActionOutcome.Applied) // 108 g aujourd'hui, journée complète

        // Ce que lit le modèle avant d'écrire son mot : les objectifs après la mise à jour, sans qu'elle soit écrite.
        val context = ContextBuilder.review(ReviewPeriods(reports, InMemoryReviewHistory())).build(ContextEnv(engine, now))
        assertFalse(context, context.contains("112 g"))
        assertTrue(context, context.contains("Objectif de protéines : 130 g par jour"))
        assertTrue(context, context.contains("Protéines (va bien) : minimum tenu ce jour"))
        assertTrue(context, context.contains("Le rapport mettra le poids de référence à jour : 70 → 65 kg, calories inchangées."))
        assertEquals(70.0, engine.referenceWeight(today)!!, 0.0) // rien d'écrit à l'avance
        assertEquals(1, engine.goals().size)

        val analysis = "Minimum de protéines tenu aujourd'hui."
        val result = ProduceReportTool(ReviewPeriods(reports, InMemoryReviewHistory()), RecordingReviewNotifier()).execute(buildJsonObject { put("analysis", analysis) }, ToolContext(engine, now))
        val first = page(result.output["report_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(analysis, first.note)
        assertEquals(104.0, engine.daySummary(today).goal!!.proteinMinG!!, 1e-9) // ce que montre l'accueil
        val protein = first.points.single { it.id == "protein" }
        assertEquals(PointStatus.Ok, protein.status)
        // Le rapport est bâti sur les chiffres que le modèle a lus.
        assertTrue(context.contains(result.output["figures_used"]!!.jsonPrimitive.content.trimEnd()))
        val second = page(produce().output["report_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(protein, second.points.single { it.id == "protein" })
    }

    @Test
    fun `lignes - inchange au gramme pres, valeurs absentes omises`() {
        assertEquals(
            listOf("protéines inchangées (140 g)", "lipides max 70 → 65 g"),
            ReferenceText.lines(GoalTargets(2000.0, proteinG = 140.2, fatG = 70.0), GoalTargets(2000.0, proteinG = 139.8, fatG = 65.0)),
        )
        assertFalse(ReferenceText.lines(base, base).any { "→" in it })
    }

    private companion object {
        const val PROFILE = """{"current_reference_weight_kg":70.0,"goals":[{"effective_from":"2026-09-01","kcal":2300,"protein_ideal_g_per_kg":2.0,"protein_min_g_per_kg":1.6,"fat_g_per_kg":1.0}]}"""
    }
}
