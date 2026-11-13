package app.trackly.live

import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.SetWeightGoal
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.review.DaySteps
import app.trackly.core.review.DeclareWeekInfo
import app.trackly.core.review.SessionCount
import app.trackly.core.review.WeekRange
import app.trackly.core.review.WeighIn
import app.trackly.review.InMemoryReportRepository
import app.trackly.review.RecordingReviewNotifier
import app.trackly.review.ReviewServices
import app.trackly.review.page.ReportContent
import app.trackly.review.page.ReportPage
import app.trackly.review.page.ReportPageBuilder
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import java.time.Instant
import java.time.LocalDate

/**
 * Banc du prompt du bilan : des périodes **fictives** jouées par le vrai interpréteur du bilan (consignes, contexte,
 * outils, moteur, page), avec un modèle simulé. Aucun réseau, aucune donnée réelle.
 *
 * Données communes : départ à 68 kg le 14 septembre 2026, objectif 62 kg au 1er mars 2027, 2 300 kcal (protéines 135 g,
 * minimum 120 g, lipides 70 g), dernier bilan jusqu'au 30 octobre, bilan du 31 octobre au vendredi 6 novembre au soir.
 * Le poids suit la dépense du modèle de l'app (référence de [app.trackly.core.review.ReviewParams] : 3 000 kcal à 70 kg,
 * 30 kcal de moins par kilo) : la perte ralentit d'elle-même.
 *
 * Les mêmes scénarios, exportés tels que le modèle les reçoit, servent à comparer plusieurs modèles hors du dépôt
 * (`TRACKLY_REVIEW_EXPORT`, voir [ReviewBenchTest]).
 */
object ReviewBench {
    val start: LocalDate = LocalDate.parse("2026-09-14")
    val period = WeekRange(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-06"))
    private val previousStart = LocalDate.parse("2026-10-24")
    private val base = GoalTargets(2300.0, 135.0, 250.0, 70.0, 120.0)
    private const val NOW = "2026-11-06T20:30"

    /** Ce que le scénario attend du modèle : un rapport (`produce_weekly_report`) ou une réponse dans le chat. */
    enum class Expect { Report, Reply }

    /** Un jour de la période : `null` en kcal = jour non saisi. */
    data class Day(val kcal: Double?, val protein: Double = 135.0, val fat: Double = 64.0, val steps: Int? = 20_000)

    data class Scenario(
        val id: String,
        val title: String,
        /** Ce que le scénario vérifie, en une phrase (pour la grille et la page de comparaison). */
        val focus: String,
        val message: String,
        val expect: Expect,
        /** Jour d'arrivée (en jours depuis le 14 septembre) de la courbe simulée ; 168 = 1er mars. */
        val arrivalDay: Int,
        val days: List<Day>,
        val sessions: List<SessionCount>?,
        /** Jours (depuis le 14 septembre) des pesées ; par défaut trois par semaine sur toute la durée. */
        val weighDays: List<Int> = regularWeighDays,
        /** Pesées données telles quelles, à la place de la courbe simulée (ralentissement observé). */
        val weighIns: List<WeighIn>? = null,
        /** Écart ajouté à la pesée d'un jour (en jours depuis le 14 septembre) : une pesée haute après un gros repas. */
        val bumps: Map<Int, Double> = emptyMap(),
        /** Ce que la réponse doit relever, ce qu'elle ne doit pas affirmer (grille, contrôles du scénario). */
        val mention: Regex? = null,
        val avoid: Regex? = null,
        /** Questions permises dans la phrase qui suit le rapport ; `null` : non contrôlé. */
        val followUpQuestions: IntRange? = null,
    )

    private val regularWeighDays = ((0..6).flatMap { w -> listOf(w * 7 + 1, w * 7 + 3, w * 7 + 6) } + listOf(47, 49, 51, 53)).distinct()
    private val dailyWeighDays = (0..53).toList()
    private val muscu3 = listOf(SessionCount("musculation", 3))

    /** Mardi 3 novembre, gros repas avec des amis ; la pesée du mercredi 4 (jour 51) est haute, d'eau et de repas surtout. */
    private val bigDinner = List(7) { i -> if (i == 3) Day(3600.0, protein = 150.0, fat = 120.0, steps = 18_000) else Day(2290.0, protein = 136.0, fat = 64.0, steps = 20_100) }
    private val highAfterDinner = mapOf(51 to 0.9)

    /** Une courbe un peu plus rapide, pour que la pesée d'eau ne fasse pas à elle seule basculer le verdict : B10 isole la pesée haute. */
    private const val DINNER_ARRIVAL = 125
    private val reprise = Regex("tu as repris|reprise de poids|vraie reprise|une reprise|tu remontes|pris du poids|tu grossis")
    private val eveLink = Regex("veille|lendemain|repas de mardi|gros repas|mardi soir|\\beau\\b")

    val SCENARIOS: List<Scenario> = listOf(
        Scenario(
            "B01", "En avance", "Verdict « en avance » dit avec la bonne marge, tout au vert : un mot court qui félicite sans en rajouter.",
            "J'ai tout donné, tu peux générer mon bilan.", Expect.Report, arrivalDay = 95, followUpQuestions = 0..0,
            days = List(7) { Day(2240.0, protein = 138.0, fat = 63.0, steps = 21_400) }, sessions = muscu3, weighDays = dailyWeighDays,
        ),
        Scenario(
            "B02", "Dans les temps, protéines à surveiller", "Dans les temps ; un seul point coince (protéines sous le minimum trois jours) : le dire sans reproche.",
            "Génère le bilan stp.", Expect.Report, arrivalDay = 166,
            days = listOf(112.0, 131.0, 104.0, 138.0, 127.0, 141.0, 109.0).map { Day(2290.0, protein = it, fat = 66.0, steps = 19_800) }, sessions = muscu3,
        ),
        Scenario(
            "B03", "En retard, dépassement à rattraper", "En retard avec un vrai dépassement (+400 kcal) : un seul conseil de l'app, chiffres tels quels, présenté comme une option.",
            "Vas-y, fais le bilan avec ce que tu as.", Expect.Report, arrivalDay = 380,
            days = listOf(290.0, 220.0, -40.0, -30.0, 10.0, -40.0, -10.0).zip(listOf(14_200, 16_800, 12_500, 15_900, 17_300, 14_600, 15_800))
                .map { (gap, steps) -> Day(2300.0 + gap, protein = 131.0, fat = 68.0, steps = steps) },
            sessions = listOf(SessionCount("musculation", 2)), weighDays = dailyWeighDays,
        ),
        Scenario(
            "B04", "Ralentissement normal", "La perte ralentit comme prévu, la date tient : le dire normal, jamais une stagnation, rien à changer.",
            "C'est bon pour moi, génère le bilan.", Expect.Report, arrivalDay = 165,
            days = List(7) { Day(2270.0, protein = 137.0, fat = 61.0, steps = 20_300) }, sessions = muscu3, weighIns = slowing(),
        ),
        Scenario(
            "B05", "Données manquantes, estimation", "Deux jours non saisis, pas et séances non donnés, pesées récentes seulement : aucune date ni verdict, rien comblé.",
            "Je n'ai pas mes pas cette fois, génère quand même.", Expect.Report, arrivalDay = 170, followUpQuestions = 0..0,
            days = listOf(Day(2310.0), Day(2280.0), Day(null), Day(2350.0), Day(2260.0), Day(null), Day(2300.0)).map { it.copy(steps = null) },
            sessions = null, weighDays = listOf(43, 49, 52),
        ),
        Scenario(
            "B06", "Ralentissement, envie de baisser", "Le souci de l'utilisateur : il voit sa perte ralentir et veut baisser ses calories ou faire plus de sport. Réponse : c'est normal, rien à changer.",
            "Je perds moins vite qu'en septembre, ça me stresse. Je devrais pas descendre à 2 100 kcal ou rajouter du cardio ?", Expect.Reply, arrivalDay = 165,
            days = List(7) { Day(2270.0, protein = 137.0, fat = 61.0, steps = 20_300) }, sessions = muscu3, weighIns = slowing(),
        ),
        Scenario(
            "B07", "Plutôt en retard, sans dépassement", "Le retard vient du rythme, pas des repas (objectif tenu) : ni baisse ni sport proposés ; la nouvelle date de la page peut être signalée, set_weight_goal seulement s'il le demande.",
            "Génère mon bilan.", Expect.Report, arrivalDay = 186,
            days = List(7) { Day(2290.0, protein = 136.0, fat = 64.0, steps = 20_100) }, sessions = muscu3,
        ),
        Scenario(
            "B08", "Poids stable", "Le poids ne bouge plus depuis des semaines (pas un ralentissement prévu) : le dire comme la page, sans « c'est normal », sans date, sans baisse ni sport.",
            "Génère le bilan.", Expect.Report, arrivalDay = 168,
            days = List(7) { Day(2300.0, protein = 136.0, fat = 64.0, steps = 20_000) }, sessions = muscu3, weighIns = flat(),
        ),
        Scenario(
            "B09", "Plutôt en retard, envie de couper", "Plutôt en retard sans dépassement, l'utilisateur veut descendre à 2 000 kcal et faire du cardio : ça vient du rythme, rien de tout ça ; au plus la nouvelle date, comme une option.",
            "Je suis en retard, du coup je descends à 2 000 kcal et je rajoute du cardio, ok ?", Expect.Reply, arrivalDay = 186,
            days = List(7) { Day(2290.0, protein = 136.0, fat = 64.0, steps = 20_100) }, sessions = muscu3,
        ),
        Scenario(
            "B10", "Pesée haute après un gros repas", "La pesée du mercredi est haute après un gros repas le mardi (vu dans « Jour par jour ») : faire le lien, sans parler de reprise ni s'alarmer.",
            "Génère mon bilan.", Expect.Report, arrivalDay = DINNER_ARRIVAL, days = bigDinner, sessions = muscu3, bumps = highAfterDinner,
            mention = eveLink, avoid = reprise,
        ),
        Scenario(
            "B11", "Un jour de pas douteux", "Un jeudi à 2 100 pas parmi des jours à 20 000 (montre oubliée ?) fait passer les pas en jaune : produire le bilan, puis une ou deux questions courtes sur ce jour, pas plus.",
            "Génère le bilan.", Expect.Report, arrivalDay = 166,
            days = List(7) { i -> Day(2290.0, protein = 136.0, fat = 64.0, steps = if (i == 5) 2_100 else 20_300) }, sessions = muscu3,
            mention = Regex("jeudi|2 100|montre"), followUpQuestions = 1..2,
        ),
        Scenario(
            "B12", "Il signale sa pesée haute", "L'utilisateur dit que sa pesée est haute parce qu'il a beaucoup mangé la veille avec des amis : le rassurer en faisant le lien, sans baisse ni sport.",
            "Ma pesée de mercredi est haute, mardi soir j'ai beaucoup mangé avec des amis. C'est grave ?", Expect.Reply, arrivalDay = DINNER_ARRIVAL,
            days = bigDinner, sessions = muscu3, bumps = highAfterDinner,
            mention = eveLink, avoid = reprise,
        ),
    )

    fun scenario(id: String) = SCENARIOS.single { it.id == id }


    /** Une partie jouée : la première requête vue par le modèle, et ce que l'app en a tiré. */
    class Played(
        val scenario: Scenario,
        val requests: List<ScriptedModel.Request>,
        /** Page du bilan produite (si le modèle a appelé `produce_weekly_report`). */
        val page: ReportPage?,
        /** Dernier texte du modèle dans le chat. */
        val reply: String,
        /** La page que l'app affiche (ou afficherait) pour la période : elle fait foi pour le verdict et les points. */
        val appPage: ReportPage,
    ) {
        val first: ScriptedModel.Request get() = requests.first()
    }

    /** Joue [scenario] : données fictives dans le moteur, un message de l'utilisateur, réponses de [model]. */
    suspend fun play(scenario: Scenario, model: ScriptedModel): Played {
        val clock = SampleData.clock(NOW)
        val engine = SampleData.emptyEngine(clock)
        seed(engine, scenario)
        val reports = InMemoryReportRepository()
        // Dernier bilan jusqu'au 30 octobre : la période va du 31 octobre à aujourd'hui.
        reports.save(previousStart, Instant.parse("2026-10-30T19:00:00Z"), "<p>Ancien bilan.</p>", period.start.minusDays(1))
        val figures = engine.weeklyFigures(period)
        val appPage = ReportPageBuilder.build(figures, "", period.end, engine.catchUp(figures, period.end))
        val services = ReviewServices.inMemory(engine, RecordingReviewNotifier(), model, reports = reports)
        val outcome = services.interpreter.run(TurnInput(scenario.message)) as TurnOutcome.Completed
        val page = reports.latestFor(period.start)?.let { reports.get(it.id) }?.let { ReportContent.decode(it.html) }
        return Played(scenario, model.requests.map { ScriptedModel.Request(it) }, page, outcome.reply, appPage)
    }

    private suspend fun seed(engine: TracklyEngine, s: Scenario) {
        check(engine.execute(SetGoal(base, from = LocalDate.parse("2026-09-01"))) is ActionOutcome.Applied)
        check(engine.execute(SetWeightGoal(62.0, LocalDate.parse("2027-03-01"))) is ActionOutcome.Applied)
        var day = start
        while (!day.isAfter(period.end)) {
            val i = period.days.indexOf(day)
            val d = if (i >= 0) s.days[i] else Day(2300.0, 135.0, 66.0)
            d.kcal?.let { kcal ->
                val carbs = ((kcal - 4 * d.protein - 9 * d.fat) / 4).coerceAtLeast(0.0)
                val food = Food("Repas de la journée", Nutrition(kcal, d.protein, carbs, d.fat))
                check(engine.execute(AddConsumptions(NewConsumption(food, Moment.Soir, explicitDay = day))) is ActionOutcome.Applied)
            }
            day = day.plusDays(1)
        }
        val curve = curve(s.arrivalDay)
        val weighIns = s.weighIns ?: s.weighDays.filter { it <= TODAY }.mapIndexed { n, t ->
            WeighIn(start.plusDays(t.toLong()), Math.round((curve[t] + noise(n) + (s.bumps[t] ?: 0.0)) * 10) / 10.0)
        }
        val (inPeriod, before) = weighIns.partition { !it.day.isBefore(period.start) }
        if (before.isNotEmpty()) check(engine.execute(DeclareWeekInfo(start, weighIns = before)) is ActionOutcome.Applied)
        val steps = period.days.mapIndexedNotNull { i, d -> s.days[i].steps?.let { DaySteps(d, it) } }
        if (inPeriod.isNotEmpty() || steps.isNotEmpty() || s.sessions != null) {
            check(engine.execute(DeclareWeekInfo(period.start, weighIns = inPeriod, steps = steps, sessions = s.sessions)) is ActionOutcome.Applied)
        }
    }

    private const val TODAY = 53

    /** Une pesée par jour depuis le 14 septembre, autour de 66 kg : le poids ne bouge plus. */
    private fun flat(): List<WeighIn> = (0..TODAY).map { t -> WeighIn(start.plusDays(t.toLong()), Math.round((66.0 + noise(t)) * 10) / 10.0) }

    /**
     * Sept semaines de pesées (trois par semaine, finissant avec la période) : 0,5 kg par semaine, puis environ 0,38. C'est le
     * ralentissement que prévoit le modèle de dépense de l'app, donc le point « Rythme » de la page apparaît, en « à savoir ».
     */
    private fun slowing(startKg: Double = 70.0): List<WeighIn> {
        val first = period.end.minusDays(7L * 7 - 1)
        return (0 until 7).flatMap { w -> listOf(1, 3, 5).map { d -> w * 7 + d } }.map { t ->
            val kg = if (t <= 24) startKg - 0.5 * t / 7 else startKg - 0.5 * 24 / 7 - 0.38 * (t - 24) / 7
            WeighIn(first.plusDays(t.toLong()), Math.round(kg * 100) / 100.0)
        }
    }

    /** Poids jour par jour à apport constant, l'apport choisi pour arriver à 62 kg le jour [arrivalDay]. */
    private fun curve(arrivalDay: Int): DoubleArray {
        fun simulate(intake: Double, days: Int) = DoubleArray(days + 1).also { out ->
            var w = 68.0
            for (t in 0..days) {
                out[t] = w
                w -= (3000.0 + 30.0 * (w - 70.0) - intake) / 7700.0
            }
        }
        var low = 1500.0
        var high = 3200.0
        repeat(60) {
            val mid = (low + high) / 2
            if (simulate(mid, arrivalDay)[arrivalDay] > 62.0) high = mid else low = mid
        }
        return simulate((low + high) / 2, 200)
    }

    private fun noise(i: Int) = (((i * 7919 + 13) % 101) / 101.0 - 0.5) * 0.1
}
