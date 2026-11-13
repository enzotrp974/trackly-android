package app.trackly.usage

import app.trackly.assistant.openai.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcul du coût en micro-dollars entiers, d'après des champs `usage` écrits comme ceux de la doc OpenAI. */
class CostCalculatorTest {
    private fun usage(json: String) = Usage.parse(Json.parseToJsonElement(json).jsonObject)

    private fun simple(input: Long, output: Long, reasoning: Long = 0, cached: Long = 0, written: Long = 0) = usage(
        """{"input_tokens":$input,"input_tokens_details":{"cached_tokens":$cached,"cache_write_tokens":$written},
           "output_tokens":$output,"output_tokens_details":{"reasoning_tokens":$reasoning},"total_tokens":${input + output}}""",
    )

    @Test
    fun `sol sans cache - entree et sortie au tarif du modele`() {
        // 10 000 x 2 $/M + 2 000 x 10 $/M = 0,02 $ + 0,02 $
        val cost = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null)
        assertEquals(40_000L, cost.text)
        assertEquals(0L, cost.search)
        assertEquals(40_000L, cost.total)
        assertTrue(cost.priced)
    }

    @Test
    fun `les jetons de raisonnement sont deja dans la sortie et ne sont pas comptes deux fois`() {
        val sans = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000, reasoning = 0), null)
        val avec = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000, reasoning = 1_500), null)
        assertEquals(sans.total, avec.total)
    }

    @Test
    fun `exemple de la doc - lecture de cache, ecritures 30 min et 12 h`() {
        // Exemple du guide OpenAI sur le cache : 12 000 en entrée dont 4 000 lus et 7 000 écrits (3 000 en 30 min, 4 000 en 12 h), 500 en sortie.
        val u = usage(
            """{"input_tokens":12000,"input_tokens_details":{"cached_tokens":4000,"cache_write_tokens":7000,
               "cache_write_tokens_details":{"ttl_30m_tokens":3000,"ttl_12h_tokens":4000}},
               "output_tokens":500,"output_tokens_details":{"reasoning_tokens":300},"total_tokens":12500}""",
        )
        // 1 000 x 2 + 4 000 x 0,10 + 3 000 x 2,50 + 4 000 x 5 + 500 x 10, par million = 0,0349 $
        assertEquals(34_900L, CostCalculator.text("gpt-6.1-sol", u, null).text)
    }

    @Test
    fun `ecritures de cache sans detail de duree - comptees comme 30 min`() {
        // 1 000 ordinaires x 2 + 3 000 lus x 0,10 + 6 000 écrits x 2,50 = 2 000 + 300 + 15 000 µ$
        val cost = CostCalculator.text("gpt-6.1-sol", simple(10_000, 0, cached = 3_000, written = 6_000), null)
        assertEquals(17_300L, cost.text)
    }

    @Test
    fun `un cache lu coute vingt fois moins cher qu une entree ordinaire`() {
        val ordinaire = CostCalculator.text("gpt-6.1-sol", simple(100_000, 0), null).text
        val enCache = CostCalculator.text("gpt-6.1-sol", simple(100_000, 0, cached = 100_000), null).text
        assertEquals(200_000L, ordinaire)
        assertEquals(10_000L, enCache)
    }

    @Test
    fun `recherches web - un centime par appel, en plus du texte`() {
        val cost = CostCalculator.text("gpt-6.1-sol", simple(1_000, 0), webSearches = 3)
        assertEquals(2_000L, cost.text)
        assertEquals(30_000L, cost.search)
        assertEquals(32_000L, cost.total)
    }

    @Test
    fun `recherches web absentes de la reponse - zero`() {
        assertEquals(0L, CostCalculator.text("gpt-6.1-sol", simple(1_000, 0), null).search)
    }

    @Test
    fun `au dela de 272 000 jetons d entree, toute la requete est au tarif long contexte`() {
        // 300 000 x 4 $/M + 1 000 x 15 $/M
        assertEquals(1_215_000L, CostCalculator.text("gpt-6.1-sol", simple(300_000, 1_000), null).text)
        // Seuil inclus dans le tarif normal : 272 000 x 2 $/M
        assertEquals(544_000L, CostCalculator.text("gpt-6.1-sol", simple(272_000, 0), null).text)
    }

    @Test
    fun `terra coute moitie moins que sol`() {
        val sol = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null).text
        val terra = CostCalculator.text("gpt-6.1-terra", simple(10_000, 2_000), null).text
        assertEquals(20_000L, terra)
        assertEquals(sol / 2, terra)
    }

    @Test
    fun `identifiant date du modele reconnu, modele inconnu signale sans inventer de prix`() {
        assertEquals(40_000L, CostCalculator.text("gpt-6.1-sol-2026-11-02", simple(10_000, 2_000), null).text)

        val inconnu = CostCalculator.text("gpt-9", simple(10_000, 2_000), webSearches = 1)
        assertFalse(inconnu.priced)
        assertEquals(0L, inconnu.text)
        assertEquals(10_000L, inconnu.search) // le tarif de la recherche ne dépend pas du modèle
    }

    @Test
    fun `arrondi une seule fois a la fin, au micro-dollar le plus proche`() {
        // 1 jeton en cache = 0,1 µ$. L'arrondi porte sur le total de la requête, pas sur chaque jeton.
        assertEquals(0L, CostCalculator.text("gpt-6.1-sol", simple(4, 0, cached = 4), null).text) // 0,4
        assertEquals(1L, CostCalculator.text("gpt-6.1-sol", simple(5, 0, cached = 5), null).text) // 0,5 → 1
        assertEquals(1L, CostCalculator.text("gpt-6.1-sol", simple(14, 0, cached = 14), null).text) // 1,4
        assertEquals(2L, CostCalculator.text("gpt-6.1-sol", simple(15, 0, cached = 15), null).text) // 1,5 → 2
    }

    @Test
    fun `usage incoherent - jamais de montant negatif`() {
        val cost = CostCalculator.text("gpt-6.1-sol", simple(100, 0, cached = 500, written = 500), null)
        assertTrue(cost.text >= 0)
    }

    @Test
    fun `transcription gpt-transcribe - 0,0045 dollar la minute`() {
        assertEquals(4_500L, CostCalculator.transcription("gpt-transcribe", 60_000).transcription)
        assertEquals(6_750L, CostCalculator.transcription("gpt-transcribe", 90_000).transcription)
        assertEquals(0L, CostCalculator.transcription("gpt-transcribe", 0).total)
    }

    @Test
    fun `transcription gpt-live-transcribe - 0,017 dollar la minute`() {
        assertEquals(17_000L, CostCalculator.transcription("gpt-live-transcribe", 60_000).transcription)
        // 1 s = 283,33 µ$ → 283
        assertEquals(283L, CostCalculator.transcription("gpt-live-transcribe", 1_000).transcription)
        // une dictée de repas de 2 min, en direct : 0,034 $
        assertEquals(34_000L, CostCalculator.transcription("gpt-live-transcribe", 120_000).total)
    }

    @Test
    fun `transcription d un modele inconnu - non chiffree`() {
        val cost = CostCalculator.transcription("whisper-1", 60_000)
        assertFalse(cost.priced)
        assertEquals(0L, cost.total)
    }

    @Test
    fun `appel vocal - 0,05 dollar la minute, 15 s au minimum`() {
        assertEquals(50_000L, CostCalculator.live("gpt-live-1", 60_000).live)
        assertEquals(500_000L, CostCalculator.live("gpt-live-1", 600_000).total)
        // 4 s de session (raccroché pendant la connexion) : facturé comme 15 s.
        assertEquals(12_500L, CostCalculator.live("gpt-live-1", 4_000).live)
        assertEquals(0L, CostCalculator.live("gpt-live-1", 4_000).transcription)
        assertFalse(CostCalculator.live("gpt-live-2", 60_000).priced)
    }

    @Test
    fun `reflexion de l appel en priorite - tarif double`() {
        val standard = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null)
        val priority = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null, serviceTier = "priority")
        assertEquals(2 * standard.text, priority.text)
        assertEquals(standard.text, CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null, serviceTier = "default").text)
    }

    /**
     * Vraie API, 6/11 : une requête envoyée avec `service_tier: "priority"` revient avec `service_tier: "fast"` (le
     * niveau a été renommé « Fast mode » le 30/07/2026, page des tarifs), facturé deux fois le Standard.
     */
    @Test
    fun `niveau renvoye fast par la vraie API - tarif double aussi`() {
        val standard = CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null)
        assertEquals(2 * standard.text, CostCalculator.text("gpt-6.1-sol", simple(10_000, 2_000), null, serviceTier = "fast").text)
    }

    @Test
    fun `la table de prix est datee et sourcee`() {
        assertEquals("2026-11-02", PriceTable.AS_OF)
        assertTrue(PriceTable.SOURCE.startsWith("https://developers.openai.com/"))
    }

    @Test
    fun `montants affiches en francais`() {
        val nbsp = ' '
        assertEquals("12,34${nbsp}$", Money.format(12_340_000))
        assertEquals("0,00${nbsp}$", Money.format(0))
        assertEquals("moins de 0,01${nbsp}$", Money.format(2_000))
        assertEquals("0,03${nbsp}$", Money.format(34_900))
        assertEquals("1${nbsp}234,50${nbsp}$", Money.format(1_234_500_000))
        assertEquals("82${nbsp}$", Money.formatWhole(UsageBudget.MONTHLY))
    }
}
