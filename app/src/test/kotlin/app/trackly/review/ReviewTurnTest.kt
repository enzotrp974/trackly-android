package app.trackly.review

import app.trackly.review.page.ReportContent
import app.trackly.assistant.cards.ChatCards
import app.trackly.assistant.cards.ReportReadyCard
import app.trackly.assistant.cards.WeekInfoCard
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.review.WeekRange
import app.trackly.testing.ModelReplies
import app.trackly.testing.ScriptedTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tours de la conversation de bilan avec des réponses de modèle simulées (aucun réseau) :
 * déclaration des pesées → enregistrées par le moteur ; aucun poids → le rapport le signale.
 */
class ReviewTurnTest {
    private val week = ReviewSample.week
    private fun day(iso: String) = LocalDate.parse(iso)

    private class Setup(val engine: TracklyEngine, val transport: ScriptedTransport) {
        val notifier = RecordingReviewNotifier()
        val services = ReviewServices.inMemory(engine, notifier, transport)
        suspend fun run(text: String, images: List<app.trackly.assistant.openai.ImageInput> = emptyList()) =
            services.interpreter.run(TurnInput(text, images = images)) as TurnOutcome.Completed
    }

    private fun setup(vararg steps: ScriptedTransport.Step): Setup = runBlocking {
        val transport = ScriptedTransport(*steps)
        val engine = ReviewSample.engine()
        Setup(engine, transport)
    }

    private fun toolOutput(transport: ScriptedTransport, request: Int): JsonObject {
        val output = transport.requests[request]["input"]!!.jsonArray.last().jsonObject
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        return Json.parseToJsonElement(output["output"]!!.jsonPrimitive.content).jsonObject
    }

    private val declareArgs = """
        {"weigh_ins":[{"date":"2026-10-27","kg":72.4},{"date":"2026-10-30","kg":72.0}],
         "steps_by_day":null,"steps_average":18500,
         "sessions":[{"type":"musculation","count":3},{"type":"course","count":1}],
         "remove_weigh_in_dates":null,"remove_steps_dates":null}
    """.trimIndent().replace("\n", "")

    /**
     * « Une notification t'a aussi été envoyée » n'est écrit que si elle l'a été. Quand l'utilisateur regarde la
     * conversation du bilan, rien n'est notifié : la carte ne le prétend pas.
     */
    @Test
    fun `carte bilan pret - la notification n est mentionnee que si elle a ete envoyee`() = runBlocking {
        suspend fun produce(shown: Boolean): Pair<ReportReadyCard, RecordingReviewNotifier> {
            val notifier = RecordingReviewNotifier()
            val presence = ReviewPresence().apply { conversationShown = shown }
            val transport = ScriptedTransport(
                ModelReplies.call("produce_weekly_report", """{"analysis":"Belle période, continue comme ça."}"""),
                ModelReplies.text("Voilà ton bilan."),
            )
            val services = ReviewServices.inMemory(ReviewSample.engine(), notifier, transport, presence = presence)
            val outcome = services.interpreter.run(TurnInput("Produis mon bilan.")) as TurnOutcome.Completed
            return outcome.cards.filterIsInstance<ReportReadyCard>().single() to notifier
        }

        val (watched, quiet) = produce(shown = true)
        assertTrue(quiet.ready.isEmpty())
        assertEquals("Enregistré sur ton téléphone.", watched.savedText)
        assertFalse(watched.summaryForModel().contains("notifié"))

        val (away, notifier) = produce(shown = false)
        assertEquals(1, notifier.ready.size)
        assertEquals("Enregistré sur ton téléphone. Une notification t’a aussi été envoyée.", away.savedText)
    }

    @Test
    fun `les pesees dites sont enregistrees par le moteur, avec une carte et les chiffres recalcules`() = runBlocking {
        val s = setup(
            ModelReplies.call("record_week_info", declareArgs),
            ModelReplies.text("C’est noté : deux pesées, 18 500 pas par jour et quatre séances. Je produis le bilan ?"),
        )

        val outcome = s.run("Mardi 72,4 kg, jeudi 72,0 kg. 18 500 pas par jour en moyenne, 3 séances de muscu et une course.")

        // Moteur : valeurs telles que dites, rien d'autre.
        val info = s.engine.weekInfo(week.start)!!
        assertEquals(listOf(day("2026-10-27") to 72.4, day("2026-10-30") to 72.0), info.weighIns.map { it.day to it.kg })
        assertEquals(18_500, info.stepsAverage)
        assertEquals(listOf("course" to 1, "musculation" to 3), info.sessions!!.map { it.type to it.count })
        assertTrue(info.steps.isEmpty()) // jamais de pas par jour déduits de la moyenne

        // Carte construite par le code, annulable (action du moteur).
        val card = outcome.cards.single() as WeekInfoCard
        assertEquals("2026-10-26", card.weekStart)
        assertEquals(listOf(72.4, 72.0), card.weighIns.map { it.kg })
        assertEquals(outcome.applied.single().id.value, card.actionId)
        assertEquals(card, ChatCards.decode(ChatCards.encode(listOf(card))).single())
        assertTrue(card.summaryForModel().contains("2026-10-27 72,4 kg"))

        // Résultat renvoyé au modèle : état enregistré + chiffres calculés par l'app.
        val result = toolOutput(s.transport, 1)
        assertEquals("recorded", result["status"]!!.jsonPrimitive.content)
        val figures = result["figures_computed_by_app"]!!.jsonPrimitive.content
        assertTrue(figures, figures.contains("Poids de la période (moyenne de 2 pesée(s)) : 72,2 kg"))
        assertTrue(figures, figures.contains("Pas : 18 500 par jour (moyenne donnée)."))
        assertTrue(figures, figures.contains("Séances : 1 course, 3 musculation (4 au total)."))
        assertFalse(figures, figures.contains("Aucune pesée cette semaine"))
        // La page change avec les pesées : le mot de Trackly s'accorde à celle-ci.
        val page = result["page_computed_by_app"]!!.jsonPrimitive.content
        assertTrue(page, page.startsWith("Ce que la page du bilan affiche"))
        assertTrue(page, page.contains("- Points de la période, dans l'ordre de la page :"))

        // Le fil de bilan ne compte aucune consommation.
        assertEquals(2700.0, s.engine.daySummary(day("2026-10-27")).totals.kcal, 0.0)
        assertEquals(1, s.engine.observeRecentActions(10).first().count { it.type == "week_info.declare" })
    }

    @Test
    fun `la requete du bilan - ses outils seulement, consignes et contexte du bilan`() = runBlocking {
        val s = setup(ModelReplies.text("Bonjour, donne-moi tes pesées."))
        s.run("Bonjour")

        val request = s.transport.requests.single()
        val tools = request["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("record_week_info", "set_weight_goal", "propose_temporary_goal", "produce_weekly_report"), tools)
        val instructions = request["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("JAMAIS de valeur inventée"))
        assertTrue(instructions.contains("Ce fil ne compte aucune consommation"))
        // Mot de Trackly : court, en accord avec la page, ton d'ami, sans écart en ouverture.
        assertTrue(instructions.contains("Le champ analysis est le « mot de Trackly »"))
        assertTrue(instructions.contains("jamais plus optimiste ni plus inquiet"))
        assertTrue(instructions.contains("Ne commence jamais par un écart"))
        assertTrue(instructions.contains("500 caractères au plus"))
        val developer = request["input"]!!.jsonArray.map { it.jsonObject }.first { it["role"]?.jsonPrimitive?.content == "developer" }["content"]!!.jsonPrimitive.content
        assertTrue(developer, developer.contains("26 octobre au 1er novembre 2026 (2026-10-26 au 2026-11-01)"))
        assertTrue(developer, developer.contains("mardi = 2026-10-27"))
        assertTrue(developer, developer.contains("rien n'a encore été donné pour cette période"))
        assertTrue(developer, developer.contains("Calories moyennes : 2 583 kcal par jour sur 6 jour(s)."))
        assertTrue(developer, developer.contains("INCONNUE sur la période (aucune pesée)"))
        // Prévision d'arrivée calculée par l'application, ralentissement présenté comme normal.
        assertTrue(developer, developer.contains("Prévision d'arrivée (calculée par l'application"))
        // Ce que la page affiche, pour que le mot s'y accorde.
        assertTrue(developer, developer.contains("Ce que la page du bilan affiche"))
        assertTrue(developer, developer.contains("- En-tête : « Pas de pesée. »"))
        // Conseils chiffrés : sans date ferme, aucun, et l'interdiction d'en inventer.
        assertTrue(developer, developer.contains("Aucun conseil : pas de date ferme"))
        assertTrue(instructions.contains("Les seuls conseils possibles sont ceux de « Conseils chiffrés »"))
        assertTrue(developer, developer.contains("objectif 60,0 kg au 1er mars 2027"))
        assertTrue(developer, developer.contains("Aucune pesée récente : pas de date d'arrivée"))
        assertTrue(developer, developer.contains("n'en tire jamais un conseil de manger moins ou de faire plus de sport"))
        assertTrue(instructions.contains("set_weight_goal seulement si l'utilisateur le demande"))
        assertTrue(instructions.contains("ne calcule jamais de date"))
        // Consignes stables : aucune date ni aucun chiffre du jour (ils sont dans le contexte).
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(instructions))
    }

    @Test
    fun `aucun poids donne - le rapport est produit et signale l absence de pesee, sans poids invente`() = runBlocking {
        val analysis = "Tu as mangé 2 583 kcal par jour en moyenne pour un objectif de 2 600.\n\nSans pesée cette semaine, je ne peux pas situer ta trajectoire."
        val s = setup(
            ModelReplies.call("produce_weekly_report", """{"analysis":${Json.encodeToString(kotlinx.serialization.serializer<String>(), analysis)}}"""),
            ModelReplies.text("Ton bilan est prêt."),
        )

        val outcome = s.run("Je n'ai pas de pesée cette semaine. Produis mon bilan avec ce que tu as.")

        // Aucune information déclarée n'a été inventée.
        assertNull(s.engine.weekInfo(week.start))
        // Rapport enregistré, notification envoyée, carte affichée.
        val ready = s.notifier.ready.single()
        assertEquals(week.start, ready.weekStart)
        val stored = s.services.reports.get(ready.id)!!
        val page = ReportContent.decode(stored.html)!!
        // Sans pesée : pas de poids, pas de courbe, pas de date.
        assertEquals("Pas de pesée.", page.headline.verdict)
        assertNull(page.weight.chart)
        assertEquals("Pas encore", page.headline.arrival)
        assertEquals("—", page.points.single { it.id == "weighins" }.value)
        assertTrue(page.note.contains("Tu as mangé 2 583 kcal par jour en moyenne"))
        assertEquals("non fournies", page.points.single { it.id == "sessions" }.desc)
        val card = outcome.cards.single() as ReportReadyCard
        assertEquals(ready.id, card.reportId)
        assertTrue(s.services.reports.isDone(week.start))
        // Le résultat renvoyé au modèle ne contient aucun poids.
        val result = toolOutput(s.transport, 1)
        assertEquals("report_ready", result["status"]!!.jsonPrimitive.content)
        assertEquals(ready.id, result["report_id"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `declarer puis produire dans le meme tour - le rapport utilise les valeurs qui viennent d etre notees`() = runBlocking {
        val s = setup(
            ModelReplies.call("record_week_info", """{"weigh_ins":[{"date":"2026-10-29","kg":71.8}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}"""),
            ModelReplies.call("produce_weekly_report", """{"analysis":"Une pesée à 71,8 kg cette semaine."}"""),
            ModelReplies.text("Ton bilan est prêt."),
        )

        s.run("Jeudi j'étais à 71,8 kg. C'est tout, génère.")

        val page = ReportContent.decode(s.services.reports.get(s.notifier.ready.single().id)!!.html)!!
        // La pesée qui vient d'être notée est sur la courbe ; le reste manque et le reste est signalé.
        assertEquals(listOf(71.8), page.weight.chart!!.weighIns.map { it.kg })
        assertFalse(page.headline.verdict == "Pas de pesée.")
        assertEquals("non fournis", page.points.single { it.id == "steps" }.desc)
    }

    @Test
    fun `une date apres aujourd hui est refusee, rien n est enregistre`() = runBlocking {
        val s = setup(
            ModelReplies.call("record_week_info", """{"weigh_ins":[{"date":"2026-11-02","kg":72.0}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}"""),
            ModelReplies.text("Cette pesée est de lundi : elle comptera dans le bilan de la semaine prochaine."),
        )

        val outcome = s.run("Ce matin 72,0 kg")

        assertNull(s.engine.weekInfo(week.start))
        assertTrue(outcome.cards.isEmpty())
        val result = toolOutput(s.transport, 1)
        assertEquals("rejected", result["status"]!!.jsonPrimitive.content)
        assertEquals("true", result["nothing_saved"]!!.jsonPrimitive.content)
        assertTrue(result["message"]!!.jsonPrimitive.content.contains("dans le futur"))
    }

    @Test
    fun `un poids absurde ou une date illisible sont refuses`() = runBlocking {
        val s = setup(
            ModelReplies.call("record_week_info", """{"weigh_ins":[{"date":"2026-10-27","kg":7.2}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}"""),
            ModelReplies.call("record_week_info", """{"weigh_ins":[{"date":"mardi","kg":72.0}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}"""),
            ModelReplies.text("Je n'ai pas pu lire ces valeurs, peux-tu me les redonner ?"),
        )
        s.run("capture illisible")
        assertNull(s.engine.weekInfo(week.start))
        assertEquals("error", toolOutput(s.transport, 1)["status"]!!.jsonPrimitive.content)
        assertTrue(toolOutput(s.transport, 2)["error"]!!.jsonPrimitive.content.contains("Date invalide"))
    }

    @Test
    fun `une capture est envoyee au modele en haute definition`() = runBlocking {
        val s = setup(ModelReplies.text("Je lis 72,4 kg le mardi 27 octobre."))
        s.run("Voici ma capture", images = listOf(ReviewSample.screenshot))

        val user = s.transport.requests.single()["input"]!!.jsonArray.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "user" }
        val parts = user["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("high", parts[1]["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `analyse vide ou trop longue refusee`() = runBlocking {
        val tooLong = "a".repeat(ProduceTooLong)
        val s = setup(
            ModelReplies.call("produce_weekly_report", """{"analysis":"   "}"""),
            ModelReplies.call("produce_weekly_report", """{"analysis":"$tooLong"}"""),
            ModelReplies.text("Je reprends."),
        )
        s.run("génère")
        assertTrue(s.notifier.ready.isEmpty())
        assertEquals("error", toolOutput(s.transport, 1)["status"]!!.jsonPrimitive.content)
        assertTrue(toolOutput(s.transport, 2)["error"]!!.jsonPrimitive.content.contains("trop long"))
    }

    @Test
    fun `annuler la carte des informations rend la semaine vide`() = runBlocking {
        val s = setup(ModelReplies.call("record_week_info", declareArgs), ModelReplies.text("Noté."))
        val outcome = s.run("mes pesées")
        val card = outcome.cards.single() as WeekInfoCard
        val undo = s.engine.execute(app.trackly.core.action.Undo(app.trackly.core.model.ActionId(card.actionId)))
        assertTrue(undo is ActionOutcome.Applied)
        assertNull(s.engine.weekInfo(week.start))
        assertNotNull(s.engine.weeklyFigures(week))
    }

    @Test
    fun `bilan a tout moment - la periode va du lendemain du dernier bilan a aujourd hui, et le rapport garde sa fin`() = runBlocking {
        // Mercredi 4 novembre : le dernier bilan est la semaine du 19 au 25 octobre (ancien format, sans fin enregistrée).
        val engine = ReviewSample.engine(ReviewSample.clock("2026-11-04T10:00"))
        val transport = ScriptedTransport(
            ModelReplies.call("record_week_info", """{"weigh_ins":[{"date":"2026-11-03","kg":71.5}],"steps_by_day":null,"steps_average":null,"sessions":null,"remove_weigh_in_dates":null,"remove_steps_dates":null}"""),
            ModelReplies.call("produce_weekly_report", """{"analysis":"Une pesée à 71,5 kg depuis le dernier bilan."}"""),
            ModelReplies.text("Ton bilan est prêt."),
        )
        val s = Setup(engine, transport)
        val old = s.services.reports.save(day("2026-10-19"), java.time.Instant.parse("2026-10-26T08:00:00Z"), "<html>ancien</html>")

        val outcome = s.run("Mardi 71,5 kg. C'est tout, génère.")

        // Une pesée du 3 novembre, après le dimanche 1er : acceptée, rangée sous le premier jour de la période.
        assertEquals(listOf(day("2026-11-03") to 71.5), engine.weekInfo(day("2026-10-26"))!!.weighIns.map { it.day to it.kg })
        val ready = s.notifier.ready.single()
        assertEquals(day("2026-10-26"), ready.weekStart)
        assertEquals(day("2026-11-04"), ready.periodEnd)
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-04")), outcome.cards.filterIsInstance<ReportReadyCard>().single().period)
        val page = ReportContent.decode(s.services.reports.get(ready.id)!!.html)!!
        assertEquals("2026-10-26", page.periodStart)
        assertEquals("2026-11-04", page.periodEnd)
        // L'ancien bilan reste lisible, avec sa semaine du lundi au dimanche.
        assertEquals(WeekRange(day("2026-10-19")), old.period)
        assertEquals("<html>ancien</html>", s.services.reports.get(old.id)!!.html)
        // Le jour même, rouvrir le bilan reprend la même période ; le lendemain, une nouvelle commence.
        val now = engine.now()
        assertEquals(WeekRange(day("2026-10-26"), day("2026-11-04")), s.services.periods.at(now))
        assertEquals(WeekRange(day("2026-11-05"), day("2026-11-05")), s.services.periods.at(now.plusSeconds(24 * 3600)))
    }

    @Test
    fun `l objectif de poids se change dans le chat du bilan, avec une carte annulable`() = runBlocking {
        val s = setup(
            ModelReplies.call("set_weight_goal", """{"target_kg":60.0,"target_date":"2027-04-01"}"""),
            ModelReplies.text("C'est noté : 60 kg pour le 1er avril."),
        )
        val goalsBefore = s.engine.goals()
        val outcome = s.run("Finalement vise 60 kg pour le 1er avril")

        assertEquals(app.trackly.core.goal.WeightTarget(60.0, day("2027-04-01")), s.engine.weightTarget())
        val card = outcome.cards.single() as app.trackly.assistant.cards.WeightGoalCard
        assertEquals(60.0, card.beforeKg, 0.0)
        assertEquals("2027-03-01", card.beforeDate)
        assertEquals(60.0, card.afterKg, 0.0)
        assertEquals("2027-04-01", card.afterDate)
        assertEquals(outcome.applied.single().id.value, card.actionId)
        assertEquals(card, ChatCards.decode(ChatCards.encode(listOf(card))).single())

        val result = toolOutput(s.transport, 1)
        assertEquals("weight_goal_set", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["forecast_computed_by_app"]!!.jsonPrimitive.content.contains("objectif 60,0 kg au 1er avril 2027"))
        // Le verdict et les conseils changent avec l'objectif : la page et les conseils suivent.
        assertTrue(result["page_computed_by_app"]!!.jsonPrimitive.content.startsWith("Ce que la page du bilan affiche"))
        assertTrue(result["advice_computed_by_app"]!!.jsonPrimitive.content.startsWith("Conseils chiffrés"))

        // Le chat du bilan ne touche pas aux calories.
        assertEquals(goalsBefore, s.engine.goals())

        val undo = s.engine.execute(app.trackly.core.action.Undo(app.trackly.core.model.ActionId(card.actionId)))
        assertTrue(undo is ActionOutcome.Applied)
        assertEquals(app.trackly.core.goal.WeightTarget.Default, s.engine.weightTarget())
    }

    @Test
    fun `un objectif de poids a une date passee est refuse, rien n est enregistre`() = runBlocking {
        val s = setup(
            ModelReplies.call("set_weight_goal", """{"target_kg":60.0,"target_date":"2026-01-01"}"""),
            ModelReplies.text("Cette date est passée."),
        )
        val outcome = s.run("vise 60 kg pour le 1er janvier 2026")
        assertTrue(outcome.cards.isEmpty())
        val result = toolOutput(s.transport, 1)
        assertEquals("rejected", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["message"]!!.jsonPrimitive.content.contains("après aujourd'hui"))
        assertEquals(app.trackly.core.goal.WeightTarget.Default, s.engine.weightTarget())
    }

    private companion object {
        const val ProduceTooLong = app.trackly.assistant.tools.ProduceReportTool.MAX_ANALYSIS + 1
    }

    @Test
    fun `sans conseil calcule par l app, propose_temporary_goal refuse, aucune carte ni objectif change`() = runBlocking {
        val s = setup(
            ModelReplies.call("propose_temporary_goal", """{"days":4,"with_steps":null}"""),
            ModelReplies.text("Pas besoin de baisser : rien à rattraper."),
        )
        val goalsBefore = s.engine.goals()

        val outcome = s.run("Tu peux baisser mon objectif quelques jours ?")

        assertTrue(outcome.cards.isEmpty())
        val output = toolOutput(s.transport, 1)
        assertEquals("error", output["status"]!!.jsonPrimitive.content)
        assertTrue(output["error"]!!.jsonPrimitive.content, output["error"]!!.jsonPrimitive.content.contains("Aucun"))
        assertEquals(goalsBefore, s.engine.goals())
    }
}
