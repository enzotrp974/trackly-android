package app.trackly.call

import app.trackly.call.live.AudioLevels
import app.trackly.call.live.LaunchStep
import app.trackly.call.live.SessionRequest
import app.trackly.call.live.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Scénarios de l'appel, sans réseau et en temps virtuel. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val key = "sk-test-jamais-une-vraie-cle"

    /** Réflexion simulée : attend [delayMs] (temps virtuel), puis rend [result], ou le suivant de [results]. */
    private class FakeThinker(
        var delayMs: Long = 2_000,
        var result: ThinkResult = ThinkResult.Done(questions = listOf("Ta pomme, elle faisait quelle taille ?")),
        val results: ArrayDeque<ThinkResult> = ArrayDeque(),
    ) : CallThinker {
        val requests = mutableListOf<ThinkRequest>()
        var running = 0
        var maxRunning = 0
        override suspend fun think(request: ThinkRequest): ThinkResult {
            requests += request
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                delay(delayMs)
            } finally {
                running--
            }
            return results.removeFirstOrNull() ?: result
        }
    }

    private class Harness(scope: TestScope, val thinker: FakeThinker = FakeThinker(), transport: FakeLiveTransport = FakeLiveTransport()) {
        var summary: CallSummary? = null
        var meals: CallMealReport? = null
        var listened = 0
        val transport = transport
        val controller = CallController(
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
            wallClock = { Instant.parse("2026-11-06T12:00:00Z") },
            thinker = thinker,
            onListening = { listened++ },
            onFinished = { summary = it },
            onMeals = { meals = it },
        )
        val session = SessionRequest.session(CallInstructions.VOICE, emptyList())

        fun start(reflection: CallReflection = CallReflection.Medium, steps: List<Pair<LaunchStep, Long>> = emptyList()) =
            controller.start(transport, "sk-test-jamais-une-vraie-cle", session, reflection, preparedSteps = steps)
    }

    private fun TestScope.started(thinker: FakeThinker = FakeThinker()): Harness {
        val h = Harness(this, thinker)
        h.start()
        runCurrent()
        h.transport.emit(ServerEvents.started())
        advanceTimeBy(100)
        return h
    }

    @Test
    fun `je t ecoute au demarrage, vibration, cle seulement au transport`() = runTest {
        val h = started()
        assertEquals(CallPhase.Live, h.controller.state.value.phase)
        assertEquals(1, h.listened)
        assertEquals(key, h.transport.keyUsed)
        assertEquals(listOf("session.commentary.append"), h.transport.sentTypes())
        assertTrue(h.transport.sentText().contains(CallInstructions.LISTENING))
        assertFalse(h.transport.sentText().contains(key))
        assertFalse(h.transport.openedWith.toString().contains(key))
    }

    @Test
    fun `pas de je t ecoute si l utilisateur parle deja`() = runTest {
        val h = Harness(this)
        h.start()
        runCurrent()
        h.transport.emit(ServerEvents.input("Ce midi j'ai mangé"))
        h.transport.emit(ServerEvents.started())
        advanceTimeBy(100)
        assertEquals(CallPhase.Live, h.controller.state.value.phase)
        assertFalse(h.transport.sentText().contains(CallInstructions.LISTENING))
    }

    @Test
    fun `etapes du lancement chronometrees, preparees avant le toucher marquees`() = runTest {
        val h = Harness(this)
        h.start(steps = listOf(LaunchStep.Key to 2L, LaunchStep.Factory to 40L, LaunchStep.Offer to 30L, LaunchStep.Ice to 5L))
        runCurrent()
        advanceTimeBy(400)
        h.transport.emit(ServerEvents.started())
        advanceTimeBy(100)
        h.controller.hangUp()
        advanceTimeBy(100)
        h.transport.emit(ServerEvents.closed("close_requested", 1.0))
        advanceTimeBy(100)
        val s = h.summary!!
        assertTrue(s.prepared)
        assertEquals(setOf(LaunchStep.Key, LaunchStep.Factory, LaunchStep.Offer, LaunchStep.Ice), s.beforeTap)
        assertEquals(300L, s.steps.toMap()[LaunchStep.Http])
        assertEquals(400L, s.listenMs)
        assertEquals(400L, s.steps.toMap()[LaunchStep.Started])
        assertEquals(400L, s.metrics().listenMs)
    }

    @Test
    fun `relance a 15 s puis raccroche a 30 s, apres la phrase de la voix`() = runTest {
        val h = started()
        advanceTimeBy(15_100)
        assertTrue(h.transport.sentText().contains(CallInstructions.NUDGE))
        // La voix qui relance ne remet pas le compteur de silence à zéro.
        h.transport.emit(ServerEvents.output("Tu es toujours là ?"))
        advanceTimeBy(15_000)
        assertTrue(h.transport.sentText().contains(CallInstructions.GOODBYE))
        assertEquals(CallPhase.Ending, h.controller.state.value.phase)
        assertFalse(h.transport.sentTypes().contains("session.close"))
        h.transport.emit(ServerEvents.output("Je raccroche, à plus !"))
        advanceTimeBy(500)
        assertFalse("la voix finit sa phrase", h.transport.sentTypes().contains("session.close"))
        advanceTimeBy(500)
        assertTrue(h.transport.sentTypes().contains("session.close"))
        h.transport.emit(ServerEvents.closed("close_requested", 33.0))
        advanceTimeBy(100)
        val s = h.summary!!
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertEquals(EndReason.Silence, s.endReason)
        assertEquals(1, s.nudges)
        assertEquals(33_000L, s.billedMs)
        assertTrue(h.transport.closed)
    }

    @Test
    fun `parler remet le silence a zero`() = runTest {
        val h = started()
        advanceTimeBy(14_000)
        h.transport.emit(ServerEvents.input("et aussi"))
        advanceTimeBy(14_000)
        assertFalse(h.transport.sentText().contains(CallInstructions.NUDGE))
    }

    @Test
    fun `parole captee par le micro remet le silence a zero`() = runTest {
        val h = started()
        repeat(40) {
            h.transport.levels.value = AudioLevels(mic = 0.2f, ai = 0f)
            advanceTimeBy(500)
        }
        assertFalse(h.transport.sentText().contains(CallInstructions.NUDGE))
    }

    @Test
    fun `la reflection ne compte pas comme un silence, son delai est garde`() = runTest {
        val h = started(FakeThinker(delayMs = 40_000))
        h.transport.emit(ServerEvents.input("Ce midi, poulet riz brocolis."))
        advanceTimeBy(1_000)
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(30_000)
        assertTrue(h.controller.state.value.thinking)
        assertFalse(h.transport.sentText().contains(CallInstructions.NUDGE))
        advanceTimeBy(10_100)
        assertFalse(h.controller.state.value.thinking)
        // Dit dans la pause, avec l'identifiant de la délégation.
        val reply = h.transport.sent.last { it.contains("session.commentary.append") }
        assertTrue(reply.contains("\"delegation_id\":\"item_1\""))
        assertTrue(reply.contains("quelle taille"))
        val request = h.thinker.requests.single()
        assertEquals(listOf("item_1"), request.delegationIds)
        assertEquals(ThinkTrigger.Voice, request.trigger)
        assertEquals(CallReflection.Medium, request.reflection)
        assertEquals(CallLine(Speaker.User, "Ce midi, poulet riz brocolis."), request.transcript.last())
        h.controller.hangUp()
        advanceTimeBy(100)
        h.transport.emit(ServerEvents.closed("close_requested", 45.0))
        advanceTimeBy(100)
        assertEquals(listOf(ReflectionTiming(CallReflection.Medium, 40_000, true)), h.summary!!.reflections)
    }

    @Test
    fun `une reflexion attend que l utilisateur ait fini de parler`() = runTest {
        val h = started(FakeThinker(delayMs = 1_000))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        // L'utilisateur parle encore pendant et après la réflexion.
        repeat(10) {
            h.transport.emit(ServerEvents.input("et "))
            advanceTimeBy(300)
        }
        assertFalse(h.transport.sentText().contains("item_1"))
        advanceTimeBy(800)
        assertTrue(h.transport.sentText().contains("\"delegation_id\":\"item_1\""))
    }

    @Test
    fun `reflexion en echec, la voix continue sans inventer`() = runTest {
        val h = started(FakeThinker(delayMs = 500, result = ThinkResult.Failed("NoInternet")))
        h.transport.emit(ServerEvents.delegationCreated("item_9"))
        advanceTimeBy(1_500)
        val sent = h.transport.sent.last()
        assertTrue(sent.contains("session.thinking.append"))
        assertTrue(sent.contains("item_9"))
        assertTrue(sent.contains(CallInstructions.THINKING_FAILED))
        h.controller.abort(EndReason.User)
        assertFalse(h.summary!!.reflections.single().ok)
    }

    @Test
    fun `au revoir de l utilisateur, elle finit sa phrase puis raccroche`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Bon, merci, au revoir !"))
        advanceTimeBy(1_000)
        assertEquals(CallPhase.Ending, h.controller.state.value.phase)
        assertFalse(h.transport.sentTypes().contains("session.close"))
        h.transport.emit(ServerEvents.output("Salut, à plus !"))
        advanceTimeBy(900)
        assertTrue(h.transport.sentTypes().contains("session.close"))
        // Aucun au revoir ajouté par l'app : la voix dit le sien.
        assertFalse(h.transport.sentText().contains(CallInstructions.GOODBYE))
        h.transport.emit(ServerEvents.closed("close_requested", 12.0))
        advanceTimeBy(100)
        assertEquals(EndReason.Goodbye, h.summary!!.endReason)
    }

    @Test
    fun `au revoir sans reponse de la voix, raccroche quand meme`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("c'est tout, merci"))
        advanceTimeBy(1_000)
        assertEquals(CallPhase.Ending, h.controller.state.value.phase)
        advanceTimeBy(7_100)
        assertTrue(h.transport.sentTypes().contains("session.close"))
    }

    @Test
    fun `c est tout qui finit un repas ne raccroche pas`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Ce midi poulet riz et brocolis, c'est tout."))
        advanceTimeBy(2_000)
        assertEquals(CallPhase.Live, h.controller.state.value.phase)
    }

    @Test
    fun `l utilisateur parle par dessus la voix, l app la coupe puis lui rend le son`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.output("Alors ton poulet, tu l'as"))
        h.transport.levels.value = AudioLevels(mic = 0.3f, ai = 0.2f, micProcessed = 0.2f)
        advanceTimeBy(200)
        assertFalse(h.transport.speakerMuted)
        advanceTimeBy(200)
        assertTrue(h.transport.speakerMuted)
        assertTrue(h.transport.sent.last().contains("session.instructions.append"))
        assertTrue(h.transport.sent.last().contains(CallInstructions.BARGE_IN))
        // Voix coupée : plus de son joué ; elle se tait (plus de transcription) et le son revient.
        h.transport.levels.value = AudioLevels(mic = 0.3f, ai = 0f, micProcessed = 0.2f)
        advanceTimeBy(900)
        assertFalse(h.transport.speakerMuted)
        h.controller.abort(EndReason.User)
        assertEquals(1, h.summary!!.bargeIns)
    }

    @Test
    fun `l echo seul ne coupe pas la voix`() = runTest {
        val h = started()
        // Micro brut fort (écho du haut-parleur), mais après traitement presque rien.
        h.transport.levels.value = AudioLevels(mic = 0.25f, ai = 0.2f, micProcessed = 0.01f)
        advanceTimeBy(2_000)
        assertFalse(h.transport.speakerMuted)
    }

    @Test
    fun `dix minutes au plus`() = runTest {
        val h = started()
        repeat(60) {
            h.transport.emit(ServerEvents.input("…"))
            advanceTimeBy(10_000)
        }
        advanceTimeBy(200)
        assertTrue(h.transport.sentText().contains(CallInstructions.GOODBYE))
        advanceTimeBy(13_000)
        assertEquals(EndReason.MaxDuration, h.summary!!.endReason)
    }

    @Test
    fun `echec de connexion, rien de facture`() = runTest {
        val h = Harness(this, transport = FakeLiveTransport(failWith = TransportException("Clé refusée par OpenAI (401)", 401)))
        h.start()
        runCurrent()
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertEquals(EndReason.Failed, h.summary!!.endReason)
        assertEquals("Clé refusée par OpenAI (401)", h.controller.state.value.status)
        assertEquals(0L, h.summary!!.billedMs)
        assertNull(h.summary!!.listenMs)
    }

    @Test
    fun `raccrocher pendant une connexion bloquee ferme le transport et compte la session`() = runTest {
        val h = Harness(this, transport = FakeLiveTransport(hangOnOpen = true))
        h.start()
        advanceTimeBy(3_000)
        h.controller.hangUp()
        runCurrent()
        assertTrue(h.transport.closed)
        assertEquals(EndReason.User, h.summary!!.endReason)
        // La session a pu être créée : elle est comptée (le minimum de 15 s est appliqué au coût).
        assertTrue(h.summary!!.billedMs > 0)
    }

    @Test
    fun `raccroche avant l envoi de la requete, rien de compte`() = runTest {
        val h = Harness(this, transport = FakeLiveTransport(hangOnOpen = true, requestSent = false))
        h.start()
        advanceTimeBy(500)
        h.controller.hangUp()
        runCurrent()
        assertEquals(0L, h.summary!!.billedMs)
    }

    @Test
    fun `demarrage arrive apres le raccroche, ignore`() = runTest {
        val h = Harness(this)
        h.start()
        runCurrent()
        advanceTimeBy(16_000) // le serveur ne démarre pas à temps : fin demandée
        h.transport.emit(ServerEvents.started())
        advanceTimeBy(100)
        assertFalse(h.transport.sentText().contains(CallInstructions.LISTENING))
        assertEquals(0, h.listened)
    }

    @Test
    fun `micro coupe, la voix n est jamais coupee`() = runTest {
        val h = started()
        h.controller.setMicMuted(true)
        h.transport.levels.value = AudioLevels(mic = 0.3f, ai = 0.2f, micProcessed = 0.2f)
        advanceTimeBy(2_000)
        assertFalse(h.transport.speakerMuted)
        assertTrue(h.transport.micMuted)
    }

    @Test
    fun `la reflexion part quand la phrase de l utilisateur est retombee`() = runTest {
        val h = started(FakeThinker(delayMs = 100))
        h.transport.emit(ServerEvents.input("Ce midi poulet riz"))
        h.transport.emit(ServerEvents.delegationCreated("item_3"))
        advanceTimeBy(150)
        h.transport.emit(ServerEvents.input(" et une pomme."))
        advanceTimeBy(200)
        assertTrue("pas encore partie", h.thinker.requests.isEmpty())
        advanceTimeBy(300)
        assertEquals("Ce midi poulet riz et une pomme.", h.thinker.requests.single().transcript.last().text)
    }

    @Test
    fun `la reflexion part au plus tard apres une seconde`() = runTest {
        val h = started(FakeThinker(delayMs = 100))
        h.transport.emit(ServerEvents.delegationCreated("item_4"))
        repeat(8) {
            h.transport.emit(ServerEvents.input("et "))
            advanceTimeBy(150)
        }
        assertEquals(1, h.thinker.requests.size)
    }

    @Test
    fun `connexion perdue`() = runTest {
        val h = started()
        h.transport.drop("ice failed")
        advanceTimeBy(100)
        assertEquals(EndReason.Lost, h.summary!!.endReason)
    }

    @Test
    fun `serveur qui ne demarre pas la session`() = runTest {
        val h = Harness(this)
        h.start()
        advanceTimeBy(16_000)
        advanceTimeBy(6_000)
        assertEquals(EndReason.Failed, h.summary!!.endReason)
    }

    @Test
    fun `fermeture immediate quand l app disparait`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.usage(42.0))
        advanceTimeBy(100)
        h.controller.abort(EndReason.ClosedApp)
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertTrue(h.transport.sentTypes().contains("session.close"))
        assertTrue(h.transport.closed)
        assertEquals(EndReason.ClosedApp, h.summary!!.endReason)
        assertTrue(h.summary!!.billedMs >= 42_000)
    }

    @Test
    fun `cout final sur la duree du serveur`() = runTest {
        val h = started()
        repeat(7) {
            h.transport.emit(ServerEvents.input("…"))
            advanceTimeBy(10_000)
        }
        h.controller.hangUp() // 70 s côté téléphone, 60 s facturées côté serveur
        advanceTimeBy(100)
        h.transport.emit(ServerEvents.closed("close_requested", 60.0))
        advanceTimeBy(100)
        assertEquals(60_000L, h.summary!!.billedMs)
    }

    @Test
    fun `transcription regroupee par locuteur`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Une pomme "))
        h.transport.emit(ServerEvents.input("et un yaourt."))
        h.transport.emit(ServerEvents.output("Quelle taille, "))
        h.transport.emit(ServerEvents.output("la pomme ?"))
        advanceTimeBy(100)
        h.controller.abort(EndReason.User)
        assertEquals(
            listOf(CallLine(Speaker.User, "Une pomme et un yaourt."), CallLine(Speaker.Voice, "Quelle taille, la pomme ?")),
            h.summary!!.transcript,
        )
    }

    @Test
    fun `refus avant tout reseau`() = runTest {
        val h = Harness(this)
        h.controller.refuse(EndReason.MissingKey)
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertEquals(EndReason.MissingKey.label, h.controller.state.value.status)
        assertNull(h.transport.keyUsed)
        assertNull(h.summary)
    }

    // ───── Repas : seule la réflexion note, la voix n'attend jamais ─────

    private fun entry(label: String, id: String = "e-" + label.lowercase(), action: Long = 7) = NotedEntry(id, label, "midi", "2026-11-06", actionId = action)

    private fun commentaries(h: Harness) = h.transport.sent.filter { it.contains("session.commentary.append") }

    @Test
    fun `fragment - la voix demande l autre truc, aucune reflexion`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Ce petit déjeuner, j'ai mangé une pomme et un autre truc."))
        advanceTimeBy(1_200)
        h.transport.emit(ServerEvents.output("C'est quoi l'autre truc ?"))
        advanceTimeBy(10_000)
        assertTrue(h.thinker.requests.isEmpty())
        assertFalse(h.controller.state.value.thinking)
        // Sa réponse complète le repas : sans délégation de la voix, l'app réfléchit d'elle-même après une pause.
        h.transport.emit(ServerEvents.input("Un yaourt nature."))
        advanceTimeBy(2_000)
        assertTrue(h.thinker.requests.isEmpty())
        advanceTimeBy(1_000)
        val request = h.thinker.requests.single()
        assertEquals(ThinkTrigger.Pause, request.trigger)
        assertTrue(request.delegationIds.isEmpty())
        assertEquals("Un yaourt nature.", request.transcript.last().text)
    }

    @Test
    fun `filet de l app - ni sur un bonjour, ni sur une question de la voix sans reponse, ni deux fois`() = runTest {
        val h = started(FakeThinker(delayMs = 500, result = ThinkResult.Done()))
        h.transport.emit(ServerEvents.input("Salut."))
        advanceTimeBy(5_000)
        h.transport.emit(ServerEvents.input("Ce midi poulet riz."))
        advanceTimeBy(500)
        h.transport.emit(ServerEvents.output("Il y avait autre chose ?"))
        advanceTimeBy(6_000)
        assertTrue(h.thinker.requests.isEmpty())
        h.transport.emit(ServerEvents.input("Et des brocolis."))
        advanceTimeBy(4_000)
        assertEquals(1, h.thinker.requests.size)
        advanceTimeBy(10_000)
        assertEquals("parole déjà couverte : pas de deuxième réflexion", 1, h.thinker.requests.size)
    }

    @Test
    fun `repas complet - la reflexion note, la voix pose une question a la fois, puis relance avec les reponses`() = runTest {
        val q1 = "Ta pomme, elle était grosse comment ?"
        val q2 = "Ta salade, c'était laquelle ?"
        val h = started(FakeThinker(delayMs = 3_000, result = ThinkResult.Done(questions = listOf(q1, q2), noted = listOf(entry("Pomme"), entry("Salade")))))
        h.transport.emit(ServerEvents.input("Ce midi une pomme et une salade."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(4_000)
        // La voix n'a pas attendu : elle pose la première question seulement, avec la délégation.
        val first = commentaries(h).single { it.contains("item_1") }
        assertTrue(first.contains(q1))
        assertFalse(h.transport.sentText().contains(q2))

        h.transport.emit(ServerEvents.output(q1))
        advanceTimeBy(5_000)
        assertFalse("pas de deuxième question avant sa réponse", h.transport.sentText().contains(q2))
        h.transport.emit(ServerEvents.input("Moyenne."))
        advanceTimeBy(3_000)
        assertTrue(commentaries(h).last().contains(q2))
        assertEquals("pas de réflexion entre deux questions", 1, h.thinker.requests.size)

        h.transport.emit(ServerEvents.output(q2))
        advanceTimeBy(500)
        h.transport.emit(ServerEvents.input("La César."))
        advanceTimeBy(2_500)
        val again = h.thinker.requests.last()
        assertEquals(2, h.thinker.requests.size)
        assertEquals(ThinkTrigger.Answer, again.trigger)
        assertEquals(listOf(q1, q2), again.asked)
        assertEquals(listOf("Pomme", "Salade"), again.noted.map { it.label })
    }

    @Test
    fun `repas clair - au plus c est note, jamais de recap ni de chiffre`() = runTest {
        val h = started(FakeThinker(delayMs = 1_000, result = ThinkResult.Done(noted = listOf(entry("Salade thon")))))
        h.transport.emit(ServerEvents.input("À midi, ma salade habituelle."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_500)
        val reply = commentaries(h).single { it.contains("item_1") }
        assertTrue(reply.contains(CallInstructions.NOTED))
        assertFalse(Regex("\\d").containsMatchIn(reply.substringAfter("content")))
    }

    @Test
    fun `ajout pendant la reflexion - une seule a la fois, la suivante recoit ce qui est note`() = runTest {
        val thinker = FakeThinker(
            delayMs = 5_000,
            results = ArrayDeque(
                listOf(
                    ThinkResult.Done(questions = listOf("Ta pomme, grosse ou petite ?"), noted = listOf(entry("Pomme"))),
                    ThinkResult.Done(noted = listOf(entry("Yaourt"))),
                ),
            ),
        )
        val h = started(thinker)
        h.transport.emit(ServerEvents.input("Une pomme."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_000)
        h.transport.emit(ServerEvents.input(" Ah, et un yaourt."))
        h.transport.emit(ServerEvents.delegationCreated("item_2"))
        advanceTimeBy(500)
        h.transport.emit(ServerEvents.delegationCreated("item_3"))
        advanceTimeBy(2_000)
        assertEquals("la deuxième attend la fin de la première", 1, thinker.requests.size)
        assertTrue(h.controller.state.value.thinking)
        advanceTimeBy(1_500)
        assertEquals(2, thinker.requests.size)
        val second = thinker.requests[1]
        assertEquals(listOf("item_2", "item_3"), second.delegationIds)
        assertEquals(listOf("Pomme"), second.noted.map { it.label })
        assertEquals("Une pomme. Ah, et un yaourt.", second.transcript.last().text)
        assertEquals(1, thinker.maxRunning)
        advanceTimeBy(6_000)
        // Chaque délégation reçoit une réponse ; la dernière porte le contenu.
        assertTrue(h.transport.sent.any { it.contains("item_2") && it.contains(CallInstructions.THINKING_MERGED) })
        assertTrue(h.transport.sent.any { it.contains("item_3") && it.contains("session.thinking.append") && it.contains(CallInstructions.THINKING_WAIT) })
    }

    @Test
    fun `chiffre de la voix contredit - en fait non d abord, la question ensuite`() = runTest {
        val h = started(
            FakeThinker(delayMs = 1_000, result = ThinkResult.Done(say = "En fait non, c'est plutôt 80 kcal.", questions = listOf("Grosse ou petite ?"))),
        )
        h.transport.emit(ServerEvents.input("Une pomme, ça fait combien ?"))
        h.transport.emit(ServerEvents.output("Environ 200 calories."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_500)
        val replies = commentaries(h)
        assertTrue(replies.last().contains("En fait non, c'est plutôt 80 kcal."))
        assertFalse(h.transport.sentText().contains("Grosse ou petite ?"))
        advanceTimeBy(2_500)
        assertTrue(commentaries(h).last().contains("Grosse ou petite ?"))
    }

    @Test
    fun `reflexion lente - la voix continue, l appel finit avant, elle finit quand meme puis le rapport part`() = runTest {
        val h = started(FakeThinker(delayMs = 60_000, result = ThinkResult.Done(questions = listOf("Pesé cru ou cuit ?"), noted = listOf(entry("Poulet")))))
        h.transport.emit(ServerEvents.input("Ce midi, 200 grammes de poulet."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(20_000)
        assertFalse("la réflexion n'est pas un silence", h.transport.sentText().contains(CallInstructions.NUDGE))
        assertTrue(h.controller.state.value.thinking)
        h.controller.hangUp()
        advanceTimeBy(100)
        h.transport.emit(ServerEvents.closed("close_requested", 21.0))
        advanceTimeBy(100)
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertNull("le rapport attend la réflexion en cours", h.meals)
        advanceTimeBy(45_000)
        val report = h.meals!!
        assertEquals(listOf("Poulet"), report.noted.map { it.label })
        assertEquals(listOf("Pesé cru ou cuit ?"), report.openQuestions)
        assertTrue(report.unprocessed.isEmpty())
        // Rien n'a été dit à la voix après le raccroché.
        assertFalse(h.transport.sentText().contains("Pesé cru ou cuit ?"))
    }

    @Test
    fun `reflexion en echec - aucun chiffre invente, sa parole va au rapport`() = runTest {
        val h = started(FakeThinker(delayMs = 500, result = ThinkResult.Failed("NoInternet")))
        h.transport.emit(ServerEvents.input("Ce midi poulet riz."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(2_000)
        assertTrue(h.transport.sent.last().contains(CallInstructions.THINKING_FAILED))
        advanceTimeBy(10_000)
        assertEquals("pas de nouvel essai sans nouvelle parole", 1, h.thinker.requests.size)
        h.controller.abort(EndReason.User)
        val report = h.meals!!
        assertEquals(listOf("Ce midi poulet riz."), report.unprocessed)
        assertTrue(report.noted.isEmpty())
        assertFalse(report.settled)
    }

    @Test
    fun `delegation de la voix et relance de l app sur la meme reponse - une seule reflexion`() = runTest {
        val q = "Ta pomme, grosse ou petite ?"
        val thinker = FakeThinker(
            delayMs = 3_000,
            results = ArrayDeque(listOf(ThinkResult.Done(questions = listOf(q), noted = listOf(entry("Pomme"))), ThinkResult.Done(noted = listOf(entry("Pomme"))))),
        )
        val h = started(thinker)
        h.transport.emit(ServerEvents.input("Une pomme."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(4_000)
        h.transport.emit(ServerEvents.output(q))
        advanceTimeBy(1_000)
        h.transport.emit(ServerEvents.input("Une grosse."))
        h.transport.emit(ServerEvents.delegationCreated("item_2"))
        advanceTimeBy(10_000)
        assertEquals(2, thinker.requests.size)
        assertEquals(listOf("item_2"), thinker.requests[1].delegationIds)
        assertTrue(commentaries(h).last().contains("item_2") && commentaries(h).last().contains(CallInstructions.NOTED))
    }

    @Test
    fun `reflexion coupee par le delai - ce qu elle a deja note est garde`() = runTest {
        val thinker = CallThinker { request ->
            request.onNoted(listOf(entry("Pomme")))
            delay(500_000)
            ThinkResult.Done()
        }
        val transport = FakeLiveTransport()
        var meals: CallMealReport? = null
        val controller = CallController(
            scope = backgroundScope,
            now = { testScheduler.currentTime },
            thinker = thinker,
            onMeals = { meals = it },
        )
        controller.start(transport, key, SessionRequest.session(CallInstructions.VOICE, emptyList()), CallReflection.Medium)
        runCurrent()
        transport.emit(ServerEvents.started())
        transport.emit(ServerEvents.input("Ce midi une pomme."))
        transport.emit(ServerEvents.delegationCreated("item_1"))
        // Réflexion coupée à 120 s ; l'appel, lui, raccroche au silence (la réflexion abandonnée ne compte plus après 90 s).
        advanceTimeBy(125_000)
        controller.abort(EndReason.User)
        val report = meals!!
        assertEquals(listOf("Pomme"), report.noted.map { it.label })
        assertEquals(listOf("Ce midi une pomme."), report.unprocessed)
    }

    @Test
    fun `appel fini avant que la reflexion parte - la parole va au rapport`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Ce soir des pâtes au thon."))
        h.transport.emit(ServerEvents.delegationCreated("item_1"))
        advanceTimeBy(100)
        h.controller.abort(EndReason.ClosedApp)
        advanceTimeBy(5_000)
        assertTrue(h.thinker.requests.isEmpty())
        assertEquals(listOf("Ce soir des pâtes au thon."), h.meals!!.unprocessed)
    }

    @Test
    fun `sans reflexion, rien n est jamais note - la voix n a aucun outil`() = runTest {
        val session = SessionRequest.session(CallInstructions.VOICE, emptyList())
        assertNull(session["tools"])
        assertEquals("client", (session["delegation"] as kotlinx.serialization.json.JsonObject)["type"].toString().trim('"'))
        val h = started(FakeThinker(delayMs = 100, result = ThinkResult.Failed("aucune réflexion")))
        h.transport.emit(ServerEvents.input("Une pomme."))
        h.transport.emit(ServerEvents.output("C'est noté !"))
        advanceTimeBy(5_000)
        h.controller.abort(EndReason.User)
        assertTrue(h.meals!!.noted.isEmpty())
    }

    @Test
    fun `erreur de session - l appel se ferme avec un message clair, plus rien de facture`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.input("Ce midi une pomme."))
        advanceTimeBy(500)
        // Erreur qui ne vise aucune commande de l'app : rien dans la doc ne dit que la session repart.
        h.transport.emit(ServerEvents.error("server_error", "The server had an error."))
        // La voix et le micro continuent de bouger : ils ne doivent pas garder l'appel ouvert.
        h.transport.levels.value = AudioLevels(mic = 0.02f, ai = 0.3f)
        advanceTimeBy(200)
        assertTrue("session.close envoyé tout de suite", h.transport.sentTypes().contains("session.close"))
        h.transport.emit(ServerEvents.closed("close_requested", 12.0))
        advanceTimeBy(200)
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertEquals("erreur-session", h.summary!!.endReason.code)
        val status = h.controller.state.value.status!!
        assertTrue(status, status.contains("erreur") && status.contains("server_error"))
        assertEquals(12_000L, h.summary!!.billedMs)
    }

    @Test
    fun `erreur de session sans reponse du serveur - fermee quand meme apres 5 s`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.error(null, "Something went wrong."))
        advanceTimeBy(6_000)
        assertEquals(CallPhase.Ended, h.controller.state.value.phase)
        assertTrue(h.transport.closed)
    }

    @Test
    fun `commande de l app refusee - la doc la rattache a la commande, l appel continue`() = runTest {
        val h = started()
        h.transport.emit(ServerEvents.error("invalid_value", "Unknown delegation.", clientEventId = "evt_abc"))
        advanceTimeBy(2_000)
        assertEquals(CallPhase.Live, h.controller.state.value.phase)
        assertFalse(h.transport.sentTypes().contains("session.close"))
    }

    @Test
    fun `erreurs pendant la fermeture - attendues, la fin reste celle demandee`() = runTest {
        val h = started()
        h.controller.hangUp()
        advanceTimeBy(100)
        // Doc : « Closing the session returns errors for appends that are still pending. »
        h.transport.emit(ServerEvents.error("session_closing", "Session is closing."))
        h.transport.emit(ServerEvents.closed("close_requested", 5.0))
        advanceTimeBy(200)
        assertEquals(EndReason.User, h.summary!!.endReason)
    }
}
