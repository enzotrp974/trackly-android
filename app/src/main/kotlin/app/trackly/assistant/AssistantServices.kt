package app.trackly.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.trackly.assistant.cards.RecordAlerts
import app.trackly.assistant.chat.ChatHistory
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.DayThread
import app.trackly.assistant.chat.InMemoryTurnMarkerStore
import app.trackly.assistant.chat.TurnKeepAlive
import app.trackly.assistant.chat.TurnMarkerStore
import app.trackly.assistant.chat.TurnNotifier
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.assistant.chat.InMemoryChatHistory
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.InMemoryApiKeyStore
import app.trackly.assistant.model.InMemoryReasoningSettings
import app.trackly.assistant.model.ReasoningSettings
import app.trackly.assistant.net.Connectivity
import app.trackly.assistant.net.LatencyLog
import app.trackly.assistant.net.Prewarmer
import app.trackly.photos.SessionThumbnails
import app.trackly.assistant.openai.AssistantError
import app.trackly.assistant.openai.AssistantException
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.openai.ResponsesTransport
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.assistant.turn.UsageSink
import app.trackly.core.engine.TracklyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Ce dont le chat a besoin : le moteur, la clé, l'historique local et l'interpréteur de tour.
 * Assemblé par `AppContainer` ; les tests en construisent un avec un faux transport.
 */
class AssistantServices(
    val engine: TracklyEngine,
    val keyStore: ApiKeyStore,
    val history: ChatHistory,
    val interpreter: TurnInterpreter,
    /** Portée des tours : celle de l'application (un tour survit à l'écran). */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    keepAlive: TurnKeepAlive = TurnKeepAlive.None,
    notifier: TurnNotifier = TurnNotifier.None,
    inForeground: () -> Boolean = { true },
    /** Réseau disponible, suivi en continu (bannière « Hors ligne »). */
    val connectivity: Connectivity = Connectivity.AlwaysOnline,
    /** Préchauffage de la connexion à l'ouverture du chat ; rien sans clé. */
    val prewarmer: Prewarmer = Prewarmer.None,
    /** Mesure locale de la latence (durées seulement). */
    latency: LatencyLog = LatencyLog.None,
    /** Repère « tour en cours » (reprise après un arrêt d'Android) ; en mémoire par défaut. */
    turnMarkers: TurnMarkerStore = InMemoryTurnMarkerStore(),
    /** Niveau de raisonnement du sélecteur : il reste choisi d'un message à l'autre et après un redémarrage. */
    val reasoning: ReasoningSettings = InMemoryReasoningSettings(),
    /** Rouge de la carte d'enregistrement, figé aussi quand le téléphone note une proposition lui-même. */
    val recordAlerts: RecordAlerts = RecordAlerts.None,
    /** Questions laissées dans le fil du jour par la relecture après un appel, pas encore vues ; en mémoire par défaut. */
    val unseenQuestions: UnseenQuestions = UnseenQuestions(),
    /** Repas et carte du fil écrits ensemble ou pas du tout (« Noter », CHAT-01) ; sans effet en mémoire. */
    val sharedTransaction: SharedTransaction = SharedTransaction.None,
) {
    /** Tours du chat, hors du cycle de vie de l'écran : une seule instance pour toute l'app. */
    val turns: ChatTurns = ChatTurns(engine, history, interpreter, scope, keepAlive, notifier, inForeground, latency, markers = turnMarkers)

    /** Fil du jour : ce que le chat affiche et envoie au modèle, remis à zéro à 5 h (voir [DayThread]). */
    val thread: DayThread = DayThread(history, onCleared = turns::clearFinished) { engine.now() }

    /** Nombre de questions pas encore vues dans le fil du jour en cours : la pastille de l'accueil (0 : aucune). */
    val waitingQuestions: Flow<Int> = combine(unseenQuestions.state, thread.days()) { unseen, day -> UnseenQuestions.count(unseen, day) }
        .distinctUntilChanged()

    /** Vignettes des photos envoyées pendant la session, en mémoire seulement (idée 7). */
    val thumbnails: SessionThumbnails = SessionThumbnails()

    companion object {
        /** Assistant branché sur [transport] (faux en tests), avec clé et historique en mémoire. */
        fun inMemory(
            engine: TracklyEngine,
            transport: ResponsesTransport = ResponsesTransport { throw AssistantException(AssistantError.NoInternet) },
            keyStore: ApiKeyStore = InMemoryApiKeyStore(),
            history: ChatHistory = InMemoryChatHistory(),
            usageSink: UsageSink = UsageSink.None,
            notifier: TurnNotifier = TurnNotifier.None,
            inForeground: () -> Boolean = { true },
            connectivity: Connectivity = Connectivity.AlwaysOnline,
            prewarmer: Prewarmer = Prewarmer.None,
            reasoning: ReasoningSettings = InMemoryReasoningSettings(),
            recordAlerts: RecordAlerts = RecordAlerts.None,
            unseenQuestions: UnseenQuestions = UnseenQuestions(),
            sharedTransaction: SharedTransaction = SharedTransaction.None,
        ) = AssistantServices(
            engine, keyStore, history, TurnInterpreter(ResponsesClient(transport), engine, usageSink = usageSink, recordAlerts = recordAlerts),
            notifier = notifier, inForeground = inForeground, connectivity = connectivity, prewarmer = prewarmer, reasoning = reasoning,
            recordAlerts = recordAlerts, unseenQuestions = unseenQuestions, sharedTransaction = sharedTransaction,
        )
    }
}

/** Le téléphone a-t-il un réseau avec accès à Internet ? */
fun Context.hasInternet(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Une transaction qui couvre à la fois le moteur et l'historique du chat : dans l'app, ils vivent dans la même base
 * Room, et [run] y ouvre une transaction (les transactions du moteur et de l'historique s'y joignent). Une exception
 * dans [block] annule tout : un repas noté sans sa carte, que « Noter » compterait une seconde fois, n'existe pas.
 */
interface SharedTransaction {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        /** Moteur et historique séparés (tests en mémoire) : le bloc s'exécute tel quel. */
        val None: SharedTransaction = object : SharedTransaction {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}
