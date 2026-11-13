package app.trackly.usage

import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.time.TrackingDay
import app.trackly.storage.db.UsageDao
import app.trackly.storage.db.UsageEventEntity
import app.trackly.storage.db.UsageTotalsRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Coût estimé d'un mois civil, par poste. Montants en micro-dollars. */
data class MonthCost(
    val month: YearMonth,
    val text: Micros = 0,
    val search: Micros = 0,
    val transcription: Micros = 0,
    /** Appels vocaux (voix seule ; la réflexion pendant l'appel est comptée dans [text]). */
    val live: Micros = 0,
    /** Messages de l'utilisateur ayant coûté au moins une requête. */
    val exchanges: Int = 0,
    /** Requêtes à OpenAI (un message avec appels d'outils en fait plusieurs). */
    val requests: Int = 0,
    val dictations: Int = 0,
    val calls: Int = 0,
    /** Requêtes dont le modèle n'a pas de prix connu : leur coût de texte n'est pas compté. */
    val unpriced: Int = 0,
) {
    val total: Micros get() = text + search + transcription + live
}

/**
 * Compteur de coût local : enregistre chaque requête à OpenAI (via [UsageSink]) et chaque
 * transcription, avec leur coût calculé par [CostCalculator], puis donne le total d'un mois
 * civil. C'est une estimation faite sur le téléphone ; la facture d'OpenAI fait foi.
 *
 * Un échec d'écriture du compteur ne doit jamais faire échouer un message : il est ignoré.
 */
class RoomUsageLedger(
    private val dao: UsageDao,
    private val zone: ZoneId = TrackingDay.ZONE,
) : UsageSink {

    override suspend fun record(event: UsageEvent) {
        val cost = CostCalculator.text(event.model, event.usage, event.webSearchRequests, event.serviceTier)
        safely {
            dao.insert(
                UsageEventEntity(
                    recordedAt = event.at.toEpochMilli(),
                    kind = KIND_TEXT,
                    model = event.model,
                    effort = event.effort.code,
                    toolRound = event.round,
                    inputTokens = event.usage.inputTokens,
                    cachedTokens = event.usage.cachedTokens,
                    cacheWriteTokens = event.usage.cacheWriteTokens,
                    outputTokens = event.usage.outputTokens,
                    reasoningTokens = event.usage.reasoningTokens,
                    webSearches = event.webSearchRequests ?: 0,
                    audioMs = 0,
                    textMicros = cost.text,
                    searchMicros = cost.search,
                    transcriptionMicros = 0,
                    priced = cost.priced,
                ),
            )
        }
    }

    /**
     * Enregistre une transcription vocale (dictée) : [model] (`gpt-transcribe` ou
     * `gpt-live-transcribe`) et [audioMs], la durée d'audio facturée, en millisecondes.
     *
     * Les deux modèles sont facturés à la minute. Si la réponse donne `usage.type = "duration"`,
     * passer `seconds x 1000` ; si elle donne des jetons (aucun prix par jeton n'est publié pour
     * ces modèles), passer la durée de l'enregistrement mesurée par l'app.
     */
    suspend fun recordTranscription(model: String, audioMs: Long, at: Instant) {
        val cost = CostCalculator.transcription(model, audioMs)
        safely {
            dao.insert(
                UsageEventEntity(
                    recordedAt = at.toEpochMilli(),
                    kind = KIND_TRANSCRIPTION,
                    model = model,
                    effort = null,
                    toolRound = 0,
                    inputTokens = 0,
                    cachedTokens = 0,
                    cacheWriteTokens = 0,
                    outputTokens = 0,
                    reasoningTokens = 0,
                    webSearches = 0,
                    audioMs = audioMs.coerceAtLeast(0),
                    textMicros = 0,
                    searchMicros = 0,
                    transcriptionMicros = cost.transcription,
                    priced = cost.priced,
                ),
            )
        }
    }

    /**
     * Enregistre un appel vocal ([model] `gpt-live-1`) de [sessionMs] millisecondes de session facturée (durée du
     * serveur quand elle est connue). 15 s au minimum, comme la facture.
     */
    suspend fun recordLiveCall(model: String, sessionMs: Long, at: Instant) {
        val cost = CostCalculator.live(model, sessionMs)
        safely {
            dao.insert(
                UsageEventEntity(
                    recordedAt = at.toEpochMilli(),
                    kind = KIND_LIVE,
                    model = model,
                    effort = null,
                    toolRound = 0,
                    inputTokens = 0,
                    cachedTokens = 0,
                    cacheWriteTokens = 0,
                    outputTokens = 0,
                    reasoningTokens = 0,
                    webSearches = 0,
                    audioMs = sessionMs.coerceAtLeast(0),
                    textMicros = 0,
                    searchMicros = 0,
                    transcriptionMicros = cost.live,
                    priced = cost.priced,
                ),
            )
        }
    }

    /** Coût du mois civil [month] (fuseau local), mis à jour à chaque enregistrement. */
    fun observeMonth(month: YearMonth): Flow<MonthCost> {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeTotals(from, to).map { it.toMonthCost(month) }
    }

    fun monthOf(at: Instant): YearMonth = YearMonth.from(at.atZone(zone))

    private suspend fun safely(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Compteur facultatif : on perd une ligne plutôt que d'interrompre le message.
        }
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_TRANSCRIPTION = "transcription"
        const val KIND_LIVE = "live"
    }
}

private fun UsageTotalsRow.toMonthCost(month: YearMonth) = MonthCost(
    month = month,
    text = textMicros,
    search = searchMicros,
    transcription = transcriptionMicros,
    live = liveMicros,
    exchanges = exchanges,
    requests = requests,
    dictations = dictations,
    calls = calls,
    unpriced = unpriced,
)
