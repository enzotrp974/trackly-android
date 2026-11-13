package app.trackly.assistant

import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.key.InMemoryKeyVerificationPrefs
import app.trackly.assistant.key.KeyCheck
import app.trackly.assistant.key.KeyChecker
import app.trackly.assistant.key.KeyVerificationPrefs
import app.trackly.assistant.key.KeyVerificationState
import app.trackly.assistant.key.KeyVerifier
import app.trackly.assistant.key.VerifyingApiKeyStore
import app.trackly.assistant.key.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Vérification gratuite de la clé (`GET /v1/models`) face à un serveur local. Clés factices uniquement :
 * aucun appel à OpenAI, aucune vraie clé.
 */
class KeyVerificationTest {
    private val server = MockWebServer()
    private val fakeKey = "sk-test-cle-factice-0000000000"

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun checker(online: Boolean = true) = KeyChecker(OkHttpClient(), server.url("/v1/"), isOnline = { online }, io = Dispatchers.Unconfined)

    @Test
    fun `cle valide - GET models avec la cle en en-tete, rien d autre`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())
        assertEquals(KeyCheck.Valid, checker().check(fakeKey))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer $fakeKey", request.headers["Authorization"])
        assertEquals(0, request.body?.size ?: 0)
        assertTrue(request.url.query.isNullOrEmpty())
    }

    @Test
    fun `401 et 403 - cle refusee`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Incorrect API key","code":"invalid_api_key"}}""").build())
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())
        assertEquals(KeyCheck.Refused, checker().check(fakeKey))
        assertEquals(KeyCheck.Refused, checker().check(fakeKey))
    }

    @Test
    fun `erreur du serveur ou trop de demandes - a refaire plus tard, pas refusee`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(KeyCheck.ServerTrouble, checker().check(fakeKey))
        assertEquals(KeyCheck.ServerTrouble, checker().check(fakeKey))
    }

    @Test
    fun `sans reseau - rien n est envoye`() = runBlocking {
        assertEquals(KeyCheck.NoInternet, checker(online = false).check(fakeKey))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `serveur injoignable - pas d Internet`() = runBlocking {
        val closed = KeyChecker(OkHttpClient(), server.url("/v1/"), io = Dispatchers.Unconfined)
        server.close()
        assertEquals(KeyCheck.NoInternet, closed.check(fakeKey))
    }

    @Test
    fun `caractere impossible dans un en-tete - refusee, sans planter`() = runBlocking {
        assertEquals(KeyCheck.Refused, checker().check("sk-clé-avec-accent-0000000"))
        assertEquals(0, server.requestCount)
    }

    private fun verifier(store: InMemoryApiKeyStore, prefs: InMemoryKeyVerificationPrefs = InMemoryKeyVerificationPrefs(), online: Boolean = true) =
        KeyVerifier(store, checker(online), CoroutineScope(Dispatchers.Unconfined), prefs)

    @Test
    fun `enregistrer une cle valide - Cle valide, rappel emis, fin de cle gardee`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val store = InMemoryApiKeyStore()
        val prefs = InMemoryKeyVerificationPrefs()
        val verifier = verifier(store, prefs)
        val wrapped = VerifyingApiKeyStore(store, verifier)
        val events = mutableListOf<Unit>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { verifier.verified.collect { events += it } }

        wrapped.save(fakeKey)

        assertEquals(KeyVerificationState.Valid, verifier.state.value)
        assertEquals("Clé valide", verifier.state.value.message())
        assertEquals(1, events.size)
        assertEquals("0000", prefs.validLastFour)
        collector.cancel()
    }

    @Test
    fun `cle fausse - Cle refusee, pas de rappel`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        val store = InMemoryApiKeyStore()
        val verifier = verifier(store)
        val events = mutableListOf<Unit>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { verifier.verified.collect { events += it } }

        VerifyingApiKeyStore(store, verifier).save(fakeKey)

        assertEquals(KeyVerificationState.Refused, verifier.state.value)
        assertEquals("Clé refusée", verifier.state.value.message())
        assertTrue(events.isEmpty())
        collector.cancel()
    }

    @Test
    fun `hors ligne - vérifiée plus tard, puis reprise quand le reseau revient`() = runBlocking {
        val store = InMemoryApiKeyStore()
        var online = false
        val verifier = KeyVerifier(store, KeyChecker(OkHttpClient(), server.url("/v1/"), isOnline = { online }, io = Dispatchers.Unconfined), CoroutineScope(Dispatchers.Unconfined))
        VerifyingApiKeyStore(store, verifier).save(fakeKey)
        assertEquals(KeyVerificationState.Pending(noInternet = true), verifier.state.value)
        assertEquals("Pas d’Internet, vérifiée plus tard", verifier.state.value.message())

        online = true
        server.enqueue(MockResponse.Builder().code(HttpURLConnection.HTTP_OK).body("{}").build())
        verifier.retryIfPending()
        assertEquals(KeyVerificationState.Valid, verifier.state.value)
    }

    @Test
    fun `une cle deja reconnue reste valide au demarrage, sans appel`() = runBlocking {
        val store = InMemoryApiKeyStore(fakeKey)
        val verifier = verifier(store, InMemoryKeyVerificationPrefs("0000"))
        assertEquals(KeyVerificationState.Valid, verifier.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `une autre cle n est pas reconnue d office, et la supprimer efface la verification`() = runBlocking {
        val store = InMemoryApiKeyStore(fakeKey)
        val prefs = InMemoryKeyVerificationPrefs("9999")
        val verifier = verifier(store, prefs)
        assertEquals(KeyVerificationState.Unchecked, verifier.state.value)

        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val wrapped = VerifyingApiKeyStore(store, verifier)
        wrapped.save(fakeKey)
        assertEquals(KeyVerificationState.Valid, verifier.state.value)
        wrapped.clear()
        assertEquals(KeyVerificationState.Unchecked, verifier.state.value)
        assertNull(prefs.validLastFour)
    }

    // ───── Deux contrôles qui se chevauchent : seul le dernier compte ─────

    private fun concurrentVerifier(store: InMemoryApiKeyStore, prefs: InMemoryKeyVerificationPrefs) =
        KeyVerifier(store, KeyChecker(OkHttpClient(), server.url("/v1/")), CoroutineScope(Dispatchers.Default), prefs)

    private suspend fun waitUntil(@Suppress("UNUSED_PARAMETER") what: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `l ancien controle qui finit en dernier n ecrase pas le resultat de la cle actuelle`() = runBlocking {
        // Première clé : OpenAI répond lentement (refusée). Seconde clé : réponse immédiate (valide).
        server.enqueue(MockResponse.Builder().code(401).body("{}").headersDelay(800, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val store = InMemoryApiKeyStore()
        val prefs = InMemoryKeyVerificationPrefs()
        val verifier = concurrentVerifier(store, prefs)
        val wrapped = VerifyingApiKeyStore(store, verifier)
        val events = mutableListOf<Unit>()
        // Abonné avant le premier enregistrement : le flux ne rejoue rien, un collecteur en retard manquerait l'événement.
        val collector = CoroutineScope(Dispatchers.Default).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            verifier.verified.collect { synchronized(events) { events += it } }
        }

        wrapped.save("sk-test-ancienne-cle-1111")
        waitUntil("la première requête est partie") { server.requestCount == 1 }
        wrapped.save(fakeKey)
        waitUntil("la clé actuelle est reconnue") { verifier.state.value == KeyVerificationState.Valid }
        // Le refus de l'ancienne clé arrive maintenant, après le résultat de la clé actuelle.
        delay(1_200)

        assertEquals(KeyVerificationState.Valid, verifier.state.value)
        assertEquals("0000", prefs.validLastFour)
        assertEquals(2, server.requestCount)
        assertEquals(1, synchronized(events) { events.size })
        collector.cancel()
    }

    @Test
    fun `supprimer la cle pendant un controle - le resultat tardif ne la rend pas valide`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        val store = InMemoryApiKeyStore()
        val prefs = InMemoryKeyVerificationPrefs()
        val verifier = concurrentVerifier(store, prefs)
        val wrapped = VerifyingApiKeyStore(store, verifier)

        wrapped.save(fakeKey)
        waitUntil("la requête est partie") { server.requestCount == 1 }
        wrapped.clear()
        delay(900)

        assertEquals(KeyVerificationState.Unchecked, verifier.state.value)
        assertNull(prefs.validLastFour)
    }

    // ───── Un enregistrement qui s'intercale entre le test du numéro et les écritures ─────

    @Test
    fun `une nouvelle cle enregistree entre le test du numero et les ecritures n est pas ecrasee`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build()) // contrôle de l'ancienne clé : valide
        server.enqueue(MockResponse.Builder().code(401).body("{}").build()) // contrôle de la nouvelle clé : refusée
        val store = InMemoryApiKeyStore(fakeKey)
        lateinit var verifier: KeyVerifier
        var intruder: Thread? = null
        val prefs = object : KeyVerificationPrefs {
            private var armed = true
            override var validLastFour: String? = null
                set(value) {
                    field = value
                    // Le contrôle de l'ancienne clé écrit son résultat : à cet instant, une nouvelle clé est enregistrée.
                    if (value != null && armed) {
                        armed = false
                        intruder = Thread { verifier.onSaved() }.also { it.start(); it.join(300) }
                    }
                }
        }
        verifier = KeyVerifier(store, checker(), CoroutineScope(Dispatchers.Unconfined), prefs)

        verifier.verifyNow()
        intruder!!.join(5_000)

        // La dernière clé enregistrée décide : refusée, et l'ancienne clé n'est pas gardée comme « valide ».
        assertEquals(KeyVerificationState.Refused, verifier.state.value)
        assertNull(prefs.validLastFour)
    }

    /** Clé dont la première lecture (au démarrage) attend qu'on l'ouvre. */
    private class SlowStartStore(private val inner: InMemoryApiKeyStore) : app.trackly.assistant.key.ApiKeyStore by inner {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun refresh() {
            gate.await()
            inner.refresh()
        }
    }

    @Test
    fun `le controle du demarrage est numerote - un contrôle demande entre-temps n est pas ecrase`() = runBlocking<Unit> {
        val store = SlowStartStore(InMemoryApiKeyStore(fakeKey))
        // Hors ligne : le contrôle demandé donne « vérifiée plus tard » et garde la fin de clé reconnue.
        val verifier = KeyVerifier(store, checker(online = false), CoroutineScope(Dispatchers.Unconfined), InMemoryKeyVerificationPrefs("0000"))

        verifier.verify()
        assertEquals(KeyVerificationState.Pending(noInternet = true), verifier.state.value)
        store.gate.complete(Unit) // la lecture du démarrage finit après

        assertEquals(KeyVerificationState.Pending(noInternet = true), verifier.state.value)
    }
}
