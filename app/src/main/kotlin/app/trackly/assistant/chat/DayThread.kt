package app.trackly.assistant.chat

import app.trackly.core.time.TrackingDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Fil du jour : la part de l'historique du chat qui correspond à la date de rattachement courante. Le
 * chat l'affiche et l'envoie au modèle ; à 5 h, il repart à zéro sans rien supprimer. [now] donne l'heure du moteur.
 */
class DayThread(
    private val history: ChatHistory,
    /** Appelé après chaque effacement : la réponse terminée et l'erreur encore affichées n'ont plus leur place. */
    private val onCleared: () -> Unit = {},
    private val now: () -> Instant,
) {
    /** Date de rattachement du fil en cours. */
    fun today(): LocalDate = TrackingDay.of(now())

    /**
     * Date du fil en cours, réémise quand elle change : un chat resté ouvert passe seul au nouveau fil à 5 h. Relue au plus
     * toutes les [CHECK_EVERY], car un délai ne compte pas toujours le temps passé téléphone endormi.
     */
    fun days(): Flow<LocalDate> = flow {
        while (true) {
            val at = now()
            emit(TrackingDay.of(at))
            delay(untilNextCheck(at).toMillis())
        }
    }.distinctUntilChanged()

    /** Messages visibles du fil en cours ; suit le passage à un nouveau jour. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(limit: Int = ChatHistory.DISPLAY_LIMIT): Flow<List<ChatMessage>> = days().flatMapLatest { history.observe(it, limit) }

    suspend fun last(limit: Int): List<ChatMessage> = history.last(today(), limit)

    /**
     * « Effacer la discussion » : masque le fil du jour sans supprimer de ligne ni toucher à une consommation. Renvoie le
     * nombre de messages masqués.
     */
    suspend fun clear(): Int {
        val at = now()
        val hidden = history.clear(TrackingDay.of(at), at)
        onCleared()
        return hidden
    }

    companion object {
        /** Intervalle maximal entre deux relectures de l'heure. */
        val CHECK_EVERY: Duration = Duration.ofMinutes(1)

        /** Délai avant la prochaine relecture : la minute suivante, ou le début du jour suivant s'il est plus proche. */
        internal fun untilNextCheck(at: Instant): Duration {
            val local = at.atZone(TrackingDay.ZONE)
            var start = local.toLocalDate().atTime(TrackingDay.DAY_START).atZone(TrackingDay.ZONE)
            if (!start.isAfter(local)) start = start.plusDays(1)
            return minOf(CHECK_EVERY, Duration.between(local, start)).coerceAtLeast(Duration.ofMillis(1))
        }
    }
}
