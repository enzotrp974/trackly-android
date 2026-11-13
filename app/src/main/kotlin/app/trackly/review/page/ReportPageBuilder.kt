package app.trackly.review.page

import app.trackly.core.forecast.ArrivalForecast
import app.trackly.core.forecast.ArrivalVerdict
import app.trackly.core.forecast.CurvePoint
import app.trackly.core.forecast.ForecastStatus
import app.trackly.core.review.CatchUpResult
import app.trackly.core.review.DayFigure
import app.trackly.core.review.ObservedPaces
import app.trackly.core.review.StepsSource
import app.trackly.core.review.WeeklyFigures
import app.trackly.core.review.WeighInReliability
import app.trackly.core.review.WeightHistory
import app.trackly.review.ReportFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Construit la [ReportPage] depuis les chiffres du moteur. **Aucun calcul de prévision ici** : la date, la marge, le verdict
 * et les courbes sont ceux de `figures.forecast`, reportés tels quels. Ce que ce fichier décide est de la lecture :
 * quel mot, quel ton, quel point ouvrir. Les seuils sont des constantes nommées ci-dessous.
 *
 * - Un ralentissement de la perte, déjà compté dans la courbe attendue, est un point [PointStatus.Info] : jamais [PointStatus.Warn].
 * - Sans date ferme (estimation, aucune pesée), aucune date n'est écrite : ni phrase, ni chiffre, ni point sur la courbe.
 */
object ReportPageBuilder {
    /** Pas : au-dessous de cette part de la référence, le point s'ouvre ; au-dessus de [STEPS_TOP], il est « top ». */
    const val STEPS_WARN_BELOW = 0.90
    const val STEPS_TOP_ABOVE = 1.05

    /** Calories : au-dessus de cette part de l'objectif en moyenne, le point s'ouvre. */
    const val KCAL_WARN_ABOVE = 0.02

    /** Un jour d'une période est cité dans le détail quand il dépasse l'objectif d'au moins cette part (et [KCAL_DAY_MIN_KCAL]). */
    const val KCAL_DAY_SHARE = 0.05
    const val KCAL_DAY_MIN_KCAL = 100.0

    /** Les petits graphiques montrent au plus ces derniers jours de la période. */
    const val CHART_MAX_DAYS = 14

    /** [advice] : conseils chiffrés (`TracklyEngine.catchUp`), calculés par l'application ; `null` : aucun. */
    fun build(figures: WeeklyFigures, analysis: String, generatedOn: LocalDate, advice: CatchUpResult? = null): ReportPage {
        val forecast = figures.forecast
        return ReportPage(
            periodStart = figures.range.start.toString(),
            periodEnd = figures.range.end.toString(),
            generatedOn = generatedOn.toString(),
            headline = headline(forecast),
            weight = weight(figures),
            note = analysis.trim(),
            points = points(figures),
            advice = AdviceText.of(advice),
            howItWorks = listOf(
                "La date vient de Trackly, pas de l’IA. Elle part de tes pesées et tient compte de ta dépense, qui baisse quand tu maigris : la courbe « prévu » ralentit donc toute seule. Un ralentissement ne déclenche pas d’alerte.",
                "L’IA écrit seulement les phrases. Tes calories ne changent jamais sans ton accord.",
            ) + forecast.assumptions + figures.missing.map { it.message },
        )
    }

    // ───── Verdict et date ─────

    internal fun headline(p: ArrivalForecast): Headline {
        val target = dateLabel(p.target.date, p.asOf)
        val goal = "${kgLabel(p.target.kg)} kg"
        val passed = if (p.targetDatePassed) " La date visée est passée." else ""
        return when (p.status) {
            ForecastStatus.NoWeighIn -> Headline(
                verdict = "Pas de pesée.",
                tone = Tone.Neutral,
                sentence = "Sans pesée récente, je ne sais pas où en est ton poids : pas de date d’arrivée.$passed",
                arrival = "Pas encore", target = target,
            )

            ForecastStatus.Provisional -> provisional(p, target, passed)

            ForecastStatus.Reached -> Headline(
                verdict = "Objectif atteint.",
                tone = Tone.Ok,
                sentence = (p.currentKg?.let { "Tu es à ${ReportFormat.one(it)} kg : ton objectif de $goal est atteint." } ?: "Ton objectif de $goal est atteint."),
                arrival = "Atteint", target = target,
            )

            ForecastStatus.Stalled -> Headline(
                verdict = "Poids stable.",
                tone = Tone.Warn,
                sentence = "Ton poids ne bouge presque plus : à ce rythme, tu n’atteins pas $goal.$passed",
                arrival = "Pas d’arrivée", target = target,
            )

            ForecastStatus.MovingAway -> Headline(
                verdict = "Tu t’éloignes de l’objectif.",
                tone = Tone.Warn,
                sentence = "Ton poids ${if (p.target.losing) "monte" else "baisse"} alors que tu vises $goal : à ce rythme, tu ne l’atteins pas.$passed",
                arrival = "Pas d’arrivée", target = target,
            )

            ForecastStatus.Measured -> measured(p, goal, target, passed)
        }
    }

    /**
     * Estimation théorique (pas assez de semaines pesées) : jamais une date ferme, ni verdict d'avance ou de retard, ni marge en jours.
     * Seulement l'ordre de grandeur **en mois** de la prévision, dit provisoire et à confirmer avec les pesées.
     */
    private fun provisional(p: ArrivalForecast, target: String, passed: String): Headline {
        val weeks = "Il faut au moins ${p.minWeighedWeeks} semaines pesées pour une date ferme (${p.weighedWeeks} pour l’instant)."
        val early = p.earliest ?: p.arrival
        val late = p.latest
        if (early == null) {
            return Headline(
                verdict = "Estimation.", tone = Tone.Neutral,
                sentence = "$weeks Pas de rythme estimable pour l’instant : pas de date.$passed",
                arrivalLabel = ESTIMATE_LABEL, arrival = "Pas encore", target = target,
            )
        }
        val first = monthLabel(early, p.asOf)
        val sentence: String
        val strip: String
        when {
            late == null -> { sentence = "pas avant $first, peut-être bien plus tard"; strip = "Pas avant $first" }
            monthLabel(late, p.asOf) == first -> { sentence = "vers $first"; strip = "Vers $first" }
            else -> { sentence = "entre $first et ${monthLabel(late, p.asOf)}"; strip = "$first – ${monthLabel(late, p.asOf)}" }
        }
        return Headline(
            verdict = "Estimation.", tone = Tone.Neutral,
            sentence = "$weeks Estimation provisoire : $sentence, à confirmer avec tes pesées.$passed",
            arrivalLabel = ESTIMATE_LABEL, arrival = strip, target = target,
        )
    }

    /** « février » ; l'année s'ajoute quand elle n'est pas celle du bilan. */
    private fun monthLabel(day: LocalDate, asOf: LocalDate): String {
        val month = ReportFormat.dayMonth(day.withDayOfMonth(2)).substringAfter(' ')
        return if (day.year == asOf.year) month else "$month ${day.year}"
    }

    private fun measured(p: ArrivalForecast, goal: String, target: String, passed: String): Headline {
        val arrival = p.arrival
        val verdict = p.verdict ?: ArrivalVerdict.Late
        if (arrival == null) {
            return Headline(
                verdict = "En retard.", tone = Tone.Warn,
                sentence = "À ce rythme, tu n’atteins pas $goal.$passed",
                arrival = "Pas d’arrivée", target = target,
            )
        }
        val day = dateLabel(arrival, p.asOf)
        val margin = ChronoUnit.DAYS.between(arrival, p.target.date).toInt()
        val days = abs(margin)
        val range = rangeLabel(p)
        val sentence: String
        val label: String
        val tone: Tone
        when (verdict) {
            ArrivalVerdict.Early -> {
                label = "En avance."; tone = Tone.Ok
                sentence = "À ce rythme, tu atteins $goal le $day" + if (margin > 0) ", ${plural(days, "jour")} avant le $target." else "."
            }
            ArrivalVerdict.LikelyOnTime -> {
                label = "Dans les temps."; tone = Tone.Ok
                sentence = "À ce rythme, tu atteins $goal vers le $day, à temps pour le $target, même si la fourchette reste large."
            }
            ArrivalVerdict.LikelyLate -> {
                label = "Plutôt en retard."; tone = Tone.Warn
                sentence = "À ce rythme, tu atteins $goal vers le $day, après le $target (${plural(days, "jour")} de plus), mais la marge permet encore d’arriver à temps."
            }
            ArrivalVerdict.Late -> {
                label = "En retard."; tone = Tone.Warn
                sentence = "À ce rythme, tu atteins $goal le $day, pas le $target : ${plural(days, "jour")} de plus."
            }
        }
        val late = margin < 0
        return Headline(
            verdict = label, tone = tone,
            sentence = sentence + passed, sentenceBold = day,
            arrival = day, target = target,
            margin = (if (margin > 0) "+" else if (margin < 0) "−" else "") + days,
            marginUnit = if (days > 1) "jours" else "jour",
            marginLate = late,
            range = range,
        )
    }

    /** « Fourchette : du 6 décembre au 20 février ». Omise quand la fin de la marge est à plus d'un an ou n'existe pas : une fourchette aussi large n'apprend rien. */
    private fun rangeLabel(p: ArrivalForecast): String? {
        val early = p.earliest ?: return null
        val late = p.latest ?: return null
        if (ChronoUnit.DAYS.between(p.asOf, late) > 365) return null
        return "Fourchette : du ${dateLabel(early, p.asOf)} au ${dateLabel(late, p.asOf)}."
    }

    // ───── Courbe du poids ─────

    private fun weight(f: WeeklyFigures): WeightSection {
        val p = f.forecast
        if (f.weightHistory.isEmpty() && p.currentKg == null) {
            return WeightSection(emptyNote = "Aucune pesée récente : donne-en une dans le chat du bilan pour voir ta courbe.")
        }
        val firm = p.status == ForecastStatus.Measured || p.status == ForecastStatus.Stalled || p.status == ForecastStatus.MovingAway
        val history = WeightHistory.of(emptyList(), f.weightHistory)
        val means = history.weeklyMeans(f.range, f.params.forecastWindowWeeks + 2)
        val you = means.map { XY(it.range.start.plusDays(3).toEpochDay(), it.averageKg) }.toMutableList()
        // La courbe « toi » rejoint le poids du jour, là où partent « prévu » et « à ce rythme ».
        val currentKg = p.currentKg
        if (currentKg != null) {
            val day = (p.currentKgDay ?: p.asOf).toEpochDay()
            if (you.none { it.day == day }) you += XY(day, currentKg)
        }
        val projection = if (firm) curve(p.expectedCurve) else emptyList()
        // « À ce rythme » est jaune quand le verdict l'est : en retard (même sans date d'arrivée), poids stable, poids qui s'éloigne.
        val warn = p.status == ForecastStatus.Stalled || p.status == ForecastStatus.MovingAway ||
            p.verdict == ArrivalVerdict.LikelyLate || p.verdict == ArrivalVerdict.Late
        val arrival = if (p.status == ForecastStatus.Measured) p.arrival?.let { XY(it.toEpochDay(), p.target.kg) } else null
        return WeightSection(
            chart = WeightChart(
                today = p.asOf.toEpochDay(),
                targetDay = p.target.date.toEpochDay(),
                targetKg = p.target.kg,
                you = you.sortedBy { it.day },
                weighIns = f.weightHistory.map { XY(it.day.toEpochDay(), it.kg) },
                planned = if (p.status == ForecastStatus.Reached) emptyList() else curve(p.onTimeCurve),
                projection = projection,
                arrival = arrival,
                late = firm && warn,
                currentKg = currentKg,
            ),
        )
    }

    private fun curve(points: List<CurvePoint>) = points.map { XY(it.day.toEpochDay(), it.kg) }

    // ───── Points de la période ─────

    internal fun points(f: WeeklyFigures): List<Point> {
        val all = listOfNotNull(rhythm(f), steps(f), calories(f), protein(f), fat(f), sessions(f), regularity(f), weighIns(f))
        // Ce qui coince d'abord, puis ce qui s'explique ; le reste garde l'ordre fixe.
        return all.sortedBy { when (it.status) { PointStatus.Warn -> 0; PointStatus.Info -> 1; else -> 2 } }
    }

    private fun shown(f: WeeklyFigures): List<DayFigure> = f.daily.takeLast(CHART_MAX_DAYS)

    private fun dayLabels(days: List<DayFigure>, period: Int): List<String> =
        days.map { if (period <= 7) ReportFormat.weekdayName(it.day).first().uppercase() else it.day.dayOfMonth.toString() }

    /** « samedi », ou « samedi 7 » quand la période dépasse 7 jours (le même jour de semaine y revient). */
    private fun dayName(day: LocalDate, f: WeeklyFigures): String =
        ReportFormat.weekdayName(day) + if (f.range.days.size > 7) " ${day.dayOfMonth}" else ""

    private fun enumerateDays(days: List<LocalDate>, f: WeeklyFigures, suffix: (Int) -> String = { "" }): String =
        enumerate(days.mapIndexed { i, d -> dayName(d, f).let { if (i == 0) it.replaceFirstChar { c -> c.uppercase() } else it } + suffix(i) })

    private fun steps(f: WeeklyFigures): Point {
        val a = f.activity
        val average = a.averageSteps ?: return Point("steps", "Pas", PointStatus.Neutral, "non fournis", "—")
        val reference = f.params.referenceSteps
        val value = ReportFormat.int(average)
        val habitual = "tes ${ReportFormat.int(reference)} habituels"
        val status = when {
            average >= reference * STEPS_TOP_ABOVE -> PointStatus.Top
            average >= reference * STEPS_WARN_BELOW -> PointStatus.Ok
            else -> PointStatus.Warn
        }
        if (status != PointStatus.Warn) {
            return Point("steps", "Pas", status, if (status == PointStatus.Top) "au-dessus de $habitual" else "comme d’habitude", value, "/j")
        }
        val adjustment = f.expenditure.stepsAdjustmentKcal?.takeIf { it < 0 }
        val chart = if (a.stepsSource == StepsSource.PerDay && a.stepsByDay.isNotEmpty()) {
            val byDay = a.stepsByDay.associate { it.day to it.steps.toDouble() }
            val days = shown(f)
            val values = days.map { byDay[it.day] }
            val low = values.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index
            PointChart(
                kind = ChartKind.Bars, title = "Pas par jour",
                labels = dayLabels(days, f.range.days.size), values = values,
                refs = listOf(ChartRef(reference, ReportFormat.int(reference))),
                valueLabels = values.indices.map { if (it == low) ReportFormat.int(values[it]!!) else null },
            )
        } else {
            null
        }
        return Point(
            "steps", "Pas", PointStatus.Warn, "${ReportFormat.int(reference - average)} sous $habitual", value, "/j",
            detail = adjustment?.let { "Environ ${ReportFormat.int(abs(it))} kcal de dépense en moins chaque jour." },
            chart = chart,
        )
    }

    private fun calories(f: WeeklyFigures): Point {
        val counted = f.coverage.countedDays
        val average = f.intake.averageKcal
        val gap = f.goal.averageDifferenceKcal
        val target = f.goal.averageTargetKcal
        if (counted == 0 || average == null) return Point("kcal", "Calories", PointStatus.Neutral, "aucun jour complet", "—")
        if (gap == null || target == null) return Point("kcal", "Calories", PointStatus.Neutral, "aucun objectif à comparer", ReportFormat.int(average), "kcal/j")
        // Vers une perte, manger au-dessus de l'objectif coince ; vers une prise, c'est manger en dessous.
        val losing = f.forecast.target.losing
        val adverse = if (losing) gap else -gap
        if (adverse <= target * KCAL_WARN_ABOVE) {
            val desc = if (abs(gap) < 15) "pile sur ton objectif"
            else if (gap < 0) "${ReportFormat.int(abs(gap))} sous ton objectif en moyenne" else "${ReportFormat.int(gap)} au-dessus de ton objectif en moyenne"
            return Point("kcal", "Calories", PointStatus.Ok, desc, ReportFormat.int(average), "kcal/j")
        }
        val days = shown(f)
        val gaps = days.map { d -> if (d.counted && d.kcal != null && d.goalKcal != null) d.kcal!! - d.goalKcal!! else null }
        val sign = if (losing) 1.0 else -1.0
        val over = days.indices.filter { (gaps[it] ?: 0.0) * sign >= maxOf(KCAL_DAY_MIN_KCAL, KCAL_DAY_SHARE * (days[it].goalKcal ?: target)) }
            .sortedByDescending { (gaps[it] ?: 0.0) * sign }.take(3).sorted()
        val detail = if (over.isEmpty()) {
            "Un peu ${if (losing) "au-dessus de" else "sous"} l’objectif presque chaque jour."
        } else {
            "${enumerateDays(over.map { days[it].day }, f) { i -> " " + ReportFormat.signedInt(gaps[over[i]]!!) }}. " + if (over.size < counted) "Le reste de la période est proche de l’objectif." else ""
        }.trim()
        // L'écart moyen porte sur les jours avec objectif : le total aussi, et il le dit s'il en manque.
        val withGoal = f.goal.daysWithGoal
        val scope = if (withGoal < counted) "sur les $withGoal ${if (withGoal > 1) "jours" else "jour"} avec objectif" else "sur la période"
        return Point(
            "kcal", "Calories", PointStatus.Warn,
            "$scope${if (over.isNotEmpty()) ", surtout les jours cités" else ""}",
            ReportFormat.signedInt(gap * withGoal), "kcal",
            detail = detail,
            chart = PointChart(
                kind = ChartKind.Gap, title = "Écart à ton objectif, par jour",
                labels = dayLabels(days, f.range.days.size), values = gaps,
                refs = listOf(ChartRef(0.0, ReportFormat.int(target))),
                flagged = gaps.map { (it ?: 0.0) * sign > 0 },
                valueLabels = gaps.map { if ((it ?: 0.0) * sign >= 200) ReportFormat.signedInt(it!!) else null },
            ),
        )
    }

    private fun protein(f: WeeklyFigures): Point? {
        val days = shown(f)
        val known = f.daily.filter { it.proteinG != null }
        if (known.isEmpty()) {
            return if (f.coverage.countedDays > 0) Point("protein", "Protéines", PointStatus.Neutral, "macros inconnues", "—") else null
        }
        val withMin = known.filter { it.proteinMinG != null }
        if (withMin.isEmpty()) {
            val average = known.sumOf { it.proteinG!! } / known.size
            return Point("protein", "Protéines", PointStatus.Neutral, "aucun minimum connu", ReportFormat.int(average), "g/j")
        }
        val kept = withMin.count { it.proteinG!! >= it.proteinMinG!! - MIN_TOLERANCE_G }
        if (kept == withMin.size) {
            return Point("protein", "Protéines", PointStatus.Ok, "minimum tenu ${if (withMin.size == 1) "ce jour" else "tous les jours"}", "$kept/${withMin.size}", "jours")
        }
        val under = withMin.filter { it.proteinG!! < it.proteinMinG!! - MIN_TOLERANCE_G }
        val shortBy = under.map { it.proteinMinG!! - it.proteinG!! }
        val range = if (shortBy.size == 1 || ReportFormat.int(shortBy.min()) == ReportFormat.int(shortBy.max())) "de ${ReportFormat.int(shortBy.max())} g" else "de ${ReportFormat.int(shortBy.min())} à ${ReportFormat.int(shortBy.max())} g"
        val values = days.map { it.proteinG }
        val ideal = days.lastOrNull { it.proteinGoalG != null }?.proteinGoalG
        val minRef = days.lastOrNull { it.proteinMinG != null }?.proteinMinG ?: under.last().proteinMinG!!
        // Chaque jour est jugé sur son propre minimum. S'il a changé pendant la période (poids de référence mis
        // à jour), le texte donne le minimum de ces jours-là et le repère du graphique, qui ne peut en porter qu'un, dit « actuel ».
        val minima = (withMin + days.filter { it.proteinMinG != null }).map { ReportFormat.int(it.proteinMinG!!) }.distinct()
        val underMinima = under.map { it.proteinMinG!! }
        val minimumText = when {
            minima.size == 1 -> "ton minimum de ${ReportFormat.int(underMinima.first())} g"
            ReportFormat.int(underMinima.min()) == ReportFormat.int(underMinima.max()) -> "ton minimum de ces jours-là, ${ReportFormat.int(underMinima.first())} g"
            else -> "ton minimum de chaque jour, de ${ReportFormat.int(underMinima.min())} à ${ReportFormat.int(underMinima.max())} g"
        }
        val minLabel = if (minima.size == 1) "min ${ReportFormat.int(minRef)}" else "min actuel ${ReportFormat.int(minRef)}"
        return Point(
            "protein", "Protéines", PointStatus.Warn, "minimum tenu $kept ${if (kept > 1) "jours" else "jour"} sur ${withMin.size}", "$kept/${withMin.size}", "jours",
            detail = "${enumerateDays(under.map { it.day }, f)} : sous $minimumText ($range).",
            chart = PointChart(
                kind = ChartKind.Dots, title = "Protéines par jour, en grammes",
                labels = dayLabels(days, f.range.days.size), values = values,
                refs = listOfNotNull(ChartRef(minRef, minLabel), ideal?.takeIf { it > minRef }?.let { ChartRef(it, "idéal ${ReportFormat.int(it)}") }),
                flagged = days.map { it.proteinG != null && it.proteinMinG != null && it.proteinG!! < it.proteinMinG!! - MIN_TOLERANCE_G },
                valueLabels = days.map { d -> d.proteinG?.takeIf { d.proteinMinG != null && it < d.proteinMinG!! - MIN_TOLERANCE_G }?.let { ReportFormat.int(it) } },
            ),
        )
    }

    private fun fat(f: WeeklyFigures): Point? {
        val average = f.intake.averageFatG ?: return null
        val limit = f.goal.fat.averageTargetG ?: return Point("fat", "Lipides", PointStatus.Neutral, "aucune limite connue", ReportFormat.int(average), "g/j")
        return if (average <= limit + 1.0) {
            Point("fat", "Lipides", PointStatus.Ok, "sous ta limite de ${ReportFormat.int(limit)} g", ReportFormat.int(average), "g/j")
        } else {
            Point(
                "fat", "Lipides", PointStatus.Warn, "au-dessus de ta limite de ${ReportFormat.int(limit)} g", ReportFormat.int(average), "g/j",
                detail = "${ReportFormat.int(average - limit)} g de plus que ta limite en moyenne.",
            )
        }
    }

    private fun sessions(f: WeeklyFigures): Point {
        val total = f.activity.sessionsTotal ?: return Point("sessions", "Séances", PointStatus.Neutral, "non fournies", "—")
        val expected = (floor(f.params.referenceSessionsPerWeek) * f.range.days.size / 7.0).roundToInt()
        if (expected == 0) return Point("sessions", "Séances", PointStatus.Neutral, "sur la période", "$total")
        return if (total >= expected) {
            Point("sessions", "Séances", PointStatus.Ok, if (total == expected) "toutes faites" else "plus que d’habitude", "$total/$expected")
        } else {
            Point("sessions", "Séances", PointStatus.Neutral, "${expected - total} de moins que d’habitude", "$total/$expected")
        }
    }

    private fun regularity(f: WeeklyFigures): Point {
        val days = f.range.days.size
        val counted = f.coverage.countedDays
        return if (counted == days) {
            Point("days", "Repas notés", PointStatus.Ok, "aucun jour oublié", "$counted/$days", "jours")
        } else {
            Point("days", "Repas notés", PointStatus.Neutral, "les autres jours sont exclus des moyennes", "$counted/$days", "jours")
        }
    }

    private fun weighIns(f: WeeklyFigures): Point {
        val count = f.weighIns.size
        return when (f.trajectory.reliability) {
            WeighInReliability.None -> Point("weighins", "Pesées", PointStatus.Neutral, "aucune sur la période", "—")
            WeighInReliability.Single, WeighInReliability.Few -> Point("weighins", "Pesées", PointStatus.Neutral, "peu de pesées : un ordre de grandeur", "$count")
            WeighInReliability.Enough -> Point("weighins", "Pesées", PointStatus.Ok, "assez pour une tendance fiable", "$count")
        }
    }

    // ───── Ralentissement de la perte : une explication, jamais une alerte ─────

    /**
     * La perte observée par semaine, au début de la fenêtre puis récemment (`ObservedPaces`, dans `:core`), et le ralentissement
     * que le modèle de prévision attend entre les deux. Le point n'existe que si la perte a vraiment baissé **et** que cette baisse est
     * celle du modèle ; sinon rien : une perte régulière n'a rien à expliquer, et une perte qui s'effondre n'est pas « prévue » (la
     * prévision la traite à part, en poids stable). Statut [PointStatus.Info] quoi qu'il arrive, même si la date est en retard : le
     * retard se lit dans le verdict, pas dans ce point.
     */
    private fun rhythm(f: WeeklyFigures): Point? {
        val p = f.forecast
        if (p.status != ForecastStatus.Measured) return null
        val losing = p.target.losing
        val pace = ObservedPaces.compute(f.weightHistory, f.range.end, losing, f.params) ?: return null
        if (!pace.slowdownExpected) return null
        val month = ReportFormat.dayMonth(pace.earlyFrom).substringAfter(' ')
        val chart = PointChart(
            kind = ChartKind.Weekly, title = "${if (losing) "Perte" else "Prise"} par semaine",
            labels = listOf(month, "cette période"),
            values = listOf(pace.earlyKgPerWeek, pace.recentKgPerWeek),
            valueLabels = listOf(ReportFormat.two(pace.earlyKgPerWeek), ReportFormat.two(pace.recentKgPerWeek)),
            legend = listOf("toi, kg par semaine"),
        )
        return Point(
            "rhythm", "Rythme", PointStatus.Info, "contre ${ReportFormat.two(pace.earlyKgPerWeek)} en $month", ReportFormat.two(pace.recentKgPerWeek), "kg/sem",
            detail = (if (losing) "Plus tu maigris, moins tu dépenses : ta perte ralentit d’elle-même, et c’est ce que prévoit ta courbe." else "Plus tu prends, plus tu dépenses : ta prise ralentit d’elle-même, et c’est ce que prévoit ta courbe.") +
                " Ce n’est pas une alerte, rien à changer.",
            chart = chart,
        )
    }

    // ───── Petits outils de texte ─────

    private const val MIN_TOLERANCE_G = 0.5
    private const val ESTIMATE_LABEL = "ESTIMATION PROVISOIRE"

    private fun kgLabel(kg: Double) = if (kg == kg.toLong().toDouble()) kg.toLong().toString() else ReportFormat.one(kg)

    /** « 13 mars », avec l'année quand elle n'est pas évidente (au-delà de 11 mois de la date du bilan). */
    private fun dateLabel(day: LocalDate, asOf: LocalDate): String =
        if (abs(ChronoUnit.DAYS.between(asOf, day)) > 330) ReportFormat.dayMonthYear(day) else ReportFormat.dayMonth(day)

    private fun enumerate(items: List<String>) = if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " et " + items.last()

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n > 1) many else one}"
}
