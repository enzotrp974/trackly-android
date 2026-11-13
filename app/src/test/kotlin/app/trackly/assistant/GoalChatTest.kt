package app.trackly.assistant

import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.cards.GoalCard
import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.ContextEnv
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.tools.SetGoalTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.GoalTargets
import app.trackly.core.importing.BundleReader
import app.trackly.core.importing.ImportHistory
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Objectifs par le chat : tours de conversation avec réponses de modèle simulées,
 * aucun réseau. Le code vérifie la cohérence des macros avant d'écrire.
 */
class GoalChatTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val current = GoalTargets(2600.0, 140.0, 352.0, 70.0)

    private fun engine(local: String = "2026-11-02T16:10"): TracklyEngine = SampleData.emptyEngine(SampleData.clock(local)).also {
        runBlocking { check(it.execute(SetGoal(current, from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun interpreter(engine: TracklyEngine, transport: ScriptedTransport) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine)

    private fun goalOn(engine: TracklyEngine, iso: String) = runBlocking { engine.daySummary(LocalDate.parse(iso)).goal }

    private fun toolOutput(transport: ScriptedTransport, request: Int): JsonObject {
        val output = transport.requests[request]["input"]!!.jsonArray.last().jsonObject
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        return Json.parseToJsonElement(output["output"]!!.jsonPrimitive.content).jsonObject
    }

    @Test
    fun `finalement 2 300 kcal - l objectif change des aujourd hui, les macros suivent, le recap est affiche`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("objectif-1-appel-outil.json", "objectif-2-reponse.json")

        val outcome = interpreter(engine, transport).run(TurnInput("finalement je veux être à 2 300 kcal")) as TurnOutcome.Completed

        assertEquals(listOf("set_goal"), outcome.toolCalls.map { it.name })
        // Moteur : objectif du jour de suivi en cours modifié, macros ajustées (cohérentes), jours passés intacts.
        assertEquals(GoalTargets(2300.0, 140.0, 278.0, 70.0), goalOn(engine, "2026-11-02"))
        assertEquals(current, goalOn(engine, "2026-11-01"))
        assertEquals(2, engine.goals().size)

        // Carte : construite par le code, avant → après et date d'effet.
        val card = outcome.cards.single() as GoalCard
        assertEquals("2026-11-02", card.effectiveFrom)
        assertEquals(2600.0, card.before!!.kcal, 0.0)
        assertEquals(2300.0, card.after.kcal, 0.0)
        assertEquals(278.0, card.after.carbsG!!, 0.0)
        assertTrue(card.macrosChanged)
        assertTrue(card.summaryForModel().contains("2600 kcal"))
        assertTrue(card.summaryForModel().contains("2300 kcal"))
        assertEquals(card, ChatCards.decode(ChatCards.encode(listOf(card))).single()) // relue depuis l'historique

        // Résultat renvoyé au modèle : avant, après, bilan du jour recalculé par l'app.
        val result = toolOutput(transport, 1)
        assertEquals("goal_set", result["status"]!!.jsonPrimitive.content)
        assertEquals("2026-11-02", result["effective_from"]!!.jsonPrimitive.content)
        assertEquals("2600.0", result["before"]!!.jsonObject["kcal"]!!.jsonPrimitive.content)
        assertEquals("2300.0", result["after"]!!.jsonObject["kcal"]!!.jsonPrimitive.content)
        assertEquals("2300.0", result["days"]!!.jsonArray.single().jsonObject["goal_kcal"]!!.jsonPrimitive.content)

        // Annuler (bouton de la carte) rend l'objectif d'avant ; la version écrasée reste dans l'historique.
        assertTrue(engine.execute(Undo(ActionId(card.actionId))) is ActionOutcome.Applied)
        assertEquals(current, goalOn(engine, "2026-11-02"))
    }

    @Test
    fun `minimum de proteines - connu comme plancher, garde quand les calories changent`() = runBlocking {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10"))
        check(engine.execute(SetGoal(current.copy(proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        val transport = ScriptedTransport.replies("objectif-1-appel-outil.json", "objectif-2-reponse.json")

        interpreter(engine, transport).run(TurnInput("finalement je veux être à 2 300 kcal"))

        // Le modèle voit l'idéal comme objectif et le minimum comme plancher, jamais l'inverse.
        val context = transport.requests[0]["input"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(context, context.contains("protéines 140 g (idéal visé ; minimum 112 g = plancher, jamais l'objectif)"))
        // L'objectif à 2 300 kcal garde l'idéal et le minimum, sans que le modèle ait eu à le redonner.
        assertEquals(GoalTargets(2300.0, 140.0, 278.0, 70.0, proteinMinG = 112.0), goalOn(engine, "2026-11-02"))
        assertEquals("112.0", toolOutput(transport, 1)["protein_minimum_g"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la requete expose set_goal, les consignes sur les objectifs, l objectif et le profil`() = runBlocking {
        val engine = engine()
        engine.execute(ImportHistory(BundleReader.read(SampleData.historyBundle)))
        val transport = ScriptedTransport.replies("objectif-1-appel-outil.json", "objectif-2-reponse.json")

        interpreter(engine, transport).run(TurnInput("finalement je veux être à 2 300 kcal"))

        val first = transport.requests[0]
        val tools = first["tools"]!!.jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("set_goal" in tools)
        val instructions = first["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("# Objectifs"))
        assertTrue(instructions.contains("status refused"))
        val context = first["input"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(context, context.contains("## Objectif en vigueur"))
        assertTrue(context, context.contains("2600 kcal, protéines 140 g, glucides 352 g, lipides 70 g"))
        assertTrue(context, context.contains("## Profil nutritionnel"))
        assertTrue(context, context.contains("Poids de référence : 72,5 kg"))
        assertTrue(context, context.contains("lipides"))
    }

    @Test
    fun `a partir de demain - demain change, aujourd hui reste, la carte porte la date`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies("objectif-demain-1-appel-outil.json", "objectif-demain-2-reponse.json")

        val outcome = interpreter(engine, transport).run(TurnInput("à partir de demain je veux 2 200 kcal")) as TurnOutcome.Completed

        assertEquals(current, goalOn(engine, "2026-11-02"))
        assertEquals(GoalTargets(2200.0, 140.0, 253.0, 70.0), goalOn(engine, "2026-11-03"))
        val card = outcome.cards.single() as GoalCard
        assertEquals("2026-11-03", card.effectiveFrom)
        assertEquals(2600.0, card.before!!.kcal, 0.0) // l'objectif qui vaut demain avant le changement
    }

    @Test
    fun `macros incoherentes - refus explicite renvoye au modele, rien n est ecrit, puis la correction passe`() = runBlocking {
        val engine = engine()
        val transport = ScriptedTransport.replies(
            "objectif-incoherent-1-appel-outil.json",
            "objectif-incoherent-2-appel-outil.json",
            "objectif-incoherent-3-reponse.json",
        )

        val outcome = interpreter(engine, transport).run(TurnInput("mets-moi à 2 300 kcal")) as TurnOutcome.Completed

        // Premier appel : anciennes macros avec de nouvelles calories, donc refusé avec la raison chiffrée par le code.
        val refusal = toolOutput(transport, 1)
        assertEquals("refused", refusal["status"]!!.jsonPrimitive.content)
        assertEquals("true", refusal["nothing_saved"]!!.jsonPrimitive.content)
        val message = refusal["message"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("2598") && message.contains("2300"))
        // Deuxième appel corrigé : appliqué. Une seule version écrite, une seule carte.
        assertEquals("goal_set", toolOutput(transport, 2)["status"]!!.jsonPrimitive.content)
        assertEquals(GoalTargets(2300.0, 140.0, 278.0, 70.0), goalOn(engine, "2026-11-02"))
        assertEquals(1, outcome.cards.size)
        assertEquals(2, engine.goals().size)
    }

    // ───── Contrôles de l'outil, un par un ─────

    private fun call(engine: TracklyEngine, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = runBlocking {
        SetGoalTool.execute(buildJsonObject(build), ToolContext(engine, engine.now()))
    }

    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.content

    private fun args(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        kcal: Double,
        p: Double?,
        c: Double?,
        f: Double?,
        from: String? = null,
    ) {
        builder.put("kcal", kcal)
        builder.put("protein_g", p)
        builder.put("carbs_g", c)
        builder.put("fat_g", f)
        builder.put("from_date", from)
    }

    @Test
    fun `calories seules alors que l objectif a des macros - refuse, le modele doit les ajuster`() {
        val engine = engine()
        val result = call(engine) { args(this, 2300.0, null, null, null) }
        assertEquals("refused", result.output.status())
        assertTrue(result.output["message"]!!.jsonPrimitive.content.contains("trois macros"))
        assertNull(result.card)
        assertEquals(current, goalOn(engine, "2026-11-02"))
    }

    @Test
    fun `calories seules sans macros dans l objectif actuel - appliquees telles quelles`() = runBlocking {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T10:00"))
        val result = call(engine) { args(this, 2300.0, null, null, null) }
        assertEquals("goal_set", result.output.status())
        assertEquals(JsonNull, result.output["before"])
        assertNull((result.card as GoalCard).before)
        assertEquals(GoalTargets(kcal = 2300.0), goalOn(engine, "2026-11-02"))
    }

    @Test
    fun `une date passee, une date invalide et des valeurs impossibles sont refusees sans rien ecrire`() {
        val engine = engine()
        for (bad in listOf<kotlinx.serialization.json.JsonObjectBuilder.() -> Unit>(
            { args(this, 2300.0, 140.0, 278.0, 70.0, "2026-11-01") }, // jour passé
            { args(this, 2300.0, 140.0, 278.0, 70.0, "demain") }, // pas une date (le schéma l'interdit, défense en plus)
            { args(this, 23.0, 1.0, 1.0, 1.0) }, // faute de frappe sur les calories
            { args(this, -5.0, null, null, null) },
            { args(this, 2300.0, -140.0, 278.0, 70.0) },
        )) {
            val result = call(engine, bad)
            assertTrue(result.output.toString(), result.output.status() in setOf("error", "refused", "rejected"))
            assertNull(result.applied)
        }
        assertEquals(current, goalOn(engine, "2026-11-02"))
        assertEquals(1, runBlocking { engine.goals().size })
    }

    @Test
    fun `un objectif identique est refuse par le moteur, rien ne change`() {
        val engine = engine()
        val result = call(engine) { args(this, 2600.0, 140.0, 352.0, 70.0) }
        assertEquals("rejected", result.output.status())
        assertFalse(result.output["nothing_saved"] == null)
        assertNull(result.card)
    }

    @Test
    fun `avant 5 h sans date, l objectif part du jour de suivi en cours, la veille du calendrier`() = runBlocking {
        val engine = engine("2026-11-03T01:30")
        val result = call(engine) { args(this, 2300.0, 140.0, 278.0, 70.0) }
        assertEquals("2026-11-02", (result.card as GoalCard).effectiveFrom)
        assertEquals(GoalTargets(2300.0, 140.0, 278.0, 70.0), goalOn(engine, "2026-11-02"))
    }

    @Test
    fun `le contexte annonce aussi les changements deja prevus`() = runBlocking {
        val engine = engine()
        engine.execute(SetGoal(GoalTargets(2200.0, 140.0, 253.0, 70.0), from = LocalDate.parse("2026-11-03")))
        val text = ContextBuilder.default().build(ContextEnv(engine, engine.now()))
        assertTrue(text, text.contains("Prévu dès mardi 3 novembre (2026-11-03) : 2200 kcal"))
        assertFalse(text.contains("## Profil nutritionnel")) // pas de reprise : pas de profil
    }
}
