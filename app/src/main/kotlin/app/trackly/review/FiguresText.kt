package app.trackly.review

import app.trackly.core.forecast.ArrivalForecast
import app.trackly.core.forecast.ArrivalVerdict
import app.trackly.core.forecast.ForecastStatus
import app.trackly.core.forecast.PaceSource
import app.trackly.core.review.CatchUpResult
import app.trackly.core.review.NoAdviceReason
import app.trackly.core.review.WeighInReliability
import app.trackly.core.review.WeightBasis
import app.trackly.core.review.StepsSource
import app.trackly.core.review.TrajectoryStatus
import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeeklyFigures

/**
 * Les chiffres de la période en texte pour le modèle : calculés par le moteur, qu'il cite sans
 * jamais les recalculer. Même source que le rapport HTML.
 */
object FiguresText {
    fun render(f: WeeklyFigures): String = buildString {
        val i = f.intake
        val c = f.coverage
        appendLine("Régularité de saisie : ${c.countedDays} jour(s) saisi(s) sur ${f.range.days.size} (dont ${c.estimated.size} estimé(s)), ${c.bounded.size} à minimum, ${c.partial.size} partiel(s), ${c.missing.size} non saisi(s). Un jour non saisi n'est pas un jour à 0 kcal.")
        if (i.averageKcal == null) {
            appendLine("Calories moyennes : indisponibles (aucun jour complet).")
        } else {
            appendLine("Calories moyennes : ${ReportFormat.int(i.averageKcal!!)} kcal par jour sur ${c.countedDays} jour(s).")
        }
        if (i.macroDays > 0) {
            appendLine(
                "Macros moyennes (sur ${i.macroDays} jour(s) au détail complet) : protéines ${ReportFormat.int(i.averageProteinG!!)} g, " +
                    "glucides ${ReportFormat.int(i.averageCarbsG!!)} g, lipides ${ReportFormat.int(i.averageFatG!!)} g.",
            )
        } else if (c.countedDays > 0) {
            appendLine("Macros moyennes : inconnues.")
        }
        val g = f.goal
        if (g.averageDifferenceKcal == null) {
            val week = g.weekAverageTargetKcal
            if (week == null) {
                appendLine("Objectif calorique : aucun en vigueur sur ces jours.")
            } else {
                appendLine(
                    "Objectif calorique : ${ReportFormat.int(week)} kcal par jour${if (g.goalChanged) " en moyenne" else ""} en vigueur sur la période, mais aucun jour complet à lui comparer : " +
                        "écart indisponible. Ne dis jamais qu'il n'y avait pas d'objectif.",
                )
            }
        } else {
            appendLine(
                "Objectif calorique${if (g.goalChanged) " (a changé dans la période, moyenne)" else ""} : ${ReportFormat.int(g.averageTargetKcal!!)} kcal par jour ; " +
                    "écart moyen ${ReportFormat.signedInt(g.averageDifferenceKcal!!)} kcal par jour (positif : au-dessus).",
            )
        }
        for ((name, goal) in listOf("protéines" to g.protein, "glucides" to g.carbs, "lipides" to g.fat)) {
            if (goal.averageTargetG != null && goal.averageDifferenceG != null) {
                appendLine("Objectif de $name : ${ReportFormat.int(goal.averageTargetG!!)} g par jour ; écart moyen ${ReportFormat.signedInt(goal.averageDifferenceG!!)} g.")
            }
        }
        val a = f.activity
        appendLine(
            when (a.stepsSource) {
                StepsSource.PerDay -> "Pas : ${ReportFormat.int(a.averageSteps!!)} par jour en moyenne, donnés pour ${a.stepsDays} jour(s)."
                StepsSource.Average -> "Pas : ${ReportFormat.int(a.averageSteps!!)} par jour (moyenne donnée)."
                StepsSource.Unknown -> "Pas : non fournis."
            },
        )
        appendLine(
            a.sessions.let { s ->
                when {
                    s == null -> "Séances : non fournies."
                    s.isEmpty() -> "Séances : aucune sur la période."
                    else -> "Séances : ${s.joinToString(", ") { "${it.count} ${it.type}" }} (${a.sessionsTotal} au total)."
                }
            },
        )
        val e = f.expenditure
        appendLine(
            "Dépense estimée : ${ReportFormat.approxRange(e.kcalPerDay, e.lowKcal, e.highKcal)} (référence ${ReportFormat.int(e.referenceKcal)}" +
                (e.stepsAdjustmentKcal?.let { ", pas ${ReportFormat.signedInt(it)}" } ?: ", pas non ajusté") +
                (e.sessionsAdjustmentKcal?.let { ", séances ${ReportFormat.signedInt(it)}" } ?: ", séances non ajustées") +
                "). C'est une estimation fondée sur des hypothèses, pas une mesure.",
        )
        appendLine(
            "Poids utilisé pour le coût des pas : ${ReportFormat.one(e.weightKg)} kg (" + when (e.weightBasis) {
                WeightBasis.WeekAverage -> "moyenne de la période"
                WeightBasis.LastKnown -> "dernier poids connu, aucune pesée sur la période"
                WeightBasis.Default -> "valeur de travail, ce n'est PAS le poids de l'utilisateur : ne la cite jamais comme son poids"
            } + ").",
        )
        f.balance?.let {
            appendLine(
                "Bilan énergétique théorique : ${if (it.deficitKcal >= 0) "déficit" else "surplus"} de ${ReportFormat.int(kotlin.math.abs(it.deficitKcal))} kcal " +
                    "sur ${c.countedDays} jour(s), soit environ ${ReportFormat.two(kotlin.math.abs(it.theoreticalChangeKg))} kg de ${if (it.theoreticalChangeKg <= 0) "perte" else "prise"} théorique " +
                    "(à ${ReportFormat.two(it.changeUncertaintyKg)} kg près).",
            )
        } ?: appendLine("Bilan énergétique théorique : indisponible.")
        val t = f.trajectory
        if (t.status == TrajectoryStatus.Unknown) {
            appendLine("Trajectoire vers ${ReportFormat.one(t.targetKg)} kg au ${ReportFormat.dayMonthYear(t.targetDate)} : INCONNUE sur la période (aucune pesée).")
        } else {
            appendLine("Pesées de la période : ${f.weighIns.joinToString(", ") { "${ReportFormat.dayMonth(it.day)} ${ReportFormat.one(it.kg)} kg" }}.")
            appendLine(
                "Poids de la période (moyenne de ${t.weighInCount} pesée(s)) : ${ReportFormat.one(t.weekAverageKg!!)} kg" +
                    when (t.reliability) {
                        WeighInReliability.Single -> " : UNE SEULE pesée, peu fiable, à présenter comme un ordre de grandeur."
                        WeighInReliability.Few -> " : peu de pesées, moyenne indicative (3 pesées du matin ou plus la rendent plus fiable)."
                        else -> "."
                    } + " Objectif : ${ReportFormat.one(t.targetKg)} kg au ${ReportFormat.dayMonthYear(t.targetDate)}.",
            )
            t.requiredKgPerWeek?.let { appendLine("Rythme nécessaire : ${ReportFormat.signedTwo(it)} kg par semaine (${ReportFormat.one(t.weeksRemaining)} semaines restantes).") }
            val trend = t.trend
            if (trend != null) {
                appendLine(
                    "Tendance sur ${t.weighedWeeks} semaines pesées : ${ReportFormat.signedTwo(trend.slopeKgPerWeek)} kg par semaine, fourchette ${ReportFormat.rateRange(trend.lowKgPerWeek, trend.highKgPerWeek)}.",
                )
            } else if (!measured(f.forecast)) {
                appendLine("Tendance : non calculée (${t.weighedWeeks} semaine(s) pesée(s) sur les dernières, il en faut au moins ${t.trendMinWeeks}).")
            }
            t.theoreticalKgPerWeek?.let { appendLine("Indicatif seulement : le bilan énergétique théorique donne ${ReportFormat.signedTwo(it)} kg par semaine (ne décide pas du statut).") }
            // Avec une prévision mesurée, son verdict remplace l'ancien statut en ligne droite (qui ignore le ralentissement).
            if (!measured(f.forecast)) appendLine("Statut de la trajectoire : ${statusLabel(t.status)}.")
        }
        append(dayByDay(f))
        appendLine(forecast(f.forecast))
        val cu = f.cumulative
        if (cu.countedDays > 0 && cu.from != null) {
            appendLine(
                "Cumul depuis le ${ReportFormat.dayMonthYear(cu.from!!)} : ${cu.countedDays} jour(s) comptés, moyenne ${ReportFormat.int(cu.averageKcal!!)} kcal par jour" +
                    (cu.theoreticalLossKg?.let { ", perte théorique ${ReportFormat.two(it)} kg (dépense de référence non ajustée)" } ?: ", perte théorique indisponible") + ".",
            )
        }
        if (f.missing.isEmpty()) {
            appendLine("Informations manquantes : aucune.")
        } else {
            appendLine("Informations manquantes (à signaler, jamais à combler) :")
            f.missing.forEach { appendLine("- ${it.message}") }
        }
    }.trimEnd()

    /**
     * Prévision d'arrivée en texte pour le modèle : la date, sa marge et son statut viennent de l'application. Le
     * ralentissement attendu y est dit comme normal, pour que le modèle n'en tire jamais un conseil de manger moins
     * ou de bouger plus.
     */
    fun forecast(p: ArrivalForecast): String = buildString {
        val target = "${ReportFormat.one(p.target.kg)} kg au ${ReportFormat.dayMonthYear(p.target.date)}"
        appendLine("Prévision d'arrivée (calculée par l'application, à citer telle quelle, jamais à recalculer) : objectif $target.")
        p.currentKg?.let { kg ->
            val day = p.currentKgDay ?: p.asOf
            appendLine(if (day == p.asOf) "Poids estimé au ${ReportFormat.dayMonthYear(day)} : ${ReportFormat.one(kg)} kg." else "Dernier poids connu (semaine du ${ReportFormat.dayMonthYear(day)}) : ${ReportFormat.one(kg)} kg.")
        }
        when (p.status) {
            ForecastStatus.NoWeighIn -> appendLine("Aucune pesée récente : pas de date d'arrivée. N'en invente aucune.")
            ForecastStatus.Reached -> appendLine("Objectif atteint.")
            ForecastStatus.Provisional -> {
                appendLine(
                    "Pas encore assez de pesées (${p.weighedWeeks} semaine(s) pesée(s) sur les dernières, il en faut ${p.minWeighedWeeks}) : " +
                        "aucune date ferme, aucun verdict d'avance ou de retard.",
                )
                if (p.arrival != null || p.earliest != null) {
                    appendLine(
                        "Ordre de grandeur théorique (${if (p.paceSource == PaceSource.MealsAndReference) "référence moins les repas comptés" else "référence moins l'objectif calorique"}) : " +
                            "${months(p)}. Ce n'est pas une date : ne cite aucun jour précis.",
                    )
                } else {
                    appendLine("Pas de rythme estimable : ne donne aucune date.")
                }
            }
            ForecastStatus.Measured, ForecastStatus.Stalled, ForecastStatus.MovingAway -> {
                appendLine(
                    "Rythme mesuré sur les pesées : ${ReportFormat.signedTwo(p.paceKgPerWeek!!)} kg par semaine aujourd'hui " +
                        "(fourchette ${ReportFormat.rateRange(p.paceLowKgPerWeek!!, p.paceHighKgPerWeek!!)}).",
                )
                appendLine("Date d'arrivée estimée : ${dates(p)}.")
                p.verdict?.let { appendLine("Verdict : ${verdictLabel(it)}.") }
                if (p.status == ForecastStatus.Stalled) appendLine("Poids stable sur les dernières semaines, mesuré avec assez de précision pour le dire.")
                if (p.status == ForecastStatus.MovingAway) appendLine("Le poids s'éloigne de l'objectif, au-delà du bruit des pesées.")
            }
        }
        if (p.targetDatePassed) appendLine("La date visée est passée.")
        p.requiredKgPerWeek?.let { appendLine("Rythme qu'il faudrait aujourd'hui pour arriver pile à la date visée : ${ReportFormat.signedTwo(it)} kg par semaine (ralentissement compris).") }
        p.plateauKg?.takeIf { p.arrival == null && p.paceSource != PaceSource.WeighIns }?.let {
            val change = if (p.target.losing) "la perte" else "la prise"
            val intake = if (p.paceSource == PaceSource.CalorieGoalAndReference) "À l'objectif calorique actuel" else "Aux apports actuels"
            appendLine("$intake, $change s'arrêterait vers ${ReportFormat.one(it)} kg, avant l'objectif.")
        }
        p.measuredExpenditure?.let {
            appendLine(
                "Dépense déduite des pesées et des repas réels (${it.countedDays} jours comptés) : environ ${ReportFormat.int(it.kcalPerDay)} kcal par jour " +
                    "(± ${ReportFormat.int(it.uncertaintyKcal)}), contre ${ReportFormat.int(it.modelKcalPerDay)} kcal pour la référence à ce poids.",
            )
        }
        if (p.paceAtArrivalKgPerWeek != null && p.paceKgPerWeek != null) {
            appendLine(
                "Ralentissement attendu : à repas égaux, le rythme passe d'environ ${ReportFormat.two(kotlin.math.abs(p.paceKgPerWeek!!))} à " +
                    "${ReportFormat.two(kotlin.math.abs(p.paceAtArrivalKgPerWeek!!))} kg par semaine d'ici l'arrivée.",
            )
        }
        appendLine(
            "Une perte qui ralentit à mesure que le poids baisse est NORMALE : la dépense baisse avec le poids, c'est déjà compté dans la date. " +
                "Ne présente jamais ce ralentissement comme une stagnation ou un problème, et n'en tire jamais un conseil de manger moins ou de faire plus de sport.",
        )
        append("Hypothèses : " + p.assumptions.joinToString(" "))
    }

    /**
     * Conseils chiffrés pour le modèle : calculés par l'application, qu'il cite ou choisit sans en inventer. Sans
     * conseil, la raison est dite, avec l'interdiction de proposer de lui-même de manger moins ou de bouger plus.
     */
    fun advice(result: CatchUpResult): String = buildString {
        appendLine("Conseils chiffrés (calculés par l'application ; tu les cites ou les choisis, tu n'inventes aucun chiffre, aucune durée, aucun nombre de pas) :")
        val none = "Ne propose ni de manger moins, ni de bouger plus, ni de changer d'objectif de toi-même."
        when (result) {
            is CatchUpResult.None -> append(
                when (result.reason) {
                    NoAdviceReason.OnTrack -> "Aucun conseil : la prévision (ralentissement attendu compris) arrive à temps, tout va bien. $none"
                    NoAdviceReason.NoFirmDate -> "Aucun conseil : pas de date ferme, on ne sait pas s'il y a du retard. $none"
                    NoAdviceReason.TargetDatePassed -> "Aucun conseil chiffré : la date visée est passée ; seul l'objectif de poids peut se rediscuter, s'il le demande."
                    NoAdviceReason.GainGoal -> "Aucun conseil chiffré pour un objectif de prise de poids. $none"
                },
            )
            is CatchUpResult.Advice -> {
                val over = result.overshootKcal
                appendLine(
                    if (over == null) "Écart à l'objectif sur la période : inconnu (aucun jour compté avec un objectif)."
                    else "Écart aux objectifs sur la période : ${ReportFormat.signedInt(over)} kcal sur ${result.countedDays} jour(s) compté(s).",
                )
                val plan = result.plan
                if (result.inProgress) {
                    appendLine("Un objectif temporaire est déjà prévu ou en cours : le dépassement est déjà rattrapé. Ne propose ni autre baisse, ni pas en plus.")
                } else if (plan == null && result.steps == null) {
                    appendLine("Pas de dépassement à rattraper${result.planBlocked?.let { " ($it)" }.orEmpty()} : le retard vient du rythme, pas des repas. $none")
                }
                plan?.let {
                    appendLine(
                        "Objectif temporaire possible (outil propose_temporary_goal, qui affiche une carte avec un bouton ; rien ne change sans ce bouton) : " +
                            "${ReportFormat.int(it.temporaryKcal)} kcal du ${it.start} au ${it.end} (${ReportFormat.signedInt(-it.dropKcal)} par jour), " +
                            "retour à ${ReportFormat.int(it.baseKcal)} le ${it.returnOn}, tout seul. L'utilisateur peut préférer une autre durée (1 à 7 jours) : passe-la en `days`, l'application recalcule la baisse.",
                    )
                }
                if (plan == null && !result.inProgress && result.planBlocked != null && result.steps != null) appendLine("Pas d'objectif temporaire possible : ${result.planBlocked}.")
                result.steps?.let {
                    appendLine(
                        "Équivalent en pas (une phrase, sans bouton ni outil) : environ +${ReportFormat.int(it.extraSteps.toDouble())} pas par jour pendant ${it.days} jour(s), " +
                            "à peu près ${ReportFormat.int(it.kcalPerDay)} kcal par jour au poids actuel de ${ReportFormat.one(it.weightKg)} kg.",
                    )
                }
                result.mix?.let {
                    appendLine(
                        "Mélange possible (propose_temporary_goal avec with_steps) : ${ReportFormat.signedInt(-it.plan.dropKcal)} kcal par jour et environ " +
                            "+${ReportFormat.int(it.steps.extraSteps.toDouble())} pas par jour pendant ${it.plan.days} jour(s).",
                    )
                }
                result.newDate?.let {
                    appendLine("Ou garder son rythme et viser le ${ReportFormat.dayMonthYear(it)} ($it) : seulement s'il le demande, avec set_weight_goal et cette date exacte.")
                }
                append("Un seul de ces conseils, présenté comme une option, jamais une obligation. Aucun autre conseil de baisse ou de sport.")
            }
        }
    }

    /**
     * Jour par jour : calories, pas et pesées de chaque jour, calculés par l'application, avec la veille de la période.
     * Une pesée du matin se lit avec les repas de la veille : le modèle peut faire le lien sans rien recalculer.
     */
    fun dayByDay(f: WeeklyFigures): String = buildString {
        if (f.daily.isEmpty()) return@buildString
        val steps = f.activity.stepsByDay.associate { it.day to it.steps }
        val weighIns = f.weighIns.groupBy { it.day }
        val bounded = f.coverage.bounded.associate { it.day to it.kcalMin }
        val shown = f.daily.takeLast(MAX_DAYS_SHOWN)
        // La veille du premier jour montré : celle de la période, ou le jour d'avant dans la période quand elle est longue.
        val eve = if (shown.size == f.daily.size) f.eve else f.daily[f.daily.size - shown.size - 1]
        appendLine(
            "Jour par jour (calculé par l'application ; une pesée du matin, à jeun, reflète surtout les repas des jours d'avant)" +
                (if (f.daily.size > shown.size) ", les $MAX_DAYS_SHOWN derniers jours" else "") + " :",
        )
        eve?.let { appendLine("- ${dayLabel(it.day)} (${if (it == f.eve) "veille de la période" else "veille du premier jour montré"}) : ${dayKcal(it, bounded[it.day])}") }
        for (d in shown) {
            // La pesée du matin d'abord : elle suit les repas de la veille (ligne précédente), pas ceux du jour.
            val parts = listOfNotNull(
                weighIns[d.day]?.let { w -> "pesée du matin ${w.joinToString(" puis ") { "${ReportFormat.one(it.kg)} kg" }}" },
                dayKcal(d, bounded[d.day]),
                steps[d.day]?.let { "${ReportFormat.int(it.toDouble())} pas" },
            )
            appendLine("- ${dayLabel(d.day)} : ${parts.joinToString(" ; ")}")
        }
    }

    private const val MAX_DAYS_SHOWN = 14

    private fun dayLabel(day: java.time.LocalDate) = "${ReportFormat.weekdayName(day)} ${ReportFormat.dayMonth(day)}"

    /** [boundMin] : un jour noté « au moins X kcal » (borne minimale, sans centre inventé). */
    private fun dayKcal(d: app.trackly.core.review.DayFigure, boundMin: Double? = null): String {
        val kcal = d.kcal?.takeIf { d.counted }
            ?: return boundMin?.let { "au moins ${ReportFormat.int(it)} kcal (borne minimale, hors moyennes)" } ?: "non saisi ou incomplet (hors moyennes)"
        val goal = d.goalKcal ?: return "${ReportFormat.int(kcal)} kcal"
        // L'écart se lit sur les valeurs affichées : 2 290 contre 2 300 fait −10, jamais −11.
        return "${ReportFormat.int(kcal)} kcal (objectif ${ReportFormat.int(goal)}, écart ${ReportFormat.signedInt((Math.round(kcal) - Math.round(goal)).toDouble())})"
    }

    private fun measured(p: ArrivalForecast) =
        p.status == ForecastStatus.Measured || p.status == ForecastStatus.Stalled || p.status == ForecastStatus.MovingAway

    /** Fourchette en mois, pour une estimation sans date ferme. */
    private fun months(p: ArrivalForecast): String {
        fun month(day: java.time.LocalDate) = ReportFormat.dayMonthYear(day.withDayOfMonth(2)).substringAfter(' ')
        val low = p.earliest ?: p.arrival
        val high = p.latest
        return when {
            low == null -> "pas d'arrivée à ce rythme"
            high == null -> "pas avant ${month(low)}, peut-être bien plus tard"
            month(low) == month(high) -> "vers ${month(low)}"
            else -> "entre ${month(low)} et ${month(high)}"
        }
    }

    private fun dates(p: ArrivalForecast): String {
        val central = p.arrival?.let { "vers le ${ReportFormat.dayMonthYear(it)}" } ?: "pas d'arrivée à ce rythme"
        val low = p.earliest?.let { "au plus tôt le ${ReportFormat.dayMonthYear(it)}" }
        val high = p.latest?.let { "au plus tard le ${ReportFormat.dayMonthYear(it)}" } ?: if (p.earliest != null) "sans date au plus tard (la fourchette permet de ne pas arriver)" else null
        return listOfNotNull(central, low, high).joinToString(", ")
    }

    private fun verdictLabel(v: ArrivalVerdict) = when (v) {
        ArrivalVerdict.Early -> "en avance ou à l'heure, même au rythme le moins favorable de la fourchette"
        ArrivalVerdict.LikelyOnTime -> "plutôt à temps, sans certitude (la fourchette déborde après la date visée)"
        ArrivalVerdict.LikelyLate -> "plutôt en retard, sans certitude (la fourchette permet encore d'arriver à temps)"
        ArrivalVerdict.Late -> "en retard, même au rythme le plus favorable de la fourchette"
    }

    private fun statusLabel(status: TrajectoryStatus) = when (status) {
        TrajectoryStatus.Unknown -> "inconnue"
        TrajectoryStatus.Reached -> "objectif atteint"
        TrajectoryStatus.PassedDate -> "date de l'objectif dépassée"
        TrajectoryStatus.TooEarly -> "trop tôt pour conclure (l'écart au rythme nécessaire ne dépasse pas le bruit des pesées)"
        TrajectoryStatus.OnTrack -> "dans le rythme"
        TrajectoryStatus.Slower -> "plus lent que le rythme nécessaire (l'écart dépasse le bruit des pesées)"
        TrajectoryStatus.Faster -> "plus rapide que le rythme nécessaire (l'écart dépasse le bruit des pesées)"
    }

    /** Ce que l'utilisateur a déjà donné pour la période (ligne par ligne). */
    fun declared(info: WeekInfo?): String {
        if (info == null) return "- rien n'a encore été donné pour cette période."
        val weigh = if (info.weighIns.isEmpty()) "aucune" else info.weighIns.joinToString(", ") { "${ReportFormat.weekdayName(it.day)} ${it.day} : ${ReportFormat.one(it.kg)} kg" }
        val steps = when {
            info.steps.isNotEmpty() -> info.steps.joinToString(", ") { "${ReportFormat.weekdayName(it.day)} ${it.day} : ${ReportFormat.int(it.steps.toDouble())}" }
            info.stepsAverage != null -> "moyenne ${ReportFormat.int(info.stepsAverage!!.toDouble())} par jour"
            else -> "non fournis"
        }
        val sessions = info.sessions.let { s ->
            when {
                s == null -> "non fournies"
                s.isEmpty() -> "aucune sur la période"
                else -> s.joinToString(", ") { "${it.count} ${it.type}" }
            }
        }
        return "- pesées : $weigh\n- pas : $steps\n- séances : $sessions"
    }
}
