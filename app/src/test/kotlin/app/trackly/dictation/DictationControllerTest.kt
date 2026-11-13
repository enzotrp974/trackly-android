package app.trackly.dictation

import app.trackly.assistant.openai.AssistantError
import app.trackly.core.model.TermCorrection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Parcours de dictée de bout en bout, sans micro ni réseau : micro factice, serveurs factices.
 *
 * Le contrôleur tourne sur un dispatcher de test (horloge virtuelle : les délais d'attente du texte final ne dépendent plus
 * de la charge de la machine). Seule la capture du micro, qui bloque comme un vrai micro, garde un thread réel. Les attentes
 * sont des conditions ([waitUntil]), jamais des pauses : le test avance l'horloge de test tant que la condition n'est pas vraie,
 * et ne tombe en échec qu'après un garde-fou très large.
 */
class DictationControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val settings = InMemoryDictationSettings()
    private val permission = FixedMicPermission()
    private val live = FakeLive().apply {
        script = { c ->
            c.emit(LiveEvent.Committed("item_1"))
            c.emit(LiveEvent.Completed("item_1", "Comme snack j’ai pris une barre PRO", seconds = null))
        }
    }
    private val file = FakeFile().apply { result = { FileTranscription("Une barre PRO et une pomme", seconds = null) } }
    private val usage = RecordingUsage()
    private val now = Instant.parse("2026-11-03T08:15:00Z")
    private var source = FakeAudioSource().speak(2.0)
    private var mic = FakeMic(source)

    @After
    fun stop() {
        scope.cancel()
    }

    private val nutrio = listOf(TermCorrection("tc-1", "nutri", "Nutrio"))

    private fun controller(
        maxDurationMs: Long = DictationController.MAX_DURATION_MS,
        finalTimeoutMs: Long = 3_000,
        corrections: List<TermCorrection> = emptyList(),
    ) = DictationController(
        services = DictationServices(
            settings = settings,
            permission = permission,
            audio = mic,
            live = live,
            file = file,
            keywords = { listOf("PRO", "Barre protéinée PRO chocolat") },
            corrections = { corrections },
            usage = usage,
            clock = { now },
        ),
        scope = scope,
        io = Dispatchers.Default,
        maxDurationMs = maxDurationMs,
        finalTimeoutMs = finalTimeoutMs,
    )

    /**
     * Fait avancer le dispatcher de test jusqu'à ce que [probe] rende une valeur. Le thread de capture, seul réel, rend la
     * main par ses mises à jour d'état ; [GUARD_MS] n'est qu'un garde-fou contre un test bloqué, jamais une durée attendue.
     */
    private fun <T : Any> waitUntil(probe: () -> T?): T {
        val deadline = System.nanoTime() + GUARD_MS * 1_000_000
        while (true) {
            scheduler.advanceUntilIdle()
            probe()?.let { return it }
            check(System.nanoTime() < deadline) { "Condition jamais atteinte" }
            Thread.yield()
        }
    }

    private fun DictationController.until(predicate: (DictationUiState) -> Boolean): DictationUiState =
        waitUntil { state.value.takeIf(predicate) }

    private fun DictationController.result(): DictationResult {
        val received = CompletableDeferred<DictationResult>()
        scope.launch { received.complete(results.first()) }
        return waitUntil { if (received.isCompleted) received.getCompleted() else null }
    }

    /** Attend que les 2 s de parole aient été lues (le niveau/durée remontent dans l'état). */
    private fun DictationController.heard(ms: Long) = until { it.elapsedMs >= ms }

    // ───── Mode en direct ─────

    @Test
    fun `direct - arret et envoi - le texte final est envoye, l audio part avant le commit`() {
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening && !it.connecting }
        c.heard(2_000)

        c.stopAndSend()
        val result = c.result()

        assertEquals(DictationResult("Comme snack j’ai pris une barre PRO", send = true), result)
        val conn = live.connection!!
        // 2 s de PCM 24 kHz 16 bits = 96 000 octets, en morceaux de 200 ms (9 600 octets), puis le commit.
        assertEquals(96_000, conn.audioBytes)
        assertTrue(conn.log.dropLast(1).all { it == "audio:9600" })
        assertEquals("commit", conn.log.last())
        assertTrue(conn.closed)
        assertTrue(source.closed)
        assertEquals(DictationPhase.Idle, c.state.value.phase)
    }

    @Test
    fun `direct - la session est configuree avec les mots-cles et le francais`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        val config = live.configs.single()
        assertEquals("gpt-live-transcribe", config.model)
        assertEquals(listOf("fr"), config.languages)
        assertEquals(listOf("PRO", "Barre protéinée PRO chocolat"), config.keywords)
        c.cancel()
    }

    @Test
    fun `direct - les deltas s affichent pendant la parole`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        val conn = live.connection!!
        conn.emit(LiveEvent.Delta("item_1", "Comme snack "))
        assertEquals("Comme snack", c.until { it.text.isNotEmpty() }.text)
        conn.emit(LiveEvent.Delta("item_1", "j’ai pris une barre"))
        assertEquals("Comme snack j’ai pris une barre", c.until { it.text.endsWith("barre") }.text)
        c.cancel()
    }

    @Test
    fun `direct - relire d abord - le texte va dans la zone de saisie, pas au chat`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndEdit()
        assertFalse(c.result().send)
    }

    @Test
    fun `direct - le micro s ouvre tout de suite, l audio capte pendant la connexion est envoye ensuite (rien de perdu)`() {
        val gate = CompletableDeferred<Unit>()
        live.gate = gate
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening && it.connecting }
        c.heard(2_000)
        assertNull(live.connection)
        gate.complete(Unit)
        c.until { !it.connecting }
        c.stopAndSend()
        c.result()
        assertEquals(96_000, live.connection!!.audioBytes)
    }

    @Test
    fun `direct - arret pendant la connexion - on attend la connexion puis le texte final`() {
        val gate = CompletableDeferred<Unit>()
        live.gate = gate
        val c = controller()
        c.onMicTapped()
        c.until { it.connecting }
        c.heard(2_000)
        c.stopAndSend()
        c.until { it.phase == DictationPhase.Finishing }
        gate.complete(Unit)
        assertTrue(c.result().send)
    }

    @Test
    fun `direct - cout enregistre avec la duree facturee par l API quand elle existe`() {
        live.script = { c ->
            c.emit(LiveEvent.Completed("item_1", "Bonjour", seconds = 1.5))
        }
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndSend()
        c.result()
        assertEquals(listOf(Triple("gpt-live-transcribe", 1_500L, now)), usage.records.toList())
    }

    @Test
    fun `direct - cout enregistre avec la duree mesuree quand l API donne des jetons`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndSend()
        c.result()
        assertEquals(listOf(Triple("gpt-live-transcribe", 2_000L, now)), usage.records.toList())
    }

    @Test
    fun `direct - texte final qui n arrive pas - le texte vu est garde a relire, le cout est compte`() {
        live.script = { c -> c.emit(LiveEvent.Delta("item_1", "une barre PRO")) }
        val c = controller(finalTimeoutMs = 300)
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndSend()
        val result = c.result()
        assertEquals("une barre PRO", result.text)
        assertFalse("un texte non confirmé n'est jamais envoyé tout seul", result.send)
        assertEquals(DictationController.UNCONFIRMED_NOTICE, result.notice)
        assertEquals(1, usage.records.size)
    }

    @Test
    fun `direct - texte final qui n arrive pas et rien a garder - erreur de delai, enregistrement garde`() {
        live.script = {}
        val c = controller(finalTimeoutMs = 300)
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndSend()
        val state = c.until { it.phase == DictationPhase.Failed }
        assertEquals(DictationFailure.Api(AssistantError.Timeout), state.failure)
        assertTrue(state.canRetry)
    }

    @Test
    fun `direct - rien entendu (texte final vide) - message dedie, aucun envoi`() {
        live.script = { c -> c.emit(LiveEvent.Completed("item_1", "  ", seconds = null)) }
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndSend()
        val state = c.until { it.phase == DictationPhase.Failed }
        assertEquals(DictationFailure.NothingHeard, state.failure)
        assertFalse(state.canRetry)
    }

    @Test
    fun `appui accidentel (moins de 0,3 s) - rien n est envoye, aucun cout`() {
        source = FakeAudioSource().speak(0.1)
        mic = FakeMic(source)
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(100)
        c.stopAndSend()
        assertEquals(DictationFailure.NothingHeard, c.until { it.phase == DictationPhase.Failed }.failure)
        assertTrue(usage.records.isEmpty())
        assertNull(live.connection!!.log.firstOrNull { it == "commit" })
    }

    // ───── Erreurs : jamais de perte silencieuse ─────

    @Test
    fun `direct - cle refusee a la connexion - message de cle, enregistrement non rejouable`() {
        live.error = AssistantError.KeyRejected
        val c = controller()
        c.onMicTapped()
        val state = c.until { it.phase == DictationPhase.Failed }
        assertEquals(DictationFailure.Api(AssistantError.KeyRejected), state.failure)
        assertTrue(state.failure!!.notice().opensKeySettings)
        assertFalse("une clé refusée ne se réessaie pas", state.canRetry)
        assertTrue("le micro est coupé", source.closed || source.stopped)
    }

    @Test
    fun `direct - credit epuise - message distinct, meme texte que le chat`() {
        live.error = AssistantError.CreditExhausted
        val c = controller()
        c.onMicTapped()
        val failure = c.until { it.phase == DictationPhase.Failed }.failure!!
        assertEquals("Crédit OpenAI épuisé", failure.notice().title)
    }

    @Test
    fun `direct - pas d Internet - reessayer transcrit l enregistrement garde (mode fichier), texte dans la saisie`() {
        // La connexion échoue après que les 2 s de parole ont été captées : rien n'est perdu.
        val gate = CompletableDeferred<Unit>()
        live.gate = gate
        live.error = AssistantError.NoInternet
        val c = controller()
        c.onMicTapped()
        c.heard(2_000)
        gate.complete(Unit)
        val failed = c.until { it.phase == DictationPhase.Failed }
        assertEquals("Pas de connexion Internet", failed.failure!!.notice().title)
        assertTrue(failed.canRetry)

        c.retry()
        val result = c.result()

        assertEquals("Une barre PRO et une pomme", result.text)
        assertFalse(result.send)
        val (wav, config) = file.calls.single()
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + 96_000, wav.size)
        assertEquals(listOf("PRO", "Barre protéinée PRO chocolat"), config.keywords)
        assertEquals("gpt-transcribe", usage.records.single().first)
        assertEquals(DictationPhase.Idle, c.state.value.phase)
    }

    @Test
    fun `direct - coupure en cours de dictee - le texte partiel reste recuperable`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        // L'enregistrement n'est « récupérable » que s'il a été capté : on attend les 2 s de parole avant la coupure.
        c.heard(2_000)
        val conn = live.connection!!
        conn.emit(LiveEvent.Delta("item_1", "Ce matin j’ai pris deux œufs"))
        c.until { it.text.isNotEmpty() }
        conn.emit(LiveEvent.Failed(AssistantError.NoInternet))

        val failed = c.until { it.phase == DictationPhase.Failed }
        assertEquals("Ce matin j’ai pris deux œufs", failed.text)
        assertTrue(failed.canRetry)

        c.keepText()
        val result = c.result()
        assertEquals("Ce matin j’ai pris deux œufs", result.text)
        assertFalse(result.send)
        assertEquals(DictationPhase.Idle, c.state.value.phase)
    }

    @Test
    fun `reessayer - le texte partiel est conserve si la nouvelle tentative echoue aussi`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        live.connection!!.apply {
            emit(LiveEvent.Delta("item_1", "deux œufs"))
        }
        c.until { it.text == "deux œufs" }
        live.connection!!.emit(LiveEvent.Failed(AssistantError.NoInternet))
        c.until { it.phase == DictationPhase.Failed }

        file.error = AssistantError.NoInternet
        c.retry()
        val again = c.until { it.phase == DictationPhase.Failed && it.failure != null && it.text.isNotEmpty() }
        assertEquals("deux œufs", again.text)
        assertTrue(again.canRetry)
    }

    @Test
    fun `fermer apres une erreur - l enregistrement garde est jete`() {
        live.error = AssistantError.NoInternet
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Failed }
        c.dismiss()
        assertEquals(DictationPhase.Idle, c.state.value.phase)
        c.retry() // plus rien à rejouer
        assertTrue(file.calls.isEmpty())
    }

    @Test
    fun `micro indisponible - message dedie`() {
        mic = FakeMic(null)
        val c = controller()
        c.onMicTapped()
        val state = c.until { it.phase == DictationPhase.Failed }
        assertEquals(DictationFailure.MicUnavailable, state.failure)
        assertEquals("Micro indisponible", state.failure!!.notice().title)
    }

    // ───── Annulation, arrière-plan, durée maximale ─────

    @Test
    fun `annuler - tout est jete, rien n est envoye ni compte`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.cancel()
        assertEquals(DictationPhase.Idle, c.state.value.phase)
        waitUntil { true.takeIf { source.closed && live.connection!!.closed } }
        assertTrue(usage.records.isEmpty())
        val received = CompletableDeferred<DictationResult>()
        scope.launch { received.complete(c.results.first()) }
        scheduler.advanceUntilIdle()
        assertFalse("rien n'est envoyé après « Annuler »", received.isCompleted)
    }

    @Test
    fun `l app passe en arriere-plan pendant la dictee - elle se termine, le texte va dans la saisie`() {
        val c = controller()
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.onAppStopped()
        val result = c.result()
        assertFalse(result.send)
        assertEquals("Comme snack j’ai pris une barre PRO", result.text)
    }

    @Test
    fun `duree maximale - arret automatique, jamais d envoi tout seul`() {
        source = FakeAudioSource().speak(3.0)
        mic = FakeMic(source)
        val c = controller(maxDurationMs = 1_000)
        c.onMicTapped()
        val result = c.result()
        assertFalse(result.send)
        assertTrue(live.connection!!.audioBytes in 48_000..60_000)
    }

    // ───── Mots corrigés ─────

    @Test
    fun `mots corriges - en direct, le texte final est corrige avant d arriver au chat`() {
        live.script = { conn ->
            conn.emit(LiveEvent.Committed("item_1"))
            conn.emit(LiveEvent.Completed("item_1", "Comme snack j’ai pris une barre nutri", seconds = null))
        }
        val c = controller(corrections = nutrio)
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening && !it.connecting }
        c.heard(2_000)

        c.stopAndSend()

        assertEquals(DictationResult("Comme snack j’ai pris une barre Nutrio", send = true), c.result())
    }

    @Test
    fun `mots corriges - apres l arret, mot entier seulement`() {
        settings.setMode(DictationMode.AfterStop)
        file.result = { FileTranscription("Un nutri de nutritif, le multinutri et une barre Nutri", seconds = 2.0) }
        val c = controller(corrections = nutrio)
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        c.heard(2_000)

        c.stopAndEdit()

        assertEquals(DictationResult("Un Nutrio de nutritif, le multinutri et une barre Nutrio", send = false), c.result())
    }

    @Test
    fun `mots corriges - le texte partiel garde est corrige aussi`() {
        val c = controller(corrections = nutrio)
        c.onMicTapped()
        c.until { !it.connecting && it.phase == DictationPhase.Listening }
        c.heard(2_000)
        val conn = live.connection!!
        conn.emit(LiveEvent.Delta("item_1", "une barre nutri"))
        c.until { it.text.isNotEmpty() }
        conn.emit(LiveEvent.Failed(AssistantError.NoInternet))
        c.until { it.phase == DictationPhase.Failed }

        c.keepText()

        assertEquals("une barre Nutrio", c.result().text)
    }

    @Test
    fun `mots corriges - sans correction, le texte est rendu tel quel`() {
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening && !it.connecting }
        c.heard(2_000)
        c.stopAndSend()
        assertEquals("Comme snack j’ai pris une barre PRO", c.result().text)
    }

    // ───── Mode après l'arrêt ─────

    @Test
    fun `apres l arret - un seul fichier WAV est envoye, le texte arrive dans la saisie, cout en duree API`() {
        settings.setMode(DictationMode.AfterStop)
        file.result = { FileTranscription("Une barre PRO et une pomme", seconds = 2.0) }
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        assertEquals(DictationMode.AfterStop, c.state.value.mode)
        assertTrue("pas de WebSocket dans ce mode", live.configs.isEmpty())
        c.heard(2_000)

        c.stopAndEdit()
        val result = c.result()

        assertEquals(DictationResult("Une barre PRO et une pomme", send = false), result)
        assertEquals(44 + 96_000, file.calls.single().first.size)
        assertEquals(listOf(Triple("gpt-transcribe", 2_000L, now)), usage.records.toList())
        assertTrue(live.configs.isEmpty())
    }

    @Test
    fun `apres l arret - sans duree dans la reponse, la duree mesuree est comptee`() {
        settings.setMode(DictationMode.AfterStop)
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndEdit()
        c.result()
        assertEquals(listOf(Triple("gpt-transcribe", 2_000L, now)), usage.records.toList())
    }

    @Test
    fun `apres l arret - 24 kHz exige - un micro a 48 kHz est ramene a 24 kHz (duree conservee)`() {
        settings.setMode(DictationMode.AfterStop)
        source = FakeAudioSource(sampleRate = 48_000).speak(2.0)
        mic = FakeMic(source)
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        c.heard(1_900)
        c.stopAndEdit()
        c.result()
        val wavSize = file.calls.single().first.size
        assertEquals(96_000, wavSize - 44)
    }

    @Test
    fun `apres l arret - erreur d Internet - l enregistrement est garde pour reessayer`() {
        settings.setMode(DictationMode.AfterStop)
        file.error = AssistantError.NoInternet
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndEdit()
        val failed = c.until { it.phase == DictationPhase.Failed }
        assertTrue(failed.canRetry)
        assertTrue(usage.records.isEmpty())

        file.error = null
        c.retry()
        assertEquals("Une barre PRO et une pomme", c.result().text)
        assertEquals(2, file.calls.size)
    }

    @Test
    fun `apres l arret - reponse vide - rien entendu`() {
        settings.setMode(DictationMode.AfterStop)
        file.result = { FileTranscription("", null) }
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        c.heard(2_000)
        c.stopAndEdit()
        assertEquals(DictationFailure.NothingHeard, c.until { it.phase == DictationPhase.Failed }.failure)
    }

    @Test
    fun `le mode est lu au debut de chaque dictee`() {
        val c = controller()
        c.onMicTapped()
        c.until { it.phase == DictationPhase.Listening }
        assertEquals(DictationMode.Live, c.state.value.mode)
        c.cancel()
        settings.setMode(DictationMode.AfterStop)
        source = FakeAudioSource().speak(1.0)
        mic = FakeMic(source)
        val d = controller()
        d.onMicTapped()
        assertEquals(DictationMode.AfterStop, d.until { it.phase == DictationPhase.Listening }.mode)
        d.cancel()
    }

    // ───── Permission du micro ─────

    @Test
    fun `premiere utilisation - explication d abord, le micro ne s ouvre pas`() {
        permission.granted = false
        val c = controller()
        c.onMicTapped()
        assertEquals(DictationPhase.Permission, c.state.value.phase)
        assertEquals(PermissionPrompt.Explain, c.state.value.permission)
        assertEquals(0, mic.opened)
    }

    @Test
    fun `autorisation accordee - la dictee demarre`() {
        permission.granted = false
        val c = controller()
        c.onMicTapped()
        permission.granted = true
        c.onPermissionResult(true)
        assertNotNull(c.until { it.phase == DictationPhase.Listening })
        assertTrue(settings.micRequested)
        c.cancel()
    }

    @Test
    fun `refus simple - message qui propose de reessayer`() {
        permission.granted = false
        permission.rationale = true
        val c = controller()
        c.onMicTapped()
        c.onPermissionResult(false)
        assertEquals(PermissionPrompt.Denied, c.state.value.permission)
        assertEquals(0, mic.opened)
    }

    @Test
    fun `refus definitif - chemin vers les parametres de l app`() {
        permission.granted = false
        permission.rationale = false
        val c = controller()
        c.onMicTapped()
        c.onPermissionResult(false)
        assertEquals(PermissionPrompt.Blocked, c.state.value.permission)
    }

    @Test
    fun `apres un refus definitif, un nouvel appui montre directement le chemin vers les parametres`() {
        settings.micRequested = true
        permission.granted = false
        permission.rationale = false
        val c = controller()
        c.onMicTapped()
        assertEquals(PermissionPrompt.Blocked, c.state.value.permission)
    }

    @Test
    fun `autorise depuis les parametres - un nouvel appui demarre la dictee`() {
        settings.micRequested = true
        permission.granted = false
        val c = controller()
        c.onMicTapped()
        assertEquals(PermissionPrompt.Blocked, c.state.value.permission)
        permission.granted = true
        c.onMicTapped()
        assertEquals(DictationPhase.Listening, c.until { it.phase == DictationPhase.Listening }.phase)
        c.cancel()
    }

    @Test
    fun `plus tard - le message se ferme`() {
        permission.granted = false
        val c = controller()
        c.onMicTapped()
        c.dismiss()
        assertEquals(DictationPhase.Idle, c.state.value.phase)
    }

    private companion object {
        /** Garde-fou d'un test bloqué (pas une attente normale). */
        const val GUARD_MS = 60_000L
    }
}
