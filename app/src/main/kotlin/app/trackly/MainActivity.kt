package app.trackly

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.trackly.assistant.background.AndroidTurnNotifier
import app.trackly.backup.AndroidBackupNotifier
import app.trackly.review.AndroidReviewNotifier
import app.trackly.core.time.TrackingDay
import app.trackly.importing.HistoryImportViewModel
import app.trackly.ui.SettingsSlot
import app.trackly.ui.TracklyRoot
import app.trackly.ui.day.rememberDayAlert
import app.trackly.ui.day.rememberReviewDue
import app.trackly.assistant.key.AndroidSecretClipboard
import app.trackly.ui.settings.AssistantModelSettingsRoute
import app.trackly.ui.settings.DiscussionSettingsRoute
import app.trackly.ui.settings.BackupSettingsRoute
import app.trackly.ui.settings.NotificationsSettingsRoute
import app.trackly.ui.settings.KeyScanRoute
import app.trackly.ui.settings.KeyTools
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.review.ReviewDestination
import app.trackly.ui.review.ReviewHost
import app.trackly.ui.review.ReviewNav
import app.trackly.ui.photos.rememberPhotoPicker
import app.trackly.ui.settings.DictationSettingsRoute
import app.trackly.ui.settings.AlertsSettingsRoute
import app.trackly.ui.settings.GoalsSettingsRoute
import app.trackly.ui.settings.SetupSettingsRoute
import app.trackly.ui.setup.SetupNav
import app.trackly.ui.setup.SetupRoute
import app.trackly.ui.setup.SetupSlot
import app.trackly.ui.settings.ReviewSettingsRoute
import app.trackly.ui.settings.UsageSettingsRoute
import app.trackly.ui.theme.MotionPolicy
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import app.trackly.call.EndReason
import app.trackly.ui.call.CallHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.ui.settings.CallSettingsRoute

class MainActivity : ComponentActivity() {
    private var motion by mutableStateOf(MotionPolicy(reduced = false))
    private val container get() = (application as TracklyApplication).container
    private val importModel: HistoryImportViewModel by viewModels {
        HistoryImportViewModel.factory(container.engine)
    }

    /** Augmente à chaque demande d'ouverture de Réglages › Sauvegarde (notification). */
    private var backupRequests by mutableIntStateOf(0)

    /** Augmente à chaque demande d'ouverture du chat (notification de fin de tour). */
    private var chatRequests by mutableIntStateOf(0)

    /** Mise en route : ouverte après la reprise, ou depuis les Réglages tant qu'elle n'est pas finie. */
    private val setupNav = SetupNav(
        autoOpen = { container.setupChecklist.takeAutoOpen() },
        onLeave = { container.setupChecklist.leave() },
    )

    /** Écrans du bilan du lundi, posés par-dessus l'app (notification, ou Réglages › Bilan du lundi). */
    private val reviewNav = ReviewNav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        motion = MotionPolicy.fromSystem(this)
        container.micPermission.attach(this)
        AndroidBackupNotifier.ensureChannel(this)
        AndroidReviewNotifier.ensureChannel(this)
        AndroidTurnNotifier.ensureChannel(this)
        if (savedInstanceState == null) {
            handleOpenRequest(intent)
            // Recale la sauvegarde de nuit (idempotent) si Drive est connecté.
            if (container.backupStatus.status.value.connected) runCatching { container.backupScheduling.schedule() }
            // Recale l'invitation du lundi et la relance du soir (idempotent).
            runCatching { container.scheduleReview() }
        }
        container.startPreloadedHistory()
        val keyTools = KeyTools(
            clipboard = AndroidSecretClipboard(this),
            // Le scan enregistre par la clé « vérifiante » : « Clé valide » / « Clé refusée » s'affiche aussitôt.
            scanner = { onClose -> KeyScanRoute(container.verifyingKeyStore, onClose) },
            verification = container.keyVerifier,
        )
        setContent {
            val dayAlert = rememberDayAlert(container.homeAlerts, container.notificationsAllowed)
            val reviewDue by rememberReviewDue(container.homeAlerts)
            // L'écran d'appel est ouvert : l'app dessous sort de l'arbre d'accessibilité.
            val callOpen by container.call.screenOpen.collectAsStateWithLifecycle()
            Box {
              // Bilan du lundi ouvert par-dessus : l'app dessous (réglages compris) sort de l'arbre d'accessibilité,
              // pour que TalkBack ne lise pas un écran recouvert.
              Box(if (reviewNav.current != null || callOpen) Modifier.clearAndSetSemantics {} else Modifier) {
                TracklyRoot(
                    engine = container.engine,
                    motion = motion,
                    devPanelEnabled = container.devPanelEnabled,
                    assistant = container.assistant,
                    dictation = container.dictation,
                    importModel = importModel,
                    openChatRequest = chatRequests,
                    dayAlert = dayAlert,
                    // « Bilan » de la barre du bas : la conversation de bilan, comme l'ancienne ligne « Faire mon bilan ».
                    onOpenReview = { reviewNav.start(ReviewDestination.Conversation) },
                    reviewDue = reviewDue,
                    // Bouton d'appel : un toucher, sans confirmation, avec une vibration.
                    onCall = ::startCall,
                    settings = SettingsSlot(
                        backupRequests,
                        keyTools,
                        setup = SetupSlot(setupNav) { nav, onPickHistory ->
                            SetupRoute(container.setupChecklist, nav, container.notificationsAllowed, container.backupController, onPickHistory)
                        },
                        groups = SettingsGroups(
                            top = { SetupSettingsRoute(container.setupChecklist, setupNav) },
                            model = {
                                AssistantModelSettingsRoute(container.assistantModelSettings)
                                DiscussionSettingsRoute(container.assistant.thread)
                            },
                            dictation = { DictationSettingsRoute(container.dictationSettings) },
                            call = { CallSettingsRoute(container.callSettings, container.lastCallTranscript, container.callMetrics) },
                            usage = { UsageSettingsRoute(container.usageLedger, container.clock) },
                            drive = {
                                AlertsSettingsRoute(container.homeAlerts, container.notificationsAllowed, container.backupController) {}
                                BackupSettingsRoute(container.backupController, container.clock, TrackingDay.ZONE)
                            },
                            goals = { GoalsSettingsRoute(container.engine) },
                            review = {
                                ReviewSettingsRoute(
                                    settings = container.reviewSettings,
                                    reschedule = { container.scheduleReview() },
                                    onStart = { reviewNav.start(ReviewDestination.Conversation) },
                                    onReports = { reviewNav.start(ReviewDestination.Reports) },
                                    notifications = container.notificationsAllowed,
                                )
                            },
                            notifications = { NotificationsSettingsRoute(container.notificationsAllowed) },
                        ),
                    ),
                )
              }
                // Bilan du lundi : posé par-dessus, avec le sélecteur de photos pour les captures de la montre.
                ReviewHost(
                    nav = reviewNav,
                    services = container.review,
                    motion = motion,
                    imagePicker = { remaining, onPhotos, onProblem -> rememberPhotoPicker(remaining, onPhotos, onProblem) },
                    dictation = container.dictation,
                )
                // Appel : écran plein par-dessus tout, ouvert dès le toucher.
                CallHost(container.call, onBackground = { moveTaskToBack(true) })
            }
        }
    }

    /** Toucher du bouton d'appel : vibration, puis l'appel (après la permission du micro, demandée au premier appel). */
    private fun startCall() {
        container.call.platformVibrate()
        if (container.micPermission.isGranted()) {
            container.call.start(micAllowed = true)
        } else {
            micForCall.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Réponse à la demande du micro pour l'appel : refusé, l'écran d'appel le dit clairement, sans plantage. */
    private val micForCall = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.call.start(micAllowed = granted)
    }

    override fun onStart() {
        super.onStart()
        // Accueil visible : préparation gratuite de l'appel (clé, WebRTC, offre), sans rien facturer.
        container.call.prepare()
        // Relecture d'un appel restée en attente (réseau, app tuée juste après l'appel) : reprise à l'ouverture.
        container.call.resumeReview()
    }

    override fun onStop() {
        // Arrière-plan sans appel : tout ce qui a été préparé est libéré (un appel en cours continue).
        container.call.release()
        super.onStop()
    }

    override fun onDestroy() {
        // Tâche fermée pour de bon : l'appel s'arrête (jamais d'appel sans écran). Une recréation le laisse vivre.
        if (isFinishing) container.call.abort(EndReason.ClosedApp)
        container.micPermission.detach(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        // Le réglage « Supprimer les animations » a pu changer pendant que l'app était en arrière-plan.
        motion = MotionPolicy.fromSystem(this)
        // L'utilisateur a pu changer les notifications dans Android, ou retrouver du réseau : on relit.
        container.homeAlerts.refresh()
        container.keyVerifier.retryIfPending()
    }

    private fun handleOpenRequest(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_BACKUP -> {
                intent.removeExtra(EXTRA_OPEN)
                backupRequests++
            }
            OPEN_CHAT -> {
                intent.removeExtra(EXTRA_OPEN)
                while (reviewNav.current != null) reviewNav.back() // le chat est sous le bilan
                chatRequests++
            }
            OPEN_REVIEW -> {
                intent.removeExtra(EXTRA_OPEN)
                AndroidReviewNotifier.dismissInvitations(this)
                reviewNav.start(ReviewDestination.Conversation)
            }
            OPEN_REPORT -> {
                val id = intent.getLongExtra(EXTRA_REPORT_ID, -1L)
                intent.removeExtra(EXTRA_OPEN)
                intent.removeExtra(EXTRA_REPORT_ID)
                if (id >= 0) reviewNav.start(ReviewDestination.Report(id))
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "app.trackly.OUVRIR"
        const val OPEN_BACKUP = "sauvegarde"

        /** Notification de fin de tour de l'assistant : ouvre le chat. */
        const val OPEN_CHAT = "chat"

        /** Notification d'invitation ou de relance : ouvre la conversation de bilan. */
        const val OPEN_REVIEW = "bilan"

        /** Notification « Ton bilan est prêt » : ouvre le rapport [EXTRA_REPORT_ID]. */
        const val OPEN_REPORT = "bilan-rapport"
        const val EXTRA_REPORT_ID = "app.trackly.RAPPORT"
    }
}
