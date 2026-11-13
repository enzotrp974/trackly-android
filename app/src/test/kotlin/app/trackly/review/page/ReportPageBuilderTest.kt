package app.trackly.review.page

import app.trackly.core.forecast.ArrivalVerdict
import app.trackly.core.forecast.ForecastStatus
import app.trackly.core.goal.WeightTarget
import app.trackly.core.review.WeighIn
import app.trackly.review.page.ReportPageFixtures.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Contenu de la page du bilan : un test par statut de la prévision, le ralentissement toujours en info, aucune date sans date ferme. */
class ReportPageBuilderTest {
    private val generatedOn = LocalDate.parse("2026-11-06")
    private val monthDay = Regex("""\d{1,2}(er)? (janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre)""")

    private fun page(scenario: Scenario) = ReportPageBuilder.build(ReportPageFixtures.figures(scenario), ReportPageFixtures.ANALYSIS, generatedOn)

    private fun page(weights: List<WeighIn>, target: WeightTarget = ReportPageFixtures.target) =
        ReportPageBuilder.build(ReportPageFixtures.figures(weights, target = target), ReportPageFixtures.ANALYSIS, generatedOn)

    private val wobble = listOf(0.2, -0.2, 0.1, -0.1)

    /** Pesées des [weeks] dernières semaines (quatre par semaine, un petit écart fixe), poids donné par [kgAt] (jours depuis la première). */
    private fun weights(weeks: Int = 6, kgAt: (Int) -> Double): List<WeighIn> {
        val first = app.trackly.core.review.WeekRange.containing(ReportPageFixtures.range.end).start.minusWeeks(weeks - 1L)
        return (0 until weeks).flatMap { w ->
            listOf(0, 2, 4, 6).mapIndexed { i, d -> WeighIn(first.plusDays(w * 7L + d), Math.round((kgAt(w * 7 + d) + wobble[i]) * 10) / 10.0) }
        }.filter { !it.day.isAfter(ReportPageFixtures.range.end) }
    }

    private fun texts(p: ReportPage) = listOf(p.headline.sentence, p.headline.arrival, p.headline.range.orEmpty()) +
        p.points.flatMap { listOfNotNull(it.desc, it.detail) }

    // ───── Verdict et date ─────

    @Test
    fun `mesuree - la date, la marge et le verdict viennent de la prevision`() {
        for (scenario in Scenario.values()) {
            val figures = ReportPageFixtures.figures(scenario)
            val forecast = figures.forecast
            assertEquals(scenario.name, ForecastStatus.Measured, forecast.status)
            val h = ReportPageBuilder.build(figures, "", generatedOn).headline
            val days = java.time.temporal.ChronoUnit.DAYS.between(forecast.arrival, forecast.target.date)
            assertEquals("${scenario.name} : marge en jours", days.toString().removePrefix("-"), h.margin!!.removePrefix("+").removePrefix("−"))
            assertEquals(days < 0, h.marginLate)
            assertTrue(h.sentence.contains(h.sentenceBold!!))
        }
    }

    @Test
    fun `quatre verdicts - ton et mots`() {
        val base = ReportPageFixtures.figures(Scenario.Temps).forecast
        val arrival = base.target.date.minusDays(20)
        fun headline(verdict: ArrivalVerdict, arrival: LocalDate?) =
            ReportPageBuilder.headline(base.copy(verdict = verdict, arrival = arrival))

        val early = headline(ArrivalVerdict.Early, arrival)
        assertEquals("En avance.", early.verdict)
        assertEquals(Tone.Ok, early.tone)
        assertTrue(early.sentence.contains("20 jours avant le 1er mars"))
        assertEquals("+20", early.margin)

        assertEquals("Dans les temps.", headline(ArrivalVerdict.LikelyOnTime, arrival).verdict)

        val likelyLate = headline(ArrivalVerdict.LikelyLate, base.target.date.plusDays(5))
        assertEquals("Plutôt en retard.", likelyLate.verdict)
        assertEquals(Tone.Warn, likelyLate.tone)
        assertEquals("−5", likelyLate.margin)
        assertTrue(likelyLate.marginLate)

        val late = headline(ArrivalVerdict.Late, base.target.date.plusDays(12))
        assertEquals("En retard.", late.verdict)
        assertTrue(late.sentence.contains("pas le 1er mars : 12 jours de plus"))
        assertEquals("jours", late.marginUnit)

        // Pas d'arrivée à ce rythme : aucune date.
        val never = headline(ArrivalVerdict.Late, null)
        assertEquals("Pas d’arrivée", never.arrival)
        assertNull(never.margin)
        assertFalse(monthDay.containsMatchIn(never.sentence))
    }

    @Test
    fun `aucune pesee - pas de date, pas de courbe`() {
        val p = page(emptyList())
        assertEquals("Pas de pesée.", p.headline.verdict)
        assertEquals(Tone.Neutral, p.headline.tone)
        assertEquals("Pas encore", p.headline.arrival)
        assertNull(p.headline.margin)
        assertFalse(monthDay.containsMatchIn(p.headline.sentence))
        assertNull(p.weight.chart)
        assertNotNull(p.weight.emptyNote)
        assertTrue(p.points.none { it.id == "rhythm" })
    }

    @Test
    fun `estimation - l ordre de grandeur en mois, dit provisoire, sans verdict ni marge ni jour`() {
        // Deux semaines pesées seulement : en dessous du minimum, l'estimation est théorique, jamais une date ferme.
        val few = weights(weeks = 2) { 66.0 - 0.07 * it }
        val forecast = ReportPageFixtures.figures(few).forecast
        assertEquals(ForecastStatus.Provisional, forecast.status)
        assertNotNull(forecast.arrival ?: forecast.earliest)
        val p = page(few)
        val h = p.headline
        assertEquals("Estimation.", h.verdict)
        assertEquals("ESTIMATION PROVISOIRE", h.arrivalLabel)
        assertEquals(Tone.Neutral, h.tone)
        // Un mois (ou deux), présenté comme provisoire.
        val months = Regex("(janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre)")
        assertTrue(h.arrival, months.containsMatchIn(h.arrival))
        assertTrue(h.sentence, months.containsMatchIn(h.sentence))
        assertTrue(h.sentence, h.sentence.contains("Estimation provisoire") && h.sentence.contains("à confirmer avec tes pesées"))
        // Jamais un jour précis dans l'estimation, ni verdict d'avance ou de retard, ni marge en jours, ni fourchette de dates.
        assertFalse(h.sentence, monthDay.containsMatchIn(h.sentence))
        assertFalse(h.arrival, monthDay.containsMatchIn(h.arrival))
        assertFalse(h.sentence.contains("avance") || h.sentence.contains("retard"))
        assertNull(h.margin)
        assertFalse(h.marginLate)
        assertNull(h.range)
        assertNull(h.sentenceBold)
        val chart = p.weight.chart!!
        assertTrue(chart.projection.isEmpty())
        assertNull(chart.arrival)
        assertFalse(chart.late)
        assertTrue(p.points.none { it.id == "rhythm" })
    }

    @Test
    fun `estimation sans rythme estimable - pas de mois non plus`() {
        val base = ReportPageFixtures.figures(weights(weeks = 2) { 66.0 - 0.07 * it }).forecast
        val h = ReportPageBuilder.headline(base.copy(arrival = null, earliest = null, latest = null))
        assertEquals("Estimation.", h.verdict)
        assertEquals("Pas encore", h.arrival)
        assertTrue(h.sentence.contains("pas de date"))
    }

    @Test
    fun `estimation - les trois formes du mois`() {
        val base = ReportPageFixtures.figures(weights(weeks = 2) { 66.0 - 0.07 * it }).forecast
        val feb = LocalDate.parse("2027-02-10")
        val mar = LocalDate.parse("2027-03-20")
        assertEquals("Vers février 2027", ReportPageBuilder.headline(base.copy(arrival = feb, earliest = feb, latest = feb.plusDays(3))).arrival)
        assertEquals("février 2027 – mars 2027", ReportPageBuilder.headline(base.copy(arrival = feb, earliest = feb, latest = mar)).arrival)
        val open = ReportPageBuilder.headline(base.copy(arrival = feb, earliest = feb, latest = null))
        assertEquals("Pas avant février 2027", open.arrival)
        assertTrue(open.sentence.contains("peut-être bien plus tard"))
    }

    @Test
    fun `atteint`() {
        val p = page(weights { 61.2 })
        assertEquals("Objectif atteint.", p.headline.verdict)
        assertEquals(Tone.Ok, p.headline.tone)
        assertEquals("Atteint", p.headline.arrival)
        assertNull(p.headline.margin)
        assertTrue(p.weight.chart!!.projection.isEmpty())
        assertTrue(p.weight.chart!!.planned.isEmpty())
    }

    @Test
    fun `poids stable`() {
        val p = page(weights { 66.0 })
        assertEquals("Poids stable.", p.headline.verdict)
        assertEquals(Tone.Warn, p.headline.tone)
        assertEquals("Pas d’arrivée", p.headline.arrival)
        assertNull(p.headline.margin)
        assertFalse(monthDay.containsMatchIn(p.headline.sentence))
    }

    @Test
    fun `le poids s eloigne de l objectif`() {
        val p = page(weights { 63.0 + 0.4 * it / 7 })
        assertEquals("Tu t’éloignes de l’objectif.", p.headline.verdict)
        assertEquals(Tone.Warn, p.headline.tone)
        assertTrue(p.headline.sentence.contains("monte"))
        assertEquals("Pas d’arrivée", p.headline.arrival)
    }

    // ───── Le ralentissement n'est jamais un problème ─────

    @Test
    fun `un ralentissement observe et attendu est une information - jamais jaune, meme en retard`() {
        for (scenario in Scenario.values()) {
            val figures = ReportPageFixtures.figures(ReportPageFixtures.slowingWeights(), scenario)
            val p = ReportPageBuilder.build(figures, "", generatedOn)
            val rhythm = p.points.single { it.id == "rhythm" }
            assertEquals(scenario.name, PointStatus.Info, rhythm.status)
            assertTrue(rhythm.detail!!.contains("Ce n’est pas une alerte"))
            assertEquals(ChartKind.Weekly, rhythm.chart!!.kind)
            // Observé contre observé : deux barres, le début puis cette période, la seconde plus basse.
            val (early, recent) = rhythm.chart!!.values.map { it!! }
            assertTrue("$early contre $recent", recent < early)
            assertTrue(rhythm.desc, rhythm.desc.startsWith("contre 0,5"))
        }
        // Le retard se lit dans le verdict, pas dans le point du rythme.
        val late = ReportPageBuilder.build(ReportPageFixtures.figures(ReportPageFixtures.slowingWeights(), Scenario.Retard), "", generatedOn)
        assertEquals(PointStatus.Info, late.points.single { it.id == "rhythm" }.status)
    }

    @Test
    fun `rien n a ralenti - pas de point rythme`() {
        // Une perte régulière, et la perte des scénarios de démonstration (simulée avec le modèle) : rien à expliquer.
        assertTrue(page(weights(weeks = 7) { 68.0 - 0.4 * it / 7 }).points.none { it.id == "rhythm" })
        for (scenario in Scenario.values()) {
            val figures = ReportPageFixtures.figures(scenario)
            val pace = app.trackly.core.review.ObservedPaces.compute(figures.weightHistory, figures.range.end, true, figures.params)
            assertEquals(scenario.name, pace?.slowdownExpected == true, page(scenario).points.any { it.id == "rhythm" })
        }
    }

    @Test
    fun `un rythme qui s effondre n est pas explique comme prevu - pas de point rythme`() {
        val collapse = weights(weeks = 7) { if (it < 21) 68.0 - 0.5 * it / 7 else 66.5 - 0.03 * (it - 21) / 7 }
        assertTrue(page(collapse).points.none { it.id == "rhythm" })
    }

    @Test
    fun `quel que soit le statut de la prevision, le rythme n est jamais warn et aucun mot d alerte n y figure`() {
        val pages = Scenario.values().map { page(it) } + listOf(
            page(emptyList()), page(weights { 66.0 }), page(weights { 63.0 + 0.4 * it / 7 }), page(weights { 66.0 - 0.07 * it }),
            page(ReportPageFixtures.slowingWeights()),
        )
        assertTrue(pages.any { p -> p.points.any { it.id == "rhythm" } })
        for (p in pages) {
            val rhythm = p.points.firstOrNull { it.id == "rhythm" } ?: continue
            assertEquals(PointStatus.Info, rhythm.status)
            val text = (listOf(rhythm.desc, rhythm.detail.orEmpty())).joinToString(" ").lowercase()
            for (word in listOf("stagn", "problème grave", "alerte :", "mauvais", "raté")) assertFalse(word, text.contains(word))
        }
    }

    // ───── Points ─────

    @Test
    fun `ce qui coince passe avant ce qui s explique, puis le reste dans l ordre fixe`() {
        val retard = ReportPageBuilder.build(ReportPageFixtures.figures(ReportPageFixtures.slowingWeights(), Scenario.Retard), "", generatedOn)
        val statuses = retard.points.map { it.status }
        val firstInfo = statuses.indexOf(PointStatus.Info)
        assertTrue(firstInfo > 0)
        assertTrue(statuses.take(firstInfo).all { it == PointStatus.Warn })
        assertTrue(statuses.drop(firstInfo + 1).none { it == PointStatus.Warn || it == PointStatus.Info })
        assertEquals(listOf("steps", "kcal", "rhythm"), retard.points.take(3).map { it.id })
    }

    @Test
    fun `une ligne quand ca va, un detail et un graphique quand ca coince`() {
        for (point in page(Scenario.Avance).points.filter { it.status != PointStatus.Info }) {
            assertNull(point.id, point.detail)
            assertNull(point.id, point.chart)
        }
        val late = page(Scenario.Retard).points.associateBy { it.id }
        assertEquals(PointStatus.Warn, late.getValue("steps").status)
        assertEquals(ChartKind.Bars, late.getValue("steps").chart!!.kind)
        assertEquals(7, late.getValue("steps").chart!!.values.size)
        assertEquals("+400", late.getValue("kcal").value.replace("+", "+"))
        assertEquals(ChartKind.Gap, late.getValue("kcal").chart!!.kind)
        assertTrue(late.getValue("kcal").detail!!.contains("Samedi +290 et dimanche +220"))
    }

    @Test
    fun `proteines - le graphique porte le minimum et l ideal, les jours sous le minimum en alerte`() {
        val protein = page(Scenario.Temps).points.single { it.id == "protein" }
        assertEquals(PointStatus.Warn, protein.status)
        assertEquals("4/7", protein.value)
        val chart = protein.chart!!
        assertEquals(ChartKind.Dots, chart.kind)
        assertEquals(listOf(120.0, 135.0), chart.refs.map { it.value })
        assertEquals(listOf(true, false, true, false, false, false, true), chart.flagged)
        // Les valeurs sont écrites seulement sur les jours sous le minimum.
        assertEquals(listOf("112", null, "104", null, null, null, "109"), chart.valueLabels)
    }

    @Test
    fun `une valeur inconnue reste inconnue - jamais zero`() {
        val figures = ReportPageFixtures.figures(Scenario.Temps)
        val noPeriodInfo = figures.copy(activity = figures.activity.copy(averageSteps = null, stepsSource = app.trackly.core.review.StepsSource.Unknown, sessions = null, stepsByDay = emptyList()))
        val points = ReportPageBuilder.build(noPeriodInfo, "", generatedOn).points.associateBy { it.id }
        assertEquals("—", points.getValue("steps").value)
        assertEquals(PointStatus.Neutral, points.getValue("steps").status)
        assertEquals("—", points.getValue("sessions").value)
    }

    @Test
    fun `un jour non note n est pas un jour a zero`() {
        val figures = ReportPageFixtures.figures(Scenario.Temps)
        val kept = figures.coverage.counted.dropLast(2)
        val partial = figures.copy(coverage = figures.coverage.copy(counted = kept, missing = figures.coverage.counted.takeLast(2)))
        val days = ReportPageBuilder.build(partial, "", generatedOn).points.single { it.id == "days" }
        assertEquals("5/7", days.value)
        assertEquals(PointStatus.Neutral, days.status)
    }

    // ───── Cas limites ─────

    @Test
    fun `la courbe a ce rythme est jaune quand le verdict l est, meme sans date d arrivee`() {
        assertTrue(page(weights { 66.0 }).weight.chart!!.late)
        assertTrue(page(weights { 63.0 + 0.4 * it / 7 }).weight.chart!!.late)
        assertTrue(page(Scenario.Retard).weight.chart!!.late)
        assertFalse(page(Scenario.Temps).weight.chart!!.late)
    }

    @Test
    fun `une periode longue nomme les jours avec leur numero, jamais deux fois le meme jour de semaine`() {
        val figures = ReportPageFixtures.figures(Scenario.Temps)
        val long = figures.copy(range = app.trackly.core.review.WeekRange(figures.range.start.minusDays(7), figures.range.end))
        val detail = ReportPageBuilder.build(long, "", generatedOn).points.single { it.id == "protein" }.detail!!
        assertTrue(detail, Regex("""[Ss]amedi 31""").containsMatchIn(detail))
    }

    @Test
    fun `vers une prise de poids, manger sous l objectif est ce qui coince`() {
        val target = WeightTarget(70.0, ReportPageFixtures.target.date, losing = false)
        val figures = ReportPageFixtures.figures(weights { 66.0 + 0.3 * it / 7 }, Scenario.Retard, target)
        val points = ReportPageBuilder.build(figures, "", generatedOn).points.associateBy { it.id }
        // Le scénario « retard » mange 400 kcal de PLUS que l'objectif : pour une prise, c'est bon.
        assertEquals(PointStatus.Ok, points.getValue("kcal").status)
        val rhythm = ReportPageBuilder.build(
            ReportPageFixtures.figures(weights(weeks = 7) { if (it < 21) 66.0 + 0.5 * it / 7 else 67.5 + 0.38 * (it - 21) / 7 }, Scenario.Retard, target),
            "", generatedOn,
        ).points.firstOrNull { it.id == "rhythm" }
        if (rhythm != null) {
            assertTrue(rhythm.chart!!.title.startsWith("Prise"))
            assertFalse(rhythm.detail!!.contains("maigris"))
            assertEquals(PointStatus.Info, rhythm.status)
        }
    }

    @Test
    fun `une fourchette de plus d un an n est pas ecrite`() {
        // 5 semaines pesées bruitées : la fin de la marge est très loin.
        val p = page(Scenario.Avance)
        assertNull(p.headline.range)
    }

    @Test
    fun `un seul jour - minimum tenu ce jour, au singulier`() {
        val figures = ReportPageFixtures.figures(Scenario.Temps)
        val one = figures.copy(daily = listOf(figures.daily[1]))
        val protein = ReportPageBuilder.build(one, "", generatedOn).points.single { it.id == "protein" }
        assertEquals(PointStatus.Ok, protein.status)
        assertEquals("minimum tenu ce jour", protein.desc)
        assertEquals("1/1", protein.value)
    }

    @Test
    fun `ECR-04 - des jours sans objectif - le total ne compte que les jours avec objectif, et le dit`() {
        // 7 jours comptés, 3 avec objectif, +200 kcal en moyenne sur ces 3 jours : +600 sur la période, pas +1 400.
        val figures = ReportPageFixtures.figures(Scenario.Retard)
        assertEquals(7, figures.coverage.countedDays)
        val partial = figures.copy(goal = figures.goal.copy(daysWithGoal = 3, averageDifferenceKcal = 200.0))
        val kcal = ReportPageBuilder.build(partial, "", generatedOn).points.single { it.id == "kcal" }
        assertEquals(PointStatus.Warn, kcal.status)
        assertEquals("+600", kcal.value)
        assertTrue(kcal.desc, kcal.desc.contains("3 jours avec objectif"))
        // Tous les jours avec objectif : rien ne change.
        val full = ReportPageBuilder.build(figures, "", generatedOn).points.single { it.id == "kcal" }
        assertEquals("+400", full.value)
        assertFalse(full.desc, full.desc.contains("avec objectif"))
    }

    @Test
    fun `ECR-05 - minimum de proteines qui change pendant la periode - texte et graphique disent la meme chose`() {
        // Le minimum passe de 120 à 110 g le 4e jour (poids de référence mis à jour). Sous le minimum : 112 (120), 104 (120), 109 (110).
        val figures = ReportPageFixtures.figures(Scenario.Temps)
        val changed = figures.copy(daily = figures.daily.mapIndexed { i, d -> if (i >= 4) d.copy(proteinMinG = 110.0) else d })
        val protein = ReportPageBuilder.build(changed, "", generatedOn).points.single { it.id == "protein" }
        assertEquals("4/7", protein.value)
        val detail = protein.detail!!
        assertFalse(detail, detail.contains("minimum de 120 g"))
        assertTrue(detail, detail.contains("110") && detail.contains("120"))
        val minRef = protein.chart!!.refs.first()
        assertEquals(110.0, minRef.value, 0.0)
        assertEquals("min actuel 110", minRef.label)
        // Minimum constant : le texte et le repère ne changent pas.
        val same = ReportPageBuilder.build(figures, "", generatedOn).points.single { it.id == "protein" }
        assertTrue(same.detail!!, same.detail!!.contains("sous ton minimum de 120 g"))
        assertEquals("min 120", same.chart!!.refs.first().label)
    }

    // ───── Page ─────

    @Test
    fun `l ordre et les blocs sont fixes par l application - les emplacements de 66 et 67 restent vides`() {
        val p = page(Scenario.Retard)
        assertTrue(p.advice.isEmpty())
        assertNull(p.referenceWeight)
        assertEquals(ReportPageFixtures.ANALYSIS.trim(), p.note)
        assertEquals("2026-10-31", p.periodStart)
        assertEquals("2026-11-06", p.periodEnd)
        // Le calcul est expliqué, avec ce qui manque.
        assertTrue(p.howItWorks.first().contains("pas de l’IA"))
    }

    @Test
    fun `la page est relue telle quelle, un ancien bilan HTML et un format plus recent ne sont pas des pages`() {
        val p = page(Scenario.Retard)
        val stored = ReportContent.encode(p)
        assertEquals(p, ReportContent.decode(stored))

        assertFalse(ReportContent.isPage("<!doctype html><html></html>"))
        assertNull(ReportContent.decode("<!doctype html><html></html>"))

        val future = stored.replaceFirst("\"format\":1", "\"format\":99")
        assertTrue(ReportContent.isPage(future))
        assertNull(ReportContent.decode(future))
        assertNull(ReportContent.decode("{pas du json"))
    }
}
