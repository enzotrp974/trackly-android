package app.trackly.assistant

import app.trackly.assistant.chat.notice
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.InputItems
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesRequest
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.testing.OpenAiFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Le client HTTP de la Responses API face à un serveur local (MockWebServer) : chaque type
 * d'erreur produit la bonne erreur, et donc le bon message. Aucun appel à OpenAI.
 */
class OpenAiHttpTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private var key: String? = "sk-test-cle-factice-0000000000"

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client(
        http: OkHttpClient = OkHttpClient(),
        retry: RetryPolicy = RetryPolicy(maxRetries = 2),
        online: Boolean = true,
    ) = ResponsesClient(
        OkHttpResponsesTransport(http, apiKey = { key }, baseUrl = server.url("/v1/"), isOnline = { online }),
        retry,
        sleep = { sleeps += it },
    )

    private val request = ResponsesRequest(instructions = "Consignes", input = listOf(InputItems.user("bonjour")))

    private fun reply(code: Int, fixture: String, retryAfter: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { retryAfter?.let { addHeader("Retry-After", it) } }
        .body(OpenAiFixtures.text(fixture))
        .build()

    private fun errorOf(block: suspend () -> Unit): AssistantError = runBlocking {
        try {
            block()
            fail("une erreur était attendue")
            error("inaccessible")
        } catch (e: AssistantException) {
            e.error
        }
    }

    @Test
    fun `requete - POST v1 responses, cle en en-tete Bearer, corps JSON`() = runBlocking {
        server.enqueue(reply(200, "question-reponse.json"))

        val response = client().create(request)

        assertTrue(response.text.startsWith("Une pizza"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/responses", recorded.url.encodedPath)
        assertEquals("Bearer sk-test-cle-factice-0000000000", recorded.headers["Authorization"])
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sans cle, rien n est envoye`() {
        key = null
        assertEquals(AssistantError.MissingKey, errorOf { client().create(request) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sans reseau - pas d Internet, rien n est envoye`() {
        assertEquals(AssistantError.NoInternet, errorOf { client(online = false).create(request) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `serveur injoignable - pas d Internet`() {
        val noDns = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()
        assertEquals(AssistantError.NoInternet, errorOf { client(http = noDns).create(request) })
    }

    @Test
    fun `reponse trop lente - delai depasse`() {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("{}").build())
        val impatient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        assertEquals(AssistantError.Timeout, errorOf { client(http = impatient).create(request) })
    }

    @Test
    fun `401 - cle refusee, sans nouvel essai`() {
        server.enqueue(reply(401, "erreur-401.json"))
        assertEquals(AssistantError.KeyRejected, errorOf { client().create(request) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 credit_balance_exhausted - credit epuise, sans nouvel essai`() {
        server.enqueue(reply(429, "erreur-429-credit.json"))
        assertEquals(AssistantError.CreditExhausted, errorOf { client().create(request) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 project_spend_limit_exceeded - plafond atteint, sans nouvel essai`() {
        server.enqueue(reply(429, "erreur-429-plafond.json"))
        assertEquals(AssistantError.SpendLimitReached, errorOf { client().create(request) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 insufficient_quota - credit epuise`() {
        server.enqueue(reply(429, "erreur-429-quota.json"))
        assertEquals(AssistantError.CreditExhausted, errorOf { client().create(request) })
    }

    @Test
    fun `429 de debit - nouvel essai apres Retry-After, puis succes`() = runBlocking {
        server.enqueue(reply(429, "erreur-429-debit.json", retryAfter = "3"))
        server.enqueue(reply(200, "question-reponse.json"))

        val response = client().create(request)

        assertTrue(response.text.isNotEmpty())
        assertEquals(2, server.requestCount)
        assertEquals(listOf(3_000L), sleeps)
    }

    @Test
    fun `429 de debit persistant - limite de debit`() {
        repeat(3) { server.enqueue(reply(429, "erreur-429-debit.json", retryAfter = "1")) }
        assertEquals(AssistantError.RateLimited(1), errorOf { client().create(request) })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `500 persistant - erreur serveur apres les essais`() {
        repeat(3) { server.enqueue(reply(500, "erreur-500.json")) }
        assertEquals(AssistantError.ServerError(500), errorOf { client().create(request) })
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `chaque erreur a son propre message`() {
        val errors = listOf(
            AssistantError.MissingKey,
            AssistantError.NoInternet,
            AssistantError.Timeout,
            AssistantError.KeyRejected,
            AssistantError.Forbidden,
            AssistantError.CreditExhausted,
            AssistantError.SpendLimitReached,
            AssistantError.RateLimited(2),
            AssistantError.ServerError(503),
            AssistantError.Unexpected(400, "bad"),
        )
        val titles = errors.map { it.notice().title }
        assertEquals("un titre distinct par erreur", errors.size, titles.toSet().size)
        assertEquals("Pas de connexion Internet", AssistantError.NoInternet.notice().title)
        assertEquals("Clé OpenAI refusée", AssistantError.KeyRejected.notice().title)
        assertEquals("Crédit OpenAI épuisé", AssistantError.CreditExhausted.notice().title)
        assertEquals("Plafond de dépense atteint", AssistantError.SpendLimitReached.notice().title)
        // Une clé refusée ou un crédit épuisé ne se règlent pas en réessayant.
        assertTrue(!AssistantError.KeyRejected.notice().retryable && AssistantError.KeyRejected.notice().opensKeySettings)
        assertTrue(!AssistantError.CreditExhausted.notice().retryable)
        assertTrue(!AssistantError.SpendLimitReached.notice().retryable)
        assertTrue(AssistantError.NoInternet.notice().retryable && AssistantError.RateLimited(null).notice().retryable)
    }
}
