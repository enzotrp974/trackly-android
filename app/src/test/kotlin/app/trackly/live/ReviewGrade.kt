package app.trackly.live

import app.trackly.review.page.PointStatus
import app.trackly.review.page.ReportPage
import java.math.BigDecimal

/**
 * Grille automatique du bilan : ce qui se vérifie sans juger le goût, sur le mot de Trackly (champ `analysis` de
 * `produce_weekly_report`) ou une réponse du chat du bilan. Le ton chaleureux et l'utilité réelle restent jugés à la main.
 *
 * Chaque critère renvoie des [Failure] lisibles ; une réponse qui n'en a aucune passe la partie automatique.
 * Les règles sont volontairement simples (expressions sur le texte), donc approximatives : elles visent les fautes nettes.
 */
object ReviewGrade {
    enum class Kind { Note, Reply }

    data class Failure(val criterion: String, val detail: String) {
        override fun toString() = "$criterion : $detail"
    }

    const val VERDICT = "verdict"
    const val NUMBERS = "chiffres"
    const val ADVICE = "conseil interdit"
    const val TONE = "ton"
    const val SHORT = "court"
    const val CONSISTENT = "contradiction"

    /** Plafonds de longueur : le mot tient sous la courbe, la réponse reste une réponse de chat. */
    const val NOTE_MAX_CHARS = 500
    const val NOTE_MAX_SENTENCES = 5
    const val NOTE_MAX_PARAGRAPHS = 2
    const val REPLY_MAX_CHARS = 400
    const val REPLY_MAX_SENTENCES = 3

    /** Émojis : aucune règle ferme ; seul un abus manifeste est refusé. */
    const val MAX_EMOJI = 3
    const val USEFUL = "utile"

    /**
     * [context] : tout ce que le modèle a reçu (contexte calculé et message de l'utilisateur) ; [page] : la page que l'app affiche (ou afficherait) pour la période,
     * qui fait foi pour le verdict, la marge et les points.
     */
    fun grade(text: String, kind: Kind, context: String, page: ReportPage): List<Failure> {
        val t = normalize(text)
        return verdict(t, page) + numbers(t, context, page) + advice(t, context, page) + tone(t, kind) + short(text, kind) + consistent(t, page)
    }

    // ───── Verdict ─────

    private val early = Regex("en avance|d'avance|plus tôt que prévu")
    private val late = Regex("en retard|de retard|plus tard que prévu")
    private val onTime = Regex("dans les temps|à temps|à l'heure|pile pour")

    private fun verdict(t: String, page: ReportPage): List<Failure> {
        val v = page.headline.verdict
        val out = mutableListOf<Failure>()
        fun forbid(r: Regex, why: String) = affirmed(t, r).forEach { out += Failure(VERDICT, "« $it » alors que la page dit « $v » ($why)") }
        when (v) {
            "En avance." -> forbid(late, "pas de retard")
            // « 5 jours d'avance » recopie la marge de la page ; « en avance » promettrait ce que la fourchette ne permet pas.
            "Dans les temps." -> { forbid(late, "pas de retard"); forbid(Regex("en avance"), "la marge ne permet pas de le promettre") }
            "Plutôt en retard." -> { forbid(early, "pas d'avance"); forbid(Regex("dans les temps|à l'heure"), "plutôt en retard") }
            "En retard." -> { forbid(early, "pas d'avance"); forbid(onTime, "en retard") }
            "Estimation.", "Pas de pesée." -> {
                forbid(early, "aucun verdict sans date ferme"); forbid(late, "aucun verdict sans date ferme"); forbid(onTime, "aucun verdict sans date ferme")
            }
            "Objectif atteint." -> forbid(late, "objectif atteint")
            // Sans date d'arrivée : ni avance, ni « à temps », ni « c'est normal, déjà compté dans la date ».
            "Poids stable.", "Tu t’éloignes de l’objectif.", "Tu t'éloignes de l'objectif." -> {
                forbid(early, "pas d'arrivée à ce rythme"); forbid(onTime, "pas d'arrivée à ce rythme")
                forbid(Regex("c'est normal|déjà compté|c'est prévu"), "il n'y a pas de date où ce serait compté")
                if (Regex("rien à changer").containsMatchIn(t)) out += Failure(VERDICT, "« rien à changer » alors que le poids n'avance plus vers l'objectif")
            }
        }
        if (v == "Estimation." || v == "Pas de pesée.") {
            // Les jours de la période et la date visée (« pour ton objectif du 1er mars ») restent permis.
            val allowed = periodDates(page) + dates(page.headline.target)
            dates(t).filter { it !in allowed }.forEach { out += Failure(VERDICT, "date précise « $it » sans date ferme") }
        }
        return out
    }

    // ───── Chiffres ─────

    private fun numbers(t: String, context: String, page: ReportPage): List<Failure> {
        val sources = normalize(isoDates(context) + "\n" + pageText(page))
        val known = numberTokens(sources).toSet()
        val knownDates = dates(sources).toSet() + periodDates(page)
        val out = mutableListOf<Failure>()
        numberTokens(spelled(t)).filter { it !in known }.distinct().forEach { out += Failure(NUMBERS, "« $it » n'est ni dans le contexte ni sur la page") }
        dates(t).filter { it !in knownDates }.distinct().forEach { out += Failure(NUMBERS, "date « $it » absente du contexte et de la page") }
        return out
    }

    // ───── Conseils ─────

    private val cut = Regex("(baiss|rédui|diminu|descend|\\bcoup|enlèv|enlev|retir)\\w*[^.!?]{0,40}(kcal|calorie|apport|objectif|portion|\\d)")
    private val eatLess = Regex("mange\\w*(?: un peu| encore| légèrement)? moins|moins manger")
    private val moreSport = Regex("(plus de|davantage de|rajout\\w*|ajout\\w*)[^.!?]{0,20}(sport|cardio|séance|pas\\b|marche|course)")
    private val moveMore = Regex("bouge\\w* plus|marche\\w* plus|\\+ ?\\d[\\d ]* pas")
    /** « Un peu plus de dépense » : seulement quand l'app affiche un conseil chiffré. */
    private val spendMore = Regex("plus d(e |')(dépense|activité|mouvement)|dépenser (un peu )?(plus|davantage)|(bouger|marcher) (un peu )?(plus|davantage)|plus actif")
    private val revise = Regex("(revoir|changer|modifier|ajuster|baisser)[^.!?]{0,25}(ton objectif|tes calories|tes kcal)")

    /** Jamais conseillé par l'app, quel que soit le scénario : le cardio, la course, une séance de plus. */
    private val cardio = Regex("cardio|courir|\\bcourse|footing|séances? (de plus|en plus|supplémentaires?)")
    private val problem = Regex("stagn|plateau|bloqué|ça coince|problème|inquiétant")

    private fun advice(t: String, context: String, page: ReportPage): List<Failure> {
        val none = listOf("Aucun conseil", "Pas de dépassement à rattraper", "Un objectif temporaire est déjà prévu ou en cours").any { context.contains(it) }
        val out = mutableListOf<Failure>()
        // Même avec un conseil de l'app (objectif temporaire, pas), « mange moins » et le cardio n'en font jamais partie.
        for (r in listOf(eatLess, cardio)) {
            affirmed(t, r).forEach { out += Failure(ADVICE, "« $it » : ce n'est pas un conseil de l'app") }
        }
        if (none) {
            for (r in listOf(cut, moreSport, moveMore, revise, spendMore)) {
                // « ta dépense baisse », « tes besoins en calories » : une explication, pas une baisse proposée.
                affirmed(t, r, exclude = if (r === cut) Regex("besoin|dépense") else null).forEach { out += Failure(ADVICE, "« $it » alors que l'app ne donne aucun conseil de baisse ni de sport") }
            }
            if (t.contains("objectif temporaire") && affirmed(t, Regex("objectif temporaire")).isNotEmpty()) out += Failure(ADVICE, "objectif temporaire proposé sans conseil de l'app")
        }
        if (page.headline.verdict != "Poids stable.") {
            affirmed(t, problem).forEach { out += Failure(ADVICE, "« $it » : un ralentissement prévu n'est ni une stagnation ni un problème") }
        }
        return out
    }

    // ───── Ton ─────

    private val familiar = Regex("\\b(tu|ton|ta|tes|toi|te)\\b|\\bt'")
    private val formal = Regex("(?<![-\\w])(vous|votre|vos)\\b")
    private val judging = Regex("dépass|\\braté|\\brater\\b|mauvais|échec|triche|craqu|culpabil|il faut que tu|tu dois|attention|oups|malheureusement|hélas|dommage|pas terrible|déçu|reproche")
    /** Un écart de repas en ouverture (« 400 kcal au-dessus », « sous ton minimum ») ; « tes pas au-dessus de tes habitudes » est un bon point. */
    private val gapFirst = Regex("(kcal|calories?)[^.!?]{0,20}au-dessus|au-dessus de (ton|l')objectif|en trop|sous (ton|le|tes) (minimum|objectif)|kcal de plus")

    private fun tone(t: String, kind: Kind): List<Failure> {
        val out = mutableListOf<Failure>()
        if (!familiar.containsMatchIn(t)) out += Failure(TONE, "pas de tutoiement")
        formal.find(t)?.let { out += Failure(TONE, "vouvoiement « ${it.value} »") }
        judging.findAll(t).forEach { out += Failure(TONE, "mot qui juge « ${it.value} »") }
        sentences(t).firstOrNull()?.let { first -> gapFirst.find(first)?.let { out += Failure(TONE, "commence par un écart (« ${it.value} »)") } }
        val emoji = emojiCount(t)
        if (emoji > MAX_EMOJI) out += Failure(TONE, "$emoji émojis : c'est trop")
        return out
    }

    // ───── Court ─────

    private val markup = Regex("(?m)^\\s*([-*•]|\\d+[.)])\\s|\\*\\*|__|(?m)^#|<[a-z/]")

    private fun short(text: String, kind: Kind): List<Failure> {
        val out = mutableListOf<Failure>()
        val chars = text.trim().length
        val sentences = sentences(normalize(text)).size
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        val (maxChars, maxSentences) = if (kind == Kind.Note) NOTE_MAX_CHARS to NOTE_MAX_SENTENCES else REPLY_MAX_CHARS to REPLY_MAX_SENTENCES
        if (chars > maxChars) out += Failure(SHORT, "$chars caractères (au plus $maxChars)")
        if (sentences > maxSentences) out += Failure(SHORT, "$sentences phrases (au plus $maxSentences)")
        if (kind == Kind.Note && paragraphs > NOTE_MAX_PARAGRAPHS) out += Failure(SHORT, "$paragraphs paragraphes (au plus $NOTE_MAX_PARAGRAPHS)")
        markup.find(text)?.let { out += Failure(SHORT, "mise en forme « ${it.value.trim()} » (texte brut attendu)") }
        return out
    }

    // ───── Cohérence avec la page ─────

    private val allGood = Regex("tout est au vert|tout va bien|rien à (corriger|signaler|changer|redire)|parfait|nickel|aucun point")
    private val alarm = Regex("à surveiller|à corriger|à rattraper|coince")

    private fun consistent(t: String, page: ReportPage): List<Failure> {
        val warn = page.points.filter { it.status == PointStatus.Warn }
        val out = mutableListOf<Failure>()
        if (warn.isNotEmpty()) {
            affirmed(t, allGood).forEach { out += Failure(CONSISTENT, "« $it » alors que la page signale ${warn.joinToString { it.name.lowercase() }}") }
        } else if (page.headline.tone != app.trackly.review.page.Tone.Warn) {
            affirmed(t, alarm).forEach { out += Failure(CONSISTENT, "« $it » alors que la page n'a aucun point à regarder") }
        }
        return out
    }

    // ───── Contrôles propres à un scénario ─────

    /**
     * [mention] : ce que la réponse doit relever (le lien entre une pesée haute et la veille, le jour douteux) ; [avoid] : ce
     * qu'elle ne doit pas affirmer (« tu as repris ») ; [questions] : nombre de questions permis (aucune s'il ne manque rien).
     */
    fun scenario(text: String, mention: Regex? = null, avoid: Regex? = null, questions: IntRange? = null): List<Failure> {
        val t = normalize(text)
        val out = mutableListOf<Failure>()
        if (mention != null && !mention.containsMatchIn(t)) out += Failure(USEFUL, "ne relève pas ce qui compte ici (${mention.pattern})")
        if (avoid != null) affirmed(t, avoid, looseNegation = true).forEach { out += Failure(CONSISTENT, "« $it » : ce n'est pas ce que montrent les chiffres") }
        val asked = t.count { it == '?' }
        if (questions != null && asked !in questions) out += Failure(USEFUL, "$asked question(s), attendu ${questions.first} à ${questions.last}")
        return out
    }

    // ───── Outils de texte ─────

    /** Minuscules, apostrophes et espaces insécables simplifiés. */
    fun normalize(s: String): String = s.replace(emojiThenCapital, "$1\n").lowercase().replace('’', '\'').replace(' ', ' ').replace(' ', ' ').replace('−', '-')

    /**
     * Occurrences de [r] qui ne sont pas niées dans la même proposition, avant elles (« pas de retard », « sans baisser »,
     * « rien n'indique qu'il faille baisser… ou ajouter… », « ni »). Une proposition s'arrête à une virgule, un point-virgule
     * ou deux-points.
     */
    private fun affirmed(t: String, r: Regex, exclude: Regex? = null, looseNegation: Boolean = false): List<String> = sentences(t).flatMap { sentence ->
        r.findAll(sentence).filter { m ->
            val before = sentence.substring(0, m.range.first).split(Regex("[,;:]")).last()
            // « pas » seul est aussi le nom des pas de marche : seules ses tournures de négation comptent.
            // « pas une vraie reprise », « plutôt qu'une reprise » : niées pour ce qui ne doit pas être affirmé (avoid) seulement,
            // car « pas » est aussi le nom des pas de marche.
            val loose = if (looseNegation) "|\\bpas (un|une|vraiment|forcément)\\b|plutôt qu" else ""
            val negated = Regex("\\bpas (besoin|la peine|de |d'|à |question|du tout)$loose|\\bsans\\b|\\baucun|\\brien\\b|\\bni\\b|\\bjamais\\b|inutile|n'|\\bne\\b").containsMatchIn(before)
            !negated && (exclude == null || !exclude.containsMatchIn(m.value))
        }.map { m -> m.value }.toList()
    }

    private fun sentences(t: String): List<String> =
        t.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

    private val number = Regex("(?<![\\w,])\\d{1,3}(?: \\d{3})+(?:,\\d+)?|(?<![\\w,])\\d+(?:,\\d+)?")

    /** Nombres d'un texte, sous une forme canonique (« 2 300 » = « 2300 », « 62,0 » = « 62 »), signe ignoré. */
    fun numberTokens(t: String): List<String> = number.findAll(normalize(t)).map { m ->
        runCatching { BigDecimal(m.value.replace(" ", "").replace(',', '.')).stripTrailingZeros().toPlainString() }.getOrDefault(m.value)
    }.toList()

    private val months = "janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre"
    private val datePhrase = Regex("\\b(1er|\\d{1,2}) ($months)\\b")

    /** Dates « jour mois » d'un texte, sous la forme « 1 mars ». */
    private fun dates(t: String): List<String> = datePhrase.findAll(normalize(t)).map { "${it.groupValues[1].removeSuffix("er")} ${it.groupValues[2]}" }.toList()

    /** Les dates ISO (« 2026-11-11 ») réécrites aussi en « 11 novembre », comme le modèle les dirait. */
    private fun isoDates(t: String): String = t + "\n" + Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").findAll(t).joinToString("\n") {
        "${it.groupValues[3].toInt()} ${months.split('|')[it.groupValues[2].toInt() - 1]}"
    }

    private fun periodDates(page: ReportPage): Set<String> {
        val start = java.time.LocalDate.parse(page.periodStart)
        val end = java.time.LocalDate.parse(page.periodEnd)
        val names = months.split('|')
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { "${it.dayOfMonth} ${names[it.monthValue - 1]}" }.toSet()
    }

    /** « bravo 💪 Tu es… » : l'émoji clôt la phrase. */
    private val emojiThenCapital = Regex("([\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}][\\x{FE0F}\\x{1F3FB}-\\x{1F3FF}]*)\\s+(?=\\p{Lu})")

    /** Émojis comptés par groupe : 👍🏽 ou 🤷‍♂️ comptent pour un. */
    private fun emojiCount(t: String): Int {
        var n = 0
        var afterJoiner = false
        t.codePoints().forEach { c ->
            val emoji = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF || c in 0x2300..0x23FF
            if (emoji && c !in 0x1F3FB..0x1F3FF && !afterJoiner) n++
            afterJoiner = c == 0x200D
        }
        return n
    }

    private val smallNumbers = listOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix")
    private val spelledNumber = Regex("\\b(un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix)\\b(?=\\s+(jours?|semaines?|kg|kilos?|séances?|pesées?|repas|mois)\\b)|(?<=\\bsur )(deux|trois|quatre|cinq|six|sept|huit|neuf|dix)\\b")

    /** « trois jours sur sept » devient « 3 jours sur 7 », pour comparer aussi les nombres écrits en lettres. */
    private fun spelled(t: String): String = spelledNumber.replace(t) { m -> if (m.value == "une") "1" else smallNumbers.indexOf(m.value).toString() }

    /** Tout le texte que la page affiche : verdict, phrase, chiffres, points, conseils, poids de référence. */
    fun pageText(page: ReportPage): String = buildString {
        val h = page.headline
        appendLine(listOfNotNull(h.verdict, h.sentence, h.arrival, h.target, h.margin?.let { "$it ${h.marginUnit.orEmpty()}" }, h.range).joinToString("\n"))
        page.points.forEach { appendLine("${it.name} : ${it.desc}, ${it.value} ${it.unit}. ${it.detail.orEmpty()}") }
        page.advice.forEach { appendLine(listOf(it.kicker, it.title, "${it.big} ${it.unit}").plus(it.rows).joinToString("\n")) }
        page.referenceWeight?.let { r -> appendLine(r.lines.joinToString("\n")); appendLine(r.source) }
    }
}
