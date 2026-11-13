package app.trackly.assistant

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProposalItem
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CorrectProduct
import app.trackly.core.action.DeleteConsumptions
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.TracklyDatabase
import app.trackly.testing.ModelReplies
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * Bouton « Noter » d'une carte de proposition : une déclaration annulable, comme un repas dicté. Toutes les
 * fiches connues : enregistrée sur le téléphone, sans l'assistant ; un aliment sans fiche : demandée à l'assistant.
 * Une proposition déjà notée ne l'est pas deux fois ; annulée, elle peut l'être de nouveau.
 */
@RunWith(AndroidJUnit4::class)
class ProposalNoteTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val key = InMemoryApiKeyStore("sk-test-cle-factice-0000000000")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T18:40")).also { engine ->
        runBlocking {
            for ((id, name, n) in listOf(
                Triple("prd-pates", "Pâtes complètes", Nutrients(350.0, 13.0, 65.0, 2.5)),
                Triple("prd-thon", "Thon au naturel", Nutrients(110.0, 25.0, 0.0, 1.0)),
            )) {
                check(engine.execute(MemorizeProduct(NewProduct(name, nutrients = n, basis = NutritionBasis(100.0, "g"), productId = id, kind = ProductKind.Recurring))) is ActionOutcome.Applied)
            }
        }
    }

    private val known = ProposalCard(
        id = "prop-1",
        title = "Pâtes au thon",
        moment = "soir",
        items = listOf(
            // Valeurs des fiches, telles que la carte les affiche.
            ProposalItem("Pâtes complètes", "100 g", 350.0, 13.0, 65.0, 2.5, productId = "prd-pates", amount = 100.0, unit = "g"),
            ProposalItem("Thon au naturel", "80 g", 88.0, 20.0, 0.0, 0.8, productId = "prd-thon", amount = 80.0, unit = "g"),
        ),
    )

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("délai dépassé")
            Thread.sleep(5)
        }
    }

    private fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `noter - enregistre depuis les fiches sans l assistant, une fois, et de nouveau apres Annuler`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà ce que je te propose.", listOf(known)) }
        // Aucune réponse de modèle prévue : un appel ferait échouer le test.
        val model = ChatViewModel(AssistantServices.inMemory(engine, ScriptedTransport(), key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }

        model.note(known)
        model.note(known) // double toucher
        idleUntil { "prop-1" in model.state.value.notedProposals }

        val eaten = runBlocking { engine.daySummary(monday).consumptions }
        assertEquals(listOf(350.0, 88.0), eaten.map { it.food.nutrition.kcal })
        assertEquals(2, model.state.value.messages.size)
        val record = model.state.value.messages.last().cards.single() as RecordCard
        assertEquals("prop-1", record.proposalId)
        assertEquals(ChatRole.Assistant, model.state.value.messages.last().role)

        // Déjà notée : un nouveau toucher ne compte rien de plus.
        model.note(known)
        idleFor(200)
        assertEquals(2, runBlocking { engine.daySummary(monday).consumptions.size })
        assertEquals(2, model.state.value.messages.size)

        // « Annuler » sur la carte d'enregistrement : plus rien n'est compté, la proposition peut être notée de nouveau.
        model.undo(record)
        idleUntil { model.state.value.notedProposals.isEmpty() }
        assertTrue(runBlocking { engine.daySummary(monday).consumptions.isEmpty() })
        model.note(known)
        idleUntil { "prop-1" in model.state.value.notedProposals }
        assertEquals(2, runBlocking { engine.daySummary(monday).consumptions.size })
        watch.cancel()
    }

    @Test
    fun `noter avec un aliment sans fiche - la demande part a l assistant, qui l estime comme un repas dicte`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        val withSauce = known.copy(id = "prop-2", title = "Pâtes thon sauce tomate", items = known.items + ProposalItem("Sauce tomate", "60 g", unknown = "pas de fiche"))
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(withSauce)) }
        val record = """{"items":[""" +
            """{"product":{"product_id":"prd-pates","new_product":null,"quantity":100,"unit":"g","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"soir","day":null},""" +
            """{"product":{"product_id":"prd-thon","new_product":null,"quantity":80,"unit":"g","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"soir","day":null},""" +
            """{"product":null,"dish":null,"food":{"label":"Sauce tomate","quantity":60,"unit":"g","kcal":30,"protein_g":1,"carbs_g":5,"fat_g":0.5,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"soir","day":null}],""" +
            """"question":null,"proposal_id":"prop-2"}"""
        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", record), ModelReplies.text("C'est noté !"))
        val model = ChatViewModel(AssistantServices.inMemory(engine, transport, key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }

        model.note(withSauce)
        idleUntil { "prop-2" in model.state.value.notedProposals && !model.state.value.thinking }

        assertEquals(ProposalCard.noteRequest(withSauce), model.state.value.messages[1].text)
        // Le modèle a reçu la carte de proposition dans l'historique, avec son identifiant.
        assertTrue(transport.requests[0].toString().contains("proposal_id prop-2"))
        assertEquals(3, runBlocking { engine.daySummary(monday).consumptions.size })
        watch.cancel()
    }

    @Test
    fun `sans fiche, le modele oublie proposal_id - l app relie quand meme la carte d enregistrement`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        val withSauce = known.copy(id = "prop-3", items = known.items + ProposalItem("Sauce tomate", "60 g", unknown = "pas de fiche"))
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(withSauce)) }
        val record = """{"items":[""" + PATES + "," + THON + "," + SAUCE + """],"question":null}"""
        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"))
        val model = ChatViewModel(AssistantServices.inMemory(engine, transport, key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }

        model.note(withSauce)
        idleUntil { "prop-3" in model.state.value.notedProposals }

        val card = model.state.value.messages.flatMap { it.cards }.filterIsInstance<RecordCard>().single()
        assertEquals("prop-3", card.proposalId)
        // Le modèle le lira aux tours suivants : elle est déjà notée.
        assertTrue(card.summaryForModel().contains("carte de proposition prop-3 NOTÉE"))
        watch.cancel()
    }

    @Test
    fun `repas retire du journal depuis l ecran du jour - la proposition peut etre notee de nouveau`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(known)) }
        val model = ChatViewModel(AssistantServices.inMemory(engine, ScriptedTransport(), key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }
        model.note(known)
        idleUntil { "prop-1" in model.state.value.notedProposals }

        val ids = runBlocking { engine.daySummary(monday).consumptions.map { it.entryId } }
        check(runBlocking { engine.execute(DeleteConsumptions(ids)) } is ActionOutcome.Applied)

        idleUntil { model.state.value.notedProposals.isEmpty() }
        watch.cancel()
    }

    private fun <T> roomViewModel(block: (TracklyEngine, ChatViewModel, FailingHistory) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TracklyDatabase::class.java).build()
        try {
            val engine = TracklyEngine(RoomTracklyStore(db), SampleData.clock("2026-11-02T18:40"))
            runBlocking {
                for ((id, name, n) in listOf(
                    Triple("prd-pates", "Pâtes complètes", Nutrients(350.0, 13.0, 65.0, 2.5)),
                    Triple("prd-thon", "Thon au naturel", Nutrients(110.0, 25.0, 0.0, 1.0)),
                )) {
                    check(engine.execute(MemorizeProduct(NewProduct(name, nutrients = n, basis = NutritionBasis(100.0, "g"), productId = id, kind = ProductKind.Recurring))) is ActionOutcome.Applied)
                }
            }
            val history = FailingHistory(RoomChatHistory(db.chat()))
            runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(known)) }
            val shared = object : SharedTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
            }
            val services = AssistantServices.inMemory(engine, ScriptedTransport(), key, history, sharedTransaction = shared)
            return block(engine, ChatViewModel(services, SavedStateHandle()), history)
        } finally {
            db.close()
        }
    }

    /** Historique dont la prochaine écriture d'une carte d'enregistrement échoue (disque plein, base fermée…). */
    private class FailingHistory(private val delegate: ChatHistory) : ChatHistory by delegate {
        var failNextRecord = true

        override suspend fun append(at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long {
            if (failNextRecord && cards.any { it is RecordCard }) {
                failNextRecord = false
                throw IOException("disque plein")
            }
            return delegate.append(at, role, text, cards)
        }
    }

    @Test
    fun `CHAT-01 - carte d enregistrement impossible a ecrire - rien n est compte, pas de plantage, un second toucher note une fois`() {
        roomViewModel { engine, model, _ ->
            val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
            idleUntil { model.state.value.messages.size == 1 }

            model.note(known)
            idleUntil { model.state.value.notice != null }
            // Le repas et sa carte vont ensemble : sans carte, rien n'est compté (sinon « Noter » le compterait deux fois).
            assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
            assertFalse("prop-1" in model.state.value.notedProposals)

            model.note(known)
            idleUntil { "prop-1" in model.state.value.notedProposals }
            assertEquals(2, runBlocking { engine.daySummary(monday).consumptions.size })
            assertEquals(1, model.state.value.messages.count { m -> m.cards.any { it is RecordCard } })
            watch.cancel()
        }
    }

    @Test
    fun `CHAT-02 - sans fiche, la carte est transmise au modele meme sortie de l historique recent`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        val withSauce = known.copy(id = "prop-5", title = "Pâtes thon sauce tomate", items = known.items + ProposalItem("Sauce tomate", "60 g", unknown = "pas de fiche"))
        runBlocking {
            history.append(Instant.parse("2026-11-02T17:00:00Z"), ChatRole.Assistant, "Voilà.", listOf(withSauce))
            // Douze messages plus tard, la carte n'est plus dans ce que le modèle relit.
            repeat(8) { i ->
                history.append(Instant.parse("2026-11-02T17:10:00Z").plusSeconds(i * 60L), ChatRole.User, "question $i")
                history.append(Instant.parse("2026-11-02T17:10:30Z").plusSeconds(i * 60L), ChatRole.Assistant, "réponse $i")
            }
        }
        val record = """{"items":[""" + PATES + "," + THON + "," + SAUCE + """],"question":null,"proposal_id":"prop-5"}"""
        val transport = ScriptedTransport(ModelReplies.call("record_consumptions", record), ModelReplies.text("C'est noté !"))
        val model = ChatViewModel(AssistantServices.inMemory(engine, transport, key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 17 }

        model.note(withSauce)
        idleUntil { "prop-5" in model.state.value.notedProposals && !model.state.value.thinking }

        // Le modèle reçoit la carte elle-même : identifiant, fiches et quantités, aliment à estimer.
        val sent = transport.requests[0].toString()
        assertTrue(sent, sent.contains("prop-5") && sent.contains("prd-pates") && sent.contains("prd-thon") && sent.contains("Sauce tomate"))
        watch.cancel()
    }

    /** « Noter » d'une carte avec un aliment sans fiche, le modèle répondant [steps] ; [onRequest] joue avant chaque réponse. */
    private fun noteThroughModel(
        card: ProposalCard,
        vararg steps: ScriptedTransport.Step,
        engine: TracklyEngine = engine(),
        history: ChatHistory = InMemoryChatHistory(),
        sharedTransaction: SharedTransaction = SharedTransaction.None,
        onRequest: (Int) -> Unit = {},
    ): Pair<ChatViewModel, ScriptedTransport> {
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(card)) }
        val scripted = ScriptedTransport(*steps)
        val transport = ResponsesTransport { body -> onRequest(scripted.requests.size + 1); scripted.send(body) }
        val model = ChatViewModel(AssistantServices.inMemory(engine, transport, key, history, sharedTransaction = sharedTransaction), SavedStateHandle())
        idleUntilShown(model)
        model.note(card)
        idleUntil { !model.state.value.thinking && (model.state.value.notice != null || card.id in model.state.value.notedProposals) }
        idleFor(200)
        return model to scripted
    }

    private val watching = mutableListOf<kotlinx.coroutines.Job>()

    private fun idleUntilShown(model: ChatViewModel) {
        watching += CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.isNotEmpty() }
    }

    @org.junit.After
    fun stopWatching() = watching.forEach { it.cancel() }

    private val withSauce = known.copy(
        id = "prop-6",
        title = "Pâtes thon sauce tomate",
        items = known.items + ProposalItem("Sauce tomate", "60 g", amount = 60.0, unit = "g", unknown = "pas de fiche"),
    )

    @Test
    fun `CHAT-02 tour 3 - un autre aliment dont le nom contient celui de la carte - rien n est note`() {
        val engine = engine()
        val bread = known.copy(id = "prop-7", title = "Pâtes et pain", items = known.items + ProposalItem("Pain", "50 g", amount = 50.0, unit = "g", unknown = "pas de fiche"))
        val chocolate = SAUCE.replace("Sauce tomate", "Pain au chocolat").replace("\"quantity\":60", "\"quantity\":50")
        val record = """{"items":[$PATES,$THON,$chocolate],"question":null,"proposal_id":"prop-7"}"""
        val (model, _) = noteThroughModel(bread, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"), engine = engine)

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertFalse("prop-7" in model.state.value.notedProposals)
    }

    @Test
    fun `CHAT-02 tour 3 - estimation du bon aliment a une autre quantite - rien n est note`() {
        val engine = engine()
        val tenfold = SAUCE.replace("\"quantity\":60", "\"quantity\":600")
        val record = """{"items":[$PATES,$THON,$tenfold],"question":null,"proposal_id":"prop-6"}"""
        val (model, _) = noteThroughModel(withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"), engine = engine)

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertFalse("prop-6" in model.state.value.notedProposals)
    }

    @Test
    fun `CHAT-02 tour 3 - le modele retente apres le refus - une seule reponse finale, sans outils`() {
        val engine = engine()
        val pizza = SAUCE.replace("Sauce tomate", "Pizza")
        val record = """{"items":[$pizza],"question":null,"proposal_id":"prop-6"}"""
        val (model, transport) = noteThroughModel(
            withSauce,
            ModelReplies.call("record_consumptions", record),
            ModelReplies.call("record_consumptions", record),
            ModelReplies.call("record_consumptions", record),
            ModelReplies.text("Pas noté."),
            engine = engine,
        )

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertTrue(model.state.value.notice.orEmpty(), model.state.value.notice.orEmpty().contains("Rien n’a été noté"))
        // L'appel refusé, puis une seule requête, sans outil permis : le faux modèle retente, rien n'est exécuté.
        assertEquals(2, transport.requests.size)
        assertEquals("none", transport.requests[1]["tool_choice"].toString().trim('"'))
        // Les outils restent déclarés : le début de requête mis en cache ne change pas.
        assertTrue(transport.requests[1].containsKey("tools"))
    }

    @Test
    fun `CHAT-02 tour 3 - une carte valide en pieces se note d un toucher, malgre le pluriel`() {
        val engine = engine()
        val eggs = known.copy(id = "prop-8", title = "Pâtes et œufs", items = known.items + ProposalItem("Œuf dur", "2 pièces", amount = 2.0, unit = "pièce", unknown = "pas de fiche"))
        val egg = SAUCE.replace("Sauce tomate", "Œuf dur").replace("\"quantity\":60,\"unit\":\"g\"", "\"quantity\":2,\"unit\":\"pièces\"")
        val record = """{"items":[$PATES,$THON,$egg],"question":null,"proposal_id":"prop-8"}"""
        val (model, transport) = noteThroughModel(eggs, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"), engine = engine)

        assertTrue("prop-8" in model.state.value.notedProposals)
        assertEquals(3, runBlocking { engine.daySummary(monday).consumptions.size })
        // La consigne donne la quantité brute, sans pluriel d'affichage.
        assertTrue(transport.requests[0].toString().contains("quantity 2, unit pièce"))
    }

    @Test
    fun `CHAT-02 tour 3 - propose_meal garde la quantite d un aliment sans fiche, de facon structuree`() {
        val engine = engine()
        val args = """{"title":"Pâtes thon sauce tomate","moment":"soir","items":[{"product":null,"dish":null,"food":{"label":"Sauce tomate","quantity":60,"unit":"g"}}]}"""
        val result = runBlocking {
            app.trackly.assistant.tools.ProposeMealTool.execute(
                kotlinx.serialization.json.Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject,
                app.trackly.assistant.tools.ToolContext(engine, engine.now()),
            )
        }
        val sauce = (result.card as ProposalCard).items.single()
        assertEquals(60.0, sauce.amount!!, 0.0)
        assertEquals("g", sauce.unit)
        assertFalse(sauce.known)
    }

    @Test
    fun `CHAT-02 - le modele note autre chose que la carte - rien n est compte, un seul appel de plus`() {
        val engine = engine()
        val pizza = """{"product":null,"dish":null,"food":{"label":"Pizza","quantity":null,"unit":null,"kcal":800,"protein_g":30,"carbs_g":90,"fat_g":30,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"soir","day":null}"""
        val record = """{"items":[$pizza],"question":null,"proposal_id":"prop-6"}"""
        val (model, transport) = noteThroughModel(withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Oups."), engine = engine)

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertFalse("prop-6" in model.state.value.notedProposals)
        assertTrue(model.state.value.notice.orEmpty(), model.state.value.notice.orEmpty().contains("Rien n’a été noté"))
        // Pas de boucle payante : la réponse à l'outil refusé, puis plus rien.
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `CHAT-02 - le modele change une quantite connue - l app note celle de la carte`() {
        val engine = engine()
        val bigger = PATES.replace("\"quantity\":100", "\"quantity\":1000")
        val record = """{"items":[$bigger,$THON,$SAUCE],"question":null,"proposal_id":"prop-6"}"""
        val (model, _) = noteThroughModel(withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"), engine = engine)

        assertEquals(listOf(350.0, 88.0, 30.0), runBlocking { engine.daySummary(monday).consumptions }.map { it.food.nutrition.kcal })
        assertTrue("prop-6" in model.state.value.notedProposals)
    }

    @Test
    fun `CHAT-02 - le modele remplace l aliment a estimer - rien n est compte`() {
        val engine = engine()
        val mayo = SAUCE.replace("Sauce tomate", "Mayonnaise")
        val record = """{"items":[$PATES,$THON,$mayo],"question":null,"proposal_id":"prop-6"}"""
        val (model, _) = noteThroughModel(withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"), engine = engine)

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertFalse("prop-6" in model.state.value.notedProposals)
    }

    @Test
    fun `CHAT-01 - sans fiche, carte d enregistrement impossible a ecrire - rien n est compte`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TracklyDatabase::class.java).build()
        try {
            val engine = TracklyEngine(RoomTracklyStore(db), SampleData.clock("2026-11-02T18:40"))
            runBlocking {
                for ((id, name, n) in listOf(
                    Triple("prd-pates", "Pâtes complètes", Nutrients(350.0, 13.0, 65.0, 2.5)),
                    Triple("prd-thon", "Thon au naturel", Nutrients(110.0, 25.0, 0.0, 1.0)),
                )) {
                    check(engine.execute(MemorizeProduct(NewProduct(name, nutrients = n, basis = NutritionBasis(100.0, "g"), productId = id, kind = ProductKind.Recurring))) is ActionOutcome.Applied)
                }
            }
            val shared = object : SharedTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
            }
            val history = FailingHistory(RoomChatHistory(db.chat()))
            val record = """{"items":[$PATES,$THON,$SAUCE],"question":null,"proposal_id":"prop-6"}"""
            val (model, _) = noteThroughModel(
                withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Noté !"),
                engine = engine, history = history, sharedTransaction = shared,
            )

            assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
            assertFalse("prop-6" in model.state.value.notedProposals)
        } finally {
            db.close()
        }
    }

    @Test
    fun `CHAT-03 - fiche modifiee pendant l appel au modele - rien n est note, la carte s actualise`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        val record = """{"items":[$PATES,$THON,$SAUCE],"question":null,"proposal_id":"prop-6"}"""
        val richer = NewProduct("Pâtes complètes", nutrients = Nutrients(400.0, 14.0, 75.0, 3.0), basis = NutritionBasis(100.0, "g"))
        val (model, _) = noteThroughModel(
            withSauce, ModelReplies.call("record_consumptions", record), ModelReplies.text("Ok."),
            engine = engine, history = history,
            onRequest = { n -> if (n == 1) check(runBlocking { engine.execute(CorrectProduct("prd-pates", richer)) } is ActionOutcome.Applied) },
        )

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertTrue(model.state.value.notice.orEmpty(), model.state.value.notice.orEmpty().contains("a changé"))
        val fresh = model.state.value.messages.first().cards.single() as ProposalCard
        assertEquals(400.0, fresh.items[0].kcal!!, 0.01)
    }

    @Test
    fun `CHAT-03 - fiche modifiee depuis la carte - rien n est note, la carte montre la nouvelle version`() {
        val engine = engine()
        val history = InMemoryChatHistory()
        runBlocking { history.append(Instant.parse("2026-11-02T17:39:00Z"), ChatRole.Assistant, "Voilà.", listOf(known)) }
        val model = ChatViewModel(AssistantServices.inMemory(engine, ScriptedTransport(), key, history), SavedStateHandle())
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch { model.state.collect { } }
        idleUntil { model.state.value.messages.size == 1 }
        val richer = NewProduct("Pâtes complètes", nutrients = Nutrients(400.0, 14.0, 75.0, 3.0), basis = NutritionBasis(100.0, "g"))
        check(runBlocking { engine.execute(CorrectProduct("prd-pates", richer)) } is ActionOutcome.Applied)

        model.note(known)
        idleUntil { model.state.value.notice != null }

        assertTrue(runBlocking { engine.daySummary(monday).consumptions }.isEmpty())
        assertTrue(model.state.value.notice.orEmpty(), model.state.value.notice.orEmpty().contains("a changé"))
        val fresh = model.state.value.messages.single().cards.single() as ProposalCard
        assertEquals(400.0, fresh.items[0].kcal!!, 0.01)
        assertEquals(488.0, fresh.totalKcal, 0.01)

        // La nouvelle version, encore valide : un toucher et c'est noté, avec les valeurs de la carte.
        model.note(fresh)
        idleUntil { fresh.id in model.state.value.notedProposals }
        assertEquals(listOf(400.0, 88.0), runBlocking { engine.daySummary(monday).consumptions }.map { it.food.nutrition.kcal })
        watch.cancel()
    }

    private companion object {
        const val PATES = """{"product":{"product_id":"prd-pates","new_product":null,"quantity":100,"unit":"g","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"soir","day":null}"""
        const val THON = """{"product":{"product_id":"prd-thon","new_product":null,"quantity":80,"unit":"g","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"soir","day":null}"""
        const val SAUCE = """{"product":null,"dish":null,"food":{"label":"Sauce tomate","quantity":60,"unit":"g","kcal":30,"protein_g":1,"carbs_g":5,"fat_g":0.5,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"soir","day":null}"""
    }
}
