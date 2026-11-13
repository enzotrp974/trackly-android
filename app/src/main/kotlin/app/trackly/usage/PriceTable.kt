package app.trackly.usage

/**
 * TABLE DE PRIX UNIQUE de Trackly : le seul endroit où figurent des tarifs OpenAI.
 *
 * Unité : micro-dollars (1 $ = 1 000 000), entiers. Les prix de jetons sont « par million de
 * jetons » : 2 $ le million = 2 000 000 micro-dollars le million. Un prix par million exprimé
 * en micro-dollars est aussi le prix d'un jeton en « millionièmes de micro-dollar » : le calcul
 * additionne d'abord (jetons x prix), puis divise une seule fois par un million (voir [CostCalculator]).
 *
 * Relevé le **2 novembre 2026** dans la documentation officielle d'OpenAI (niveau Standard,
 * hors Batch, Flex, Fast et Ultrafast) :
 * - tarifs et modèles : https://developers.openai.com/api/docs/pricing
 * - gpt-6.1-sol : https://developers.openai.com/api/docs/models/gpt-6.1-sol
 * - gpt-6.1-terra : https://developers.openai.com/api/docs/models/gpt-6.1-terra
 * - cache d'entrée (écritures x1,25 en 30 min, x2,5 en 12 h) : https://developers.openai.com/api/docs/guides/prompt-caching
 * - recherche web : https://developers.openai.com/api/docs/guides/tools-web-search
 * - gpt-transcribe : https://developers.openai.com/api/docs/models/gpt-transcribe
 * - gpt-live-transcribe : https://developers.openai.com/api/docs/models/gpt-live-transcribe
 * - gpt-live-1 (appel, relevé le 5/11/2026) : https://developers.openai.com/api/docs/models/gpt-live-1 et le guide
 *   voice-latency-cost, onglet Live (facturé à la seconde, silences compris, 15 s au minimum)
 * - niveau « priority » (mode Fast) : deux fois le tarif Standard (doc relue le 6/11/2026)
 *
 * Quand OpenAI change un tarif : modifier ce fichier seulement, mettre [AS_OF] à jour et
 * ajouter un cas au test `CostCalculatorTest`. Les coûts déjà enregistrés gardent le tarif
 * du jour de l'enregistrement (ils sont figés dans la base, jamais recalculés).
 */
object PriceTable {
    /** Date du relevé des tarifs ci-dessous. */
    const val AS_OF = "2026-11-02"
    const val SOURCE = "https://developers.openai.com/api/docs/pricing"

    /** Tarifs d'un modèle de texte, en micro-dollars par million de jetons. */
    data class TextPrice(
        val input: Long,
        /** Jetons d'entrée relus dans le cache. */
        val cachedInput: Long,
        /** Jetons écrits dans le cache pour 30 minutes (x1,25 l'entrée). */
        val cacheWrite30m: Long,
        /** Jetons écrits dans le cache pour 12 heures (x2,5 l'entrée). */
        val cacheWrite12h: Long,
        /** Sortie, raisonnement compris. */
        val output: Long,
    )

    /** Un modèle : tarif normal, et tarif de toute la requête au-delà de [longContextAbove] jetons d'entrée. */
    data class TextModel(val standard: TextPrice, val longContext: TextPrice, val longContextAbove: Long = 272_000)

    val text: Map<String, TextModel> = mapOf(
        "gpt-6.1-sol" to TextModel(
            standard = TextPrice(input = 2_000_000, cachedInput = 100_000, cacheWrite30m = 2_500_000, cacheWrite12h = 5_000_000, output = 10_000_000),
            longContext = TextPrice(input = 4_000_000, cachedInput = 200_000, cacheWrite30m = 5_000_000, cacheWrite12h = 10_000_000, output = 15_000_000),
        ),
        "gpt-6.1-terra" to TextModel(
            standard = TextPrice(input = 1_000_000, cachedInput = 50_000, cacheWrite30m = 1_250_000, cacheWrite12h = 2_500_000, output = 5_000_000),
            longContext = TextPrice(input = 2_000_000, cachedInput = 100_000, cacheWrite30m = 2_500_000, cacheWrite12h = 5_000_000, output = 7_500_000),
        ),
    )

    /**
     * Recherche web : 10 $ pour 1 000 appels, soit 0,01 $ par appel facturable
     * (`tool_usage.web_search.num_requests`). Le contenu récupéré est facturé en plus, au tarif
     * du modèle, et fait partie des jetons d'entrée (la doc ne le dit pas explicitement).
     */
    const val WEB_SEARCH_PER_CALL = 10_000L

    /** Transcription : micro-dollars par minute d'audio. */
    val transcriptionPerMinute: Map<String, Long> = mapOf(
        "gpt-transcribe" to 4_500L, // 0,0045 $ par minute
        "gpt-live-transcribe" to 17_000L, // 0,017 $ par minute
    )

    /**
     * Appel vocal : micro-dollars par minute de session, silences compris, facturée à la seconde. La création d'une
     * session WebRTC facture [LIVE_MIN_BILLED_MS] d'avance, déduites ensuite : un appel coûte au moins autant.
     */
    val livePerMinute: Map<String, Long> = mapOf(
        "gpt-live-1" to 50_000L, // 0,05 $ par minute
    )
    const val LIVE_MIN_BILLED_MS = 15_000L

    /** `service_tier` « priority » (mode Fast), facturé [PRIORITY_MULTIPLIER] fois. Plus demandé par l'app. */
    const val PRIORITY_TIER = "priority"

    /**
     * `service_tier` que la vraie API renvoie pour une requête envoyée en [PRIORITY_TIER] (relevé le 6/11/2026) :
     * « Priority processing was renamed Fast mode on July 30, 2026 » (page des tarifs). Même tarif, [PRIORITY_MULTIPLIER] fois.
     */
    const val FAST_TIER = "fast"
    const val PRIORITY_MULTIPLIER = 2L

    /** Vrai si le niveau de service renvoyé est facturé au tarif Fast (ex-priority). */
    fun isFastTier(serviceTier: String?): Boolean = serviceTier == PRIORITY_TIER || serviceTier == FAST_TIER

    /**
     * Tarif du modèle [model]. L'identifiant renvoyé par l'API peut porter un suffixe daté
     * (`gpt-6.1-sol-2026-11-02`) : on accepte aussi un préfixe suivi d'un tiret.
     */
    fun textModel(model: String): TextModel? = lookup(text, model)

    fun liveRate(model: String): Long? = lookup(livePerMinute, model)

    fun transcriptionRate(model: String): Long? = lookup(transcriptionPerMinute, model)

    private fun <T> lookup(table: Map<String, T>, model: String): T? =
        table[model] ?: table.entries.filter { model.startsWith(it.key + "-") }.maxByOrNull { it.key.length }?.value
}
