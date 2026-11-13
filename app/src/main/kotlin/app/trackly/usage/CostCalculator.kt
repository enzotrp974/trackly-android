package app.trackly.usage

import app.trackly.assistant.openai.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Montant en micro-dollars (1 $ = 1 000 000) : entiers, donc aucune dérive d'arrondi flottant. */
typealias Micros = Long

/** Coût d'une requête, par poste. [priced] est faux si le modèle n'est pas dans la [PriceTable]. */
data class CostBreakdown(
    val text: Micros = 0,
    val search: Micros = 0,
    val transcription: Micros = 0,
    /** Minutes d'appel vocal (voix seule ; la réflexion de l'appel est comptée en texte). */
    val live: Micros = 0,
    val priced: Boolean = true,
) {
    val total: Micros get() = text + search + transcription + live
}

/**
 * Calcule le coût d'une requête d'après le champ `usage` et la [PriceTable]
 * (formule du guide OpenAI sur le cache d'entrée).
 *
 * ```
 * ordinaires = entrée - lus en cache - écrits en cache
 * texte      = ordinaires x entrée + lus x cache + écrits 30 min x écriture 30 min
 *              + écrits 12 h x écriture 12 h + sortie x sortie        (raisonnement inclus dans la sortie)
 * recherche  = appels x 0,01 $
 * ```
 * Tout est additionné en « micro-dollars par million », puis divisé une seule fois (arrondi à
 * l'entier le plus proche) : l'erreur est au plus d'un micro-dollar par requête.
 */
object CostCalculator {
    private const val PER_MILLION = 1_000_000L
    private const val MS_PER_MINUTE = 60_000L

    fun text(model: String, usage: Usage, webSearches: Int?, serviceTier: String? = null): CostBreakdown {
        val search = (webSearches ?: 0).coerceAtLeast(0) * PriceTable.WEB_SEARCH_PER_CALL
        val prices = PriceTable.textModel(model)
            ?: return CostBreakdown(search = search, priced = false)
        val price = if (usage.inputTokens > prices.longContextAbove) prices.longContext else prices.standard

        val cached = usage.cachedTokens.coerceIn(0, usage.inputTokens)
        val written = usage.cacheWriteTokens.coerceIn(0, usage.inputTokens - cached)
        val written12h = cacheWrite12hTokens(usage.raw).coerceIn(0, written)
        val written30m = written - written12h
        val ordinary = usage.inputTokens - cached - written

        val scaled = ordinary * price.input +
            cached * price.cachedInput +
            written30m * price.cacheWrite30m +
            written12h * price.cacheWrite12h +
            usage.outputTokens.coerceAtLeast(0) * price.output
        val tier = if (PriceTable.isFastTier(serviceTier)) PriceTable.PRIORITY_MULTIPLIER else 1L
        return CostBreakdown(text = roundedPerMillion(scaled * tier), search = search)
    }

    /** Transcription facturée à la minute d'audio ; [audioMs] en millisecondes. */
    fun transcription(model: String, audioMs: Long): CostBreakdown {
        val rate = PriceTable.transcriptionRate(model) ?: return CostBreakdown(priced = false)
        val micros = (audioMs.coerceAtLeast(0) * rate + MS_PER_MINUTE / 2) / MS_PER_MINUTE
        return CostBreakdown(transcription = micros)
    }

    /** Appel vocal facturé à la minute de session ; [sessionMs] en millisecondes, 15 s au minimum. */
    fun live(model: String, sessionMs: Long): CostBreakdown {
        val rate = PriceTable.liveRate(model) ?: return CostBreakdown(priced = false)
        val billed = sessionMs.coerceAtLeast(PriceTable.LIVE_MIN_BILLED_MS)
        return CostBreakdown(live = (billed * rate + MS_PER_MINUTE / 2) / MS_PER_MINUTE)
    }

    private fun roundedPerMillion(scaled: Long): Micros = (scaled + PER_MILLION / 2) / PER_MILLION

    /** Jetons écrits en cache pour 12 h (`cache_write_tokens_details.ttl_12h_tokens`) ; 0 si absent. */
    private fun cacheWrite12hTokens(raw: JsonObject): Long {
        val details = (raw["input_tokens_details"] as? JsonObject)?.get("cache_write_tokens_details") as? JsonObject
        return (details?.get("ttl_12h_tokens") as? JsonPrimitive)?.longOrNull ?: 0
    }
}
