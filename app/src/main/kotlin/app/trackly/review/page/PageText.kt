package app.trackly.review.page

/**
 * Ce que la page du bilan affiche, en texte pour le modèle : l'en-tête, la marge, les points et les conseils, tels que
 * [ReportPageBuilder] les construit. Le modèle écrit le mot de Trackly en accord avec la page, sans la recopier ni recalculer
 * une marge.
 */
object PageText {
    /** [reportDone] : un rapport existe déjà pour la période ; sa page est figée et peut différer de celle-ci. */
    fun forModel(page: ReportPage, reportDone: Boolean = false): String = buildString {
        appendLine(
            if (reportDone) {
                "Ce que la page du bilan affiche, recalculée maintenant (un rapport déjà produit garde sa page figée : si l'utilisateur cite ce qu'il y voit, ses chiffres font foi) :"
            } else {
                "Ce que la page du bilan affiche (construite par l'application ; ton mot s'affiche sous l'en-tête et la courbe, au-dessus des points) :"
            },
        )
        val h = page.headline
        appendLine("- En-tête : « ${h.verdict} » ${h.sentence}")
        margin(h)?.let { appendLine("- $it") }
        h.range?.let { appendLine("- $it") }
        if (page.points.isEmpty()) {
            appendLine("- Points de la période : aucun.")
        } else {
            appendLine("- Points de la période, dans l'ordre de la page :")
            page.points.forEach { p ->
                val value = listOf(p.value, p.unit).filter { it.isNotBlank() && it != "—" }.joinToString(" ")
                appendLine("  - ${p.name} (${label(p.status)}) : ${p.desc}${if (value.isNotEmpty()) ", $value" else ""}.${p.detail?.let { " $it" }.orEmpty()}")
            }
        }
        if (page.advice.isEmpty()) {
            append("- Conseils affichés : aucun.")
        } else {
            append("- Conseils affichés sous les points : " + page.advice.joinToString(" ; ") { a -> "${a.kicker} « ${a.title} » (${if (a.kind == AdviceKind.Steps) "une phrase, sans bouton" else "avec un bouton"})" } + ".")
        }
    }

    /**
     * La marge, dans le sens du verdict : « en avance » n'est écrit que sous le verdict « En avance. », pour que le modèle ne
     * promette pas d'avance quand la fourchette ne le permet pas.
     */
    private fun margin(h: Headline): String? {
        val m = h.margin ?: return null
        val days = m.trimStart('+', '−', '-')
        val unit = h.marginUnit.orEmpty()
        return when {
            days == "0" -> "Arrivée prévue pile à la date visée."
            h.marginLate -> "Marge affichée : $days $unit de retard sur la date visée."
            h.verdict == "En avance." -> "Marge affichée : $days $unit d'avance sur la date visée."
            else -> "Arrivée prévue $days $unit avant la date visée, mais le verdict reste « ${h.verdict.removeSuffix(".")} » : ne dis pas « en avance »."
        }
    }

    private fun label(status: PointStatus) = when (status) {
        PointStatus.Top -> "top"
        PointStatus.Ok -> "va bien"
        PointStatus.Warn -> "à regarder"
        PointStatus.Info -> "à savoir, ce n'est pas une alerte"
        PointStatus.Neutral -> "neutre"
    }
}
