package app.trackly

import android.app.Application
import android.content.Context
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.SharedTransaction
import app.trackly.assistant.background.AndroidTurnNotifier
import app.trackly.assistant.background.AppVisibility
import app.trackly.assistant.background.TurnWorker
import app.trackly.assistant.background.WorkManagerKeepAlive
import app.trackly.assistant.chat.SharedTurnMarkerStore
import app.trackly.assistant.chat.SharedUnseenQuestionsStore
import app.trackly.assistant.chat.UnseenQuestions
import app.trackly.assistant.chat.ChatTurns
import app.trackly.assistant.chat.RoomChatHistory
import app.trackly.day.LimitAlerts
import app.trackly.assistant.hasInternet
import app.trackly.alerts.AndroidNotificationsAllowed
import app.trackly.alerts.HomeAlertsSource
import app.trackly.alerts.NotificationsAllowed
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.assistant.key.EncryptedApiKeyStore
import app.trackly.assistant.key.KeyChecker
import app.trackly.assistant.key.KeyVerifier
import app.trackly.assistant.key.SharedKeyVerificationPrefs
import app.trackly.assistant.key.VerifyingApiKeyStore
import app.trackly.setup.SetupChecklist
import app.trackly.setup.SharedSetupPrefs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import app.trackly.assistant.key.KeystoreSecretCipher
import app.trackly.assistant.model.AssistantModelSettings
import app.trackly.assistant.model.SharedAssistantModelSettings
import app.trackly.assistant.model.SharedReasoningSettings
import app.trackly.assistant.net.AndroidConnectivity
import app.trackly.assistant.net.BuildLatencyLog
import app.trackly.assistant.net.Connectivity
import app.trackly.assistant.net.HttpPrewarmer
import app.trackly.assistant.net.LatencyEvents
import app.trackly.assistant.net.LatencyLog
import app.trackly.assistant.net.Prewarmer
import app.trackly.assistant.net.Prewarmers
import app.trackly.assistant.openai.OkHttpResponsesTransport
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.dictation.DictationMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import app.trackly.assistant.openai.ResponsesClient
import app.trackly.assistant.turn.AssistantConfig
import app.trackly.assistant.turn.TurnInterpreter
import app.trackly.backup.AndroidBackupNotifier
import app.trackly.backup.AutoBackup
import app.trackly.backup.AutoBackupSchedule
import app.trackly.backup.AutoBackupWorker
import app.trackly.backup.BackupController
import app.trackly.backup.BackupFolder
import app.trackly.backup.BackupRunner
import app.trackly.backup.BackupSchedule
import app.trackly.backup.BackupScheduling
import app.trackly.backup.BackupStatusStore
import app.trackly.backup.BackupWorker
import app.trackly.backup.DatabaseSnapshotter
import app.trackly.backup.NightlyBackup
import app.trackly.backup.ProcessRestarter
import app.trackly.backup.RestoreManager
import app.trackly.backup.drive.AuthorizerTokenSource
import app.trackly.backup.drive.DriveApi
import app.trackly.backup.drive.DriveAuthorizer
import app.trackly.backup.drive.GoogleDriveAuthorizer
import app.trackly.core.engine.TracklyEngine
import app.trackly.dictation.AndroidMicPermission
import app.trackly.dictation.DictationKeywords
import app.trackly.dictation.DictationServices
import app.trackly.dictation.OkHttpFileTranscriber
import app.trackly.dictation.OkHttpLiveTranscriber
import app.trackly.dictation.SharedDictationSettings
import app.trackly.dictation.TranscriptionUsage
import app.trackly.dictation.audio.AudioRecordSource
import app.trackly.dictation.audio.AudioSourceFactory
import app.trackly.core.time.TrackingDay
import app.trackly.review.AndroidReviewNotifier
import app.trackly.review.ReportRepository
import app.trackly.review.ReviewHistory
import app.trackly.review.ReviewPeriods
import app.trackly.review.ReviewReminderWorker
import app.trackly.review.ReviewReminders
import app.trackly.review.ReviewSchedule
import app.trackly.review.ReviewPresence
import app.trackly.review.ReviewServices
import app.trackly.review.ScreenAwareReviewNotifier
import app.trackly.review.ReviewSettings
import app.trackly.review.RoomReportRepository
import app.trackly.review.RoomReviewHistory
import app.trackly.review.SharedReviewSettings
import app.trackly.storage.RoomTracklyStore
import app.trackly.storage.db.DatabaseChanges
import app.trackly.storage.db.DatabaseContents
import app.trackly.storage.db.DatabaseFactory
import app.trackly.storage.db.PreMigrationBackup
import app.trackly.storage.db.TracklyDatabase
import app.trackly.usage.RoomUsageLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import app.trackly.core.importing.HistoryImport
import app.trackly.importing.FormatNotesCleanup
import app.trackly.importing.PreloadGate
import app.trackly.importing.PreloadedHistory
import androidx.room.withTransaction
import app.trackly.backup.AutoRestore
import app.trackly.backup.BackupContents
import app.trackly.backup.FreshInstall
import app.trackly.backup.StartupRestore
import app.trackly.backup.PortableSettings
import app.trackly.importing.applyKnowledgeSeed
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import app.trackly.call.CallLine
import app.trackly.call.CallMetrics
import app.trackly.call.CallMetricsStore
import app.trackly.call.CallReview
import app.trackly.call.FilePendingReviewStore
import app.trackly.call.CallServices
import app.trackly.call.CallSink
import app.trackly.call.EndReason
import app.trackly.call.LastCallTranscript
import app.trackly.call.AgentCallThinker
import app.trackly.call.android.CallSettleWorker
import app.trackly.call.SharedCallSettings
import app.trackly.call.android.AndroidCallPlatform
import app.trackly.call.android.AudioRouter
import app.trackly.call.live.SessionRequest
import app.trackly.call.live.WebRtcLiveTransport
import java.time.Instant

/**
 * Conteneur de dépendances, assemblé à la main (pas de Hilt). Chaque fonction y
 * ajoute ses services (assistant, import, sauvegarde…).
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemUTC(),
    /** Une activité de l'app est-elle à l'écran ? (fin de tour notifiée sinon) */
    private val inForeground: () -> Boolean = { true },
) {
    private val appContext = context.applicationContext

    val database: TracklyDatabase by lazy {
        // À la première ouverture, avant toute écriture : l'empreinte de la base d'une app neuve est notée.
        DatabaseFactory.open(appContext, clock, onOpen = freshInstall::onBaseOpened)
            .also(::watchForBackup)
    }

    /**
     * Toute écriture dans la base programme la sauvegarde automatique. L'écoute commence à l'ouverture de la base,
     * avant toute écriture ; des changements restés sans copie (app arrêtée entre-temps) sont repris tout de suite.
     */
    private fun watchForBackup(db: TracklyDatabase) {
        // L'écoute d'abord : une écriture faite avant qu'elle ne commence n'est rattrapée que par le filet de nuit.
        appScope.launch(Dispatchers.IO) {
            DatabaseChanges.watch(db) {
                freshInstall.markUsed()
                autoBackup.onChange()
            }
        }
        appScope.launch(Dispatchers.IO) { autoBackup.requestIfPending() }
    }

    /** Moteur métier : seul point d'écriture des données. */
    val engine: TracklyEngine by lazy { TracklyEngine(RoomTracklyStore(database), clock) }

    /** « Saisie manuelle » (bas de la liste des repas, ou appui long sur « Trackly »). */
    val devPanelEnabled: Boolean = true

    /** Clé OpenAI chiffrée (Android Keystore), dans `no_backup` : jamais sauvegardée. */
    val apiKeyStore: EncryptedApiKeyStore by lazy {
        EncryptedApiKeyStore(File(appContext.noBackupFilesDir, EncryptedApiKeyStore.FILE_NAME), KeystoreSecretCipher())
    }

    /**
     * Vérification gratuite de la clé (`GET /v1/models`) à chaque enregistrement, par collage comme par QR code.
     * [KeyVerification.verified] émet quand une clé vient d'être reconnue valide : le chat s'en sert pour proposer
     * « Renvoyer mon dernier message ».
     */
    val keyVerifier: KeyVerifier by lazy {
        val http = OkHttpResponsesTransport.defaultClient().newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        KeyVerifier(apiKeyStore, KeyChecker(http, isOnline = { appContext.hasInternet() }), appScope, SharedKeyVerificationPrefs(appContext))
    }

    /** La clé telle que l'écran la voit : tout enregistrement déclenche le contrôle gratuit. Les requêtes lisent [apiKeyStore]. */
    val verifyingKeyStore: ApiKeyStore by lazy { VerifyingApiKeyStore(apiKeyStore, keyVerifier) }

    /**
     * Compteur de coût local : reçoit l'usage de chaque réponse de l'assistant. La dictée
     * y enregistre ses transcriptions avec `usageLedger.recordTranscription(modèle, duréeMs, instant)`.
     */
    val usageLedger: RoomUsageLedger by lazy { RoomUsageLedger(database.usage(), TrackingDay.ZONE) }

    /** Modèle de l'assistant et du bilan (Réglages › Modèle de l'assistant), relu à chaque message. */
    val assistantModelSettings: AssistantModelSettings by lazy { SharedAssistantModelSettings(appContext) }

    private val assistantConfig get() = AssistantConfig(model = assistantModelSettings::currentCode)

    /**
     * Assistant : Responses API (OkHttp, streaming), outils exécutés localement par le moteur. Les tours du chat
     * tournent dans [appScope], gardés en vie par un travail WorkManager accéléré, et notifient leur fin si l'app
     * n'est plus à l'écran.
     */
    val assistant: AssistantServices by lazy {
        val transport = OkHttpResponsesTransport(
            client = assistantHttp,
            apiKey = { apiKeyStore.read() },
            isOnline = { appContext.hasInternet() },
        )
        AssistantServices(
            engine = engine,
            keyStore = verifyingKeyStore,
            history = RoomChatHistory(database.chat()),
            interpreter = TurnInterpreter(ResponsesClient(transport), engine, config = assistantConfig, usageSink = usageLedger, recordAlerts = LimitAlerts),
            scope = appScope,
            keepAlive = WorkManagerKeepAlive(appContext),
            notifier = AndroidTurnNotifier(appContext),
            inForeground = inForeground,
            connectivity = connectivity,
            prewarmer = prewarmer,
            latency = latencyLog,
            turnMarkers = SharedTurnMarkerStore(appContext),
            reasoning = SharedReasoningSettings(appContext),
            recordAlerts = LimitAlerts,
            unseenQuestions = UnseenQuestions(SharedUnseenQuestionsStore(appContext)),
            sharedTransaction = object : SharedTransaction {
                override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
            },
        )
    }

    /** Mesure locale de la latence : journal Android en debug (durées seulement), rien en release. */
    private val latencyLog: LatencyLog get() = BuildLatencyLog.log

    /** Client HTTP de l'assistant ; le préchauffage passe par lui pour que le premier envoi réutilise la connexion. */
    private val assistantHttp: OkHttpClient by lazy {
        OkHttpResponsesTransport.defaultClient().newBuilder().eventListenerFactory(LatencyEvents(latencyLog)).build()
    }

    /** Client HTTP de la dictée (fichier) et base de celui de la dictée en direct (même pool de connexions). */
    private val dictationHttp: OkHttpClient by lazy { OkHttpResponsesTransport.defaultClient() }

    private val liveHttp: OkHttpClient by lazy { OkHttpLiveTranscriber.defaultClient(dictationHttp) }

    /** Réseau suivi en continu (bannière « Hors ligne » du chat). */
    val connectivity: Connectivity by lazy { AndroidConnectivity(appContext) }

    /**
     * Préchauffage à l'ouverture du chat : HTTPS de l'assistant, et connexion de la dictée du mode choisi
     * (HTTP/1.1 pour la WebSocket du direct, que OkHttp réutilise pour l'ouverture de la WebSocket). Rien sans clé
     * ni réseau ; aucune clé n'est envoyée.
     */
    val prewarmer: Prewarmer by lazy {
        val ready: suspend () -> Boolean = {
            if (apiKeyStore.status.value is ApiKeyStatus.Unknown) apiKeyStore.refresh()
            apiKeyStore.status.value is ApiKeyStatus.Present && appContext.hasInternet()
        }
        val api = OkHttpResponsesTransport.DEFAULT_BASE_URL.toHttpUrl()
        val chat = HttpPrewarmer(assistantHttp, api.resolve("models")!!, appScope, ready)
        val live = HttpPrewarmer(
            liveHttp.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build(),
            api.resolve("realtime")!!,
            appScope,
            ready,
        )
        val file = HttpPrewarmer(dictationHttp, api.resolve("audio/transcriptions")!!, appScope, ready)
        Prewarmers(
            listOf(
                chat,
                Prewarmer {
                    if (dictationSettings.mode.value == DictationMode.Live) live.warm() else file.warm()
                },
            ),
        )
    }

    // ───── Dictée ─────

    val dictationSettings: SharedDictationSettings by lazy { SharedDictationSettings(appContext) }

    /** Permission micro ; `MainActivity` lui confie l'activité en cours (nécessaire pour détecter un refus définitif). */
    val micPermission: AndroidMicPermission by lazy { AndroidMicPermission(appContext) }

    val dictation: DictationServices by lazy {
        val http = dictationHttp
        DictationServices(
            settings = dictationSettings,
            permission = micPermission,
            audio = AudioSourceFactory { AudioRecordSource.open() },
            live = OkHttpLiveTranscriber(
                client = liveHttp,
                apiKey = { apiKeyStore.read() },
                isOnline = { appContext.hasInternet() },
            ),
            file = OkHttpFileTranscriber(http, apiKey = { apiKeyStore.read() }, isOnline = { appContext.hasInternet() }),
            keywords = { DictationKeywords.from(engine) },
            corrections = { engine.activeTermCorrections() },
            usage = TranscriptionUsage { model, audioMs, at -> usageLedger.recordTranscription(model, audioMs, at) },
            clock = clock::instant,
        )
    }

    // ───── Appel ─────

    /** Réflexion pendant l'appel et annulation d'écho (Réglages › Appel). */
    val callSettings: SharedCallSettings by lazy { SharedCallSettings(appContext) }

    /** Transcription du dernier appel et mesures : fichiers privés, hors sauvegarde Android et hors copie Drive. */
    val lastCallTranscript: LastCallTranscript by lazy {
        LastCallTranscript(File(File(appContext.filesDir, LastCallTranscript.DIR), LastCallTranscript.FILE_NAME))
    }
    val callMetrics: CallMetricsStore by lazy {
        CallMetricsStore(File(File(appContext.filesDir, LastCallTranscript.DIR), CallMetricsStore.FILE_NAME))
    }

    /** Relectures après l'appel pas encore faites : même dossier privé, effacées dès la relecture finie. */
    private val pendingReviews: FilePendingReviewStore by lazy {
        FilePendingReviewStore(File(File(appContext.filesDir, LastCallTranscript.DIR), FilePendingReviewStore.FILE_NAME))
    }

    /** Client de la requête de session d'appel ; le préchauffage passe par lui (TLS déjà ouvert au toucher). */
    private val callHttp: OkHttpClient by lazy { WebRtcLiveTransport.httpClient(OkHttpResponsesTransport.defaultClient()) }

    /**
     * Appel vocal : GPT-Live en WebRTC, réflexion par l'agent Trackly (délégation `client`, mêmes outils et même mémoire que
     * le chat) avec le modèle du réglage de l'appel. Contexte tiré du fil du jour (jamais du fil du bilan) ; seule la
     * réflexion note un repas, dans le chat du jour, annulable.
     */
    val call: CallServices by lazy {
        val router = AudioRouter(appContext)
        val thinkerTransport = OkHttpResponsesTransport(client = assistantHttp, apiKey = { apiKeyStore.read() }, isOnline = { appContext.hasInternet() })
        val thinker = AgentCallThinker(
            client = ResponsesClient(thinkerTransport),
            engine = engine,
            history = assistant.history,
            usage = usageLedger,
            recordAlerts = assistant.recordAlerts,
        )
        // Relecture après l'appel : le même agent, au réglage de l'appel ; son coût va dans le suivi d'usage.
        val review = CallReview(
            scope = appScope,
            store = pendingReviews,
            reviewer = thinker,
            history = assistant.history,
            unseen = assistant.unseenQuestions,
            now = engine::now,
            isOnline = { appContext.hasInternet() },
        )
        val ready: suspend () -> Boolean = { appContext.hasInternet() }
        val warm = HttpPrewarmer(callHttp, SessionRequest.ENDPOINT.toHttpUrl(), appScope, ready)
        CallServices(
            scope = appScope,
            settings = callSettings,
            readKey = { apiKeyStore.read() },
            dayThread = assistant.thread,
            thinker = thinker,
            newTransport = { echo -> WebRtcLiveTransport(appContext, echo, callHttp) },
            audio = router,
            platform = AndroidCallPlatform(appContext),
            sink = object : CallSink {
                override suspend fun recordCost(model: String, sessionMs: Long, at: Instant) = usageLedger.recordLiveCall(model, sessionMs, at)
                override fun saveTranscript(lines: List<CallLine>, at: Instant) = lastCallTranscript.write(lines, at)
                override fun saveMetrics(metrics: CallMetrics) = callMetrics.add(metrics)
            },
            prewarm = warm::warm,
            now = SystemClock::elapsedRealtime,
            network = { appContext.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork },
            keyChanges = apiKeyStore.status,
            review = review,
        ).also { services ->
            router.onRoute = services.controller::setRoute
            router.onFocusLost = { services.abort(EndReason.Interrupted) }
        }
    }

    // ───── Bilan du lundi ─────

    val reviewSettings: ReviewSettings by lazy { SharedReviewSettings(appContext) }
    val reportRepository: ReportRepository by lazy { RoomReportRepository(database) }
    private val reviewHistory: ReviewHistory by lazy { RoomReviewHistory(database.review()) }

    /** Période du bilan : rapports produits et fil déjà commencé. */
    private val reviewPeriods: ReviewPeriods by lazy { ReviewPeriods(reportRepository, reviewHistory) }
    private val reviewNotifier by lazy { AndroidReviewNotifier(appContext) }

    /** Conversation de bilan : interpréteur à part (outils, consignes et contexte du bilan), même clé et même compteur de coût. */
    val review: ReviewServices by lazy {
        val transport = OkHttpResponsesTransport(
            client = OkHttpResponsesTransport.defaultClient(),
            apiKey = { apiKeyStore.read() },
            isOnline = { appContext.hasInternet() },
        )
        val presence = ReviewPresence()
        // « Ton bilan est prêt » n'est pas notifié à qui regarde la conversation de bilan.
        val notifier = ScreenAwareReviewNotifier(reviewNotifier) { presence.conversationShown && inForeground() }
        ReviewServices(
            engine = engine,
            keyStore = apiKeyStore,
            history = reviewHistory,
            reports = reportRepository,
            interpreter = ReviewServices.interpreter(
                ResponsesClient(transport), engine, reviewPeriods, notifier, usageLedger, assistantConfig,
            ),
            presence = presence,
        )
    }

    val reviewReminders: ReviewReminders by lazy {
        ReviewReminders(reviewPeriods, reviewNotifier, reviewSettings, clock, TrackingDay.ZONE)
    }

    /** Planifie (ou annule) l'invitation du lundi et la relance du soir ; idempotent. */
    fun scheduleReview() = ReviewSchedule.schedule(appContext, clock, TrackingDay.ZONE, reviewSettings.state.value)

    // ───── Alertes et mise en route ─────

    /** Les notifications de Trackly sont-elles permises ? Relu à chaque retour dans l'app. */
    val notificationsAllowed: NotificationsAllowed by lazy { AndroidNotificationsAllowed(appContext) }

    /**
     * Alertes discrètes, exposées pour l'écran du jour (`homeAlerts.alerts.value.forDayScreen`) et les Réglages :
     * copie Drive de plus de 36 h, notifications bloquées alors que les rappels du lundi sont voulus.
     */
    val homeAlerts: HomeAlertsSource by lazy {
        HomeAlertsSource(backupStatus.status, reviewSettings.state, notificationsAllowed, clock, TrackingDay.ZONE, appScope, reportRepository.observeAll())
    }

    /** Liste « Mise en route » : clé vérifiée, historique, Drive, notifications, batterie Samsung, fichier d'export. */
    val setupChecklist: SetupChecklist by lazy {
        SetupChecklist(
            keyStore = apiKeyStore,
            verification = keyVerifier,
            notificationsAllowed = notificationsAllowed.allowed,
            backup = backupStatus.status,
            importDone = engine.revision.map { engine.importedSourceCount() > 0 }.distinctUntilChanged(),
            prefs = SharedSetupPrefs(appContext),
            scope = appScope,
            install = freshInstall,
        )
    }

    // ───── Sauvegarde (Réglages › Sauvegarde) ─────

    /** Portée des opérations longues lancées depuis l'interface (survit à l'écran). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val backupStatus: BackupStatusStore by lazy { BackupStatusStore(appContext) }
    private val driveAuthorizer: DriveAuthorizer by lazy { GoogleDriveAuthorizer(appContext) }
    private val httpClient by lazy { DriveApi.defaultClient() }
    private val backupFolder by lazy { BackupFolder(backupStatus) }

    /** Un client Drive par opération : le jeton (1 h) est redemandé à chaque fois. */
    private fun driveApi() = DriveApi(AuthorizerTokenSource(driveAuthorizer), httpClient)

    val backupRunner: BackupRunner by lazy {
        BackupRunner(
            // Chaque copie Drive emporte les réglages non secrets (jamais la clé OpenAI).
            snapshotter = DatabaseSnapshotter({ database }, decorate = { copy ->
                PortableSettings.attach(copy, PortableSettings.read(PortableSettings.prefsOf(appContext)))
            }),
            workDir = File(appContext.cacheDir, "sauvegarde"),
            drive = ::driveApi,
            folder = backupFolder,
            status = backupStatus,
            clock = clock,
            zone = TrackingDay.ZONE,
        )
    }

    val nightlyBackup: NightlyBackup by lazy {
        NightlyBackup(backupRunner, AndroidBackupNotifier(appContext), backupStatus, clock)
    }

    /** Copie sur Drive quelques minutes après chaque changement ; la nuit n'est plus qu'un filet. */
    val autoBackup: AutoBackup by lazy {
        AutoBackup(backupRunner, backupStatus, AndroidBackupNotifier(appContext), autoBackupScheduling, clock)
    }

    private val autoBackupScheduling by lazy { AutoBackupSchedule.scheduling(appContext) }

    private val restoreManager: RestoreManager by lazy {
        RestoreManager(
            databaseFile = appContext.getDatabasePath(TracklyDatabase.NAME),
            workDir = File(appContext.noBackupFilesDir, "restauration"),
            localCopiesDir = File(appContext.noBackupFilesDir, PreMigrationBackup.DIR),
            drive = ::driveApi,
            folder = backupFolder,
            status = backupStatus,
            openWithRoom = { file -> DatabaseFactory.builder(appContext, file.absolutePath).build() },
            zone = TrackingDay.ZONE,
        )
    }

    val backupScheduling: BackupScheduling = object : BackupScheduling {
        override fun schedule() = BackupSchedule.schedule(appContext, clock, TrackingDay.ZONE)
        override fun backupSoon() = autoBackup.requestIfPending()
        override fun cancel() {
            BackupSchedule.cancel(appContext)
            autoBackupScheduling.cancel()
        }
    }

    val backupController: BackupController by lazy {
        BackupController(
            runner = backupRunner,
            restore = restoreManager,
            folder = backupFolder,
            authorizer = driveAuthorizer,
            drive = ::driveApi,
            status = backupStatus,
            scheduling = backupScheduling,
            restarter = ProcessRestarter(appContext),
            scope = appScope,
            workDir = File(appContext.noBackupFilesDir, "restauration"),
            zone = TrackingDay.ZONE,
            importHistory = { text -> HistoryImport.run(engine, text) },
            autoRestore = AutoRestore(
                fresh = freshInstall,
                restore = restoreManager,
                drive = ::driveApi,
                folder = backupFolder,
                baseContents = { withContext(Dispatchers.IO) { BackupContents.of(database.openHelper.writableDatabase) } },
                zone = TrackingDay.ZONE,
            ),
            fresh = freshInstall,
            clock = clock,
        )
    }

    // ───── Historique embarqué dans l'APK (facultatif) ─────

    private val preloadStarted = AtomicBoolean(false)

    private val preloadedHistory: PreloadedHistory by lazy {
        PreloadedHistory(
            read = { PreloadedHistory.readAsset(appContext) },
            import = { text -> HistoryImport.run(engine, text) },
            prefs = SharedSetupPrefs(appContext),
            // Base déjà remplie (mise à jour par-dessus, Auto Backup, restauration) : jamais de préchargement.
            baseHasData = { DatabaseContents.hasData(database) },
            seed = { applyKnowledgeSeed(engine, PreloadedHistory.readAsset(appContext, PreloadedHistory.KNOWLEDGE_ASSET)) },
            // App neuve : le préchargement attend la réponse à la proposition de connexion Google.
            gate = PreloadGate { freshInstall.awaitPreloadChoice() },
            // La base est relue vide dans la transaction même de la reprise : aucune saisie ne peut s'intercaler.
            importOnEmptyBase = { text ->
                database.withTransaction { if (DatabaseContents.hasData(database)) null else HistoryImport.run(engine, text) }
            },
        )
    }

    /**
     * Reprend l'historique embarqué, une seule fois, en arrière-plan, sur une base vide. Sans effet si l'APK n'en contient pas.
     * Ensuite, une seule fois par base, retire de la mémoire les notes de mise en forme.
     */
    fun startPreloadedHistory() {
        if (!preloadStarted.compareAndSet(false, true)) return
        val preloaded = preloadedHistory
        appScope.launch {
            // Il n'y a pas de fichier d'export à supprimer : la liste de mise en route n'en parle pas.
            if (withContext(Dispatchers.IO) { preloaded.runOnce() }) setupChecklist.markExportFileDeleted()
            // Après le préchargement : les notes qu'il apporte sont regardées aussi.
            withContext(Dispatchers.IO) {
                FormatNotesCleanup(engine, baseHasData = { DatabaseContents.hasData(database) }).runOnce()
            }
        }
    }

    // ───── Installation et restauration au démarrage ─────

    /** App neuve ou non : décidé avant toute écriture de préférences (voir [FreshInstall.previousInstall]). */
    val freshInstall: FreshInstall = FreshInstall.create(appContext)

    init {
        applyPendingRestore()
    }

    /**
     * Restauration préparée (Réglages, ou restauration automatique) : appliquée ici, au démarrage du processus, avant
     * l'ouverture de la base et avant qu'un réglage ne soit lu, pour que les réglages de la copie soient ceux de l'app.
     */
    private fun applyPendingRestore() {
        StartupRestore.apply(
            databaseFile = appContext.getDatabasePath(TracklyDatabase.NAME),
            safetyDir = File(appContext.noBackupFilesDir, PreMigrationBackup.DIR),
            clock = clock,
            prefsOf = PortableSettings.prefsOf(appContext),
            status = backupStatus,
            fresh = freshInstall,
            onBaseReplaced = { preloadedHistory.onBaseReplaced() },
        )
    }
}

class TracklyApplication :
    Application(),
    BackupWorker.Host,
    AutoBackupWorker.Host,
    ReviewReminderWorker.Host,
    TurnWorker.Host,
    CallSettleWorker.Host {
    /** L'app est-elle à l'écran ? (activités démarrées) */
    private val visibility = AppVisibility()

    val container: AppContainer by lazy { AppContainer(this, inForeground = { visibility.inForeground }) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibility)
        enableLeakDetectionInDebug()
    }

    /**
     * Fuites de ressources (flux, curseurs, activités) : signalées dans le journal système, en debug seulement.
     * Jamais en release, jamais bloquant (`penaltyLog`).
     */
    private fun enableLeakDetectionInDebug() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    override val chatTurns: ChatTurns get() = container.assistant.turns

    override val callServices: CallServices get() = container.call

    override val nightlyBackup: NightlyBackup get() = container.nightlyBackup

    override fun rescheduleBackup() = container.backupScheduling.schedule()

    override val autoBackup: AutoBackup get() = container.autoBackup

    override val reviewReminders: ReviewReminders get() = container.reviewReminders

    override fun rescheduleReview() = container.scheduleReview()
}
