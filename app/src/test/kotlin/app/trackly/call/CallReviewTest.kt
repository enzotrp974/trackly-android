package app.trackly.call

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.time.TrackingDay
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import app.trackly.testing.SettableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Relecture après l'appel : la réflexion relit une fois, sans bloquer, au réglage de l'appel. Elle note ce qui a été
 * dit clairement sans être traité, écrit dans le fil du jour les questions restées sans réponse et importantes, et rien
 * d'autre. Elle survit à un arrêt d'Android, se reprend à l'ouverture, puis laisse un message court. Réponses du modèle
 * simulées : aucun appel réseau.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallReviewTest {
    @get:Rule val tmp = TemporaryFolder()

    private val monday = LocalDate.parse("2026-11-02")

    private inner class Setup(
        val scope: TestScope,
        val clock: SettableClock = SettableClock("2026-11-02T12:30"),
        val engine: TracklyEngine = SampleData.engine(clock),
        val history: InMemoryChatHistory = InMemoryChatHistory(),
        val store: PendingReviewStore = InMemoryPendingReviewStore(),
        val unseen: UnseenQuestions = UnseenQuestions(),
        transport: ResponsesTransport,
    ) {
        val usage = mutableListOf<UsageEvent>()
        val requests = mutableListOf<JsonObject>()
        var online = true
        val thinker = AgentCallThinker(
            ResponsesClient({ body -> requests += body; transport.send(body) }, RetryPolicy.None),
            engine, history, UsageSink { usage += it },
        )

        /** Une nouvelle instance sur les mêmes fichiers et la même base : l'app relancée après un arrêt d'Android. */
        fun review() = CallReview(scope.backgroundScope, store, thinker, history, unseen, engine::now, { online }, StandardTestDispatcher(scope.testScheduler))

        val startedAt: Instant = engine.now().minusSeconds(120)

        fun report(
            unprocessed: List<String> = emptyList(),
            openQuestions: List<String> = emptyList(),
            noted: List<NotedEntry> = emptyList(),
            transcript: List<CallLine> = unprocessed.map { CallLine(Speaker.User, it) },
        ) = CallMealReport(noted, openQuestions, unprocessed, transcript, startedAt, CallReflection.Medium)

        suspend fun thread(): List<ChatMessage> = history.last(TrackingDay.of(engine.now()), 50)
    }

    private fun scripted(vararg steps: (ScriptedModel.Request) -> JsonObject) = ScriptedModel(*steps)

    private val offline = ResponsesTransport { throw AssistantException(AssistantError.NoInternet) }

    @Test
    fun `sans doute - parole non traitee claire notee, aucune question, rien de plus`() = runTest {
        val s = Setup(
            this,
            transport = scripted(
                { CallReplies.recordFood("Yaourt nature", 60, moment = "midi") },
                { CallReplies.review(recap = "🥣 **Yaourt nature** noté, 60 kcal.") },
            ),
        )
        s.review().onReport(s.report(unprocessed = listOf("Ah, et un yaourt nature de 125 g.")))
        runCurrent()

        val message = s.thread().single()
        assertEquals(ChatRole.Assistant, message.role)
        assertEquals("📞 Après l’appel\n\n🥣 **Yaourt nature** noté, 60 kcal.", message.text)
        assertEquals("Yaourt nature", (message.cards.single() as RecordCard).items.single().label)
        assertTrue("Yaourt nature" in s.engine.daySummary(monday).consumptions.map { it.food.label })
        assertNull("aucune question : pas de pastille", s.unseen.state.value)
        assertTrue("effacé dès la relecture réussie", s.store.isEmpty())
        // Même modèle et même effort que la réflexion de l'appel, sans le niveau prioritaire (l'utilisateur n'attend pas).
        val body = s.requests.first()
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(body["service_tier"])
        assertTrue(body.toString().contains("Relecture après l'appel"))
        assertTrue(body.toString().contains("- Ah, et un yaourt nature de 125 g."))
        // Coût dans le suivi d'usage : une ligne par requête.
        assertEquals(2, s.usage.size)
    }

    @Test
    fun `avec doute - rien n est note, la question va dans le fil du jour, pastille`() = runTest {
        val s = Setup(this, transport = scripted({ CallReplies.review(questions = listOf("Ta salade, c’était laquelle ?")) }))
        s.review().onReport(s.report(unprocessed = listOf("Et ce soir une salade.")))
        runCurrent()

        val message = s.thread().single()
        assertEquals("📞 Après l’appel\n\nIl me reste une question :\n- Ta salade, c’était laquelle ?", message.text)
        assertTrue(message.cards.isEmpty())
        assertEquals(1, s.unseen.state.value!!.count)
        assertEquals(monday, s.unseen.state.value!!.day)
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `plusieurs questions - seules celles qui comptent, dans l ordre, et la pastille les compte`() = runTest {
        val open = listOf("Le poulet, pesé cru ou cuit ?", "Tu as mis de l’huile ?", "C’était bon ?")
        val s = Setup(
            this,
            transport = scripted({ r ->
                // Les questions restées sans réponse sont données à la relecture, qui retire l'inutile.
                assertTrue(r.body.toString().contains("- Tu as mis de l’huile ?"))
                CallReplies.review(questions = open.take(2))
            }),
        )
        s.review().onReport(s.report(openQuestions = open, transcript = listOf(CallLine(Speaker.User, "Du poulet et du riz."))))
        runCurrent()

        assertEquals(
            "📞 Après l’appel\n\nIl me reste 2 questions :\n- Le poulet, pesé cru ou cuit ?\n- Tu as mis de l’huile ?",
            s.thread().single().text,
        )
        assertEquals(2, s.unseen.state.value!!.count)
    }

    @Test
    fun `bavardage sans repas - aucune relecture, rien d ecrit, rien de garde ni paye`() = runTest {
        val s = Setup(this, transport = scripted())
        val chat = listOf(CallLine(Speaker.User, "Salut, ça va ?"), CallLine(Speaker.Voice, "Ça va !"), CallLine(Speaker.User, "Bon, merci, au revoir !"))
        val review = s.review()
        review.onCallEnded(s.startedAt, chat, CallReflection.Medium)
        runCurrent()
        assertTrue("rien gardé pour un appel sans vraie parole", s.store.isEmpty())
        review.onReport(s.report(unprocessed = listOf("Salut, ça va ?", "Bon, merci, au revoir !"), transcript = chat))
        runCurrent()
        assertTrue(s.requests.isEmpty())
        assertTrue(s.thread().isEmpty())
        assertNull(s.unseen.state.value)
        assertFalse(review.running.value)
    }

    @Test
    fun `tout traite pendant l appel - aucune relecture, le recap de l appel suffit`() = runTest {
        val s = Setup(this, transport = scripted())
        val noted = listOf(NotedEntry("e1", "Pomme", "midi", "2026-11-02", actionId = 1))
        s.review().onReport(s.report(noted = noted, transcript = listOf(CallLine(Speaker.User, "Une grosse pomme à midi."))))
        runCurrent()
        assertTrue(s.requests.isEmpty())
        assertTrue(s.thread().isEmpty())
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `echec reseau - rien d invente, reessai aux ouvertures, puis un message court sans chiffre`() = runTest {
        val s = Setup(this, transport = offline)
        s.review().onReport(s.report(unprocessed = listOf("Et un yaourt."), openQuestions = listOf("Ta pomme, grosse ou petite ?")))
        runCurrent()
        assertTrue("rien n'est écrit après un échec", s.thread().isEmpty())
        assertEquals(1, s.store.all().single().attempts)

        // Prochaines ouvertures de l'app : nouvel essai à chaque fois, au plus trois en tout.
        assertTrue(s.review().resume())
        runCurrent()
        assertEquals(2, s.store.all().single().attempts)
        assertTrue(s.thread().isEmpty())
        assertTrue(s.review().resume())
        runCurrent()

        assertEquals(3, s.requests.size)
        val message = s.thread().single()
        assertTrue(message.text.startsWith("📞 Après l’appel\n\nJe n’ai pas pu relire la fin de ton appel de 12h28."))
        assertTrue(message.text.contains("redis-le-moi"))
        assertTrue(message.text.endsWith("Il me reste une question :\n- Ta pomme, grosse ou petite ?"))
        assertFalse("aucun chiffre inventé", message.text.contains("kcal") || message.text.contains(" g "))
        assertEquals(1, s.unseen.state.value!!.count)
        assertTrue(s.store.isEmpty())
        assertFalse("plus rien à reprendre", s.review().resume())
    }

    @Test
    fun `echec puis reseau revenu - la relecture reprend a l ouverture et efface ce qu elle gardait`() = runTest {
        var down = true
        val model = scripted({ CallReplies.review(questions = listOf("Ta pomme, grosse ou petite ?")) })
        val s = Setup(this, transport = { body -> if (down) throw AssistantException(AssistantError.NoInternet) else model.send(body) })
        s.review().onReport(s.report(openQuestions = listOf("Ta pomme, grosse ou petite ?"), transcript = listOf(CallLine(Speaker.User, "Une pomme."))))
        runCurrent()
        assertTrue(s.thread().isEmpty())
        down = false
        assertTrue(s.review().resume())
        runCurrent()
        assertTrue(s.thread().single().text.endsWith("- Ta pomme, grosse ou petite ?"))
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `hors ligne - aucun essai compte, la relecture attend la prochaine ouverture`() = runTest {
        val s = Setup(this, transport = scripted({ CallReplies.review(questions = listOf("Quelle salade ?")) }))
        s.online = false
        s.review().onReport(s.report(unprocessed = listOf("Une salade ce midi.")))
        runCurrent()
        assertTrue(s.requests.isEmpty())
        assertEquals(0, s.store.all().single().attempts)
        s.online = true
        s.review().resume()
        runCurrent()
        assertEquals(1, s.requests.size)
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `app tuee juste apres l appel - la relecture reprend au retour depuis le fichier, sans renoter`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val history = InMemoryChatHistory()
        val clock = SettableClock("2026-11-02T12:30")
        val engine = SampleData.engine(clock)
        // Pendant l'appel, la réflexion a noté la pomme : sa carte est dans le fil du jour (base durable).
        val during = Setup(
            this, clock, engine, history, FilePendingReviewStore(file),
            transport = scripted({ CallReplies.recordFood("Pomme", 80, grams = 150) }, { CallReplies.answer(questions = listOf("Grosse ou petite ?")) }),
        )
        val transcript = listOf(CallLine(Speaker.User, "Ce midi une pomme et un yaourt nature."), CallLine(Speaker.Voice, "Ok."))
        during.thinker.think(ThinkRequest(listOf("item_1"), transcript, CallReflection.Medium))
        val pommeId = (during.thread().single().cards.single() as RecordCard).items.single().entryId
        // Raccroché, puis Android tue l'app avant le rapport de la dernière réflexion : seul le fichier reste.
        during.review().onCallEnded(during.startedAt, transcript, CallReflection.High)
        runCurrent()
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("yaourt nature"))

        val after = Setup(
            this, clock, engine, history, FilePendingReviewStore(file),
            transport = scripted({ r ->
                val text = r.body.toString()
                assertTrue(text.contains("L'app a été fermée avant la fin de la dernière réflexion"))
                // Ce que la réflexion a noté est retrouvé dans le fil du jour et donné à la relecture.
                assertTrue(text.contains("Pomme (midi, 2026-11-02) [entry_id $pommeId]"))
                assertTrue(text.contains("${Speaker.User.label} : Ce midi une pomme et un yaourt nature."))
                // Le modèle se trompe et renote la pomme : la garde refuse.
                CallReplies.recordFoods("Pomme" to 80, "Yaourt nature" to 60, id = "a")
            }, { r ->
                assertEquals("error", r.lastToolOutput["status"]!!.jsonPrimitive.content)
                assertTrue(r.lastToolOutput["error"]!!.jsonPrimitive.content.contains(pommeId))
                CallReplies.recordFood("Yaourt nature", 60, id = "b")
            }, { CallReplies.review(questions = listOf("Ta pomme, grosse ou petite ?"), recap = "Yaourt noté.") }),
        )
        assertTrue(after.review().resume())
        runCurrent()
        // Réglage de l'appel gardé dans le fichier : Élevée.
        assertEquals("high", after.requests.first()["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val last = after.thread().last()
        assertEquals("📞 Après l’appel\n\nYaourt noté.\n\nIl me reste une question :\n- Ta pomme, grosse ou petite ?", last.text)
        assertEquals(listOf("Pomme", "Yaourt nature"), engine.daySummary(monday).consumptions.map { it.food.label }.filter { it == "Pomme" || it == "Yaourt nature" }.sorted())
        assertFalse("fichier effacé dès la relecture réussie", file.exists())
        assertEquals(1, after.unseen.state.value!!.count)
    }

    @Test
    fun `rapport apres la fin d appel - il remplace ce qui avait ete garde, une seule relecture`() = runTest {
        val s = Setup(this, transport = scripted({ r ->
            assertFalse(r.body.toString().contains("L'app a été fermée"))
            CallReplies.review(questions = listOf("Quelle salade ?"))
        }))
        val review = s.review()
        val transcript = listOf(CallLine(Speaker.User, "Ce soir une salade."))
        review.onCallEnded(s.startedAt, transcript, CallReflection.Medium)
        review.onReport(s.report(unprocessed = listOf("Ce soir une salade."), transcript = transcript))
        runCurrent()
        assertEquals(1, s.requests.size)
        assertTrue(s.store.isEmpty())
        assertFalse(review.running.value)
    }

    @Test
    fun `fil de l appel passe a 5 h - message court dans le nouveau fil, sans relire`() = runTest {
        val s = Setup(this, clock = SettableClock("2026-11-02T23:50"), transport = scripted())
        s.store.put(
            PendingReview(
                s.startedAt, s.engine.now(), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une pomme.")),
                openQuestions = listOf("Grosse ou petite ?"), attempts = 1,
            ),
        )
        s.clock.set("2026-11-03T07:00")
        assertTrue(s.review().resume())
        runCurrent()
        assertTrue(s.requests.isEmpty())
        val message = s.history.last(LocalDate.parse("2026-11-03"), 10).single()
        // Le message dit le jour de l'appel : l'utilisateur sait de quel repas parlent les questions.
        assertTrue(message.text.contains("Je n’ai pas pu relire la fin de ton appel d’hier à 23h48."))
        assertTrue(message.text.endsWith("- Grosse ou petite ?"))
        assertEquals(LocalDate.parse("2026-11-03"), s.unseen.state.value!!.day)
    }

    @Test
    fun `chat pendant la relecture, ou enregistrement sans carte - la garde relit le moteur et ne renote pas`() = runTest {
        val model = scripted(
            { CallReplies.recordFood("Yaourt nature", 60) },
            { r ->
                assertEquals("error", r.lastToolOutput["status"]!!.jsonPrimitive.content)
                CallReplies.review()
            },
        )
        lateinit var s: Setup
        var first = true
        s = Setup(this, transport = { body ->
            if (first) {
                first = false
                // Pendant que la relecture réfléchit, l'utilisateur écrit « un yaourt nature » dans le chat : le tour du chat l'enregistre.
                check(s.engine.execute(AddConsumptions(NewConsumption(Food("Yaourt nature", Nutrition(60.0, 5.0, 6.0, 1.0)), Moment.Midi, explicitDay = monday))) is ActionOutcome.Applied)
            }
            model.send(body)
        })
        s.review().onReport(s.report(unprocessed = listOf("Ah, et un yaourt nature.")))
        runCurrent()
        assertEquals(1, s.engine.daySummary(monday).consumptions.count { it.food.label == "Yaourt nature" })
        assertTrue("rien de noté, aucune question : rien d'écrit", s.thread().isEmpty())
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `un seul vrai mot ou un nombre suffit - la parole est relue`() = runTest {
        val s = Setup(this, transport = scripted({ CallReplies.review(questions = listOf("Quel pain, et combien ?")) }))
        s.review().onReport(s.report(unprocessed = listOf("Ah, et le pain.")))
        runCurrent()
        assertEquals(1, s.requests.size)
        assertTrue(SmallTalk.substantive(listOf("Oui, 200")))
        assertFalse(SmallTalk.substantive(listOf("Ok merci, c'est tout, au revoir !")))
    }

    @Test
    fun `app tuee apres l ecriture du message, avant d effacer le fichier - rien n est ecrit ni paye deux fois`() = runTest {
        val s = Setup(this, transport = scripted())
        val attemptAt = s.engine.now().minusSeconds(10)
        s.store.put(
            PendingReview(
                s.startedAt, s.engine.now().minusSeconds(30), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une salade.")),
                unprocessed = listOf("Une salade."), attempts = 1, attemptAt = attemptAt,
            ),
        )
        s.history.append(s.engine.now().minusSeconds(5), ChatRole.Assistant, "📞 Après l’appel\n\nIl me reste une question :\n- Quelle salade ?")
        assertTrue(s.review().resume())
        runCurrent()
        assertTrue(s.requests.isEmpty())
        assertEquals(1, s.thread().size)
        assertTrue(s.store.isEmpty())
    }

    @Test
    fun `relecture d un autre appel ecrite apres un echec - elle n est pas prise pour celle-ci`() = runTest {
        val s = Setup(
            this,
            transport = scripted(
                { r ->
                    assertTrue(r.body.toString().contains("Ce midi une pomme."))
                    CallReplies.review(questions = listOf("Ta pomme, grosse ou petite ?"))
                },
                { r ->
                    assertTrue(r.body.toString().contains("Ce matin une salade."))
                    CallReplies.review(questions = listOf("Quelle salade ?"))
                },
            ),
        )
        // Appel A : sa relecture a échoué une fois (réseau), elle attend la prochaine occasion.
        val callA = s.engine.now().minusSeconds(900)
        s.store.put(
            PendingReview(
                callA, callA.plusSeconds(60), CallReflection.Medium, listOf(CallLine(Speaker.User, "Ce matin une salade.")),
                unprocessed = listOf("Ce matin une salade."), attempts = 1, attemptAt = s.engine.now().minusSeconds(600),
            ),
        )
        // Appel B : sa relecture écrit son message, puis celle de A reprend.
        s.review().onReport(s.report(unprocessed = listOf("Ce midi une pomme.")))
        runCurrent()
        assertEquals("A est relu, pas effacé sur le message de B", 2, s.requests.size)
        val texts = s.thread().map { it.text }
        assertTrue(texts.any { it.endsWith("- Ta pomme, grosse ou petite ?") })
        assertTrue(texts.any { it.endsWith("- Quelle salade ?") })
        assertTrue(s.store.isEmpty())
    }

    /** Le vrai fichier, dont une écriture choisie échoue une seule fois (disque plein un instant). */
    private class FlakyFileStore(private val real: FilePendingReviewStore, var failOnce: (PendingReview) -> Boolean) : PendingReviewStore by real {
        override fun put(review: PendingReview) {
            if (failOnce(review)) {
                failOnce = { false }
                throw java.io.IOException("disque plein")
            }
            real.put(review)
        }
    }

    /** Répond selon l'appel relu (l'ordre des relectures n'est pas imposé). */
    private fun byCall(vararg answers: Pair<String, String>) = ResponsesTransport { body ->
        val text = body.toString()
        val question = answers.first { text.contains(it.first) }.second
        CallReplies.review(questions = listOf(question))
    }

    @Test
    fun `remise a zero de l essai d un autre appel non ecrite - cette relecture attend, aucune n est effacee sans etre faite`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val callA = Instant.parse("2026-11-02T11:15:00Z")
        val store = FlakyFileStore(FilePendingReviewStore(file)) { it.startedAt == callA && it.attemptAt == null }
        val s = Setup(this, store = store, transport = byCall("Ce matin une salade." to "Quelle salade ?", "Ce midi une pomme." to "Ta pomme, grosse ou petite ?"))
        // Appel A : sa relecture a échoué une fois, elle attend avec son essai noté.
        store.put(
            PendingReview(
                callA, callA.plusSeconds(60), CallReflection.Medium, listOf(CallLine(Speaker.User, "Ce matin une salade.")),
                unprocessed = listOf("Ce matin une salade."), attempts = 1, attemptAt = s.engine.now().minusSeconds(600),
            ),
        )
        // Appel B : la remise à zéro de l'essai de A échoue (disque plein un instant).
        s.review().onReport(s.report(unprocessed = listOf("Ce midi une pomme.")))
        runCurrent()
        // Le stockage remarche ; l'app est rouverte.
        s.review().resume()
        runCurrent()
        val texts = s.thread().map { it.text }
        assertTrue("A relu, pas effacé sur le message de B : " + texts, texts.any { it.endsWith("- Quelle salade ?") })
        assertTrue(texts.any { it.endsWith("- Ta pomme, grosse ou petite ?") })
        assertEquals("chaque appel relu une fois", 2, s.requests.size)
        assertFalse(file.exists())
    }

    @Test
    fun `fichier illisible un moment - aucune relecture ne passe par-dessus celles qu il garde`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val store = FilePendingReviewStore(file)
        val s = Setup(this, store = store, transport = byCall("Ce matin une salade." to "Quelle salade ?", "Ce midi une pomme." to "Ta pomme, grosse ou petite ?"))
        val callA = Instant.parse("2026-11-02T11:15:00Z")
        store.put(
            PendingReview(
                callA, callA.plusSeconds(60), CallReflection.Medium, listOf(CallLine(Speaker.User, "Ce matin une salade.")),
                unprocessed = listOf("Ce matin une salade."), attempts = 1, attemptAt = s.engine.now().minusSeconds(600),
            ),
        )
        assertTrue(file.setReadable(false))
        // Même processus : l'app est rouverte sans avoir été tuée.
        val review = s.review()
        review.onReport(s.report(unprocessed = listOf("Ce midi une pomme.")))
        runCurrent()
        // Avant : l'écriture de B, sur une lecture ratée, remplaçait le fichier et A disparaissait.
        assertTrue("A est toujours gardé", file.isFile)
        file.setReadable(true)
        assertTrue(review.resume())
        runCurrent()
        val texts = s.thread().map { it.text }
        assertTrue("A relu : " + texts, texts.any { it.endsWith("- Quelle salade ?") })
        assertTrue(texts.any { it.endsWith("- Ta pomme, grosse ou petite ?") })
        assertFalse(file.exists())
    }

    @Test
    fun `essai qui ne s enregistre pas - aucune requete payante, le plafond de 3 tient`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val store = FilePendingReviewStore(file)
        val s = Setup(this, store = store, transport = offline)
        store.put(
            PendingReview(
                s.startedAt, s.engine.now().minusSeconds(30), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une salade.")),
                unprocessed = listOf("Une salade."),
            ),
        )
        // Le vrai fichier, dont chaque écriture d'un nouvel essai échoue (panne qui dure).
        val stuck = object : PendingReviewStore by store {
            override fun put(review: PendingReview) {
                if (review.attemptAt != null) throw java.io.IOException("disque plein")
                store.put(review)
            }
        }
        repeat(5) {
            CallReview(backgroundScope, stuck, s.thinker, s.history, s.unseen, s.engine::now, { true }, StandardTestDispatcher(testScheduler)).resume()
            runCurrent()
        }
        assertTrue("aucune requête sans essai compté : " + s.requests.size, s.requests.isEmpty())
        assertEquals(0, store.all().single().attempts)
    }

    @Test
    fun `fichier illisible a plusieurs lancements - mis de cote, message court, et les relectures repartent`() = runTest {
        val dir = File(tmp.root, "appel")
        val file = File(dir, FilePendingReviewStore.FILE_NAME)
        val s = Setup(this, transport = byCall("Ce midi une pomme." to "Ta pomme, grosse ou petite ?"))
        FilePendingReviewStore(file).put(
            PendingReview(
                s.startedAt, s.engine.now().minusSeconds(30), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une salade.")),
                unprocessed = listOf("Une salade."),
            ),
        )
        assertTrue(file.setReadable(false))
        fun launch() = CallReview(backgroundScope, FilePendingReviewStore(file), s.thinker, s.history, s.unseen, s.engine::now, { true }, StandardTestDispatcher(testScheduler))
        // Deux lancements : une panne peut être passagère, rien ne bouge.
        repeat(2) {
            val review = launch()
            review.resume()
            runCurrent()
            review.resume()
            runCurrent()
        }
        assertTrue(file.isFile)
        assertTrue(s.thread().isEmpty())
        // Troisième lancement : le fichier est mis de côté (pas effacé), l'utilisateur est prévenu sans chiffre.
        val review = launch()
        review.resume()
        runCurrent()
        val aside = dir.listFiles()!!.filter { it.name.startsWith(FilePendingReviewStore.SET_ASIDE_PREFIX) }
        assertEquals(1, aside.size)
        assertTrue(aside.single().setReadable(true))
        assertTrue("contenu gardé", aside.single().readText().contains("Une salade."))
        assertFalse(file.exists())
        val message = s.thread().single().text
        assertEquals("📞 Une relecture d’appel n’a pas pu se faire : redis-moi ce qui manque.", message)
        assertFalse(message.any(Char::isDigit))
        assertEquals(1, s.unseen.state.value!!.count)
        // Les relectures repartent sur un fichier neuf.
        review.onReport(s.report(unprocessed = listOf("Ce midi une pomme.")))
        runCurrent()
        assertTrue(s.thread().last().text.endsWith("- Ta pomme, grosse ou petite ?"))
        assertFalse(file.exists())
    }

    @Test
    fun `appel lance pendant l ecriture de l essai - l essai ne compte pas`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val real = FilePendingReviewStore(file)
        lateinit var review: CallReview
        val store = object : PendingReviewStore by real {
            override fun put(review0: PendingReview) {
                // L'utilisateur lance un appel pendant que l'essai s'écrit : la relecture est arrêtée, l'écriture finit quand même.
                if (review0.attemptAt != null) review.yieldToCall()
                real.put(review0)
            }
        }
        val s = Setup(this, store = store, transport = scripted())
        real.put(PendingReview(s.startedAt, s.engine.now().minusSeconds(30), CallReflection.Medium, listOf(CallLine(Speaker.User, "Une salade.")), unprocessed = listOf("Une salade.")))
        review = s.review()
        review.resume()
        runCurrent()
        assertTrue(s.requests.isEmpty())
        assertEquals("essai non compté", 0, real.all().single().attempts)
    }

    @Test
    fun `appel lance pendant la mise de cote - l utilisateur est quand meme prevenu`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        file.parentFile!!.mkdirs()
        file.writeText("[]")
        File(file.path + FilePendingReviewStore.UNREADABLE_SUFFIX).writeText("2")
        assertTrue(file.setReadable(false))
        val real = FilePendingReviewStore(file)
        lateinit var review: CallReview
        val store = object : PendingReviewStore by real {
            override fun setAsideIfUnreadable(at: Instant): Boolean {
                review.yieldToCall()
                return real.setAsideIfUnreadable(at)
            }
        }
        val s = Setup(this, store = store, transport = scripted())
        review = s.review()
        review.resume()
        runCurrent()
        assertFalse(file.exists())
        assertEquals(CallReview.SET_ASIDE_MESSAGE, s.thread().single().text)
    }

    @Test
    fun `rapport garde en memoire seulement - il passe avant la version provisoire du fichier`() = runTest {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val real = FilePendingReviewStore(file)
        // Le rapport (version non provisoire, sans essai) ne s'écrit pas : disque plein un instant.
        val store = object : PendingReviewStore by real {
            override fun put(review: PendingReview) {
                if (!review.provisional && review.attemptAt == null) throw java.io.IOException("disque plein")
                real.put(review)
            }
        }
        val s = Setup(this, store = store, transport = scripted({ r ->
            assertTrue("questions du rapport relues", r.body.toString().contains("- Quelle salade ?"))
            CallReplies.review(questions = listOf("Quelle salade ?"))
        }))
        val transcript = listOf(CallLine(Speaker.User, "Ce soir une salade."))
        val review = s.review()
        review.onCallEnded(s.startedAt, transcript, CallReflection.Medium)
        runCurrent()
        assertTrue(real.all().single().provisional)
        review.onReport(s.report(openQuestions = listOf("Quelle salade ?"), transcript = transcript))
        runCurrent()
        assertEquals(1, s.requests.size)
        assertTrue(s.thread().single().text.endsWith("- Quelle salade ?"))
    }

    @Test
    fun `fichier impossible a ecrire - la relecture se fait quand meme avec le rapport en memoire`() = runTest {
        val broken = object : PendingReviewStore {
            override fun isEmpty() = true
            override fun all(): List<PendingReview> = emptyList()
            override fun put(review: PendingReview) = throw java.io.IOException("disque plein")
            override fun remove(startedAt: Instant) = Unit
        }
        val s = Setup(this, store = broken, transport = scripted({ CallReplies.review(questions = listOf("Quelle salade ?")) }))
        s.review().onReport(s.report(unprocessed = listOf("Une salade ce midi.")))
        runCurrent()
        assertTrue(s.thread().single().text.endsWith("- Quelle salade ?"))
    }

    @Test
    fun `fichier - aller-retour fidele, efface quand plus rien n attend`() {
        val file = File(tmp.root, "appel/" + FilePendingReviewStore.FILE_NAME)
        val store = FilePendingReviewStore(file)
        assertTrue(store.isEmpty())
        val review = PendingReview(
            Instant.parse("2026-11-02T11:00:00Z"), Instant.parse("2026-11-02T11:03:00Z"), CallReflection.Fast,
            listOf(CallLine(Speaker.User, "Une pomme."), CallLine(Speaker.Voice, "Grosse ?")),
            listOf(NotedEntry("e1", "Pomme", "midi", "2026-11-02", productId = "p1", actionId = 4)),
            listOf("Grosse ou petite ?"), listOf("Et un café."), provisional = true, attempts = 2,
            attemptAt = Instant.parse("2026-11-02T11:04:00Z"),
        )
        store.put(review)
        assertEquals(listOf(review), FilePendingReviewStore(file).all())
        store.remove(review.startedAt)
        assertFalse(file.exists())
    }
}
