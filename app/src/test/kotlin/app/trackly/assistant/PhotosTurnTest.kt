package app.trackly.assistant

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.PendingQuestions
import app.trackly.assistant.openai.ImageInput
import app.trackly.assistant.openai.ModelResponse
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.openai.SearchLocation
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.ProductKind
import app.trackly.testing.ModelReplies
import app.trackly.testing.OpenAiFixtures
import app.trackly.testing.SampleData
import app.trackly.testing.ScriptedModel
import app.trackly.testing.ScriptedTransport
import app.trackly.usage.CostCalculator
import app.trackly.usage.PriceTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Photos et recherche web : tours de conversation avec des réponses de modèle
 * simulées (jamais de réseau). On vérifie ce que l'app envoie (image en `input_image` en `detail: high`,
 * outil `web_search` localisé), ce qu'elle enregistre (étiquette comptée et fiche mémorisée, estimation
 * puis question en attente puis nouvelle version), et ce qu'elle affiche des recherches (sources, coût).
 */
class PhotosTurnTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val photo = ImageInput("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBD")

    private fun engine(): TracklyEngine = SampleData.emptyEngine(SampleData.clock("2026-11-02T16:10")).also {
        runBlocking { check(it.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied) }
    }

    private fun interpreter(engine: TracklyEngine, transport: app.trackly.assistant.openai.ResponsesTransport, sink: UsageSink = UsageSink.None) =
        TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine, usageSink = sink)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    /** Contenu du dernier message `user` de la requête [index]. */
    private fun userContent(transport: ScriptedTransport, index: Int) =
        transport.requests[index]["input"]!!.jsonArray.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!.jsonArray.map { it.jsonObject }

    private val barre = """{"name":"Barre protéinée Vita chocolat","brand":"Vita","variant":"chocolat","per_base":{"quantity":100,"unit":"g","kcal":380,"protein_g":36,"carbs_g":22,"fat_g":14,"fiber_g":null},"per_portion":{"label":"1 barre","weight":55,"weight_unit":"g","kcal":209,"protein_g":19.8,"carbs_g":12.1,"fat_g":7.7,"fiber_g":null},"shortcuts":[],"source_type":"label","source_description":"étiquette photographiée","confidence":"high"}"""

    // ───── Requête : image et recherche web ─────

    @Test
    fun `la requete porte l image en input_image high et l outil web_search localise selon le telephone`() = runBlocking {
        val transport = ScriptedTransport(ModelReplies.text("Je vois une barre."))

        interpreter(engine(), transport).run(TurnInput("c'est quoi ça ?", images = listOf(photo, photo)))

        val content = userContent(transport, 0)
        assertEquals(listOf("input_text", "input_image", "input_image"), content.map { it.str("type") })
        assertEquals(photo.url, content[1].str("image_url"))
        assertEquals("high", content[1].str("detail"))

        val tools = transport.requests[0]["tools"]!!.jsonArray.map { it.jsonObject }
        val search = tools.single { it.str("type") == "web_search" }
        val location = search["user_location"]!!.jsonObject
        assertEquals("approximate", location.str("type"))
        assertEquals(app.trackly.core.time.TrackingDay.ZONE.id, "Europe/Zurich") // fuseau fixé pour les tests (build.gradle.kts)
        assertEquals("Europe/Zurich", location.str("timezone"))
        assertEquals(SearchLocation.COUNTRY, location.str("country"))
        // Borne de coût : au plus quelques recherches par réponse.
        assertEquals(5, transport.requests[0]["max_tool_calls"]!!.jsonPrimitive.content.toInt())
        // Les outils de l'app restent des fonctions strictes ; la recherche vient en dernier.
        assertEquals("web_search", tools.last().str("type"))
        assertTrue(tools.dropLast(1).all { it.str("type") == "function" })
    }

    @Test
    fun `les consignes separent photos et recherche web et disent quand ne pas chercher`() = runBlocking {
        val transport = ScriptedTransport(ModelReplies.text("ok"))
        interpreter(engine(), transport).run(TurnInput("bonjour"))

        val instructions = transport.requests[0].str("instructions")
        val photos = instructions.substringAfter("# Photos\n").substringBefore("\n\n# ")
        val web = instructions.substringAfter("# Recherche sur Internet\n").substringBefore("\n\n# ")
        assertTrue(photos.contains("comptée comme consommée") || photos.contains("compté comme consommé"))
        assertTrue(photos.contains("Estime selon cette photo"))
        assertTrue(photos.contains("Pose-moi des questions"))
        assertTrue(photos.contains("pizza margherita"))
        assertTrue(web.contains("NE CHERCHE PAS"))
        assertTrue(web.contains("étiquette nutritionnelle lisible"))
        assertTrue(web.contains("carte ou la fiche nutritionnelle de l'enseigne"))
        // Plus de consigne qui interdit d'enregistrer une photo sans texte.
        assertFalse(instructions.contains("une photo sans indication ne suffit pas"))
    }

    // ───── Étiquette ─────

    @Test
    fun `etiquette seule sans texte - comptee, fiche produit memorisee, aucune recherche`() = runBlocking {
        val engine = engine()
        val events = mutableListOf<UsageEvent>()
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":null,"new_product":$barre,"quantity":1,"unit":"portion","assumptions":["une barre entière supposée mangée"],"alternatives":[]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté pour ton snack."),
        )

        val outcome = interpreter(engine, transport, { events += it }).run(TurnInput("", images = listOf(photo))) as TurnOutcome.Completed

        // Comptée par défaut, même sans texte : kcal calculées par l'app depuis la fiche (1 portion).
        val consumption = engine.daySummary(monday).consumptions.single()
        assertEquals(209.0, consumption.food.nutrition.kcal, 0.0)
        // Fiche mémorisée comme produit ponctuel, liée à la consommation.
        val product = engine.allProducts().single()
        assertEquals(ProductKind.Occasional, product.kind)
        assertEquals(product.productId, consumption.food.productId)
        val card = outcome.cards.single() as RecordCard
        assertEquals(listOf(product.productId), card.memorized.map { it.productId })
        assertNull(card.question)
        // Le modèle reçoit une phrase neutre à la place du texte vide, et l'image.
        val content = userContent(transport, 0)
        assertEquals("(photo envoyée sans message)", content[0].str("text"))
        assertEquals("input_image", content[1].str("type"))
        // Pas de recherche.
        assertTrue(events.all { it.webSearchRequests == null })
        assertFalse(outcome.cards.any { it is SourcesCard })
    }

    @Test
    fun `etiquette complete avec quantite - aucune recherche et un seul aller-retour d outil`() = runBlocking {
        val engine = engine()
        val events = mutableListOf<UsageEvent>()
        val transport = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":{"product_id":null,"new_product":$barre,"quantity":40,"unit":"g","assumptions":[],"alternatives":[]},"food":null,"recipe_id":null,"moment":"snack","day":null}],"question":null}""",
            ),
            ModelReplies.text("C'est noté."),
        )

        val outcome = interpreter(engine, transport, { events += it }).run(TurnInput("j'ai mangé 40 g de ça", images = listOf(photo))) as TurnOutcome.Completed

        assertEquals(152.0, engine.daySummary(monday).consumptions.single().food.nutrition.kcal, 0.0) // 40 g x 380 / 100
        // Aucune recherche faite : aucun appel `web_search_call`, aucun coût de recherche, aucune source.
        assertTrue(transport.requests.none { it.toString().contains("web_search_call") })
        assertEquals(listOf(null, null), events.map { it.webSearchRequests })
        assertEquals(0L, events.sumOf { CostCalculator.text(it.model, it.usage, it.webSearchRequests).search })
        assertEquals(2, transport.requests.size)
        assertNull((outcome.cards.single() as RecordCard).question)
    }

    // ───── Assiette : enregistrer d'abord, affiner ensuite ─────

    @Test
    fun `assiette ambigue - estimation enregistree, question en attente, la reponse affine par une nouvelle version`() = runBlocking {
        val engine = engine()
        val plat = """{"label":"Plat d'assiette (contenu incertain)","quantity":null,"unit":null,"kcal":650,"protein_g":28,"carbs_g":70,"fat_g":26,"fiber_g":null,"assumptions":["portion moyenne supposée","sauce crémeuse supposée"],"confidence":"low"}"""
        val first = ScriptedTransport(
            ModelReplies.call(
                "record_consumptions",
                """{"items":[{"product":null,"food":$plat,"recipe_id":null,"moment":"midi","day":null}],"question":"Il y avait quoi environ dedans ?"}""",
            ),
            ModelReplies.text("J'ai compté une assiette moyenne. Il y avait quoi environ dedans ?"),
        )

        // 1. Photo seule : la meilleure estimation est comptée tout de suite, la question reste en attente.
        val sent = interpreter(engine, first).run(TurnInput("", images = listOf(photo))) as TurnOutcome.Completed
        val card = sent.cards.single() as RecordCard
        assertEquals("Il y avait quoi environ dedans ?", card.question)
        assertEquals(650.0, engine.daySummary(monday).totals.kcal, 0.0)
        val entryId = card.items.single().entryId
        assertTrue(card.summaryForModel().contains("QUESTION EN ATTENTE"))
        assertTrue(card.summaryForModel().contains("entry_id $entryId"))

        // La carte signale la question tant que l'utilisateur n'a pas répondu.
        val at = Instant.parse("2026-11-02T15:10:00Z")
        val asked = listOf(
            ChatMessage(1, at, ChatRole.User, "[1 photo envoyée]"),
            ChatMessage(2, at, ChatRole.Assistant, sent.reply, sent.cards),
        )
        assertEquals(setOf(card.actionId), PendingQuestions.of(asked))

        // 2. La réponse de l'utilisateur affine : nouvelle version de la MÊME consommation (pas de doublon).
        val changes = """{"label":"Curry de poulet, riz","quantity":null,"unit":null,"nutrition":{"kcal":720,"protein_g":38,"carbs_g":80,"fat_g":24,"fiber_g":null},"assumptions":["poulet curry et riz basmati, portion de restaurant"],"confidence":"medium"}"""
        val model = ScriptedModel(
            { req ->
                // L'historique envoyé au modèle rappelle la question et l'entry_id à corriger.
                assertTrue(req.body.toString().contains("QUESTION EN ATTENTE"))
                ScriptedModel.toolCall(
                    "correct_consumption",
                    """{"entry_id":"$entryId","changes":$changes,"food":null,"moment":null,"day":null}""",
                )
            },
            { ScriptedModel.text("C'est corrigé : curry de poulet et riz.") },
        )
        val history = listOf(
            HistoryMessage(HistoryMessage.Role.User, "[1 photo envoyée]"),
            HistoryMessage(HistoryMessage.Role.Assistant, asked[1].toHistory().text),
        )

        val refined = interpreter(engine, model).run(TurnInput("du poulet curry avec du riz", history = history)) as TurnOutcome.Completed

        val versions = engine.history(app.trackly.core.model.EntryId(entryId))
        assertEquals(listOf(1, 2), versions.map { it.version })
        assertEquals(720.0, engine.daySummary(monday).totals.kcal, 0.0)
        assertEquals(1, engine.daySummary(monday).consumptions.size)
        assertEquals(RecordKind.Corrected, (refined.cards.single() as RecordCard).kind)

        // Après la réponse, la question disparaît de la carte.
        val answered = asked + ChatMessage(3, at, ChatRole.User, "du poulet curry avec du riz") + ChatMessage(4, at, ChatRole.Assistant, refined.reply, refined.cards)
        assertTrue(PendingQuestions.of(answered).isEmpty())
    }

    // ───── Restaurant nommé : recherche, sources, confirmation ─────

    @Test
    fun `restaurant nomme - recherche web, estimation enregistree, confirmation demandee, sources et cout des recherches`() = runBlocking {
        val engine = engine()
        val events = mutableListOf<UsageEvent>()
        val bigMac = """{"label":"Big Mac (McDonald's)","quantity":1,"unit":"pièce","kcal":503,"protein_g":26,"carbs_g":41,"fat_g":26,"fiber_g":null,"assumptions":["valeurs de la carte de l'enseigne (McDonald's)"],"confidence":"high"}"""
        val transport = ScriptedTransport(
            ModelReplies.searchThenCall(
                listOf("McDonald's Big Mac valeurs nutritionnelles", "McDonald's Big Mac kcal"),
                "record_consumptions",
                """{"items":[{"product":null,"food":$bigMac,"recipe_id":null,"moment":"midi","day":null}],"question":"C'est bien le Big Mac classique ?"}""",
            ),
            ModelReplies.textWithCitations(
                "J'ai compté un Big Mac d'après la carte de l'enseigne. C'est bien le Big Mac classique ?",
                listOf(ModelReplies.Source("Big Mac | McDonald's", "https://www.exemple.com/fr/produits/big-mac?utm_source=openai")),
            ),
        )

        val outcome = interpreter(engine, transport, { events += it }).run(TurnInput("j'ai pris un Big Mac chez McDonald's")) as TurnOutcome.Completed

        // Estimation enregistrée aussitôt + confirmation demandée sur la carte.
        assertEquals(503.0, engine.daySummary(monday).totals.kcal, 0.0)
        val card = outcome.cards.first() as RecordCard
        assertEquals("C'est bien le Big Mac classique ?", card.question)
        // Les éléments `web_search_call` repartent tels quels au tour suivant (recherches faites chez OpenAI).
        val echoed = transport.requests[1]["input"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "web_search_call" }
        assertEquals(2, echoed.size)
        assertEquals("search", echoed[0]["action"]!!.jsonObject.str("type"))
        // Réponse sans le balisage de la citation ; la source est une carte discrète, avec une adresse nettoyée.
        assertEquals("J'ai compté un Big Mac d'après la carte de l'enseigne. C'est bien le Big Mac classique ?", outcome.reply)
        val sources = outcome.cards.last() as SourcesCard
        assertEquals("https://www.exemple.com/fr/produits/big-mac", sources.sources.single().url)
        assertEquals("Big Mac | McDonald's", sources.sources.single().label)
        // Le nombre de recherches remonte dans l'usage et dans le coût : 2 x 0,01 $.
        assertEquals(listOf(2, null), events.map { it.webSearchRequests })
        assertEquals(2 * PriceTable.WEB_SEARCH_PER_CALL, events.sumOf { CostCalculator.text(it.model, it.usage, it.webSearchRequests).search })
    }

    // ───── Lecture de la réponse ─────

    @Test
    fun `reponse de recherche web au format de la doc - citations, texte propre, recherches comptees`() {
        val response = ModelResponse.parse(OpenAiFixtures.json("recherche-web-reponse.json"))

        assertEquals("Je me suis basé sur la fiche nutritionnelle de l'enseigne.", response.text)
        val citation = response.citations.single()
        assertEquals("Big Mac | McDonald's", citation.title)
        assertTrue(citation.url.startsWith("https://www.exemple.com/fr/produits/big-mac"))
        assertEquals(1, response.webSearchRequests)
    }

    @Test
    fun `sans tool_usage les recherches sont comptees d apres les web_search_call, hors pages ouvertes`() {
        val json = OpenAiFixtures.json("recherche-web-reponse.json").toMutableMap().apply { remove("tool_usage") }
        val response = ModelResponse.parse(JsonObject(json))

        // 2 éléments web_search_call : une recherche et une page ouverte (non facturée).
        assertEquals(1, response.webSearchRequests)
    }

    @Test
    fun `des plages qui ne recouvrent aucun lien laissent le texte intact`() {
        val json = """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Environ 500 kcal.","annotations":[{"type":"url_citation","start_index":3,"end_index":9,"url":"https://exemple.ch","title":"x"}]}]}]}"""
        val response = ModelResponse.parse(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)

        assertEquals("Environ 500 kcal.", response.text)
        assertEquals(1, response.citations.size)
    }

    @Test
    fun `un repere de citation interne laisse dans le texte n est jamais affiche (Terra, banc d evaluation)`() {
        // Forme relevée avec gpt-6.1-terra : caractères à usage privé U+E200…U+E201, sans annotation.
        val marker = "citeturn45874search3"
        val json = buildJsonObject {
            put("id", "r")
            put("status", "completed")
            putJsonArray("output") {
                addJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "output_text")
                            put("text", "C'est enregistré pour ton snack. $marker Il te reste 2 320 kcal. $marker")
                            putJsonArray("annotations") {}
                        }
                    }
                }
            }
        }
        val response = ModelResponse.parse(json)

        assertEquals("C'est enregistré pour ton snack. Il te reste 2 320 kcal.", response.text)
    }

    @Test
    fun `une adresse qui n est pas http ou https n est pas affichee comme source`() {
        assertNull(app.trackly.assistant.cards.SourceLink.of(app.trackly.assistant.openai.Citation("javascript:alert(1)", "x")))
        assertNull(app.trackly.assistant.cards.SourceLink.of(app.trackly.assistant.openai.Citation("intent://x#Intent;end", null)))
        assertEquals("exemple.ch", app.trackly.assistant.cards.SourceLink.of(app.trackly.assistant.openai.Citation("https://www.exemple.ch/page?utm_source=openai", null))!!.label)
    }
}
