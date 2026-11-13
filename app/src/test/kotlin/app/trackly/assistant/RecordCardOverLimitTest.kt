package app.trackly.assistant

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.CardItem
import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeleteConsumptions
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.day.LimitAlerts
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate

/**
 * Rouge du récap du chat : une macro hors objectif s'affiche en rouge, **figé à l'enregistrement**. Le champ vit dans
 * la charge JSON de la carte (aucune migration Room) ; une carte d'avant reste comme avant ; l'assistant ne reçoit rien.
 * Le snack du scénario (barre protéinée et pomme) apporte 20 g de protéines, 31 g de glucides et 8 g de lipides.
 */
@RunWith(AndroidJUnit4::class)
class RecordCardOverLimitTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val snack = "comme snack j'ai pris une barre protéinée et une pomme, 150 g"

    private fun engine(fatLimitG: Double): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking {
            check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, fatLimitG, proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        }
    }

    private fun run(engine: TracklyEngine, alerts: app.trackly.assistant.cards.RecordAlerts): Pair<TurnOutcome.Completed, ScriptedTransport> {
        val transport = ScriptedTransport.replies("snack-1-appel-outil.json", "snack-2-reponse.json")
        val outcome = runBlocking { TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine, recordAlerts = alerts).run(TurnInput(snack)) }
        return (outcome as TurnOutcome.Completed) to transport
    }

    @Test
    fun `des lipides au-dessus de la limite - la carte les fige en rouge`() {
        // Limite de 5 g, le snack en apporte 8.
        val (outcome, _) = run(engine(fatLimitG = 5.0), LimitAlerts)
        assertEquals(listOf("fat"), (outcome.cards.single() as RecordCard).overLimit)
    }

    @Test
    fun `sous la limite - aucun rouge, la carte reste celle d avant`() {
        val (outcome, _) = run(engine(fatLimitG = 70.0), LimitAlerts)
        val card = outcome.cards.single() as RecordCard
        assertEquals(emptyList<String>(), card.overLimit)
        assertFalse(ChatCards.encode(listOf(card)).contains("overLimit"))
    }

    @Test
    fun `sans regle branchee - aucun rouge`() {
        val (outcome, _) = run(engine(fatLimitG = 5.0), app.trackly.assistant.cards.RecordAlerts.None)
        assertEquals(emptyList<String>(), (outcome.cards.single() as RecordCard).overLimit)
    }

    @Test
    fun `le rouge est fige - une journee corrigee ensuite ne change pas la carte deja enregistree`() {
        val engine = engine(fatLimitG = 5.0)
        val (outcome, _) = run(engine, LimitAlerts)
        val card = outcome.cards.single() as RecordCard
        // Le repas est supprimé : la journée n'est plus hors objectif, mais la carte déjà enregistrée ne se recalcule pas.
        val ids = card.items.map { it.entryId }
        assertTrue(runBlocking { engine.execute(DeleteConsumptions(ids.map { app.trackly.core.model.EntryId(it) })) } is ActionOutcome.Applied)
        assertEquals(listOf("fat"), card.overLimit)
        assertEquals(0.0, runBlocking { engine.daySummary(monday).totals.fat.known }, 0.0)
    }

    @Test
    fun `l assistant ne recoit rien - ni resume, ni resultat d outil, ni requete`() {
        val (withRed, redTransport) = run(engine(fatLimitG = 5.0), LimitAlerts)
        val (without, plainTransport) = run(engine(fatLimitG = 5.0), app.trackly.assistant.cards.RecordAlerts.None)
        val red = withRed.cards.single() as RecordCard
        val plain = without.cards.single() as RecordCard
        assertEquals(listOf("fat"), red.overLimit)
        // Les deux tours sont identiques pour le modèle : mêmes requêtes, mêmes résultats d'outil, même résumé de carte.
        // (Les identifiants des consommations sont des UUID tirés au hasard : on les remplace avant de comparer.)
        fun String.anonymous() = replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<id>")
        assertEquals(plainTransport.requests.map { it.toString().anonymous() }, redTransport.requests.map { it.toString().anonymous() })
        assertEquals(without.toolCalls.map { it.output.toString().anonymous() }, withRed.toolCalls.map { it.output.toString().anonymous() })
        assertEquals(plain.summaryForModel().anonymous(), red.summaryForModel().anonymous())
        assertFalse(red.summaryForModel().contains("fat", ignoreCase = true))
        assertFalse(redTransport.requests.joinToString().contains("overLimit"))
    }

    // ───── Format stocké ─────

    /** La carte telle que la 1.2 la connaît : sans `overLimit`. */
    @kotlinx.serialization.Serializable
    private data class ElderRecord(val actionId: Long, val kind: RecordKind, val items: List<CardItem>)

    private val item = CardItem("e1", "Barre protéinée", "55 g", "snack", "2026-11-02", 200.0, 20.0, 20.0, 8.0)

    @Test
    fun `une carte d avant la 1_3 se lit comme avant - aucun rouge`() {
        // JSON tel qu'écrit par la 1.2 : pas de champ overLimit.
        val old = """[{"card":"record","actionId":5,"kind":"Added","items":[{"entryId":"e1","label":"Barre protéinée","moment":"snack","day":"2026-11-02","kcal":200.0,"fatG":8.0}]}]"""
        val card = ChatCards.decode(old).single() as RecordCard
        assertEquals(emptyList<String>(), card.overLimit)
        assertEquals("Barre protéinée", card.items.single().label)
    }

    @Test
    fun `le rouge fait l aller-retour dans la charge JSON, et une version plus ancienne l ignore`() {
        val card = RecordCard(5, RecordKind.Added, listOf(item), overLimit = listOf("protein", "fat"))
        val text = ChatCards.encode(listOf(card))
        assertTrue(text, text.contains("\"overLimit\":[\"protein\",\"fat\"]"))
        assertEquals(listOf("protein", "fat"), (ChatCards.decode(text).single() as RecordCard).overLimit)
        // La 1.2 lit cette charge avec ignoreUnknownKeys, sans ce champ : la carte se décode et garde ses aliments.
        val legacy = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val elder = legacy.decodeFromString<ElderRecord>(text.removePrefix("[").removeSuffix("]"))
        assertEquals(listOf("Barre protéinée"), elder.items.map { it.label })
    }

    // ───── « Noter » une proposition ─────

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    @Test
    fun `noter une proposition qui depasse les lipides - la carte d enregistrement les fige en rouge, pas les proteines au soir`() {
        val engine = SampleData.emptyEngine(SampleData.clock("2026-11-02T18:40")).also { e ->
            runBlocking {
                // Limite de 2 g de lipides : 100 g de pâtes en apportent 2,5. Protéines : 13 g au soir sous un minimum de 112 g.
                check(e.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 2.0, proteinMinG = 112.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
                check(e.execute(MemorizeProduct(NewProduct("Pâtes complètes", nutrients = Nutrients(350.0, 13.0, 65.0, 2.5), basis = NutritionBasis(100.0, "g"), productId = "prd-pates", kind = ProductKind.Recurring))) is ActionOutcome.Applied)
            }
        }
        val proposal = ProposalCard(
            id = "prop-1",
            title = "Pâtes nature",
            moment = "soir",
            // Valeurs de la fiche : une carte aux valeurs périmées n'est pas notée.
            items = listOf(ProposalItem("Pâtes complètes", "100 g", 350.0, 13.0, 65.0, 2.5, productId = "prd-pates", amount = 100.0, unit = "g")),
        )
        val history = InMemoryChatHistory()
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(proposal)) }
        val model = ChatViewModel(
            AssistantServices.inMemory(engine, ScriptedTransport(), InMemoryApiKeyStore("sk-test-cle-factice-0000000000"), history, recordAlerts = LimitAlerts),
            SavedStateHandle(),
        )
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }

        model.note(proposal)
        idleUntil { "prop-1" in model.state.value.notedProposals }

        val record = model.state.value.messages.last().cards.single() as RecordCard
        assertEquals(listOf("fat"), record.overLimit)
        watch.cancel()
    }

    @Test
    fun `les proteines sous le minimum au soir ne sont pas rouges dans le chat, seulement sur la barre de l accueil`() {
        val engine = engine(fatLimitG = 70.0)
        runBlocking {
            check(engine.execute(app.trackly.core.action.AddConsumptions(app.trackly.core.action.NewConsumption(app.trackly.core.model.Food("Pâtes", app.trackly.core.model.Nutrition(700.0, 13.0, 120.0, 5.0)), Moment.Soir))) is ActionOutcome.Applied)
        }
        val day = runBlocking { engine.daySummary(monday) }
        assertEquals(setOf(app.trackly.core.overview.Bar.Protein), app.trackly.core.overview.DayOverviews.exceeded(day))
        assertEquals(emptySet<String>(), LimitAlerts.overLimit(day))
    }
}
