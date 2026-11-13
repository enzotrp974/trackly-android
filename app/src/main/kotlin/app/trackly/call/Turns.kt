package app.trackly.call

import app.trackly.call.live.AudioLevels
import java.text.Normalizer

/**
 * Tours de parole d'après les niveaux audio du téléphone : l'utilisateur parle-t-il, l'IA parle-t-elle, depuis quand
 * chacun se tait. Le micro est ignoré pendant que l'IA parle, pour que l'écho du haut-parleur ne compte pas comme
 * l'utilisateur (la coupure par l'utilisateur est l'affaire de [BargeInGuard]).
 */
class LocalTurnDetector(
    private val micThreshold: Float = 0.03f,
    private val aiThreshold: Float = AI_SPEAKING_LEVEL,
    private val aiSilenceMs: Long = 600,
    private val userHoldMs: Long = 300,
) {
    var aiSpeaking = false
        private set
    var userSpeaking = false
        private set

    /** Dernier instant où la voix de l'IA était audible ; `null` tant qu'elle n'a pas parlé. */
    var aiLastLoudAt: Long? = null
        private set

    /** Dernier instant où le micro était fort hors parole de l'IA ; `null` tant que l'utilisateur n'a pas parlé. */
    var userLastLoudAt: Long? = null
        private set

    fun onLevels(nowMs: Long, mic: Float, ai: Float) {
        if (ai > aiThreshold) {
            aiSpeaking = true
            aiLastLoudAt = nowMs
        } else if (aiSpeaking && nowMs - (aiLastLoudAt ?: nowMs) > aiSilenceMs) {
            aiSpeaking = false
        }
        if (!aiSpeaking && mic > micThreshold) userLastLoudAt = nowMs
        userSpeaking = !aiSpeaking && userLastLoudAt?.let { nowMs - it < userHoldMs } == true
    }

    /** Pause : ni l'utilisateur ni l'IA ne parlent depuis au moins [quietMs]. */
    fun isPause(nowMs: Long, quietMs: Long): Boolean {
        if (aiSpeaking || userSpeaking) return false
        val lastSound = maxOf(userLastLoudAt ?: Long.MIN_VALUE, aiLastLoudAt ?: Long.MIN_VALUE)
        return lastSound == Long.MIN_VALUE || nowMs - lastSound >= quietMs
    }

    companion object {
        /** Niveau au-dessus duquel la voix de l'IA est considérée comme audible. */
        const val AI_SPEAKING_LEVEL = 0.01f
    }
}

/**
 * Coupure gérée par l'app : la voix n'a ni annulation de parole ni détection de tour réglable,
 * et sur haut-parleur elle ne doit jamais couper l'utilisateur. Quand l'utilisateur parle fort pendant au moins [holdMs] alors que l'IA
 * parle, l'app coupe localement la voix de l'IA et demande à la voix de s'arrêter ; le son revient quand la voix
 * s'est tue (plus de transcription de sa part depuis [quietMs]), ou au plus tard après [maxMuteMs].
 *
 * Le niveau de l'utilisateur est lu après l'annulation d'écho de WebRTC quand le transport sait le donner. Sinon, avec l'écho
 * WebRTC seul ([echoGateOnRaw]), le micro brut contient aussi la voix de l'IA : il doit alors dépasser
 * [echoRatio] fois le niveau joué, en plus du seuil.
 */
class BargeInGuard(
    private val threshold: Float = THRESHOLD,
    private val echoRatio: Float = ECHO_RATIO,
    private val echoGateOnRaw: Boolean = true,
    private val holdMs: Long = HOLD_MS,
    private val quietMs: Long = QUIET_MS,
    private val maxMuteMs: Long = MAX_MUTE_MS,
) {
    enum class Action { Mute, Unmute }

    var muted = false
        private set
    var count = 0
        private set

    private var loudSince: Long? = null
    private var mutedAt = 0L

    /** [lastAiTranscriptAt] : dernier morceau de transcription de l'IA (la voix parle encore tant qu'il en arrive). */
    fun onLevels(nowMs: Long, levels: AudioLevels, lastAiTranscriptAt: Long?): Action? {
        if (muted) {
            val lastAi = maxOf(mutedAt, lastAiTranscriptAt ?: mutedAt)
            if (nowMs - lastAi >= quietMs || nowMs - mutedAt >= maxMuteMs) {
                muted = false
                loudSince = null
                return Action.Unmute
            }
            return null
        }
        val aiTalking = levels.ai > LocalTurnDetector.AI_SPEAKING_LEVEL
        val voice = levels.micProcessed ?: levels.mic
        val gate = if (levels.micProcessed == null && echoGateOnRaw) maxOf(threshold, levels.ai * echoRatio) else threshold
        if (!aiTalking || voice < gate) {
            loudSince = null
            return null
        }
        val since = loudSince ?: nowMs.also { loudSince = it }
        if (nowMs - since < holdMs) return null
        muted = true
        mutedAt = nowMs
        loudSince = null
        count++
        return Action.Mute
    }

    companion object {
        /** Seuil de la voix de l'utilisateur (niveau efficace, 0 à 1). À régler à l'essai, avec les deux réglages d'écho. */
        const val THRESHOLD = 0.08f

        /** Micro brut en écho WebRTC : il doit dépasser 1,5 fois la voix jouée (l'écho reste en dessous). */
        const val ECHO_RATIO = 1.5f
        const val HOLD_MS = 300L
        const val QUIET_MS = 800L
        const val MAX_MUTE_MS = 6_000L
    }
}

/**
 * Au revoir de l'utilisateur (liste courte) : l'app raccroche après avoir laissé la voix finir sa phrase.
 * La phrase est jugée quand l'utilisateur a fini de parler, et la formule doit la finir :
 * - « au revoir », « bye », « ciao » : quoi qu'il y ait avant (« bon ben merci, au revoir ») ;
 * - « à plus », « bonne journée »… : seulement après des mots de politesse (« allez, à plus »), car « il n'y en a
 *   plus » ou « on a passé une bonne journée » ne sont pas des au revoir ;
 * - « c'est tout », « c'est bon » : seulement après « non », « bon », « allez »… ou suivis de « merci » (« non, c'est
 *   tout »), car « poulet riz, c'est tout » finit un repas et « c'est bon » seul répond souvent à une question.
 */
object Goodbye {
    private val ANYWHERE = listOf("au revoir", "bye", "ciao")
    private val POLITE = listOf("a plus", "a plus tard", "a tout a l heure", "bonne journee", "bonne soiree")
    private val FINISHED = listOf("c est fini", "j ai fini", "on a fini")
    private val DONE = listOf("c est tout", "c est bon")

    /** Mots qui peuvent précéder une formule de fin sans en changer le sens. */
    private val LEAD = setOf("bon", "ben", "bah", "allez", "ok", "okay", "merci", "beaucoup", "salut", "alors", "non", "et", "voila", "eh", "bien", "super", "parfait", "trackly")
    private val TRAILING = setOf("merci", "beaucoup", "alors", "allez", "ok", "okay", "salut", "bisous", "trackly")

    fun isGoodbye(utterance: String): Boolean {
        val words = normalize(utterance).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        var end = words.size
        while (end > 0 && words[end - 1] in TRAILING) end--
        val core = words.subList(0, end)
        if (core.isEmpty()) return false
        val thanked = end < words.size && "merci" in words.subList(end, words.size)
        fun lead(formula: String): List<String>? {
            val f = formula.split(' ')
            if (core.size < f.size || core.subList(core.size - f.size, core.size) != f) return null
            return core.subList(0, core.size - f.size)
        }
        if (ANYWHERE.any { lead(it) != null }) return true
        if ((POLITE + FINISHED).any { f -> lead(f)?.all { it in LEAD } == true }) return true
        return DONE.any { f ->
            val before = lead(f) ?: return@any false
            before.all { it in LEAD } && (before.isNotEmpty() || thanked)
        }
    }

    /** Minuscules, sans accents ni ponctuation, apostrophes comprises (« c'est » → « c est »). */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
