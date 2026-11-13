package app.trackly.review

import app.trackly.assistant.context.ContextBuilder
import app.trackly.assistant.context.review
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.prompt.SystemPrompt
import app.trackly.assistant.prompt.review
import app.trackly.assistant.tools.ToolRegistry
import app.trackly.assistant.tools.review
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.engine.TracklyEngine

/**
 * Ce dont la conversation de bilan a besoin : le moteur, la clé, l'historique du fil de bilan, les
 * rapports et un interpréteur de tour **à part** (outils, consignes et contexte propres au bilan).
 */
class ReviewServices(
    val engine: TracklyEngine,
    val keyStore: ApiKeyStore,
    val history: ReviewHistory,
    val reports: ReportRepository,
    val interpreter: TurnInterpreter,
    /** La conversation de bilan est à l'écran (posé par `ReviewHost`) : pas de notification « bilan prêt ». */
    val presence: ReviewPresence = ReviewPresence(),
) {
    /** Période du bilan à un instant : rapports produits et fil déjà commencé. */
    val periods = ReviewPeriods(reports, history)

    companion object {
        /** Interpréteur de la conversation de bilan, branché sur [client]. */
        fun interpreter(
            client: ResponsesClient,
            engine: TracklyEngine,
            periods: ReviewPeriods,
            notifier: ReviewNotifier,
            usageSink: UsageSink = UsageSink.None,
            config: AssistantConfig = AssistantConfig(),
        ) = TurnInterpreter(
            client = client,
            engine = engine,
            tools = ToolRegistry.review(periods, notifier),
            prompt = SystemPrompt.review(),
            context = ContextBuilder.review(periods),
            config = config,
            usageSink = usageSink,
        )

        /** Bilan branché sur [transport] (faux en tests), avec clé, historique et rapports en mémoire. */
        fun inMemory(
            engine: TracklyEngine,
            notifier: ReviewNotifier,
            transport: ResponsesTransport = ResponsesTransport { throw AssistantException(AssistantError.NoInternet) },
            keyStore: ApiKeyStore = InMemoryApiKeyStore(),
            history: ReviewHistory = InMemoryReviewHistory(),
            reports: ReportRepository = InMemoryReportRepository(),
            usageSink: UsageSink = UsageSink.None,
            presence: ReviewPresence = ReviewPresence(),
        ) = ReviewServices(
            engine, keyStore, history, reports,
            interpreter(ResponsesClient(transport), engine, ReviewPeriods(reports, history), ScreenAwareReviewNotifier(notifier) { presence.conversationShown }, usageSink),
            presence,
        )
    }
}
