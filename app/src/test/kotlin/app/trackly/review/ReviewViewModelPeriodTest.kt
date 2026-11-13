package app.trackly.review

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.openai.AssistantError
import app.trackly.core.review.WeekRange
import app.trackly.testing.ModelReplies
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Un rapport par envoi même si la suite échoue, et une seule période par envoi quand 5 h passe pendant que
 * la conversation du bilan est ouverte. Modèle simulé, aucun appel réseau.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelPeriodTest {
    @Before
    fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private class MovingClock(private val base: Clock) : Clock() {
        var offset: Duration = Duration.ZERO
        override fun getZone(): ZoneId = base.zone
        override fun withZone(zone: ZoneId): Clock = base.withZone(zone)
        override fun instant(): Instant = base.instant().plus(offset)
    }

    private fun day(iso: String) = LocalDate.parse(iso)

    private class Setup(
        val clock: MovingClock,
        val services: ReviewServices,
        val transport: ScriptedTransport,
        val reports: ReportRepository,
    ) {
        fun open(): ReviewViewModel = runBlocking { ReviewViewModel(services, services.periods.at(services.engine.now())) }
        fun thread(start: String): List<ChatMessage> = runBlocking { services.history.last(LocalDate.parse(start), 100) }
    }

    private fun setup(transport: ScriptedTransport, history: ReviewHistory = InMemoryReviewHistory()) = runBlocking {
        val clock = MovingClock(ReviewSample.clock()) // dimanche 1er novembre, 21 h 30
        val engine = ReviewSample.engine(clock)
        clock.offset = Duration.ofMinutes(1)
        val reports = InMemoryReportRepository()
        Setup(clock, ReviewServices.inMemory(engine, RecordingReviewNotifier(), transport, history = history, reports = reports), transport, reports)
    }

    private val produce = ModelReplies.call("produce_weekly_report", """{"analysis":"Une semaine régulière, continue comme ça."}""")

    // ───── Rapport enregistré, puis échec ─────

    @Test
    fun `rapport enregistre puis la requete suivante echoue - pas de Reessayer, un seul rapport`() = runBlocking<Unit> {
        val s = setup(ScriptedTransport(produce, ScriptedTransport.Fail(AssistantError.NoInternet)))
        val model = s.open()
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        model.produceReport()

        val error = model.state.value.error!!
        assertNull("rejouer referait le rapport", error.retryText)
        model.retry()
        assertEquals(1, s.reports.observeAll().first().size)
        assertEquals(2, s.transport.requests.size)
        collector.cancel()
    }

    @Test
    fun `rapport enregistre puis une exception - pas de Reessayer, un seul rapport`() = runBlocking<Unit> {
        // Le fil refuse d'enregistrer la réponse du modèle (base pleine…) après le rapport.
        val inner = InMemoryReviewHistory()
        val failing = object : ReviewHistory by inner {
            override suspend fun append(week: LocalDate, at: Instant, role: ChatRole, text: String, cards: List<ChatCard>): Long =
                if (role == ChatRole.Assistant) error("disque plein") else inner.append(week, at, role, text, cards)
            override fun observe(week: LocalDate, limit: Int): Flow<List<ChatMessage>> = inner.observe(week, limit)
        }
        val s = setup(ScriptedTransport(produce, ModelReplies.text("Ton bilan est prêt.")), failing)
        val model = s.open()
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }

        model.produceReport()

        val error = model.state.value.error!!
        assertNull("rejouer referait le rapport", error.retryText)
        model.retry()
        assertEquals(1, s.reports.observeAll().first().size)
        collector.cancel()
    }

    // ───── 5 h passe pendant le bilan ─────

    @Test
    fun `5 h passe ecran ouvert apres un rapport - rien n est envoye dans l ancien fil, le texte attend dans le nouveau`() = runBlocking<Unit> {
        val weigh = ModelReplies.call(
            "record_week_info",
            """{"weigh_ins":[{"date":"2026-11-02","kg":72.0}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}""",
        )
        val s = setup(ScriptedTransport(weigh, ModelReplies.text("Noté.")))
        // Rapport produit le dimanche 1er : la période suivante commence le lundi 2.
        s.reports.save(day("2026-10-26"), s.services.engine.now(), "{}", day("2026-11-01"))
        s.clock.offset = Duration.ofHours(7).plusMinutes(29) // lundi 2, 4 h 59 : encore le jour de suivi du 1er
        val model = s.open()
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-01")), model.state.value.period)

        s.clock.offset = Duration.ofHours(7).plusMinutes(31) // 5 h 01
        val capture = app.trackly.photos.PreparedPhoto("c1", ReviewSample.screenshot, null, 3)
        model.attach(listOf(capture))
        model.send("Ce matin 72 kg")

        assertTrue("rien ne part au modèle", s.transport.requests.isEmpty())
        assertTrue("l'ancien fil ne reçoit rien", s.thread("2026-10-26").isEmpty())
        assertEquals(WeekRange(day("2026-11-02"), day("2026-11-02")), model.state.value.period)
        assertEquals("Ce matin 72 kg", model.draft.value)
        assertEquals("la capture attend aussi", listOf(capture), model.state.value.photos)
        assertNotNull(model.state.value.notice)

        model.removeAttachments()
        model.send()

        assertEquals(1, s.thread("2026-11-02").count { it.role == ChatRole.User })
        assertTrue(s.thread("2026-10-26").isEmpty())
        assertEquals(72.0, s.services.engine.weekInfo(day("2026-11-02"))!!.weighIns.single().kg, 0.0)
        assertNull(s.services.engine.weekInfo(day("2026-10-26")))
        collector.cancel()
    }

    /** Un autre écran valide son rapport (lancé à 4 h 59, fini le 1er) pendant ce tour, envoyé à 5 h 01. */
    private class Concurrent(val step: ScriptedTransport.Step) {
        val clock = MovingClock(ReviewSample.clock())
        val reports = InMemoryReportRepository()
        val scripted = ScriptedTransport(step, ModelReplies.text("D'accord."))
        lateinit var services: ReviewServices
        lateinit var model: ReviewViewModel

        fun open() = runBlocking {
            val engine = ReviewSample.engine(clock)
            var committed = false
            val transport = app.trackly.assistant.openai.ResponsesTransport { body ->
                if (!committed) {
                    committed = true
                    reports.save(LocalDate.parse("2026-10-26"), engine.now().minusSeconds(120), "{}", LocalDate.parse("2026-11-01"))
                }
                scripted.send(body)
            }
            services = ReviewServices.inMemory(engine, RecordingReviewNotifier(), transport, reports = reports)
            clock.offset = Duration.ofHours(7).plusMinutes(31) // lundi 2, 5 h 01
            model = ReviewViewModel(services, services.periods.at(engine.now()))
        }

        fun toolOutput(): String = scripted.requests[1].toString()
    }

    @Test
    fun `rapport d un autre ecran valide pendant le tour - rien n est note sous l ancien debut, l utilisateur est prevenu`() = runBlocking<Unit> {
        val c = Concurrent(
            ModelReplies.call(
                "record_week_info",
                """{"weigh_ins":[{"date":"2026-11-02","kg":72.0}],"steps_by_day":[{"date":"2026-11-02","steps":9000}],"steps_average":null,"sessions":[{"type":"musculation","count":1}],"remove_weigh_in_dates":null,"remove_steps_dates":null}""",
            ),
        )
        c.open()
        val collector = launch(Dispatchers.Unconfined) { c.model.state.collect { } }
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), c.model.state.value.period)

        c.model.send("Ce matin 72 kg, 9 000 pas et une séance de muscu")

        // Le message reste dans le fil où il a été envoyé ; rien n'est noté, ni sous l'ancien début ni sous le nouveau.
        assertEquals(1, c.services.history.last(day("2026-10-26"), 10).count { it.role == ChatRole.User })
        assertNull(c.services.engine.weekInfo(day("2026-10-26")))
        assertNull(c.services.engine.weekInfo(day("2026-11-02")))
        assertTrue(c.toolOutput(), c.toolOutput().contains("une nouvelle période a commencé"))
        // Le rapport de l'autre écran n'est pas celui de cet envoi : rien à rejouer, et rien n'est retiré à tort.
        assertNull(c.model.state.value.error)
        collector.cancel()
    }

    @Test
    fun `produire pendant qu un autre ecran valide son rapport - pas de second rapport qui chevauche`() = runBlocking<Unit> {
        val c = Concurrent(produce)
        c.open()
        val collector = launch(Dispatchers.Unconfined) { c.model.state.collect { } }

        c.model.produceReport()

        assertEquals(1, c.reports.observeAll().first().size)
        assertTrue(c.toolOutput(), c.toolOutput().contains("une nouvelle période a commencé"))
        collector.cancel()
    }

    @Test
    fun `le rapport d un autre ecran valide pendant un tour qui echoue - Reessayer reste`() = runBlocking<Unit> {
        val c = Concurrent(ScriptedTransport.Fail(AssistantError.NoInternet))
        c.open()
        val collector = launch(Dispatchers.Unconfined) { c.model.state.collect { } }

        c.model.send("Salut")

        assertEquals("Salut", c.model.state.value.error!!.retryText)
        collector.cancel()
    }

    @Test
    fun `5 h passe ecran ouvert sans changer de debut - le message part, la periode affichee s allonge`() = runBlocking<Unit> {
        val s = setup(ScriptedTransport(ModelReplies.text("Bonjour !")))
        val model = s.open()
        val collector = launch(Dispatchers.Unconfined) { model.state.collect { } }
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-01")), model.state.value.period)

        s.clock.offset = Duration.ofHours(7).plusMinutes(31) // lundi 2, 5 h 01 : même premier jour, un jour de plus
        model.send("Salut")

        assertEquals(1, s.transport.requests.size)
        assertEquals(1, s.thread("2026-10-26").count { it.role == ChatRole.User })
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-02")), model.state.value.period)
        assertFalse(model.state.value.thinking)
        collector.cancel()
    }
}
