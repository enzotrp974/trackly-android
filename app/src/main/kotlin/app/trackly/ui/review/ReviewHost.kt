package app.trackly.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trackly.core.review.WeekRange
import app.trackly.photos.PhotoLimits
import app.trackly.photos.PreparedPhoto
import app.trackly.dictation.DictationServices
import app.trackly.review.ReviewServices
import app.trackly.ui.chat.rememberDictation
import app.trackly.review.ReviewViewModel
import app.trackly.review.StoredReport
import app.trackly.ui.photos.PhotoPickerState
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy

/**
 * Fabrique l'état du sélecteur de photos de la conversation de bilan : nombre de photos encore
 * possibles, réception des photos prêtes, message en cas de problème.
 */
typealias ReviewImagePicker = @Composable (remaining: Int, onPhotos: (List<PreparedPhoto>) -> Unit, onProblem: (String) -> Unit) -> PhotoPickerState

/** Où l'on est dans le bilan du lundi. */
sealed interface ReviewDestination {
    /** La conversation de bilan de la période en cours. */
    data object Conversation : ReviewDestination

    /** Liste des bilans passés. */
    data object Reports : ReviewDestination

    /** Un bilan ouvert. */
    data class Report(val id: Long) : ReviewDestination
}

/**
 * Pile des écrans du bilan : ouvrir en ajoute un, « retour » en retire un, vide = bilan fermé.
 * [start] repart de zéro (notification, Réglages).
 */
class ReviewNav {
    private val stack = androidx.compose.runtime.mutableStateListOf<ReviewDestination>()

    val current: ReviewDestination? get() = stack.lastOrNull()

    /** Change à chaque [start] : une conversation déjà à l'écran repart alors sur la période à faire. */
    var starts by androidx.compose.runtime.mutableStateOf(0)
        private set

    fun start(destination: ReviewDestination) {
        starts++
        stack.clear()
        stack.add(destination)
    }

    fun open(destination: ReviewDestination) {
        stack.add(destination)
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

/**
 * Écrans du bilan du lundi, posés **par-dessus** l'application (aucun changement de l'écran du jour ni
 * du chat de saisie). Ouverts depuis une notification, ou depuis Réglages › Bilan du lundi.
 *
 * [imagePicker] branche le sélecteur de photos : dans l'app, `{ remaining, onPhotos, onProblem ->
 * rememberPhotoPicker(remaining, onPhotos, onProblem) }` ; les tests y mettent un faux sélecteur.
 * `null` : pas de bouton de capture.
 */
@Composable
fun ReviewHost(
    nav: ReviewNav,
    services: ReviewServices,
    motion: MotionPolicy,
    onOpenSettings: () -> Unit = {},
    imagePicker: ReviewImagePicker? = null,
    /** Affiche le HTML d'un rapport (WebView sans JavaScript ni réseau ; remplaçable dans les tests d'écran). */
    reportViewer: @Composable (String) -> Unit = { ReportWebView(it) },
    /** Dictée : micro dans la conversation de bilan ; `null` : pas de micro. */
    dictation: DictationServices? = null,
) {
    val destination = nav.current ?: return
    CompositionLocalProvider(LocalMotion provides motion) {
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                ReviewDestination.Conversation -> ConversationRoute(services, nav, onOpenSettings, imagePicker, dictation)
                ReviewDestination.Reports -> ReportsRoute(services, nav)
                is ReviewDestination.Report -> ReportRoute(services, destination.id, nav, reportViewer)
            }
        }
    }
}

@Composable
private fun ConversationRoute(
    services: ReviewServices,
    nav: ReviewNav,
    onOpenSettings: () -> Unit,
    imagePicker: ReviewImagePicker?,
    dictation: DictationServices?,
) {
    // Un fil par période : le modèle de vue est propre à la période du bilan (du lendemain du dernier bilan à aujourd'hui).
    BackHandler { nav.back() }
    val period by produceState<WeekRange?>(null, nav.starts) { value = services.periods.at(services.engine.now()) }
    val current = period ?: return
    val model: ReviewViewModel = viewModel(key = "bilan-${current.start}-${current.end}", factory = ReviewViewModel.factory(services, current))
    // L'utilisateur regarde la conversation : « Ton bilan est prêt » n'est pas notifié.
    DisposableEffect(services) {
        services.presence.conversationShown = true
        onDispose { services.presence.conversationShown = false }
    }
    val dictationBinding = dictation?.let { rememberDictation(it, model, key = "dictee-bilan") }
    val state by model.state.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val keyStatus by model.keyStatus.collectAsStateWithLifecycle()
    ReviewScreen(
        state = state,
        draft = draft,
        keyStatus = keyStatus,
        onBack = { nav.back() },
        onOpenSettings = onOpenSettings,
        actions = ReviewActions(
            onDraftChange = model::updateDraft,
            onSend = { model.send() },
            onProduce = model::produceReport,
            onUndo = model::undo,
            onRetry = model::retry,
            onDismissError = model::dismissError,
            onRemovePhoto = model::removeAttachment,
            onOpenReport = { nav.open(ReviewDestination.Report(it)) },
            onOpenReports = { nav.open(ReviewDestination.Reports) },
            onApplyAdvice = model::applyAdvice,
            onToggleAdvice = model::toggleAdvice,
        ),
        photoPicker = imagePicker?.invoke(PhotoLimits.MAX_PHOTOS - state.photos.size, model::attach, model::photoProblem),
        dictation = dictationBinding,
    )
}

@Composable
private fun ReportsRoute(services: ReviewServices, nav: ReviewNav) {
    val reports by services.reports.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    BackHandler { nav.back() }
    ReportsScreen(
        reports = reports,
        onOpen = { nav.open(ReviewDestination.Report(it)) },
        onStartReview = { nav.open(ReviewDestination.Conversation) },
        onBack = { nav.back() },
    )
}

@Composable
private fun ReportRoute(services: ReviewServices, id: Long, nav: ReviewNav, viewer: @Composable (String) -> Unit) {
    val loaded by produceState<Loaded>(Loaded.Loading, id) { value = Loaded.Done(services.reports.get(id)) }
    // Le chat du bilan est celui de la période en cours : « Poser une question » n'existe que sur le bilan de cette période.
    val current by produceState<WeekRange?>(null, id) { value = services.periods.at(services.engine.now()) }
    BackHandler { nav.back() }
    when (val l = loaded) {
        Loaded.Loading -> Box(Modifier.fillMaxSize())
        is Loaded.Done -> ReportScreen(
            report = l.report,
            onBack = { nav.back() },
            // « Poser une question » ouvre le chat du bilan, par-dessus la page : retour revient au rapport.
            onAsk = if (l.report?.info?.weekStart == current?.start) ({ nav.open(ReviewDestination.Conversation) }) else null,
            viewer = viewer,
            reference = { page -> rememberReferenceUi(services.engine, page.referenceWeight) },
            advice = { page -> rememberAdviceUi(services.engine, page.advice) },
        )
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Done(val report: StoredReport?) : Loaded
}
