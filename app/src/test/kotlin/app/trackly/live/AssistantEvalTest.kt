package app.trackly.live

import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.chat.UNPROCESSED_NOTICE
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.openai.RetryPolicy
import app.trackly.assistant.tools.RecordConsumptionsTool
import app.trackly.assistant.tools.ToolContext
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.HistoryMessage
import app.trackly.assistant.turn.TurnInput
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.TurnOutcome
import app.trackly.assistant.turn.UsageEvent
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddDishVariant
import app.trackly.core.action.CreateDish
import app.trackly.core.action.DishIngredientInput
import app.trackly.core.action.MemorizeProduct
import app.trackly.core.action.NewProduct
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.SetGoal
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.goal.GoalCoherence
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.Consumption
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrients
import app.trackly.core.model.NutritionBasis
import app.trackly.core.model.ProductKind
import app.trackly.core.model.ProductPortion
import app.trackly.core.model.Quantity
import app.trackly.core.time.TrackingDay
import app.trackly.testing.SampleData
import app.trackly.usage.CostCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Banc d'évaluation réel de l'assistant :
 * une cinquantaine de scénarios en français naturel (dictée imparfaite comprise), joués contre la vraie
 * API OpenAI avec le vrai code de l'app (client, `TurnInterpreter`, outils, consignes, contexte, moteur en
 * mémoire). Chaque scénario a des attentes vérifiées par le code (enregistre ou non, nombre d'aliments,
 * moment, date de rattachement, outils, kcal plausibles, question, recherche web) ; on mesure aussi la
 * latence, les jetons et le coût (compteur de l'app, [CostCalculator]).
 *
 * Ignoré sans `-PliveChecks=true`, sans `TRACKLY_LIVE_OPENAI_KEY` **et** sans `TRACKLY_EVAL_CONFIGS`
 * (par ex. `sol:medium,terra:low`) : un passage coûte de l'ordre du dollar, il ne part jamais par erreur
 * avec les contrôles réels. Variables facultatives : `TRACKLY_EVAL_ONLY` (identifiants séparés par des
 * virgules), `TRACKLY_EVAL_LABEL` (nom du passage), `TRACKLY_EVAL_OUT` (dossier des résultats, hors du
 * dépôt), `TRACKLY_EVAL_BUDGET_USD` (plafond cumulé de tous les passages, 5 $ par défaut),
 * `TRACKLY_EVAL_CONCURRENCY` (scénarios simultanés, 3 par défaut).
 *
 * Les résultats ne contiennent jamais la clé : seulement les identifiants, outils, chiffres et un
 * extrait de la réponse. Les attentes suivent le comportement voulu de l'assistant ; quand il laisse le
 * choix, plusieurs comportements sont acceptés.
 */
class AssistantEvalTest {

    @Test
    fun `banc d evaluation de l assistant`() = runBlocking {
        assumeTrue("Banc d'évaluation désactivé (-PliveChecks=true, TRACKLY_LIVE_OPENAI_KEY et TRACKLY_EVAL_CONFIGS)", EvalEnv.enabled)
        val only = EvalEnv.only
        val selected = SCENARIOS.filter { only == null || it.id in only }
        check(selected.isNotEmpty()) { "Aucun scénario sélectionné" }
        val out = File(EvalEnv.outDir).apply { mkdirs() }
        val budget = Budget(File(out, "budget.txt"), EvalEnv.budgetMicros)
        for (config in EvalEnv.configs) {
            val semaphore = Semaphore(EvalEnv.concurrency)
            val results = selected.map { scenario ->
                async(Dispatchers.IO) { semaphore.withPermit { runScenario(scenario, config, budget) } }
            }.awaitAll()
            write(out, config, results)
        }
        println("EVAL | budget cumulé : ${dollars(budget.spent())}")
    }

    /**
     * Sonde du cache d'entrée : une question sans outil, avec un préfixe propre à la sonde (marqueur
     * [EvalEnv.cacheProbe] en tête des consignes, pour que rien d'autre ne le réchauffe) et la rétention
     * `TRACKLY_EVAL_CACHE_TTL`. Relancée plus de 30 min après avec le même marqueur, elle dit si le
     * préfixe est encore en cache. Affiche l'usage brut (jetons seulement).
     */
    @Test
    fun `sonde du cache d entree`() = runBlocking {
        assumeTrue("Sonde désactivée (TRACKLY_EVAL_CACHE_PROBE)", EvalEnv.key != null && System.getProperty("trackly.liveChecks") == "true" && EvalEnv.cacheProbe != null)
        val marker = object : app.trackly.assistant.prompt.PromptSection {
            override val id = "probe"
            override val title = "Sonde"
            override val body = "Sonde du cache ${EvalEnv.cacheProbe}."
        }
        val prompt = app.trackly.assistant.prompt.SystemPrompt(listOf(marker) + app.trackly.assistant.prompt.SystemPrompt.default().sections)
        File(EvalEnv.outDir).mkdirs()
        val ttl = EvalEnv.cacheTtl ?: "12h"
        for (model in EvalEnv.probeModels) {
            val engine = fixture(Fixture.Base, NOON)
            val meter = Meter()
            val outcome = TurnInterpreter(
                ResponsesClient(OkHttpResponsesTransport(HTTP, apiKey = { EvalEnv.key }), RetryPolicy.None),
                engine,
                prompt = prompt,
                config = AssistantConfig(model = { model }, promptCacheTtl = ttl),
                usageSink = meter,
            ).run(TurnInput("c'est combien une barre Nuvo ?", sentAt = LocalDateTime.parse(NOON).atZone(TrackingDay.ZONE).toInstant()))
            Budget(File(EvalEnv.outDir, "budget.txt"), EvalEnv.budgetMicros).add(meter.micros)
            val status = if (outcome is TurnOutcome.Failed) "échec ${outcome.error}" else "réussi"
            println(safe("EVAL SONDE ${EvalEnv.cacheProbe} $model ttl=$ttl | $status | ${meter.events.map { it.usage.raw }} | ${dollars(meter.micros)}"))
        }
    }

    /** Hors ligne (suite normale) : chaque scénario se prépare sans réseau, identifiants uniques. */
    @Test
    fun `les scenarios du banc se preparent sans reseau`() = runBlocking {
        org.junit.Assert.assertTrue("au moins 40 scénarios", SCENARIOS.size >= 40)
        org.junit.Assert.assertEquals(SCENARIOS.size, SCENARIOS.map { it.id }.toSet().size)
        for (scenario in SCENARIOS) {
            val at = LocalDateTime.parse(scenario.at).atZone(TrackingDay.ZONE).toInstant()
            val engine = fixture(scenario.fixture, scenario.at)
            scenario.setup(Seed(engine, at))
        }
    }

    // ───── Exécution d'un scénario ─────

    /**
     * Joue [scenario] avec [transport] : jeu d'essai, préparation, tour, puis les attentes du scénario. Le même chemin sert
     * au banc réel et au banc simulé ([ToneBenchTest]), où [transport] rejoue des réponses écrites.
     */
    internal suspend fun play(
        scenario: Scenario,
        transport: ResponsesTransport,
        config: AssistantConfig = AssistantConfig(),
        effort: ReasoningEffort = ReasoningEffort.Default,
        meter: Meter = Meter(),
    ): Check {
        val at = LocalDateTime.parse(scenario.at).atZone(TrackingDay.ZONE).toInstant()
        val engine = fixture(scenario.fixture, scenario.at)
        val seed = Seed(engine, at)
        scenario.setup(seed)
        val before = snapshot(engine)
        val goalsBefore = engine.goals()
        val interpreter = TurnInterpreter(ResponsesClient(transport, RetryPolicy.None), engine, config = config, usageSink = meter)
        val outcome = interpreter.run(TurnInput(scenario.text, history = seed.history.toList(), effort = effort, sentAt = at))
        val check = Check(engine, scenario, outcome, before, snapshot(engine), goalsBefore, meter)
        if (outcome is TurnOutcome.Failed) check.fail("tour en échec : ${outcome.error}")
        runCatching { scenario.expect(check) }.onFailure { check.fail("vérification impossible : ${it::class.simpleName} ${it.message}") }
        return check
    }

    private suspend fun runScenario(scenario: Scenario, config: EvalConfig, budget: Budget): Result {
        if (!budget.allows()) return Result.skipped(scenario, config, "budget atteint")
        val meter = Meter()
        val started = System.nanoTime()
        val check = try {
            play(
                scenario,
                OkHttpResponsesTransport(HTTP, apiKey = { EvalEnv.key }),
                AssistantConfig(model = { config.model }, promptCacheTtl = EvalEnv.cacheTtl),
                config.effort,
                meter,
            )
        } catch (e: Exception) {
            budget.add(meter.micros)
            return Result.error(scenario, config, "exception ${e::class.simpleName}: ${e.message}", meter, (System.nanoTime() - started) / 1_000_000)
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        budget.add(meter.micros)
        val result = Result(
            id = scenario.id,
            title = scenario.title,
            category = scenario.category,
            config = config.label,
            status = if (check.failures.isEmpty()) "ok" else "echec",
            failures = check.failures.toList(),
            tools = check.tools,
            webSearches = meter.webSearches,
            latencyMs = latencyMs,
            requests = meter.events.size,
            inputTokens = meter.events.sumOf { it.usage.inputTokens },
            cachedTokens = meter.events.sumOf { it.usage.cachedTokens },
            cacheWriteTokens = meter.events.sumOf { it.usage.cacheWriteTokens },
            outputTokens = meter.events.sumOf { it.usage.outputTokens },
            reasoningTokens = meter.events.sumOf { it.usage.reasoningTokens },
            firstInputTokens = meter.events.firstOrNull()?.usage?.inputTokens ?: 0,
            costMicros = meter.micros,
            reply = check.reply.replace(Regex("\\s+"), " ").take(240),
            changes = check.describeChanges(),
            rounds = meter.events.map { "${it.round}:${it.usage.inputTokens}/${it.usage.cachedTokens}/${it.usage.cacheWriteTokens}/${it.usage.outputTokens}" },
        )
        println(safe("EVAL ${config.label} | ${result.id} | ${result.status} | ${result.failures.joinToString(" ; ")} | outils=${result.tools} | ${result.latencyMs} ms | ${dollars(result.costMicros)} | ${result.changes} | « ${result.reply} »"))
        return result
    }

    private suspend fun snapshot(engine: TracklyEngine): Map<String, Consumption> {
        val today = engine.today()
        return (-12L..1L).flatMap { engine.daySummary(today.plusDays(it)).consumptions }.associateBy { it.entryId.value }
    }

    // ───── Jeu d'essai (anonymisé) ─────

    /** [Imported] : [Base] plus une salade habituelle reprise d'un ancien suivi (modèle seulement, sans ingrédients pesés). */
    enum class Fixture { Base, TwoBars, Imported }

    private fun fixture(kind: Fixture, at: String): TracklyEngine {
        val store = app.trackly.core.store.InMemoryStore()
        return TracklyEngine(store, SampleData.clock(at)).also { engine -> seed(engine, store, kind) }
    }

    private fun seed(engine: TracklyEngine, store: app.trackly.core.store.InMemoryStore, kind: Fixture) {
        runBlocking {
            fun ok(outcome: ActionOutcome) = check(outcome is ActionOutcome.Applied) { "Jeu d'essai refusé : $outcome" }
            ok(engine.execute(SetGoal(GOAL, from = LocalDate.parse("2026-10-01"))))
            when (kind) {
                Fixture.Base -> ok(engine.execute(MemorizeProduct(bar(BAR, "Barre protéinée Nuvo chocolat", listOf("barre nuvo")))))
                Fixture.Imported -> {
                    ok(engine.execute(MemorizeProduct(bar(BAR, "Barre protéinée Nuvo chocolat", listOf("barre nuvo")))))
                    fun sauce(id: String, name: String, kcal: Double, p: Double, c: Double, f: Double) = MemorizeProduct(
                        NewProduct(productId = id, name = name, nutrients = Nutrients(kcal, p, c, f), basis = NutritionBasis(100.0, "g"), kind = ProductKind.Recurring),
                    )
                    ok(engine.execute(sauce(BALSAMIQUE, "Sauce balsamique", 250.0, 0.5, 20.0, 18.0)))
                    ok(engine.execute(sauce(KETCHUP, "Ketchup nature", 100.0, 1.0, 24.0, 0.1)))
                    ok(engine.execute(sauce(CESAR, "Sauce César classique", 400.0, 1.5, 6.0, 41.0)))
                    store.transaction {
                        recipes.insert(
                            app.trackly.core.model.Recipe(
                                HABITUELLE, 1, app.trackly.core.model.RecordState.Active, "Salade habituelle", aliases = listOf("salade sauce balsamique"),
                                sourceTemplateJson = """{"components":[{"name":"base salade/légumes","default":"salade verte + 1 tomate + 1/2 oignon"},{"ingredient_ref":"$BALSAMIQUE","default_quantity":{"unit":"g","value":70}},{"name":"Crème balsamique","default_quantity":{"unit":"g","value":30},"nutrition":null},{"ingredient_ref":"$KETCHUP","default_quantity":{"unit":"g","value":50}}]}""",
                                recordedAt = java.time.Instant.EPOCH, actionId = null,
                            ),
                        )
                    }
                }
                Fixture.TwoBars -> {
                    ok(engine.execute(MemorizeProduct(bar(BAR, "Barre protéinée Nuvo chocolat", emptyList()))))
                    ok(engine.execute(MemorizeProduct(bar(BAR_VANILLE, "Barre protéinée Nuvo vanille", emptyList()))))
                }
            }
            ok(
                engine.execute(
                    MemorizeProduct(
                        NewProduct(
                            productId = SKYR, name = "Skyr nature", brand = "Exemple",
                            nutrients = Nutrients(63.0, 11.0, 4.0, 0.2), basis = NutritionBasis(100.0, "g"),
                            portion = ProductPortion("1 pot", Quantity(150.0, "g"), Nutrients(95.0, 16.5, 6.0, 0.3)),
                            shortcuts = listOf("skyr"), kind = ProductKind.Recurring,
                        ),
                    ),
                ),
            )
            ok(
                engine.execute(
                    MemorizeProduct(
                        NewProduct(
                            productId = WHEY, name = "Whey vanille", brand = "Exemple",
                            nutrients = Nutrients(380.0, 78.0, 7.0, 5.0), basis = NutritionBasis(100.0, "g"),
                            portion = ProductPortion("1 dose", Quantity(30.0, "g"), Nutrients(114.0, 23.4, 2.1, 1.5)),
                            shortcuts = listOf("whey"), kind = ProductKind.Recurring,
                        ),
                    ),
                ),
            )
            val laitue = DishIngredientInput("Laitue", 100.0, Nutrients(15.0, 1.4, 2.0, 0.2))
            val thon = DishIngredientInput("Thon au naturel", 100.0, Nutrients(110.0, 25.0, 0.0, 1.0))
            val oeuf = DishIngredientInput("Œuf dur", 60.0, Nutrients(155.0, 13.0, 1.1, 11.0))
            val sauceA = DishIngredientInput("Sauce yaourt", 40.0, Nutrients(80.0, 3.0, 6.0, 4.0))
            val sauceB = DishIngredientInput("Vinaigrette", 25.0, Nutrients(450.0, 0.0, 3.0, 48.0))
            ok(engine.execute(CreateDish("Salade", listOf(laitue, thon, oeuf, sauceA), shortcuts = listOf("ma salade"), variantName = "sauce A", dishId = SALADE)))
            ok(engine.execute(AddDishVariant(SALADE, "sauce B", listOf(laitue, thon, oeuf, sauceB), variantId = SALADE_B)))
            val riz = DishIngredientInput("Riz basmati cuit", 200.0, Nutrients(130.0, 2.7, 28.0, 0.3))
            val poulet = DishIngredientInput("Blanc de poulet cuit", 150.0, Nutrients(165.0, 31.0, 0.0, 3.6))
            val courgette = DishIngredientInput("Courgette", 150.0, Nutrients(17.0, 1.2, 3.1, 0.3))
            ok(engine.execute(CreateDish("Bowl riz poulet", listOf(riz, poulet, courgette), shortcuts = listOf("bowl"), dishId = BOWL)))
            ok(engine.execute(UpdateProvisions(have = listOf(ProvisionInput("thon"), ProvisionInput("riz"), ProvisionInput("poulet")))))
        }
    }

    private fun bar(id: String, name: String, shortcuts: List<String>) = NewProduct(
        productId = id, name = name, brand = "Nuvo",
        nutrients = Nutrients(380.0, 36.0, 22.0, 14.0), basis = NutritionBasis(100.0, "g"),
        portion = ProductPortion("1 barre", Quantity(55.0, "g"), Nutrients(209.0, 19.8, 12.1, 7.7)),
        shortcuts = shortcuts, kind = ProductKind.Recurring,
    )

    /** Préparation d'un scénario : saisies antérieures (par le vrai outil, donc vraies cartes) et historique du chat. */
    class Seed(val engine: TracklyEngine, val at: java.time.Instant) {
        val history = mutableListOf<HistoryMessage>()

        /** Saisie passée par `record_consumptions` ; [said] (texte de l'utilisateur) l'ajoute aussi à l'historique du chat. */
        suspend fun record(items: String, said: String? = null, minutesBefore: Long = 3, question: String? = null, reply: String = "C'est noté."): RecordCard {
            val args = Json.parseToJsonElement("""{"items":[$items],"question":${question?.let { "\"$it\"" } ?: "null"}}""").jsonObject
            val result = RecordConsumptionsTool.execute(args, ToolContext(engine, at.minusSeconds(minutesBefore * 60)))
            val card = result.card as? RecordCard ?: error("Saisie du jeu d'essai refusée : ${result.output}")
            if (said != null) {
                history += HistoryMessage(HistoryMessage.Role.User, said)
                history += HistoryMessage(HistoryMessage.Role.Assistant, (listOf(reply) + card.summaryForModel()).joinToString("\n"))
            }
            return card
        }

        /** Message de l'utilisateur dont l'envoi a échoué : l'app le renvoie au modèle avec son marquage « non traité ». */
        fun unprocessed(text: String) {
            history += HistoryMessage(HistoryMessage.Role.User, "$UNPROCESSED_NOTICE\n$text")
        }

        fun say(user: String, assistant: String) {
            history += HistoryMessage(HistoryMessage.Role.User, user)
            history += HistoryMessage(HistoryMessage.Role.Assistant, assistant)
        }
    }

    // ───── Vérifications ─────

    class Check(
        val engine: TracklyEngine,
        val scenario: Scenario,
        val outcome: TurnOutcome,
        val before: Map<String, Consumption>,
        val after: Map<String, Consumption>,
        val goalsBefore: List<app.trackly.core.model.Goal>,
        private val meter: Meter,
    ) {
        val failures = mutableListOf<String>()
        val reply: String = (outcome as? TurnOutcome.Completed)?.reply.orEmpty()
        val cards: List<ChatCard> = outcome.cards
        val tools: List<String> = outcome.toolCalls.map { it.name }
        val added: List<Consumption> = after.values.filter { it.entryId.value !in before }
        val changed: List<Pair<Consumption, Consumption>> = after.values.mapNotNull { a -> before[a.entryId.value]?.takeIf { it.version != a.version }?.let { it to a } }
        val deleted: List<Consumption> = before.values.filter { it.entryId.value !in after }
        val addedKcal: Double get() = added.sumOf { it.food.nutrition.kcal }
        val today: LocalDate = engine.today()

        /** Question posée : champ `question` d'une carte, doute produit, ou point d'interrogation dans la réponse. */
        val asked: Boolean
            get() = reply.contains('?') || cards.filterIsInstance<RecordCard>().any { it.question != null || it.doubts.isNotEmpty() }

        fun fail(message: String) {
            failures += message
        }

        fun that(what: String, ok: Boolean) {
            if (!ok) fail(what)
        }

        fun records(range: IntRange) = that("aliments ajoutés ${added.size} hors de $range", added.size in range)
        fun records(n: Int) = records(n..n)

        fun noChange() {
            that("rien ne devait être enregistré (ajouts ${added.size}, corrections ${changed.size}, suppressions ${deleted.size})", added.isEmpty() && changed.isEmpty() && deleted.isEmpty())
        }

        fun moments(vararg allowed: Moment) =
            that("moment ${added.map { it.moment.code }} hors de ${allowed.map { it.code }}", added.isNotEmpty() && added.all { it.moment in allowed })

        fun days(vararg allowed: LocalDate) =
            that("date ${added.map { it.day }} hors de ${allowed.toList()}", added.isNotEmpty() && added.all { it.day in allowed })

        fun kcal(range: ClosedFloatingPointRange<Double>) = that("kcal ajoutées ${addedKcal.roundToInt()} hors de $range", addedKcal in range)

        fun called(name: String) = that("outil $name attendu", name in tools)
        fun notCalled(name: String) = that("outil $name inattendu", name !in tools)

        fun web(expected: Boolean) =
            that(if (expected) "recherche web attendue" else "recherche web inattendue (${meter.webSearches})", (meter.webSearches > 0) == expected)

        fun asks(expected: Boolean) = that(if (expected) "question attendue" else "question inattendue", asked == expected)

        fun noLabel(vararg words: String) {
            val bad = added.filter { c -> words.any { c.food.label.contains(it, ignoreCase = true) } }
            that("aliment exclu enregistré : ${bad.map { it.food.label }}", bad.isEmpty())
        }

        fun someLabel(vararg words: String) =
            that("aucun aliment contenant ${words.toList()}", added.any { c -> words.any { c.food.label.contains(it, ignoreCase = true) } })

        /** Questions de la réponse (points d'interrogation). */
        val questionCount: Int get() = reply.count { it == '?' }

        /** Une seule question utile par message ; [n] = 2 laisse joindre « salé ou sucré » comme dans l'exemple des consignes. */
        fun asksAtMost(n: Int) = that("trop de questions ($questionCount) : $reply", questionCount <= n)

        /** Idées en liste : lignes « - », « * », « • » ou « 1. ». */
        val listItems: Int get() = reply.lines().count { Regex("^\\s*([-*•]|\\d+[.)])\\s+\\S").containsMatchIn(it) }

        /** Aucun jugement ni commentaire sur un dépassement (« l'IA ne juge pas »). */
        fun noJudgment() {
            val plain = " " + app.trackly.core.model.TextMatch.normalize(reply) + " "
            val bad = JUDGMENT.filter { plain.contains(" $it ") }
            that("jugement ou commentaire spontané $bad : $reply", bad.isEmpty())
        }

        /** La question « minimum ou idéal ». */
        val asksMinimumOrIdeal: Boolean
            get() = asked && reply.contains("minimum", ignoreCase = true) && (reply.contains("idéal", ignoreCase = true) || reply.contains("ideal", ignoreCase = true))

        fun replyMentions(vararg options: String) {
            val plain = reply.replace(' ', ' ').replace(' ', ' ')
            that("réponse sans ${options.toList()}", options.any { plain.contains(it, ignoreCase = true) })
        }

        suspend fun goalOn(day: LocalDate): GoalTargets? = GoalTimeline.inForce(day, engine.goals())?.targets

        fun describeChanges(): String = buildString {
            if (added.isNotEmpty()) append("ajouts=").append(added.joinToString(", ") { "${it.food.label} ${it.food.nutrition.kcal.roundToInt()} kcal [${it.moment.code} ${it.day}${it.food.productId?.let { p -> " $p" } ?: ""}${it.food.recipeId?.let { r -> " $r" } ?: ""}]" })
            if (changed.isNotEmpty()) append(" corrections=").append(changed.joinToString(", ") { (b, a) -> "${b.food.label}→${a.food.label} ${a.food.nutrition.kcal.roundToInt()} kcal [${a.moment.code} ${a.day}]" })
            if (deleted.isNotEmpty()) append(" suppressions=").append(deleted.joinToString(", ") { it.food.label })
            if (isEmpty()) append("aucun changement du journal")
        }
    }

    // ───── Configuration, compteur, budget, résultats ─────

    data class EvalConfig(val model: String, val effort: ReasoningEffort) {
        val label: String get() = "${model.removePrefix("gpt-6.1-")}-${effort.code}"
    }

    class Meter : UsageSink {
        val events = java.util.Collections.synchronizedList(mutableListOf<UsageEvent>())
        override suspend fun record(event: UsageEvent) {
            events += event
        }

        val micros: Long get() = events.sumOf { CostCalculator.text(it.model, it.usage, it.webSearchRequests).total }
        val webSearches: Int get() = events.sumOf { it.webSearchRequests ?: 0 }
    }

    /** Plafond de dépense cumulé sur tous les passages (fichier hors du dépôt). */
    class Budget(private val file: File, private val capMicros: Long) {
        private val mutex = Mutex()

        fun spent(): Long = file.takeIf { it.isFile }?.readLines()?.sumOf { it.trim().toLongOrNull() ?: 0 } ?: 0

        /** Marge de 0,10 $ : un scénario coûte quelques centimes. */
        fun allows(): Boolean = spent() + 100_000 < capMicros

        suspend fun add(micros: Long) = mutex.withLock { file.appendText("$micros\n") }
    }

    data class Result(
        val id: String,
        val title: String,
        val category: String,
        val config: String,
        val status: String,
        val failures: List<String>,
        val tools: List<String>,
        val webSearches: Int,
        val latencyMs: Long,
        val requests: Int,
        val inputTokens: Long,
        val cachedTokens: Long,
        val cacheWriteTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
        val firstInputTokens: Long,
        val costMicros: Long,
        val reply: String,
        val changes: String,
        /** Par requête : rang:entrée/cache lu/cache écrit/sortie. */
        val rounds: List<String> = emptyList(),
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id); put("title", title); put("category", category); put("config", config); put("status", status)
            putJsonArray("failures") { failures.forEach { add(it) } }
            putJsonArray("tools") { tools.forEach { add(it) } }
            put("web_searches", webSearches); put("latency_ms", latencyMs); put("requests", requests)
            put("input_tokens", inputTokens); put("cached_tokens", cachedTokens); put("cache_write_tokens", cacheWriteTokens)
            put("output_tokens", outputTokens); put("reasoning_tokens", reasoningTokens); put("first_input_tokens", firstInputTokens)
            put("cost_micros", costMicros); put("reply", reply); put("changes", changes)
            putJsonArray("rounds") { rounds.forEach { add(it) } }
        }

        companion object {
            fun skipped(s: Scenario, c: EvalConfig, why: String) = Result(s.id, s.title, s.category, c.label, "ignore", listOf(why), emptyList(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "")
            fun error(s: Scenario, c: EvalConfig, why: String, m: Meter, ms: Long) = Result(
                s.id, s.title, s.category, c.label, "echec", listOf(why), emptyList(), m.webSearches, ms, m.events.size,
                m.events.sumOf { it.usage.inputTokens }, m.events.sumOf { it.usage.cachedTokens }, m.events.sumOf { it.usage.cacheWriteTokens },
                m.events.sumOf { it.usage.outputTokens }, m.events.sumOf { it.usage.reasoningTokens }, 0, m.micros, "", "",
            )
        }
    }

    private fun write(out: File, config: EvalConfig, results: List<Result>) {
        val name = "${EvalEnv.label}-${config.label}"
        File(out, "$name.jsonl").writeText(results.joinToString("\n") { safe(it.toJson().toString()) } + "\n")
        val ran = results.filter { it.status != "ignore" }
        val ok = ran.count { it.status == "ok" }
        val lat = ran.map { it.latencyMs }.sorted()
        fun pct(p: Double) = if (lat.isEmpty()) 0 else lat[((lat.size - 1) * p).roundToInt()]
        val input = ran.sumOf { it.inputTokens }
        val cached = ran.sumOf { it.cachedTokens }
        val cost = ran.sumOf { it.costMicros }
        val summary = buildString {
            append("# $name\n\n")
            append("réussite $ok/${ran.size} (${if (ran.isEmpty()) 0 else ok * 100 / ran.size} %) ; latence médiane ${pct(0.5)} ms, p90 ${pct(0.9)} ms ; ")
            append("coût ${dollars(cost)} (${dollars(if (ran.isEmpty()) 0 else cost / ran.size)} par échange) ; ")
            append("cache ${if (input == 0L) 0 else cached * 100 / input} % de l'entrée ; requêtes ${ran.sumOf { it.requests }} ; ")
            append("sortie ${ran.sumOf { it.outputTokens }} (raisonnement ${ran.sumOf { it.reasoningTokens }}) ; recherches web ${ran.sumOf { it.webSearches }}\n\n")
            append("| id | statut | échecs | outils | ms | \$ |\n|---|---|---|---|---|---|\n")
            for (r in results) append("| ${r.id} | ${r.status} | ${r.failures.joinToString("; ")} | ${r.tools.joinToString(" ")}${if (r.webSearches > 0) " web×${r.webSearches}" else ""} | ${r.latencyMs} | ${dollars(r.costMicros)} |\n")
        }
        File(out, "$name.md").writeText(safe(summary))
        println(safe("EVAL RÉSUMÉ ${config.label} | " + summary.lineSequence().drop(2).first()))
    }

    private object EvalEnv {
        val key: String? = System.getenv("TRACKLY_LIVE_OPENAI_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        val configs: List<EvalConfig> = System.getenv("TRACKLY_EVAL_CONFIGS")?.split(',')?.mapNotNull { spec ->
            val (model, effort) = spec.trim().split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val code = if (model.startsWith("gpt-")) model else "gpt-6.1-$model"
            EvalConfig(code, ReasoningEffort.entries.first { it.code == effort })
        }.orEmpty()
        val enabled: Boolean = System.getProperty("trackly.liveChecks") == "true" && key != null && configs.isNotEmpty()
        val only: Set<String>? = System.getenv("TRACKLY_EVAL_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
        val label: String = System.getenv("TRACKLY_EVAL_LABEL")?.takeIf { it.isNotBlank() } ?: "passage"
        val outDir: String = System.getenv("TRACKLY_EVAL_OUT") ?: File(System.getProperty("java.io.tmpdir"), "trackly-eval").path
        val budgetMicros: Long = ((System.getenv("TRACKLY_EVAL_BUDGET_USD")?.toDoubleOrNull() ?: 5.0) * 1_000_000).toLong()
        val concurrency: Int = System.getenv("TRACKLY_EVAL_CONCURRENCY")?.toIntOrNull()?.coerceIn(1, 8) ?: 3
        val cacheTtl: String? = System.getenv("TRACKLY_EVAL_CACHE_TTL")?.takeIf { it.isNotBlank() }
        val cacheProbe: String? = System.getenv("TRACKLY_EVAL_CACHE_PROBE")?.takeIf { it.isNotBlank() }
        val probeModels: List<String> = (System.getenv("TRACKLY_EVAL_CACHE_PROBE_MODELS") ?: "sol").split(',').map { it.trim() }
            .filter { it.isNotEmpty() }.map { if (it.startsWith("gpt-")) it else "gpt-6.1-$it" }
    }

    private fun safe(text: String): String = EvalEnv.key?.let { text.replace(it, "[clé masquée]") } ?: text

    // ───── Scénarios ─────

    class Scenario(
        val id: String,
        val category: String,
        val title: String,
        val text: String,
        val at: String = NOON,
        val fixture: Fixture = Fixture.Base,
        val setup: suspend Seed.() -> Unit = {},
        val expect: suspend Check.() -> Unit,
    )

    companion object {
        private val HTTP = OkHttpResponsesTransport.defaultClient()
        private fun dollars(micros: Long) = "%.4f \$".format(Locale.ROOT, micros / 1_000_000.0)

        // Mardi 3 novembre 2026 (heure locale).
        const val NOON = "2026-11-03T12:00"
        const val EVENING = "2026-11-03T19:00"
        const val NIGHT = "2026-11-04T00:30"
        val TUESDAY: LocalDate = LocalDate.parse("2026-11-03")
        val MONDAY: LocalDate = LocalDate.parse("2026-11-02")
        val SATURDAY: LocalDate = LocalDate.parse("2026-10-31")
        val GOAL = GoalTargets(2600.0, 140.0, 352.0, 70.0)

        const val BAR = "prod-nuvo-choco"
        const val BAR_VANILLE = "prod-nuvo-vanille"
        const val SKYR = "prod-skyr"
        const val WHEY = "prod-whey"
        const val SALADE = "rcp-salade"
        const val SALADE_B = "rcp-salade-b"
        const val BOWL = "rcp-bowl"
        const val HABITUELLE = "rcp-habituelle"
        const val BALSAMIQUE = "prod-sauce-balsamique"
        const val KETCHUP = "prod-ketchup"
        const val CESAR = "prod-sauce-cesar"

        /** Aliment libre pour les saisies du jeu d'essai. */
        fun food(label: String, kcal: Int, p: Int, c: Int, f: Int, moment: String, qty: Int? = null, unit: String? = "g", day: String? = null) =
            """{"product":null,"dish":null,"food":{"label":"$label","quantity":${qty ?: "null"},"unit":${if (qty == null) "null" else "\"$unit\""},"kcal":$kcal,"protein_g":$p,"carbs_g":$c,"fat_g":$f,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"$moment","day":${day?.let { "\"$it\"" } ?: "null"}}"""

        fun product(id: String, qty: Double, unit: String, moment: String, day: String? = null) =
            """{"product":{"product_id":"$id","new_product":null,"quantity":$qty,"unit":"$unit","assumptions":[],"alternatives":[]},"dish":null,"food":null,"recipe_id":null,"moment":"$moment","day":${day?.let { "\"$it\"" } ?: "null"}}"""

        fun dish(id: String, moment: String) =
            """{"product":null,"dish":{"recipe_id":"$id","named_version":false,"quantity":1,"unit":"portion","assumptions":[]},"food":null,"recipe_id":null,"moment":"$moment","day":null}"""

        /** Sandwich acheté mangé hier à midi : fiche ponctuelle mémorisée par l'outil. */
        const val SANDWICH_YESTERDAY =
            """{"product":{"product_id":null,"new_product":{"name":"Sandwich thon mayonnaise","brand":"Exemple","variant":null,"per_base":null,""" +
                """"per_portion":{"label":"1 sandwich","weight":190,"weight_unit":"g","kcal":480,"protein_g":21,"carbs_g":48,"fat_g":21,"fiber_g":null},""" +
                """"shortcuts":[],"source_type":"label","source_description":"étiquette","confidence":"high"},"quantity":1,"unit":"portion","assumptions":[],"alternatives":[]},""" +
                """"dish":null,"food":null,"recipe_id":null,"moment":"midi","day":"2026-11-02"}"""

        /** Mots d'un jugement ou d'un commentaire sur un dépassement, normalisés (début de mot). */
        val JUDGMENT = listOf(
            "attention", "oups", "aie", "mince", "depasse", "depasses", "depassement", "exces", "excessif", "ecart",
            "compenser", "compense", "compensation", "raisonnable", "culpabilise", "culpabiliser", "craque", "craquage",
            "c est beaucoup", "beaucoup trop", "un peu trop", "trop de", "trop mange",
        )

        /** Plats et aliments du jeu d'essai qu'une idée citerait. */
        val IDEA_WORDS = listOf("omelette", "skyr", "whey", "bowl", "poulet", "pates", "salade", "riz", "thon", "oeuf", "oeufs", "wrap", "pancake", "pancakes")

        /** Objectif avec le minimum de protéines : idéal 140 g, minimum 112 g. */
        val GOAL_WITH_MIN = GOAL.copy(proteinMinG = 112.0)

        val withProteinMin: suspend Seed.() -> Unit = {
            check(engine.execute(SetGoal(GOAL_WITH_MIN, from = TUESDAY)) is ActionOutcome.Applied)
        }

        /** Matin et midi déjà saisis (520 + 640 kcal), pour les scénarios du soir et « il me reste combien ? ». */
        val morningAndNoon: suspend Seed.() -> Unit = {
            record(food("Flocons d'avoine, lait, banane", 520, 22, 80, 12, "matin", 400), minutesBefore = 240)
            record(food("Poulet, riz, légumes", 640, 45, 75, 15, "midi", 450), minutesBefore = 60)
        }

        val SCENARIOS: List<Scenario> = listOf(
            // ── Déclarations ──
            Scenario("D01", "déclaration", "raccourci barre Nuvo", "snack barre Nuvo") {
                records(1); moments(Moment.Snack); kcal(180.0..240.0); web(false); asks(false)
                that("produit Nuvo attendu", added.singleOrNull()?.food?.productId == BAR)
            },
            Scenario("D02", "déclaration", "riz et poulet pesés à midi", "à midi j'ai mangé 150 g de riz basmati cuit et 120 g de blanc de poulet grillé") {
                records(2); moments(Moment.Midi); kcal(330.0..480.0); web(false); asks(false); days(TUESDAY)
            },
            Scenario("D03", "dictée", "dictée avec euh, sans ponctuation", "euh ce matin j'ai pris deux œufs brouillés une tranche de pain complet et un café au lait") {
                records(3..4); moments(Moment.Matin); kcal(280.0..620.0); web(false); someLabel("œuf", "oeuf")
            },
            Scenario(
                "D04", "dictée", "ajout long avec exclusion",
                "ajoute à mon petit-déjeuner un bol de flocons d'avoine 60 g avec 250 ml de lait demi-écrémé une banane une cuillère à soupe de beurre de cacahuète et un café noir mais pas le jus d'orange finalement je l'ai pas bu",
            ) {
                records(4..5); moments(Moment.Matin); noLabel("jus", "orange"); kcal(450.0..900.0); web(false)
                someLabel("cacahu", "arachide")
            },
            Scenario("D05", "dictée", "reprise dans le message (deux non trois)", "ce midi deux œufs au plat non trois et 200 g de haricots verts") {
                records(2); moments(Moment.Midi)
                val eggs = added.filter { it.food.label.contains("uf", ignoreCase = true) }
                that("œufs : trois attendus (${eggs.map { "${it.food.quantity} ${it.food.nutrition.kcal.roundToInt()} kcal" }})", eggs.sumOf { it.food.nutrition.kcal } in 200.0..340.0)
            },
            Scenario("D06", "dictée", "alternative écartée", "ce matin j'hésitais avec un croissant mais finalement j'ai pris un pain au chocolat") {
                records(1); moments(Moment.Matin); noLabel("croissant"); someLabel("chocolat"); kcal(200.0..340.0)
            },
            Scenario("D07", "dictée", "erreurs de transcription (damendes)", "jai mangé un yaourt grec nature et une poignée damendes") {
                records(2); someLabel("amande"); kcal(150.0..420.0); web(false)
            },
            Scenario("D08", "déclaration", "deux barres Nuvo", "comme snack jai pris deux barres nuvo") {
                records(1); moments(Moment.Snack); kcal(390.0..450.0)
                that("produit Nuvo attendu", added.all { it.food.productId == BAR })
            },
            Scenario("D09", "dictée", "raccourci mal transcrit (barre nouvo)", "snack barre nouvo et un café") {
                records(1..2); moments(Moment.Snack); kcal(180.0..280.0)
                that("produit Nuvo attendu", added.any { it.food.productId == BAR })
            },
            Scenario("D10", "déclaration", "langue mélangée", "for lunch j'ai eu un chicken wrap et un coca zero") {
                records(2); moments(Moment.Midi); kcal(300.0..800.0)
            },
            Scenario("D11", "date", "plusieurs jours dans un message", "hier soir j'ai mangé une pizza margherita entière et ce matin un croissant") {
                records(2)
                val pizza = added.firstOrNull { it.food.label.contains("pizza", ignoreCase = true) }
                val croissant = added.firstOrNull { it.food.label.contains("croissant", ignoreCase = true) }
                that("pizza hier soir (${pizza?.day} ${pizza?.moment})", pizza?.day == MONDAY && pizza.moment == Moment.Soir)
                that("croissant ce matin (${croissant?.day} ${croissant?.moment})", croissant?.day == TUESDAY && croissant.moment == Moment.Matin)
                that("pizza entière 650-1150 kcal", (pizza?.food?.nutrition?.kcal ?: 0.0) in 650.0..1150.0)
            },
            Scenario("D12", "date", "pour hier, à midi", "pour hier j'ai oublié un skyr au snack") {
                records(1); days(MONDAY); moments(Moment.Snack)
                that("produit skyr attendu", added.singleOrNull()?.food?.productId == SKYR)
            },
            Scenario("D13", "date", "à 00 h 30 sans date : la veille", "je viens de manger un bol de céréales avec du lait", at = NIGHT) {
                records(1..2); days(TUESDAY); moments(Moment.Soir, Moment.Snack); kcal(180.0..480.0); asks(false)
            },
            Scenario("D14", "date", "à 00 h 30, « hier midi » (choix laissé)", "pour hier midi j'avais pris un sandwich jambon beurre", at = NIGHT) {
                records(1); moments(Moment.Midi); days(TUESDAY, MONDAY); kcal(300.0..650.0)
            },
            Scenario("D15", "moment", "19 h sans moment, soir pas encore saisi", "j'ai mangé une assiette de pâtes bolognaise", at = EVENING, setup = morningAndNoon) {
                records(1..2); moments(Moment.Soir); kcal(450.0..950.0); days(TUESDAY)
            },
            Scenario("D16", "date", "samedi soir", "samedi soir j'ai mangé une fondue moitié moitié") {
                records(1..3); days(SATURDAY); moments(Moment.Soir); kcal(600.0..1500.0)
            },
            Scenario("D17", "déclaration", "déclaration et question mêlées", "j'ai pris un café au lait et au fait c'est combien de calories un croissant ?") {
                records(1); noLabel("croissant"); someLabel("café"); that("réponse chiffrée attendue", reply.any(Char::isDigit))
            },
            Scenario("D18", "plat", "salade : variante par défaut", "à midi j'ai mangé ma salade") {
                records(1); moments(Moment.Midi); asks(false)
                that("variante par défaut (sauce A) attendue : ${added.map { it.food.recipeId }}", added.singleOrNull()?.food?.recipeId == SALADE)
            },
            Scenario("D19", "plat", "salade sauce B nommée", "à midi la salade sauce B") {
                records(1); moments(Moment.Midi)
                that("variante sauce B attendue : ${added.map { it.food.recipeId }}", added.singleOrNull()?.food?.recipeId == SALADE_B)
            },
            Scenario("D20", "web", "restaurant nommé", "à midi j'ai pris un Big Mac et une moyenne frite au McDo") {
                records(1..2); moments(Moment.Midi); web(true); kcal(750.0..1150.0)
            },
            Scenario("D21", "web", "produit de marque inconnu", "comme snack j'ai mangé une barre Clif Builders chocolat") {
                records(1); moments(Moment.Snack); web(true); kcal(240.0..340.0)
                that("fiche produit mémorisée", added.singleOrNull()?.food?.productId != null)
            },
            Scenario("D22", "produit", "produit ponctuel remangé : proposition de récurrence", "à midi j'ai remangé le même sandwich qu'hier", setup = {
                record(
                    SANDWICH_YESTERDAY,
                    minutesBefore = 60 * 24,
                )
            }) {
                records(1); moments(Moment.Midi); asks(true)
                val id = engine.allProducts().firstOrNull { it.name.contains("Sandwich thon") }?.productId
                that("même produit attendu ($id)", id != null && added.singleOrNull()?.food?.productId == id)
            },
            Scenario("D23", "produit", "doute entre deux barres Nuvo", "snack barre Nuvo", fixture = Fixture.TwoBars) {
                records(1); moments(Moment.Snack); asks(true)
                that("une des barres Nuvo attendue", added.singleOrNull()?.food?.productId in setOf(BAR, BAR_VANILLE))
            },
            Scenario("D24", "limite", "quantité absurde", "j'ai mangé 3 kg de chips") {
                asks(true)
            },
            Scenario("D25", "provisions", "provision, pas une consommation", "j'ai des pommes et des bananes à la maison") {
                noChange(); called("update_provisions"); notCalled("record_consumptions")
            },
            Scenario("D26", "déclaration", "fruit isolé à midi, rien d'autre", "j'ai mangé une pomme") {
                records(1); moments(Moment.Midi, Moment.Snack); kcal(50.0..130.0); web(false); asks(false)
            },

            // ── Questions, intentions, suggestions ──
            Scenario("Q01", "question", "question sur un aliment courant", "combien de calories dans 100 g de riz cuit ?") {
                noChange(); web(false); that("réponse chiffrée attendue", reply.any(Char::isDigit))
            },
            Scenario("Q02", "intention", "intention pour ce soir", "ce soir je vais manger une raclette") {
                noChange()
            },
            Scenario("Q03", "intention", "intention dictée avec euh", "euh j'ai un peu faim je pense prendre un skyr tout à l'heure") {
                noChange()
            },
            Scenario("Q04", "question", "il me reste combien ?", "il me reste combien ?", setup = morningAndNoon) {
                noChange(); web(false); replyMentions("1440", "1 440", "1'440")
            },
            Scenario("Q05", "question", "simulation big mac ce soir", "si je prends un big mac ce soir ça passe ?", setup = morningAndNoon) {
                noChange()
            },
            Scenario("Q06", "suggestion", "idée de repas avec le reste", "qu'est-ce que je pourrais manger ce soir avec ce qui me reste ?", at = EVENING, setup = morningAndNoon) {
                noChange(); called("get_meal_ideas"); web(false)
            },
            Scenario("Q07", "question", "j'ai mangé quoi hier ?", "j'ai mangé quoi hier ?", setup = {
                record(food("Lasagnes", 780, 38, 70, 36, "soir", 400, day = "2026-11-02"), minutesBefore = 60 * 16)
            }) {
                noChange(); called("get_day"); replyMentions("lasagne")
            },
            Scenario("Q08", "question", "valeur d'un produit connu", "c'est combien une barre Nuvo ?") {
                noChange(); web(false); replyMentions("209")
            },

            // ── Corrections ──
            Scenario("C01", "correction", "non c'était 200 g", "non c'était 200 g", setup = {
                record(food("Riz basmati cuit", 195, 4, 42, 1, "midi", 150), said = "à midi 150 g de riz basmati cuit")
            }) {
                records(0); called("correct_consumption")
                val after = changed.singleOrNull()?.second
                that("riz corrigé à 240-290 kcal (${after?.food?.nutrition?.kcal})", after != null && after.food.nutrition.kcal in 240.0..290.0)
            },
            Scenario("C02", "correction", "enlève la pomme", "enlève la pomme", setup = {
                record(product(BAR, 1.0, "portion", "snack") + "," + food("Pomme", 78, 0, 21, 0, "snack", 150), said = "comme snack j'ai pris une barre Nuvo et une pomme, 150 g")
            }) {
                records(0); called("delete_consumption")
                that("seule la pomme est supprimée (${deleted.map { it.food.label }})", deleted.singleOrNull()?.food?.label == "Pomme")
            },
            Scenario("C03", "correction", "c'était au dîner", "tu t'es trompé c'était au dîner", at = EVENING, setup = {
                record(food("Pâtes carbonara", 750, 28, 85, 32, "midi", 350), said = "j'ai mangé des pâtes carbonara", minutesBefore = 5)
            }) {
                records(0)
                val after = changed.singleOrNull()?.second
                that("moment soir attendu (${after?.moment})", after?.moment == Moment.Soir)
                that("kcal inchangées", after != null && abs(after.food.nutrition.kcal - 750.0) < 1)
            },
            Scenario("C04", "correction", "c'est l'autre barre", "non c'est l'autre barre", fixture = Fixture.TwoBars, setup = {
                record(product(BAR, 1.0, "portion", "snack"), said = "snack barre Nuvo chocolat")
            }) {
                records(0); called("switch_product")
                that("barre vanille attendue", changed.singleOrNull()?.second?.food?.productId == BAR_VANILLE)
            },
            Scenario("C05", "correction", "hier : deux yaourts, pas un (get_day)", "en fait hier au snack j'avais pris deux yaourts nature pas un seul", setup = {
                record(food("Yaourt nature", 80, 5, 6, 4, "snack", 125, day = "2026-11-02"), minutesBefore = 60 * 20)
            }) {
                called("get_day")
                val monday = engine.daySummary(MONDAY).consumptions.filter { it.food.label.contains("aourt", ignoreCase = true) }
                that("yaourts d'hier 140-200 kcal (${monday.sumOf { it.food.nutrition.kcal }})", monday.sumOf { it.food.nutrition.kcal } in 140.0..200.0)
            },
            Scenario("C06", "correction", "annule", "annule", setup = {
                record(food("Croissant", 230, 5, 26, 12, "matin", 1, "pièce"), said = "ce matin un croissant")
            }) {
                called("undo_action"); that("croissant retiré", deleted.size == 1 && added.isEmpty())
            },
            Scenario("C07", "correction", "réponse à une question en attente", "à peu près la taille d'un big mac", setup = {
                record(
                    food("Burger maison", 750, 35, 55, 40, "midi", 1, "pièce"), said = "à midi j'ai mangé un gros burger au buffet",
                    question = "Il faisait quelle taille environ ?", reply = "C'est noté. Il faisait quelle taille environ ?",
                )
            }) {
                records(0); called("correct_consumption")
                val after = changed.singleOrNull()?.second
                that("burger corrigé vers 450-650 kcal (${after?.food?.nutrition?.kcal})", after != null && after.food.nutrition.kcal in 450.0..650.0)
            },
            Scenario("C08", "correction", "salade : celle avec la sauce B", "non c'était celle avec la sauce B", setup = {
                record(dish(SALADE, "midi"), said = "à midi ma salade")
            }) {
                records(0); called("switch_dish")
                that("variante sauce B attendue", changed.singleOrNull()?.second?.food?.recipeId == SALADE_B)
            },
            Scenario("C09", "correction", "c'était hier, pas aujourd'hui", "c'était hier pas aujourd'hui", setup = {
                record(product(BAR, 1.0, "portion", "snack"), said = "snack barre Nuvo")
            }) {
                records(0)
                that("date d'hier attendue", changed.singleOrNull()?.second?.day == MONDAY)
            },
            Scenario("C10", "correction", "enlève le riz et le poulet", "enlève le riz et le poulet", setup = {
                record(
                    food("Riz basmati cuit", 195, 4, 42, 1, "midi", 150) + "," + food("Blanc de poulet", 200, 37, 0, 4, "midi", 120) + "," + food("Haricots verts", 60, 4, 8, 0, "midi", 200),
                    said = "à midi riz basmati 150 g, poulet 120 g et haricots verts 200 g",
                )
            }) {
                records(0)
                that("riz et poulet supprimés (${deleted.map { it.food.label }})", deleted.size == 2 && deleted.none { it.food.label.contains("Haricots") })
            },

            // ── Objectifs ──
            Scenario("G01", "objectif", "2 300 kcal dès aujourd'hui", "finalement je veux être à 2300 kcal") {
                called("set_goal"); noChange()
                val goal = goalOn(today)
                that("objectif du jour 2300 (${goal?.kcal})", goal?.kcal == 2300.0)
                that("veille inchangée", goalOn(today.minusDays(1)) == GOAL)
                that("macros cohérentes", goal != null && GoalCoherence.hasAllMacros(goal) && abs(GoalCoherence.macroKcal(goal) - goal.kcal) <= GoalCoherence.tolerance(goal.kcal))
            },
            Scenario("G02", "objectif", "à partir de demain", "à partir de demain mets mon objectif à 2 200 kcal") {
                called("set_goal")
                that("aujourd'hui inchangé", goalOn(today) == GOAL)
                that("demain 2200 (${goalOn(today.plusDays(1))?.kcal})", goalOn(today.plusDays(1))?.kcal == 2200.0)
            },
            Scenario("G03", "objectif", "souhait vague, rien ne change", "j'aimerais bien perdre un peu de poids") {
                notCalled("set_goal"); noChange(); that("objectif inchangé", goalOn(today) == GOAL)
            },
            Scenario("G04", "objectif", "protéines à 160 g", "mets mes protéines à 160 g") {
                called("set_goal")
                that("protéines 160 g (${goalOn(today)?.proteinG})", goalOn(today)?.proteinG == 160.0)
            },

            // ── Plats, provisions, produits ──
            Scenario("P01", "plat", "variante par défaut de la semaine", "cette semaine la salade c'est la sauce B") {
                called("manage_dish"); noChange()
                val salade = Dishes.group(engine.allRecipes()).first { it.base.recipeId == SALADE }
                that("sauce B par défaut", salade.defaultVersion.recipeId == SALADE_B)
            },
            Scenario("P02", "provisions", "plus de thon : proposer la pause sans la faire", "je n'ai plus de thon") {
                called("update_provisions"); noChange(); asks(true)
                that("thon retiré des provisions", Provisions.held(engine.allProvisions()).none { it.name.contains("thon", ignoreCase = true) })
                that("salade pas mise en pause", Dishes.group(engine.allRecipes()).none { it.isPaused })
            },
            Scenario("P03", "produit", "nommer un produit récurrent après proposition", "oui appelle-le sandwich thon", setup = {
                record(
                    SANDWICH_YESTERDAY,
                    minutesBefore = 60 * 24,
                )
                val id = engine.allProducts().first { it.name.contains("Sandwich thon") }.productId
                record(product(id, 1.0, "portion", "midi"), said = "à midi j'ai remangé le même sandwich qu'hier", reply = "C'est noté. Tu l'avais déjà mangé hier : veux-tu en faire un produit récurrent ? Donne-lui un nom.")
            }) {
                called("update_product"); records(0)
                val p = engine.allProducts().firstOrNull { it.kind == ProductKind.Recurring && (it.name.contains("sandwich thon", ignoreCase = true) || it.aliases.any { a -> a.contains("sandwich thon", ignoreCase = true) }) && it.name.contains("andwich") }
                that("produit récurrent « sandwich thon » attendu", p != null)
            },
            Scenario("P04", "provisions", "retenir des provisions", "retiens que j'ai une nouvelle whey au chocolat et des œufs") {
                called("update_provisions"); noChange()
            },
            // ── Variantes de plats dites comme l'utilisateur les dit ──
            Scenario("V01", "plat", "la salade avec la sauce B", "à midi j'ai mangé la salade avec la sauce B") {
                records(1); moments(Moment.Midi); asks(false)
                that("variante sauce B attendue : ${added.map { it.food.recipeId }}", added.singleOrNull()?.food?.recipeId == SALADE_B)
            },
            Scenario("V02", "plat", "la sauce B avec ma salade (ordre inversé)", "ce midi la sauce B avec ma salade") {
                records(1); asks(false)
                that("variante sauce B attendue : ${added.map { it.food.recipeId }}", added.singleOrNull()?.food?.recipeId == SALADE_B)
            },
            Scenario("V03", "plat", "créer une variante, une sauce à la place", "ma salade avec la sauce C c'est 30 g de mayonnaise à la place de la vinaigrette") {
                called("manage_dish"); noChange()
                val c = Dishes.group(engine.allRecipes()).first { it.base.recipeId == SALADE }.variants.firstOrNull { it.variantName?.contains("C") == true }
                that("variante sauce C attendue", c != null)
                that("vinaigrette remplacée par la mayonnaise : ${c?.ingredients?.map { it.label }}", c != null && c.ingredients.none { it.label.contains("Vinaigrette") } && c.ingredients.any { it.label.contains("ayo", ignoreCase = true) } && c.ingredients.any { it.label == "Thon au naturel" })
            },
            Scenario(
                "V04", "plat", "variante d'un plat repris sans ingrédients pesés",
                "crée une variante de ma salade habituelle avec 70 g de sauce césar classique à la place de la sauce balsamique",
                fixture = Fixture.Imported,
            ) {
                called("manage_dish"); noChange()
                val dish = Dishes.group(engine.allRecipes()).first { it.base.recipeId == HABITUELLE }
                val v = dish.variants.singleOrNull()
                that("une variante attendue : ${dish.variants.map { it.variantName }}", v != null)
                that("sauce César, 70 g, sans balsamique : ${v?.ingredients?.map { "${it.label} ${it.quantityG}" }}", v != null && v.ingredients.any { it.productId == CESAR && abs(it.quantityG - 70.0) < 1 } && v.ingredients.none { it.productId == BALSAMIQUE })
                that("ketchup gardé", v != null && v.ingredients.any { it.productId == KETCHUP })
                that("plat de base inchangé", dish.base.ingredients.isEmpty() && dish.base.version == 1)
                that("pas de refus technique : $reply", !reply.contains("structur", ignoreCase = true))
            },
            // ── Mots corrigés ──
            Scenario("P05", "mot corrigé", "« nuvo » veut dire Nuvola : correction retenue, fiches corrigées", "quand j'écris ou dicte nuvo, c'est Nuvola") {
                called("manage_term_correction"); noChange()
                that("correction nuvo → Nuvola retenue", runBlocking { engine.activeTermCorrections() }.any { it.heard.equals("nuvo", ignoreCase = true) && it.correct == "Nuvola" })
                that("fiche Nuvo renommée", engine.allProducts().any { it.name.contains("Nuvola") })
            },
            Scenario("P06", "mot corrigé", "modifier un ingrédient en parlant : pas de correction de mot", "la barre protéinée Nuvo chocolat c'est 12 g de protéines pour 100 g") {
                notCalled("manage_term_correction"); noChange()
            },

            // ── Ton d'ami, une question utile, règle des protéines. Aussi joués par le banc simulé (ToneBenchTest). ──
            Scenario("T01", "ton", "repas simple : noté, sans question ni commentaire", "à midi j'ai mangé une assiette de pâtes au thon", setup = withProteinMin) {
                records(1..2); moments(Moment.Midi); asks(false); noJudgment()
                notCalled("get_meal_ideas"); notCalled("propose_meal")
                that("réponse courte (${reply.length} signes)", reply.length <= 200)
            },
            Scenario("T02", "ton", "4 idées : une liste, au plus une question", "propose-moi 4 idées pour ce midi", setup = {
                withProteinMin()
                record(food("Œufs brouillés et pain", 420, 22, 35, 20, "matin", 250), minutesBefore = 240)
            }) {
                noChange(); notCalled("record_consumptions"); notCalled("propose_meal"); asksAtMost(1)
                that("ni liste de 4 idées ni question utile : $reply", listItems >= 4 || (asked && listItems == 0))
                that("question minimum ou idéal hors du dernier repas : $reply", !asksMinimumOrIdeal)
            },
            Scenario("T03", "ton", "dernier repas, protéines loin du minimum : minimum ou idéal ?", "je mange quoi ce soir ?", at = EVENING, setup = {
                withProteinMin()
                record(food("Tartines confiture", 450, 15, 80, 9, "matin", 200), minutesBefore = 600)
                record(food("Pâtes sauce tomate", 650, 30, 110, 10, "midi", 450), minutesBefore = 360)
            }) {
                noChange(); notCalled("propose_meal"); asksAtMost(2)
                that("question minimum ou idéal attendue : $reply", asksMinimumOrIdeal)
                val plain = " " + app.trackly.core.model.TextMatch.normalize(reply) + " "
                val ideas = IDEA_WORDS.filter { plain.contains(" $it ") }
                that("question avant les idées, sans idée citée $ideas : $reply", listItems <= 1 && ideas.isEmpty())
            },
            Scenario("T04", "ton", "beaucoup de marge : pas de question minimum ou idéal", "une idée pour ce midi ?", setup = {
                withProteinMin()
                record(food("Œufs brouillés et pain", 420, 22, 35, 20, "matin", 250), minutesBefore = 240)
            }) {
                noChange(); notCalled("record_consumptions"); asksAtMost(1)
                that("question minimum ou idéal hors du dernier repas : $reply", !asksMinimumOrIdeal)
                that("le minimum n'est pas l'objectif : $reply", !reply.contains("minimum", ignoreCase = true))
            },
            Scenario("T05", "ton", "dépassement : noté, aucun commentaire", "comme dessert j'ai mangé une tablette de chocolat au lait", at = EVENING, setup = {
                withProteinMin()
                record(food("Flocons d'avoine, lait, banane", 520, 22, 80, 12, "matin", 400), minutesBefore = 600)
                record(food("Burger, frites et soda", 1900, 55, 210, 90, "midi", 900), minutesBefore = 360)
            }) {
                records(1); asks(false); noJudgment(); someLabel("chocolat")
                that("objectif ou reste négatif cité : $reply", !reply.contains("objectif", ignoreCase = true) && !Regex("[-−–]\\s?\\d").containsMatchIn(reply) && !reply.contains("au-dessus") && !reply.contains("au dessus"))
            },

            // ── Reprise d'un message resté sans réponse : le message échoué est marqué par le code ──
            Scenario("R01", "reprise", "message échoué puis répété avec une pomme : compté une seule fois", "200 g de riz et 100 g de poulet et une pomme", setup = {
                unprocessed("200 g de riz et 100 g de poulet pour midi")
            }) {
                called("record_consumptions")
                records(3)
                that("riz compté une fois (${added.map { it.food.label }})", added.count { it.food.label.contains("riz", ignoreCase = true) } == 1)
                that("poulet compté une fois (${added.map { it.food.label }})", added.count { it.food.label.contains("poulet", ignoreCase = true) } == 1)
                someLabel("pomme")
            },
            Scenario("R02", "reprise", "message échoué puis « réessaie mon dernier message » : enregistré", "réessaie mon dernier message", setup = {
                unprocessed("200 g de riz et 100 g de poulet pour midi")
            }) {
                called("record_consumptions")
                records(2)
                moments(Moment.Midi)
                that("riz compté une fois (${added.map { it.food.label }})", added.count { it.food.label.contains("riz", ignoreCase = true) } == 1)
                that("poulet compté une fois (${added.map { it.food.label }})", added.count { it.food.label.contains("poulet", ignoreCase = true) } == 1)
            },
        )
    }
}
