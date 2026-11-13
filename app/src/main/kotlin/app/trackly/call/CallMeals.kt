package app.trackly.call

import java.text.Normalizer
import java.time.Instant

/**
 * Les repas d'un appel, sans horloge ni réseau : le contrôleur d'appel lui passe les instants et fait les envois.
 *
 * - **Une réflexion à la fois.** Une demande qui arrive pendant une réflexion (l'utilisateur ajoute « et un yaourt ») attend la fin
 *   de celle-ci, puis part avec toute la transcription et ce qui vient d'être noté : la suivante corrige au lieu de noter
 *   une deuxième fois. Les demandes en attente se regroupent en une seule.
 * - **Une question à la fois.** La réflexion rend ses questions dans l'ordre d'importance ; la voix en pose une, attend la
 *   réponse de l'utilisateur, puis pose la suivante. Une réflexion plus récente remplace la file (sans reposer une question déjà
 *   posée). Quand l'utilisateur a répondu à la dernière, l'app relance la réflexion d'elle-même ([ThinkTrigger.Answer]).
 * - **Filet de l'app.** Une parole de l'utilisateur que la voix n'a pas transmise est réfléchie après une pause ([ThinkTrigger.Pause]),
 *   sauf un fragment (« et un autre truc ») ou une question de la voix encore sans réponse.
 * - **Rien ne se perd.** Ce qui n'a pas été traité (appel fini avant, réflexion en échec) va au rapport ([report]), repris après l'appel.
 */
internal class CallMeals {
    /** Réflexion en cours. [userChars] : parole de l'utilisateur qu'elle couvre (longueur au départ). */
    class Running(val ids: MutableList<String>, val trigger: ThinkTrigger, val startedAt: Long, val userChars: Int) {
        /** Trop longue : elle ne compte plus comme une activité, mais son résultat sera pris s'il arrive. */
        var abandoned = false
    }

    /** Réflexion demandée, pas encore partie (la phrase de l'utilisateur n'est pas encore retombée, ou une autre tourne). */
    class Next(val trigger: ThinkTrigger, val askedAt: Long) {
        val ids = mutableListOf<String>()
    }

    /** Question posée à l'utilisateur, en attente de sa réponse. [userChars] : longueur de sa parole au moment de la question. */
    class Outstanding(val question: String, val userChars: Int)

    var running: Running? = null
        private set
    var next: Next? = null
        private set
    var outstanding: Outstanding? = null
        private set

    private val noted = LinkedHashMap<String, NotedEntry>()
    private val asked = mutableListOf<String>()
    private val queue = ArrayDeque<String>()

    /**
     * Questions répondues qu'aucune réflexion aboutie n'a encore relues ([Outstanding.userChars] : fin de la réponse). Un
     * aliment flou attend sa réponse pour être noté : si la réflexion suivante n'aboutit pas, la question reste au
     * rapport, et la relecture note l'aliment avec la réponse ou la portion habituelle.
     */
    private val answered = mutableListOf<Outstanding>()

    /** Parole de l'utilisateur, par réplique : la transcription regroupée par le contrôleur. */
    private val userLines = mutableListOf<StringBuilder>()
    private var userChars = 0

    /** Parole de l'utilisateur déjà traitée par une réflexion aboutie. */
    private var coveredChars = 0

    /** Longueur de sa parole au dernier échec : pas de nouvel essai tant qu'il n'a rien dit de plus. */
    private var failedAtChars = -1

    /** La réflexion travaille (ou va partir) : pour l'écran et le silence. */
    val thinking: Boolean get() = (running?.abandoned == false) || next != null

    val notedEntries: List<NotedEntry> get() = noted.values.toList()
    val askedQuestions: List<String> get() = asked.toList()
    val queuedQuestions: List<String> get() = queue.toList()

    /** Morceau de parole de l'utilisateur ; [newLine] : une nouvelle réplique commence. */
    fun onUser(delta: String, newLine: Boolean) {
        if (newLine || userLines.isEmpty()) userLines += StringBuilder()
        userLines.last().append(delta)
        userChars += delta.length
    }

    /**
     * Demande de réflexion (délégation de la voix [delegationId], ou l'app). Regroupée avec celle qui attend déjà ; rattachée
     * à celle qui tourne si l'utilisateur n'a rien dit depuis son départ (la voix et l'app demandent souvent la même chose à 1 s près).
     */
    fun request(trigger: ThinkTrigger, at: Long, delegationId: String? = null) {
        running?.let { current ->
            if (current.userChars == userChars) {
                if (delegationId != null && delegationId !in current.ids) current.ids += delegationId
                return
            }
        }
        val waiting = next ?: Next(trigger, at).also { next = it }
        if (delegationId != null && delegationId !in waiting.ids) waiting.ids += delegationId
    }

    /** La demande en attente part si aucune réflexion ne tourne et si [ready] (phrase de l'utilisateur retombée). */
    fun startIfReady(at: Long, ready: (Next) -> Boolean): Running? {
        if (running != null) return null
        val waiting = next ?: return null
        if (!ready(waiting)) return null
        next = null
        return Running(waiting.ids.toMutableList(), waiting.trigger, at, userChars).also { running = it }
    }

    fun abandonIfLonger(at: Long, maxMs: Long) {
        running?.let { if (!it.abandoned && at - it.startedAt > maxMs) it.abandoned = true }
    }

    /** Fin de la réflexion en cours : ce qui est noté est retenu, la file de questions remplacée. */
    fun finish(result: ThinkResult): Running? {
        val done = running ?: return null
        running = null
        remember(result.noted)
        when (result) {
            is ThinkResult.Done -> {
                coveredChars = maxOf(coveredChars, done.userChars)
                answered.removeAll { it.userChars <= done.userChars }
                queue.clear()
                result.questions.map { it.trim() }.filter { it.isNotEmpty() && !wasAsked(it) }.distinctBy(::normalize).forEach(queue::addLast)
            }
            // Ce qu'il a dit pendant la réflexion ratée pourra encore lancer le filet de l'app.
            is ThinkResult.Failed -> failedAtChars = done.userChars
        }
        return done
    }

    /** Ce qu'a noté une réflexion finie après l'appel. */
    fun remember(entries: List<NotedEntry>) {
        for (e in entries) if (e.deleted) noted.remove(e.entryId) else noted[e.entryId] = e
    }

    /** Prochaine question à poser, s'il n'y en a pas déjà une en attente de réponse. Elle est marquée posée. */
    fun takeQuestion(): String? {
        if (outstanding != null) return null
        val q = queue.removeFirstOrNull() ?: return null
        asked += q
        outstanding = Outstanding(q, userChars)
        return q
    }

    /**
     * L'utilisateur a fini de parler ([utteranceEnded]) : une question en attente a reçu sa réponse. Rend `true` si c'était la
     * dernière de la file : l'app relance alors la réflexion avec ses réponses.
     */
    fun onUtteranceEnded(): Boolean {
        val q = outstanding ?: return false
        if (userChars <= q.userChars) return false
        outstanding = null
        answered += Outstanding(q.question, userChars)
        return queue.isEmpty()
    }

    /**
     * Filet de l'app : une parole de l'utilisateur qu'aucune réflexion n'a couverte, que la voix n'a pas transmise, et qui n'est ni un
     * fragment ni une réponse à une question de la voix. [lastLine] : dernière réplique de l'appel.
     */
    fun needsPauseReflection(lastLine: CallLine?): Boolean {
        if (running != null || next != null || outstanding != null || queue.isNotEmpty()) return false
        if (userChars <= coveredChars || userChars == failedAtChars) return false
        val last = userLines.lastOrNull()?.toString().orEmpty()
        if (!Fragment.meaningful(uncoveredText()) || Fragment.isPartial(last) || Goodbye.isGoodbye(last)) return false
        // La voix vient de lui poser une question (« c'est quoi l'autre truc ? ») : on attend sa réponse.
        if (lastLine?.speaker == Speaker.Voice && lastLine.text.trim().endsWith("?")) return false
        return true
    }

    /** Fin de l'appel : la demande qui n'est pas partie est abandonnée (sa parole reste à traiter). */
    fun dropPending() {
        next = null
    }

    fun report(transcript: List<CallLine>, startedAt: Instant = Instant.EPOCH, reflection: CallReflection = CallReflection.Default): CallMealReport =
        CallMealReport(
            noted = notedEntries,
            openQuestions = (answered.map { it.question } + listOfNotNull(outstanding?.question) + queue).distinct(),
            unprocessed = unprocessed(),
            transcript = transcript,
            startedAt = startedAt,
            reflection = reflection,
        )

    private fun uncoveredText(): String = unprocessed().joinToString(" ")

    /** Répliques de l'utilisateur (ou leur fin) qu'aucune réflexion aboutie n'a couvertes. */
    private fun unprocessed(): List<String> {
        val out = mutableListOf<String>()
        var offset = 0
        for (line in userLines) {
            val end = offset + line.length
            if (end > coveredChars) {
                val part = line.substring((coveredChars - offset).coerceAtLeast(0)).trim()
                if (part.isNotEmpty()) out += part
            }
            offset = end
        }
        return out
    }

    private fun wasAsked(question: String) = asked.any { normalize(it) == normalize(question) }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** Morceaux de repas : la voix demande juste ce qui manque, sans réflexion. */
internal object Fragment {
    private val PARTIAL = listOf(
        Regex("\\bautres?\\s+(truc|trucs|chose|choses)\\b"),
        Regex("\\bun\\s+truc\\b"),
        Regex("\\b(attends?|attend+s)\\b"),
        Regex("\\b(et|avec|puis)\\s*[.…,]*$"),
        Regex("\\bje\\s+(sais\\s+plus|me\\s+souviens\\s+plus)\\b"),
    )

    /** Au moins deux mots : « … », « euh » ou « oui » seuls ne lancent pas de réflexion. */
    fun meaningful(text: String): Boolean = Regex("\\p{L}{2,}").findAll(text).count() >= 2

    fun isPartial(text: String): Boolean {
        val clean = text.lowercase().replace('’', '\'').trim()
        return PARTIAL.any { it.containsMatchIn(clean) }
    }
}
