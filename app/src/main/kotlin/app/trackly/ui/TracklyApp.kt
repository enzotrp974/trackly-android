package app.trackly.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trackly.core.engine.TracklyEngine
import app.trackly.day.DayView
import app.trackly.day.DayViewModel
import app.trackly.day.RecommendedTimeout
import app.trackly.importing.DocumentReader
import app.trackly.importing.HistoryImportViewModel
import app.trackly.importing.ImportScreenState
import app.trackly.ui.day.DayAlert
import app.trackly.ui.day.DayCalendar
import app.trackly.ui.day.MealActions
import app.trackly.ui.dishes.DishesRoute
import app.trackly.ui.importing.ImportScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.dev.DevPanel
import app.trackly.ui.dev.DevPanelModel
import app.trackly.assistant.AssistantServices
import app.trackly.assistant.chat.ChatPrefill
import app.trackly.assistant.chat.ChatReturn
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.ChatViewModel
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.assistant.key.ApiKeyStore
import app.trackly.dictation.DictationServices
import app.trackly.ui.chat.ChatActions
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.chat.rememberDictation
import app.trackly.ui.chat.ChatShell
import app.trackly.ui.settings.KeyTools
import app.trackly.ui.settings.SettingsGroups
import app.trackly.ui.settings.SettingsPage
import app.trackly.ui.setup.SetupSlot
import kotlinx.coroutines.delay
import app.trackly.ui.settings.SettingsScreen
import app.trackly.ui.day.DayScreen
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import kotlin.math.hypot
import kotlin.math.max

/**
 * Racine branchée sur le moteur : l'écran du jour lit les bilans en continu, et le panneau
 * de développement passe par les mêmes actions que l'assistant.
 */
@Composable
fun TracklyRoot(
    engine: TracklyEngine,
    motion: MotionPolicy,
    devPanelEnabled: Boolean = true,
    /** Assistant ; par défaut, sans réseau ni clé (aperçus et tests de l'écran du jour). */
    assistant: AssistantServices = remember(engine) { AssistantServices.inMemory(engine) },
    /** Reprise de l'historique (null = désactivée, par exemple dans les tests d'écran). */
    importModel: HistoryImportViewModel? = null,
    /** Sections ajoutées aux Réglages (sauvegarde) et demandes d'ouverture venues d'une notification. */
    settings: SettingsSlot? = null,
    /** Montre « Mes plats » ; désactivé par défaut (aperçus). */
    dishesEnabled: Boolean = true,
    /** Dictée ; `null` : pas de bouton micro (aperçus, tests de l'écran du jour). */
    dictation: DictationServices? = null,
    /** Augmente quand une notification de fin de tour demande d'ouvrir le chat. */
    openChatRequest: Int = 0,
    /** Alerte d'accueil sur l'écran du jour ; `null` : rien. */
    dayAlert: DayAlert? = null,
    /** « Bilan » de la barre du bas (conversation de bilan) ; `null` : pas de bouton. */
    onOpenReview: (() -> Unit)? = null,
    /** Le bilan est à faire : pastille sur « Bilan ». */
    reviewDue: Boolean = false,
    /** Bouton d'appel de la barre du bas ; `null` : pas de bouton. */
    onCall: (() -> Unit)? = null,
) {
    // Questions laissées dans le chat après un appel : pastille sur le champ, effacée à l'ouverture du chat.
    val chatQuestions by assistant.waitingQuestions.collectAsStateWithLifecycle(0)
    val context = LocalContext.current
    val model: DayViewModel = viewModel(factory = DayViewModel.factory(engine, RecommendedTimeout.of(context)))
    val state by model.state.collectAsStateWithLifecycle()
    val recent by model.recentActions.collectAsStateWithLifecycle()
    val outcome by model.lastOutcome.collectAsStateWithLifecycle()
    val undoOffer by model.undoOffer.collectAsStateWithLifecycle()
    val chatModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(assistant))
    val chatState by chatModel.state.collectAsStateWithLifecycle()
    val draft by chatModel.draft.collectAsStateWithLifecycle()
    val keyStatus by chatModel.keyStatus.collectAsStateWithLifecycle()
    val effort by chatModel.effort.collectAsStateWithLifecycle()
    val resolver = context.contentResolver
    val dictationBinding = dictation?.let { rememberDictation(it, chatModel) }
    // Sélecteur système (Storage Access Framework) : accès à ce seul fichier, sans permission de stockage.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel?.importFrom { DocumentReader.readText(resolver, uri) }
    }
    val importState by (importModel?.screen ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    TracklyApp(
        motion = motion,
        days = state.days,
        dev = if (devPanelEnabled) DevPanelModel(recent, outcome, model.suggestedMoment(), model::perform) else null,
        chat = ChatBinding(
            state = chatState,
            draft = draft,
            keyStatus = keyStatus,
            effort = effort,
            dictation = dictationBinding,
            actions = ChatActions(
                onDraftChange = chatModel::updateDraft,
                onSend = { chatModel.send() },
                onUndo = chatModel::undo,
                onRestore = chatModel::restore,
                onCorrect = chatModel::startCorrection,
                onNote = chatModel::note,
                onRetry = { chatModel.retry() },
                onDismissError = chatModel::dismissError,
                onCancelCorrection = chatModel::cancelCorrection,
                onEffortChange = chatModel::selectEffort,
                onPhotosAdded = chatModel::addPhotos,
                onPhotoRemoved = chatModel::removePhoto,
                onPhotoProblem = chatModel::photoProblem,
            ),
            prefill = chatModel::prefill,
            correctEntries = chatModel::correctEntries,
            onOpen = assistant.prewarmer::warm,
        ),
        meals = MealActions(
            onDelete = model::deleteMeal,
            // Correction ciblée : le prochain message corrige ces entrées (rappel « Correction : le midi (…) » au-dessus de la saisie).
            onCorrect = { ids, draft -> chatModel.correctEntries(ids, draft.removePrefix("Corrige ").removeSuffix(" : ").trim()) },
            undoLabel = undoOffer?.label,
            onUndo = model::undoDelete,
        ),
        keyStore = assistant.keyStore,
        historyImport = importModel?.let { m ->
            HistoryImportUi(
                state = importState,
                onOpen = m::open,
                onPickFile = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                onClose = m::close,
            )
        },
        settings = settings,
        dishes = if (dishesEnabled) ({ onClose -> DishesRoute(engine, onClose) }) else null,
        openChatRequest = openChatRequest,
        dayAlert = dayAlert,
        onOpenReview = onOpenReview,
        reviewDue = reviewDue,
        onCall = onCall,
        chatQuestions = chatQuestions,
        onChatQuestionsSeen = assistant.unseenQuestions::seen,
    )
}

/** État et actions du chat, tels que l'écran les affiche. */
class ChatBinding(
    val state: ChatUiState = ChatUiState(),
    val draft: String = "",
    val keyStatus: ApiKeyStatus = ApiKeyStatus.Unknown,
    val effort: ReasoningEffort = ReasoningEffort.Default,
    val actions: ChatActions = ChatActions(),
    val dictation: DictationBinding? = null,
    /** Début de message proposé par un « + » (« Ce matin : », « Pour dimanche 1er novembre : »), ou `null`. */
    val prefill: (String?) -> Unit = {},
    /** « Corriger » depuis l'écran du jour : le prochain message corrige ces consommations ([ChatViewModel.correctEntries]). */
    val correctEntries: (entryIds: List<String>, label: String) -> Unit = { _, _ -> },
    /** Le chat s'ouvre : préchauffage de la connexion ; rien sans clé. */
    val onOpen: () -> Unit = {},
)

/** Ce dont l'écran a besoin pour la reprise de l'historique. */
class HistoryImportUi(
    val state: ImportScreenState?,
    val onOpen: () -> Unit,
    val onPickFile: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * Sections ajoutées à l'écran Réglages, ouvert depuis le chat ou en touchant « Trackly ».
 * [openRequest] augmente quand une notification demande de l'ouvrir (Réglages › Sauvegarde).
 */
class SettingsSlot(
    val openRequest: Int = 0,
    /** Scan de la clé par QR code et presse-papiers à vider (Réglages › Clé OpenAI). */
    val keyTools: KeyTools = KeyTools(),
    /** Mise en route (liste à cocher après la reprise) ; `null` : pas de liste. */
    val setup: SetupSlot? = null,
    val groups: SettingsGroups = SettingsGroups(),
)

/**
 * Écran du jour, et chat qui s'ouvre en cercle depuis le bouton touché.
 * Sans jour à afficher (base en cours d'ouverture), seul le fond porcelaine est posé.
 */
@Composable
fun TracklyApp(
    motion: MotionPolicy,
    days: List<DayView>,
    dev: DevPanelModel? = null,
    chat: ChatBinding = ChatBinding(),
    /** Feuille d'un repas (supprimer, corriger, annuler) ; `null` : les lignes de repas ne s'ouvrent pas. */
    meals: MealActions? = null,
    keyStore: ApiKeyStore? = null,
    historyImport: HistoryImportUi? = null,
    settings: SettingsSlot? = null,
    /** Écran « Mes plats » (plein écran, ouvert d'un geste depuis l'écran du jour) ; `null` : pas de bouton. */
    dishes: (@Composable (onClose: () -> Unit) -> Unit)? = null,
    /** Augmente quand une notification de fin de tour demande d'ouvrir le chat. */
    openChatRequest: Int = 0,
    /** Alerte d'accueil sur l'écran du jour ; `null` : rien. */
    dayAlert: DayAlert? = null,
    /** « Bilan » de la barre du bas ; `null` : pas de bouton. */
    onOpenReview: (() -> Unit)? = null,
    /** Le bilan est à faire : pastille sur « Bilan ». */
    reviewDue: Boolean = false,
    /** Bouton d'appel de la barre du bas ; `null` : pas de bouton. */
    onCall: (() -> Unit)? = null,
    /** Questions laissées dans le chat après un appel et pas encore vues : pastille sur « Écrire à Trackly… ». */
    chatQuestions: Int = 0,
    /** Le chat est ouvert : les questions sont vues, la pastille disparaît. */
    onChatQuestionsSeen: () -> Unit = {},
) {
    CompositionLocalProvider(LocalMotion provides motion) {
        if (days.isEmpty()) {
            PorcelainBackdrop()
            historyImport?.state?.let { ImportScreen(it, historyImport.onPickFile, historyImport.onClose) }
            return@CompositionLocalProvider
        }
        // Sans jour marqué « aujourd'hui » (données pas encore rafraîchies), le plus récent en tient lieu.
        val today = days.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: days.lastIndex
        // Jour choisi par sa date (la liste s'allonge quand un jour plus ancien reçoit des données) ;
        // `null` = suivre le jour en cours.
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        val dayIndex = selected?.let { iso -> days.indexOfFirst { it.date.toString() == iso }.takeIf { it >= 0 } } ?: today
        val selectIndex: (Int) -> Unit = { i -> selected = if (i == today) null else days[i].date.toString() }
        var chatOpen by rememberSaveable { mutableStateOf(false) }
        // Premier message de cette ouverture du chat : au retour, l'écran montre le jour de ce qui y a été enregistré.
        var chatSince by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
        var devOpen by rememberSaveable { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var calendarOpen by rememberSaveable { mutableStateOf(false) }
        var dishesOpen by rememberSaveable { mutableStateOf(false) }
        // « Saisir ma clé » (chat, ligne de la mise en route) : les Réglages défilent jusqu'à la section Clé.
        var keyScrollPending by rememberSaveable { mutableStateOf(false) }
        // Clé demandée par la mise en route : la liste revient une fois les Réglages refermés.
        var keyFromSetup by rememberSaveable { mutableStateOf(false) }
        // Notification de sauvegarde : les Réglages s'ouvrent directement sur « Connexion Drive ».
        var settingsStart by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
        val setup = settings?.setup
        // Une clé vient d'être reconnue par OpenAI : le message refusé pour cause de clé peut être renvoyé. Retombe à faux à chaque nouvelle erreur.
        val keyVerification = settings?.keyTools?.verification
        var keyJustVerified by remember { mutableStateOf(false) }
        LaunchedEffect(keyVerification) { keyVerification?.verified?.collect { keyJustVerified = true } }
        LaunchedEffect(chat.state.error) { keyJustVerified = false }
        val importState = historyImport?.state
        val backupRequest = settings?.openRequest ?: 0
        var handledRequest by rememberSaveable { mutableStateOf(0) }
        LaunchedEffect(backupRequest) {
            if (backupRequest > handledRequest) {
                settingsStart = SettingsPage.Drive
                setup?.nav?.hide()
                keyFromSetup = false
                settingsOpen = true
                handledRequest = backupRequest
            }
        }
        // Mise en route : s'ouvre d'elle-même une fois, dès que la reprise est refermée (ou, sans reprise, peu après le lancement).
        var wasImporting by remember { mutableStateOf(importState != null) }
        LaunchedEffect(importState == null) {
            if (setup != null && wasImporting && importState == null) setup.nav.openIfDue()
            wasImporting = importState != null
        }
        val importing by rememberUpdatedState(importState != null)
        LaunchedEffect(setup) {
            if (setup != null) {
                delay(1_200)
                if (!importing) setup.nav.openIfDue()
            }
        }
        val keyRequest = setup?.nav?.keyRequest ?: 0
        var handledKeyRequest by remember { mutableStateOf(keyRequest) }
        LaunchedEffect(keyRequest) {
            if (keyRequest > handledKeyRequest) {
                handledKeyRequest = keyRequest
                keyScrollPending = true
                keyFromSetup = true
                settingsOpen = true
            }
        }
        var handledChatRequest by remember { mutableStateOf(0) } // remis à zéro avec l'activité, comme son compteur
        LaunchedEffect(openChatRequest) {
            if (openChatRequest > handledChatRequest) {
                // Notification touchée : le chat s'ouvre par-dessus tout le reste.
                settingsOpen = false
                devOpen = false
                calendarOpen = false
                dishesOpen = false
                chatOpen = true
                handledChatRequest = openChatRequest
            }
        }
        var origin by remember { mutableStateOf(Offset.Unspecified) }
        // Saisie prête dès l'ouverture : augmente à chaque ouverture qui doit placer le curseur.
        var chatFocus by remember { mutableIntStateOf(0) }
        val openChat: (at: Offset, prefill: String?, focus: Boolean) -> Unit = { at, prefill, focus ->
            origin = at
            chat.prefill(prefill)
            // Sans clavier (micro) : 0, sinon le chat recomposé reprendrait la dernière demande et ouvrirait le clavier.
            if (focus) chatFocus++ else chatFocus = 0
            chatOpen = true
        }
        // Fixé une seule fois par ouverture, et seulement quand l'historique est chargé (sinon, liste vide : tout compterait).
        LaunchedEffect(chatOpen, chat.state.historyLoaded) {
            if (chatOpen && chatSince == Long.MAX_VALUE) {
                ChatReturn.firstNewId(chat.state.historyLoaded, chat.state.messages)?.let { chatSince = it }
            }
        }
        val closeChat: () -> Unit = {
            chatOpen = false
            ChatReturn.dayAfter(chat.state.messages, chatSince, chat.state.undone)?.let { date ->
                val i = days.indexOfFirst { it.date == date }
                if (i >= 0) selectIndex(i)
            }
            chatSince = Long.MAX_VALUE
        }

        // Chat ouvert, ou questions arrivées pendant qu'il l'est : elles sont sous ses yeux.
        LaunchedEffect(chatOpen, chatQuestions) {
            if (chatOpen && chatQuestions > 0) onChatQuestionsSeen()
        }
        val reveal = remember { Animatable(if (chatOpen) 1f else 0f) }
        LaunchedEffect(chatOpen) {
            if (chatOpen) chat.onOpen()
            reveal.animateTo(
                if (chatOpen) 1f else 0f,
                motion.tween(if (chatOpen) TracklyDurations.CHAT_OPEN else TracklyDurations.CHAT_CLOSE, easing = TracklyEasing.Reveal),
            )
        }
        val chatVisible by remember { derivedStateOf { chatOpen || reveal.value > 0f } }
        BackHandler(enabled = chatOpen) { closeChat() }
        // Changement d'écran (Réglages, Mes plats, calendrier…) ou chat refermé : le clavier se ferme.
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val screen = when {
            setup?.nav?.open == true -> "mise-en-route"
            settingsOpen -> "reglages"
            dishesOpen -> "plats"
            devOpen -> "saisie-manuelle"
            calendarOpen -> "calendrier"
            chatOpen -> "chat"
            else -> "jour"
        }
        var shownScreen by remember { mutableStateOf(screen) }
        LaunchedEffect(screen) {
            if (screen != shownScreen && screen != "chat") {
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            }
            shownScreen = screen
        }
        BackHandler(enabled = devOpen) { devOpen = false }
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        BackHandler(enabled = dishesOpen) { dishesOpen = false }
        BackHandler(enabled = setup?.nav?.open == true) { setup?.nav?.leave() }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            DayScreen(
                days = days,
                index = dayIndex,
                todayIndex = today,
                onIndexChange = selectIndex,
                onOpenChat = { openChat(it, null, true) },
                // Champ de la barre du bas ou « + » d'un moment, sur le jour affiché : prérempli, clavier prêt.
                onAdd = { at, moment -> openChat(at, ChatPrefill.of(days[dayIndex].date, days[today].date, moment), true) },
                // Gros micro : même ouverture, sans clavier, et la dictée démarre (le texte dicté suit le début prérempli).
                onDictate = chat.dictation?.let { d ->
                    { at ->
                        openChat(at, ChatPrefill.of(days[dayIndex].date, days[today].date, null), false)
                        d.actions.onMic()
                    }
                },
                onOpenReview = onOpenReview,
                reviewDue = reviewDue,
                onCall = onCall,
                chatQuestions = chatQuestions,
                onOpenDevPanel = if (dev != null) ({ devOpen = true }) else null,
                onOpenCalendar = { calendarOpen = true },
                onOpenSettings = if (keyStore != null) ({ settingsOpen = true }) else null,
                onOpenDishes = if (dishes != null) ({ dishesOpen = true }) else null,
                meals = meals,
                alert = dayAlert?.let { a ->
                    if (a.opensSettings) {
                        // Sans réglages disponibles (aperçus), pas de geste.
                        DayAlert(
                            a.text,
                            a.actionLabel,
                            if (keyStore != null) {
                                {
                                    settingsStart = SettingsPage.Drive
                                    settingsOpen = true
                                }
                            } else {
                                null
                            },
                        )
                    } else {
                        a
                    }
                },
                modifier = Modifier
                    // Chat, panneau, calendrier, réglages ou reprise ouverts : le jour, caché, sort aussi de l'arbre d'accessibilité (TalkBack).
                    .then(if (chatOpen || devOpen || calendarOpen || settingsOpen || dishesOpen || setup?.nav?.open == true || importState != null) Modifier.clearAndSetSemantics {} else Modifier)
                    .graphicsLayer {
                    // Le jour recule, s'efface et se floute pendant que le chat s'ouvre.
                    val p = reveal.value.coerceIn(0f, 1f)
                    val s = 1f - 0.045f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin.Center
                    alpha = 1f - p
                    val blur = 8.dp.toPx() * p
                    renderEffect = if (blur >= 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(blur, blur) else null
                },
            )
            if (chatVisible) {
                // Après recréation de l'activité, l'origine est perdue : on repart de l'emplacement du micro de la barre du bas.
                val navBottom = WindowInsets.navigationBars.getBottom(density)
                val center = if (origin.isSpecified) origin else with(density) { Offset(w / 2f, h - navBottom - 51.dp.toPx()) }
                val maxRadius = max(
                    max(hypot(center.x, center.y), hypot(w - center.x, center.y)),
                    max(hypot(center.x, h - center.y), hypot(w - center.x, h - center.y)),
                )
                ChatShell(
                    dateLabel = days[today].label,
                    state = chat.state,
                    draft = chat.draft,
                    keyStatus = chat.keyStatus,
                    effort = chat.effort,
                    actions = chat.actions,
                    dictation = chat.dictation,
                    keyJustVerified = keyJustVerified,
                    focusRequest = chatFocus,
                    onBack = closeChat,
                    // Faute de réseau seulement : la saisie manuelle s'ouvre par-dessus le chat, qui revient à sa fermeture.
                    onManualEntry = if (dev != null) ({ devOpen = true }) else null,
                    onOpenSettings = {
                        // Clé absente ou refusée : c'est elle qu'on vient régler.
                        if (chat.keyStatus !is ApiKeyStatus.Present || chat.state.error?.notice?.opensKeySettings == true) keyScrollPending = true
                        settingsOpen = true
                    },
                    modifier = Modifier
                        .then(if (settingsOpen || devOpen) Modifier.clearAndSetSemantics {} else Modifier)
                        .graphicsLayer {
                            clip = true
                            val r = maxRadius * reveal.value.coerceAtLeast(0f)
                            shape = GenericShape { _, _ -> addOval(Rect(center, r)) }
                        }
                        // Le chat ouvert capte les touchers : rien ne traverse vers le jour.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                )
            }
            if (dev != null && devOpen) {
                // Inscrit après ceux du chat et de la dictée : le retour ferme le panneau, sans jeter une dictée en échec.
                BackHandler { devOpen = false }
                DevPanel(day = days[dayIndex], model = dev, onClose = { devOpen = false })
            }
            if (calendarOpen) {
                DayCalendar(
                    days = days,
                    selected = days[dayIndex].date,
                    today = days[today].date,
                    onPick = { date: LocalDate ->
                        val i = days.indexOfFirst { it.date == date }
                        if (i >= 0) selectIndex(i)
                        calendarOpen = false
                    },
                    onClose = { calendarOpen = false },
                    onImportHistory = historyImport?.let { h ->
                        {
                            calendarOpen = false
                            h.onOpen()
                        }
                    },
                )
            }
            if (setup != null && setup.nav.open) {
                setup.screen(setup.nav, historyImport?.onPickFile)
            }
            if (historyImport != null && importState != null) {
                ImportScreen(importState, historyImport.onPickFile, historyImport.onClose)
            }
            if (dishes != null && dishesOpen) {
                dishes { dishesOpen = false }
            }
            // La mise en route ouverte depuis les Réglages les remplace : ils reviennent quand elle se referme.
            if (keyStore != null && settingsOpen && setup?.nav?.open != true) {
                SettingsScreen(
                    keyStore = keyStore,
                    onClose = {
                        settingsOpen = false
                        keyScrollPending = false
                        settingsStart = null
                        if (keyFromSetup) {
                            keyFromSetup = false
                            setup?.nav?.show()
                        }
                    },
                    keyTools = settings?.keyTools ?: KeyTools(),
                    scrollToKey = keyScrollPending,
                    onScrolledToKey = { keyScrollPending = false },
                    groups = settings?.groups ?: SettingsGroups(),
                    startPage = settingsStart,
                )
            }
        }
    }
}
