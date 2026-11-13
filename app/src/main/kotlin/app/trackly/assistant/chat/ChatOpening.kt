package app.trackly.assistant.chat

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.core.model.Moment
import app.trackly.day.DayLabels
import java.time.LocalDate

/**
 * Début de message proposé quand on ouvre le chat depuis un « + » : le moment
 * touché (« Ce matin : ») et, pour un jour passé, la date en toutes lettres (« Pour dimanche 1er novembre : ») ;
 * une date exprimée prime. L'utilisateur peut l'effacer : ce n'est que du texte.
 */
object ChatPrefill {
    /** `null` : rien à proposer (« + » de la barre du bas, aujourd'hui). */
    fun of(day: LocalDate, today: LocalDate, moment: Moment?): String? {
        if (!day.isBefore(today)) return moment?.let { todayPhrase(it) + " : " }
        val date = DayLabels.inSentence(day) + if (day.year != today.year) " ${day.year}" else ""
        return "Pour $date" + (moment?.let { ", " + pastPhrase(it) }.orEmpty()) + " : "
    }

    private fun todayPhrase(moment: Moment) = when (moment) {
        Moment.Matin -> "Ce matin"
        Moment.Midi -> "À midi"
        Moment.Snack -> "En snack"
        Moment.Soir -> "Ce soir"
    }

    private fun pastPhrase(moment: Moment) = when (moment) {
        Moment.Matin -> "au matin"
        Moment.Midi -> "à midi"
        Moment.Snack -> "en snack"
        Moment.Soir -> "le soir"
    }
}

/**
 * Jour à montrer en refermant le chat : celui de la dernière consommation enregistrée (ou corrigée,
 * rétablie) pendant que le chat était ouvert, si elle compte encore. `null` : ne rien changer.
 */
object ChatReturn {
    /**
     * Identifiant du premier message de cette ouverture du chat : celui qui suit le dernier message de
     * l'historique déjà là. `null` tant que l'historique n'est pas chargé : avec une liste encore vide
     * (valeur initiale de l'état), tout l'historique compterait comme « enregistré pendant ce chat » et
     * l'écran sauterait au jour du dernier enregistrement de toujours en refermant sans rien faire.
     */
    fun firstNewId(historyLoaded: Boolean, messages: List<ChatMessage>): Long? =
        if (historyLoaded) (messages.maxOfOrNull { it.id } ?: 0L) + 1 else null

    fun dayAfter(messages: List<ChatMessage>, sinceId: Long, undone: Set<Long>): LocalDate? =
        messages.asSequence()
            .filter { it.id >= sinceId && it.role == ChatRole.Assistant }
            .flatMap { it.cards.asSequence() }
            .filterIsInstance<RecordCard>()
            .filter { it.kind != RecordKind.Deleted && it.actionId !in undone }
            .flatMap { it.items.asSequence() }
            .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }
            .lastOrNull()
}
