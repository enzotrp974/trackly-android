package app.trackly.ui.chat

import app.trackly.assistant.markdown.Markdown
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.chat.PhotoMarker
import app.trackly.photos.PhotoLimits
import app.trackly.photos.PreparedPhoto
import app.trackly.ui.photos.PhotoAttachButton
import app.trackly.ui.photos.PhotoOptions
import app.trackly.ui.photos.PhotoStrip
import app.trackly.ui.photos.rememberPhotoPicker
import app.trackly.assistant.chat.ChatError
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.chat.ChatUiState
import app.trackly.assistant.chat.LiveReply
import app.trackly.assistant.turn.StreamingText
import app.trackly.assistant.turn.TurnStatus
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.assistant.openai.ReasoningEffort
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test (coutures Compose). */
object ChatTags {
    const val HEADER = "chat-entete"
    const val THREAD = "chat-fil"
    const val BUBBLE = "chat-bulle"
    const val REPLY = "chat-reponse"
    const val INPUT = "chat-saisie"
    const val SEND = "chat-envoyer"
    const val THINKING = "chat-reflexion"

    /** Texte de la réponse pendant qu'il arrive (streaming). */
    const val LIVE_REPLY = "chat-reponse-en-cours"

    /** Réponse mise en page : titre, liste, tableau (qui défile dans son bloc). */
    const val REPLY_HEADING = "chat-reponse-titre"
    const val REPLY_LIST = "chat-reponse-liste"
    const val REPLY_TABLE = "chat-reponse-tableau"
    const val REPLY_TABLE_TRACK = "chat-reponse-tableau-rail"
    const val ERROR = "chat-erreur"
    const val CORRECTING = "chat-correction"

    /** Toute la zone de saisie (barre, pastille, rappel, bannières), du haut de son fondu jusqu'en bas. */
    const val COMPOSER = "chat-zone-saisie"
    const val CORRECTING_CANCEL = "chat-correction-retirer"
    const val PHOTO_MARK = "chat-photo-envoyee"

    /** Vignettes des photos d'un message envoyé (en mémoire seulement). */
    const val PHOTO_THUMBS = "chat-photo-vignettes"

    /** Bannière « Hors ligne » au-dessus de la saisie. */
    const val OFFLINE = "chat-hors-ligne"

    /** « Saisir à la main » : seulement faute de réseau (bannière Hors ligne, message parti sans connexion). */
    const val MANUAL = "chat-saisie-manuelle"

    /** Réponse de l'assistant barrée : tout ce qu'elle annonçait a été annulé. */
    const val REPLY_CANCELLED = "chat-reponse-annulee"
}

/** Ce que le chat peut demander (branché sur `ChatViewModel`). */
class ChatActions(
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onUndo: (ChatCard) -> Unit = {},
    /** « Rétablir » une carte annulée, sans appel à l'assistant. */
    val onRestore: (ChatCard) -> Unit = {},
    val onCorrect: (ChatCard) -> Unit = {},
    /** « Noter » une carte de proposition : déclaration annulable, comme un repas dicté. */
    val onNote: (ChatCard) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onCancelCorrection: () -> Unit = {},
    /** Niveau de raisonnement du prochain message. */
    val onEffortChange: (ReasoningEffort) -> Unit = {},
    /** Photos jointes : prêtes, retirées, ou impossibles à lire (message à afficher). */
    val onPhotosAdded: (List<PreparedPhoto>) -> Unit = {},
    val onPhotoRemoved: (PreparedPhoto) -> Unit = {},
    val onPhotoProblem: (String) -> Unit = {},
)

/**
 * Chat de l'assistant, ouvert en cercle depuis le « + » : bulles, réponses, cartes
 * d'enregistrement, réflexion en cours, erreurs, et la zone de saisie.
 *
 * L'en-tête et le fil sont empilés (le fil commence sous l'en-tête et y est coupé), un filet
 * apparaît sous l'en-tête dès que le fil défile, et le haut du fil s'estompe.
 */
@Composable
fun ChatShell(
    dateLabel: String,
    state: ChatUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    draft: String = "",
    keyStatus: ApiKeyStatus = ApiKeyStatus.Present(""),
    effort: ReasoningEffort = ReasoningEffort.Default,
    actions: ChatActions = ChatActions(),
    onOpenSettings: () -> Unit = {},
    /** Une nouvelle clé vient d'être reconnue valide par OpenAI : un message refusé pour cause de clé peut être renvoyé. */
    keyJustVerified: Boolean = false,
    /** Dictée ; `null` : pas de bouton micro (aperçus, tests de l'écran du jour). */
    dictation: DictationBinding? = null,
    /**
     * Augmente quand le chat doit placer le curseur dans la saisie et ouvrir le clavier (« + », « Demander à
     * Trackly… », « Corriger » depuis l'écran du jour). 0 : rien.
     */
    focusRequest: Int = 0,
    /**
     * Plan B sans assistant : ouvre la saisie manuelle par-dessus le chat, qui revient à sa fermeture.
     * Proposé seulement faute de réseau ; `null` : jamais.
     */
    onManualEntry: (() -> Unit)? = null,
) {
    // Chat refermé pendant une dictée : elle se termine, le texte n'est pas perdu (il va dans la zone de saisie).
    val leave by rememberUpdatedState(dictation?.actions?.onLeave)
    DisposableEffect(Unit) { onDispose { leave?.invoke() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // « Corriger » sur une carte : le curseur va aussi dans la saisie.
    var correctFocus by remember { mutableIntStateOf(0) }
    val shownActions = remember(actions) {
        ChatActions(
            onDraftChange = actions.onDraftChange,
            onSend = {
                // « J'envoie et je pars » : le clavier se ferme, le fil montre la réponse en entier.
                keyboard?.hide()
                focusManager.clearFocus()
                actions.onSend()
            },
            onUndo = actions.onUndo,
            onRestore = actions.onRestore,
            onCorrect = {
                actions.onCorrect(it)
                correctFocus++
            },
            onNote = actions.onNote,
            onRetry = actions.onRetry,
            onDismissError = actions.onDismissError,
            onCancelCorrection = actions.onCancelCorrection,
            onEffortChange = actions.onEffortChange,
            onPhotosAdded = actions.onPhotosAdded,
            onPhotoRemoved = actions.onPhotoRemoved,
            onPhotoProblem = actions.onPhotoProblem,
        )
    }
    val openSettings = {
        // Le clavier du chat ne reste pas ouvert par-dessus les Réglages.
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        onOpenSettings()
    }
    val openManual: (() -> Unit)? = onManualEntry?.let { open ->
        {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            open()
        }
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.canScrollBackward } }
    val motion = LocalMotion.current
    val edge by animateFloatAsState(if (scrolled) 1f else 0f, motion.tween(250), label = "filet")
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Seules les cartes arrivées pendant que le chat est ouvert se déroulent.
    val firstNewId = remember { (state.messages.maxOfOrNull { it.id } ?: 0L) + 1 }
    val showHint = state.messages.isEmpty() && !state.thinking
    // Réponse en cours : rattachée au message de l'assistant déjà écrit (ses cartes), sinon à part, en bas du fil.
    val live = state.live ?: LiveReply().takeIf { state.thinking }
    val liveInMessage = live?.messageId?.takeIf { id -> state.messages.any { it.id == id } }
    val liveApart = live != null && liveInMessage == null
    val itemCount = (if (showHint) 1 else 0) + state.messages.size + listOf(liveApart, state.error != null, state.notice != null).count { it }
    // Chaque nouvel élément (message, carte, réflexion, erreur) est amené en vue jusqu'à sa fin ; le texte
    // qui s'allonge, la carte qui arrive dans un message déjà là, et la saisie qui grandit (clavier) aussi.
    val liveGrowth = (live?.text?.length ?: 0) / LIVE_SCROLL_STEP
    val lastCards = state.messages.lastOrNull()?.cards?.size ?: 0
    // Le fil suit sa fin tant que l'utilisateur ne l'a pas quittée en défilant vers le haut pour lire.
    val follow = remember { FollowEnd() }
    LaunchedEffect(itemCount, lastCards) {
        if (itemCount > 0) {
            follow.following = true
            list.scrollToEnd(itemCount, animated = !motion.reduced)
        }
    }
    LaunchedEffect(liveGrowth) {
        if (liveGrowth > 0 && itemCount > 0) list.scrollToEnd(itemCount, animated = false)
    }
    // Hauteur de la saisie (barre, pastille, photos, rappel, clavier compris) : le bas du fil n'est jamais caché dessous.
    val density = LocalDensity.current
    var composerHeight by remember { mutableIntStateOf(0) }
    val bottomPadding = if (composerHeight > 0) with(density) { composerHeight.toDp() } + 12.dp else 148.dp + navBottom
    // Quand le doigt s'arrête, on sait si l'utilisateur a laissé le fil à sa fin (il le suit) ou plus haut (il lit : on ne bouge plus).
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && follow.userScrolled) {
                follow.userScrolled = false
                follow.following = list.tailOverflow()?.let { it <= 0 } ?: false
            }
        }
    }
    // Tout ce qui cache la fin du fil, d'où que cela vienne (saisie qui grandit ou rétrécit, rappel de correction, clavier qui
    // monte image par image, carte qui s'allonge après « Rétablir ») la ramène en vue, sans dépendre de l'ordre des événements.
    LaunchedEffect(list) {
        // Un défilement en cours n'est pas gêné : on reprend dès qu'il s'arrête (l'arrêt est lui-même une valeur du flux).
        snapshotFlow { list.tailOverflow() to list.isScrollInProgress }.collect { (overflow, scrolling) ->
            if (overflow != null && overflow > 0 && !scrolling && follow.following) list.scrollBy(overflow.toFloat())
        }
    }
    val followConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput || source == NestedScrollSource.SideEffect) {
                    follow.userScrolled = true
                    // Le doigt descend : le fil remonte vers le début, l'utilisateur lit plus haut.
                    if (consumed.y > 0f) follow.following = false
                }
                return Offset.Zero
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.24f, dotsAlpha = 0.8f)
        Column(Modifier.fillMaxSize()) {
            ChatHeader(dateLabel, onBack, onOpenSettings, edge)
            LazyColumn(
                state = list,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(ChatTags.THREAD)
                    .nestedScroll(followConnection)
                    .fadeTopEdge(edge),
                contentPadding = PaddingValues(
                    start = TracklySpacing.ChatGutter,
                    end = TracklySpacing.ChatGutter,
                    top = 14.dp,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (showHint) {
                    item(key = "hint") { Hint(keyStatus, openSettings) }
                }
                for (message in state.messages) {
                    item(key = "m${message.id}") {
                        MessageView(
                            message, state, animate = message.id >= firstNewId, shownActions,
                            live = live.takeIf { message.id == liveInMessage },
                        )
                    }
                }
                if (liveApart && live != null) {
                    item(key = "live") {
                        LiveReplyView(live, withMark = true)
                    }
                }
                state.error?.let { error ->
                    // Bannière Hors ligne déjà là : elle porte seule la proposition.
                    item(key = "error") { ErrorView(error, shownActions, openSettings, keyJustVerified, onManualEntry = openManual.takeIf { !state.offline }) }
                }
                state.notice?.let { text -> item(key = "notice") { NoticeLine(text) } }
            }
        }
        Composer(
            draft, enabled = !state.thinking, effort, correcting = state.correcting?.label, photos = state.photos, shownActions, dictation,
            openSettings, offline = state.offline, onManualEntry = openManual,
            // Sans clé, le clavier cacherait « Ajouter ma clé OpenAI » : le curseur attend la clé.
            focusRequest = if (keyStatus is ApiKeyStatus.Missing || keyStatus is ApiKeyStatus.Unreadable) 0 else focusRequest + correctFocus,
            modifier = Modifier.onSizeChanged { composerHeight = it.height },
        )
        if (dictation != null) DictationLivePanel(dictation.state, dictation.actions, openSettings, openManual)
    }
}

/**
 * Amène le dernier élément du fil en vue **jusqu'à sa fin** : une carte plus haute que l'écran montre ses
 * boutons et la phrase qui suit, pas seulement son haut.
 */
private suspend fun LazyListState.scrollToEnd(count: Int, animated: Boolean) {
    // Le nombre d'éléments vient de l'appelant : avant la première mise en page, la liste n'en connaît aucun.
    val last = count - 1
    if (last < 0) return
    if (animated) animateScrollToItem(last) else scrollToItem(last)
    // La mise en page suit au prochain passage : on la lit après.
    withFrameNanos { }
    val overflow = tailOverflow() ?: return
    if (overflow > 0) {
        if (animated) animateScrollBy(overflow.toFloat()) else scrollBy(overflow.toFloat())
    }
}

/** Où en est le fil : suit-il sa fin ? `userScrolled` : un geste vient d'avoir lieu, à interpréter quand il s'arrête. */
private class FollowEnd {
    var following = true
    var userScrolled = false
}

/** Ce qui dépasse sous la zone visible (marge du bas déduite) à la fin du dernier élément ; `null` s'il n'est pas à l'écran. */
private fun LazyListState.tailOverflow(): Int? {
    val info = layoutInfo
    val last = info.totalItemsCount - 1
    val item = info.visibleItemsInfo.lastOrNull { it.index == last } ?: return null
    return item.offset + item.size - (info.viewportEndOffset - info.afterContentPadding)
}

/** Au-delà de ce nombre de caractères reçus, le fil suit le texte qui s'allonge. */
private const val LIVE_SCROLL_STEP = 80

/**
 * Réponse en cours : l'étape (« Je cherche sur Internet… », « J’enregistre… ») tant qu'aucun texte n'est arrivé,
 * puis le texte au fil de l'eau. Une fois le tour terminé, le texte final, le temps que l'historique le relise.
 */
@Composable
internal fun LiveReplyView(live: LiveReply, withMark: Boolean = false) {
    val shown = remember(live.text, live.done) { if (live.done) live.text else StreamingText.visible(live.text) }
    // Une réponse qui commence par une marque (« ### », « ** ») n'a encore rien de lisible : on garde l'attente.
    val readable = remember(shown, live.done) { Markdown.parse(shown, streaming = !live.done).isNotEmpty() }
    when {
        readable -> Column(Modifier.fillMaxWidth()) {
            if (withMark) Box(Modifier.padding(top = 9.dp)) { AssistantMark() }
            AssistantReply(shown, tag = if (live.done) ChatTags.REPLY else ChatTags.LIVE_REPLY, streaming = !live.done)
        }
        !live.done -> Thinking(live.status.label, withMark = withMark)
    }
}

@Composable
private fun MessageView(
    message: ChatMessage,
    state: ChatUiState,
    animate: Boolean,
    actions: ChatActions,
    live: LiveReply? = null,
) {
    val undone = state.undone
    Column(Modifier.fillMaxWidth()) {
        when (message.role) {
            ChatRole.User -> UserBubble(message.shownText, message.photoCount, state.thumbnails[message.id].orEmpty())
            ChatRole.Assistant -> {
                // La carte d'abord (ce qui a été enregistré), puis la réponse de l'assistant, puis ses sources.
                Box(Modifier.padding(top = 9.dp)) { AssistantMark() }
                val (sources, others) = message.cards.partition { it is SourcesCard }
                for (card in others) {
                    // Clé : l'état d'une carte (hypothèses dépliées) la suit, pas sa position dans le message.
                    // Une proposition n'a pas d'action : son identifiant la distingue des autres.
                    val proposal = card as? ProposalCard
                    key(card.actionId, proposal?.id) {
                        CardView(
                            card, undone = proposal == null && card.actionId in undone, animate = animate, onUndo = actions.onUndo, onCorrect = actions.onCorrect,
                            questionPending = card.actionId in state.pendingQuestions,
                            superseded = state.superseded[card.actionId], notice = state.cardNotices[card.actionId], onRestore = actions.onRestore,
                            noted = proposal != null && proposal.id in state.notedProposals, onNote = actions.onNote,
                        )
                    }
                }
                // Tout ce que la réponse annonçait a été annulé : elle est barrée, pour ne pas contredire la carte.
                val acting = others.filter { it.actionId > 0 }
                val cancelled = acting.isNotEmpty() && acting.all { it.actionId in undone }
                if (message.text.isNotBlank()) AssistantReply(message.text, cancelled = cancelled) else if (live != null) LiveReplyView(live)
                for (card in sources) CardView(card, undone = false, animate = animate, onUndo = actions.onUndo, onCorrect = actions.onCorrect)
            }
        }
    }
}

@Composable
private fun ChatHeader(dateLabel: String, onBack: () -> Unit, onOpenSettings: () -> Unit, edge: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ChatTags.HEADER)
            .drawWithContent {
                drawContent()
                drawRect(
                    TracklyColors.Line,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                    alpha = edge,
                )
            }
            .statusBarsPadding()
            .padding(top = 8.dp, start = 14.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(TracklySpacing.BackSize)
                .pressable(onBack, pressedScale = 0.86f)
                .semantics { contentDescription = "Retour au journal" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText("Trackly", style = TracklyType.ChatTitle.copy(color = TracklyColors.Ink), modifier = Modifier.semantics { heading() })
            BasicText(dateLabel, style = TracklyType.ChatDate.copy(color = TracklyColors.Ink2))
        }
        Box(
            Modifier
                .size(TracklySpacing.BackSize)
                .pressable(onOpenSettings, pressedScale = 0.86f)
                .semantics { contentDescription = "Réglages" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Settings, TracklyColors.Ink, Modifier.size(19.dp))
        }
    }
}

/** Estompe le haut du fil quand il a défilé : aucune bulle n'est tranchée net contre l'en-tête. */
private fun Modifier.fadeTopEdge(strength: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (strength > 0f) {
            val fade = 18.dp.toPx()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 1f - strength),
                    1f to Color.Black,
                    startY = 0f,
                    endY = fade,
                ),
                size = androidx.compose.ui.geometry.Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun Hint(keyStatus: ApiKeyStatus, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(top = 56.dp, start = 8.dp, end = 8.dp)) {
        BasicText("Dis-moi ce que tu as mangé.", style = TracklyType.Hint.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(36.dp, 1.dp).background(TracklyColors.Cobalt))
        Spacer(Modifier.height(14.dp))
        BasicText(
            "J’enregistre tout de suite. Tu peux annuler ou corriger d’un geste.",
            style = TracklyType.HintBody.copy(color = TracklyColors.Ink2),
            modifier = Modifier.widthIn(max = 250.dp),
        )
        if (keyStatus is ApiKeyStatus.Missing || keyStatus is ApiKeyStatus.Unreadable) {
            Spacer(Modifier.height(18.dp))
            BasicText(
                if (keyStatus is ApiKeyStatus.Unreadable) {
                    "Ta clé OpenAI n’a pas pu être relue sur ce téléphone. Saisis-la à nouveau."
                } else {
                    "Pour commencer, ajoute ta clé OpenAI."
                },
                style = TracklyType.HintBody.copy(color = TracklyColors.Ink),
                modifier = Modifier.widthIn(max = 260.dp),
            )
            Spacer(Modifier.height(10.dp))
            PillButton("Ajouter ma clé OpenAI", primary = true, onClick = onOpenSettings)
        }
    }
}

private val BubbleShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 3.dp, bottomStart = 14.dp)
private val ReplyShape = RoundedCornerShape(topStart = 3.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)

@Composable
internal fun UserBubble(text: String, photoCount: Int = 0, thumbnails: List<Bitmap> = emptyList()) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .testTag(ChatTags.BUBBLE)
                .widthIn(max = 268.dp)
                .background(TracklyColors.Ink, BubbleShape)
                .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 13.dp),
        ) {
            // Vignettes des photos envoyées, tant que l'app reste ouverte : en mémoire seulement.
            if (thumbnails.isNotEmpty()) {
                SentThumbnails(thumbnails)
                if (text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (text.isNotBlank()) BasicText(text, style = TracklyType.Bubble.copy(color = TracklyColors.PaperHi))
            // Les photos ne sont pas conservées : seul ce repère reste dans l'historique.
            if (photoCount > 0) {
                BasicText(
                    PhotoMarker.label(photoCount),
                    style = TracklyType.CardItemDetail.copy(color = TracklyColors.PaperHi.copy(alpha = 0.78f)),
                    modifier = Modifier.testTag(ChatTags.PHOTO_MARK).padding(top = if (text.isNotBlank()) 5.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * Bulle de réponse de l'assistant, au fil du jour comme au fil du bilan. Le Markdown du modèle est mis en page
 * par l'app : une phrase garde la bulle étroite, une réponse structurée (titres, listes, tableaux) prend
 * toute la largeur, comme la carte d'enregistrement. Seul l'affichage change : l'historique renvoyé au modèle
 * garde le texte original. [streaming] : réponse en cours, dont la fin peut être inachevée.
 */
@Composable
internal fun AssistantReply(text: String, tag: String = ChatTags.REPLY, cancelled: Boolean = false, streaming: Boolean = false) {
    val blocks = remember(text, streaming) { Markdown.parse(text, streaming) }
    if (blocks.isEmpty()) return
    val simple = Markdown.isSimple(blocks)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .testTag(if (cancelled) ChatTags.REPLY_CANCELLED else tag)
                .semantics(mergeDescendants = true) {
                    if (cancelled) contentDescription = "Annulé depuis : ${Markdown.plain(blocks)}"
                }
                .then(if (simple) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth())
                .clip(ReplyShape)
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line, ReplyShape)
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            MarkdownBlocks(blocks, ink = if (cancelled) TracklyColors.Ink3 else TracklyColors.Ink, cancelled = cancelled, fill = !simple)
        }
    }
}

/** « Trackly » au-dessus d'une réponse, avec sa pastille. */
@Composable
internal fun AssistantMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(19.dp).clip(CircleShape).background(TracklyColors.PaperHi).border(TracklySpacing.Hairline, TracklyColors.Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("t", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
        }
        Spacer(Modifier.width(7.dp))
        BasicText("Trackly", style = TracklyType.Kicker.copy(color = TracklyColors.Ink2))
    }
}

/**
 * « Trackly réfléchit » : trois points qui sautillent (immobiles si les mouvements sont réduits). [label] dit
 * l'étape en cours quand elle est connue (« Je cherche sur Internet… », « J’enregistre… »).
 */
@Composable
internal fun Thinking(label: String = TurnStatus.Thinking.label, withMark: Boolean = true) {
    val motion = LocalMotion.current
    Column(Modifier.padding(top = if (withMark) 9.dp else 0.dp)) {
        if (withMark) AssistantMark()
        Row(
            Modifier
                .padding(top = 9.dp)
                .testTag(ChatTags.THINKING)
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label }
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line, RoundedCornerShape(20.dp))
                .padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (motion.reduced) {
                repeat(3) { Dot(Modifier.padding(end = 4.dp)) }
            } else {
                val transition = rememberInfiniteTransition(label = "points")
                repeat(3) { i ->
                    val lift by transition.animateFloat(
                        0f,
                        1f,
                        infiniteRepeatable(tween(1000, delayMillis = i * 150), RepeatMode.Restart),
                        label = "point$i",
                    )
                    // 0 → 40 % : monte ; 40 → 100 % : redescend.
                    val up = if (lift < 0.4f) lift / 0.4f else 1f - (lift - 0.4f) / 0.6f
                    Dot(Modifier.padding(end = 4.dp).graphicsLayer { translationY = -4.dp.toPx() * up; alpha = 0.45f + 0.55f * up })
                }
            }
            Spacer(Modifier.width(6.dp))
            BasicText(label, style = TracklyType.Thinking.copy(color = TracklyColors.Ink))
        }
    }
}

@Composable
private fun Dot(modifier: Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(TracklyColors.Cobalt))
}

/** Erreur du dernier envoi : titre, explication, et l'action utile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ErrorView(
    error: ChatError,
    actions: ChatActions,
    onOpenSettings: () -> Unit,
    keyJustVerified: Boolean = false,
    onManualEntry: (() -> Unit)? = null,
) {
    val notice = error.notice
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .testTag(ChatTags.ERROR)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(12.dp))
            .background(TracklyColors.Mist)
            .drawWithContent {
                drawContent()
                drawRect(TracklyColors.Cobalt, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            }
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        BasicText(notice.title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(4.dp))
        BasicText(notice.body, style = TracklyType.Notice.copy(color = TracklyColors.Ink2))
        val canRetry = notice.retryable && error.retryText != null
        // Clé corrigée et reconnue : le message refusé n'a rien enregistré, on peut le renvoyer tel quel.
        val canResend = keyJustVerified && notice.opensKeySettings && error.retryText != null
        val manual = onManualEntry.takeIf { notice.network }
        if (canRetry || canResend || notice.opensKeySettings || manual != null) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notice.opensKeySettings) PillButton("Saisir ma clé", primary = !canRetry && !canResend, onClick = onOpenSettings)
                if (canRetry) PillButton("Réessayer", primary = true, onClick = actions.onRetry)
                if (canResend) PillButton("Renvoyer mon dernier message", primary = true, onClick = actions.onRetry)
                if (manual != null) Box(Modifier.testTag(ChatTags.MANUAL)) { PillButton("Saisir à la main", primary = false, onClick = manual) }
            }
        }
    }
}

@Composable
internal fun NoticeLine(text: String) {
    BasicText(
        text,
        style = TracklyType.Notice.copy(color = TracklyColors.Ink2),
        modifier = Modifier.padding(top = 6.dp, start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private val PillShape = RoundedCornerShape(999.dp)

@Composable
internal fun PillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.CardButton.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .pressable(onClick, pressedScale = 0.96f)
            .clip(PillShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, if (primary) TracklyColors.Cobalt else TracklyColors.Line2, PillShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

private val ComposerShape = RoundedCornerShape(14.dp)

/** Zone de saisie : photos, texte libre, micro (dictée), envoi. */
@Composable
private fun BoxScope.Composer(
    draft: String,
    enabled: Boolean,
    effort: ReasoningEffort,
    correcting: String?,
    photos: List<PreparedPhoto>,
    actions: ChatActions,
    dictation: DictationBinding?,
    onOpenSettings: () -> Unit,
    offline: Boolean,
    focusRequest: Int,
    modifier: Modifier = Modifier,
    onManualEntry: (() -> Unit)? = null,
) {
    val canSend = enabled && (draft.isNotBlank() || photos.isNotEmpty())
    val picker = rememberPhotoPicker(
        remaining = PhotoLimits.MAX_PHOTOS - photos.size,
        onPhotos = actions.onPhotosAdded,
        onProblem = actions.onPhotoProblem,
    )
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Le curseur dans la saisie et le clavier ouvert, sans toucher de plus. Le curseur va en fin de texte
    // (après « Ce matin : »).
    var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    // Brouillon changé d'ailleurs (« + » prérempli, dictée, envoi) : la saisie le reprend. Pendant la frappe,
    // le brouillon suit la saisie avec un temps de retard : il ne doit pas l'écraser.
    var lastDraft by remember { mutableStateOf(draft) }
    if (draft != lastDraft) {
        lastDraft = draft
        if (draft != field.text) field = TextFieldValue(draft, TextRange(draft.length))
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.isSuccess) keyboard?.show()
        }
    }
    Column(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .testTag(ChatTags.COMPOSER)
            .background(
                Brush.verticalGradient(
                    0f to TracklyColors.PaperHi.copy(alpha = 0f),
                    0.4f to TracklyColors.PaperHi.copy(alpha = 0.94f),
                    1f to TracklyColors.PaperHi.copy(alpha = 0.94f),
                ),
            )
            .navigationBarsPadding()
            .imePadding()
            .padding(start = TracklySpacing.DockGutter, end = TracklySpacing.DockGutter, top = 12.dp, bottom = 20.dp),
    ) {
        // Hors ligne : dit avant même d'envoyer ; le texte reste là.
        if (offline) {
            OfflineBanner(onManualEntry)
            Spacer(Modifier.height(6.dp))
        }
        // Rappel « Je corrige : … » au-dessus du sélecteur de raisonnement, lui-même collé à la saisie.
        if (correcting != null) {
            CorrectionChip(correcting, actions.onCancelCorrection)
            Spacer(Modifier.height(6.dp))
        }
        // Dictée : permission du micro, ou enregistrement du mode « après l'arrêt », juste au-dessus de la saisie.
        if (dictation != null && dictation.state.showsStrip()) {
            DictationStrip(dictation.state, dictation.actions, onOpenSettings, onManualEntry)
            Spacer(Modifier.height(6.dp))
        }
        EffortSelector(effort, actions.onEffortChange, Modifier.padding(start = 6.dp, bottom = 2.dp))
        // Photos jointes (vignettes) et choix de la source, juste au-dessus de la saisie.
        PhotoStrip(photos, actions.onPhotoRemoved, Modifier.padding(start = 6.dp, bottom = 4.dp))
        PhotoOptions(picker, Modifier.padding(start = 6.dp, bottom = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TracklySpacing.AskHeight)
                .softShadow(ComposerShape, radius = 22.dp, spread = (-16).dp, y = 10.dp, alpha = 0.35f)
                .clip(ComposerShape)
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, ComposerShape)
                .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoAttachButton(picker, enabled = enabled && photos.size < PhotoLimits.MAX_PHOTOS)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    BasicText(if (correcting != null) "Dis ce qu’il faut changer…" else "Dis ce que tu as mangé…", style = TracklyType.AskPlaceholder.copy(color = TracklyColors.Ink3), maxLines = 1)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != draft) actions.onDraftChange(it.text)
                    },
                    textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
                    cursorBrush = SolidColor(TracklyColors.Cobalt),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(ChatTags.INPUT)
                        .semantics { contentDescription = "Message pour Trackly" },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (dictation != null) {
                MicButton(busy = dictation.state.active, onClick = dictation.actions.onMic)
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .size(TracklySpacing.MicSize)
                    .testTag(ChatTags.SEND)
                    .pressable(actions.onSend, pressedScale = 0.9f, enabled = canSend)
                    .semantics { contentDescription = "Envoyer" }
                    .alpha(if (canSend || !enabled) 1f else 0.55f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canSend) TracklyColors.Cobalt else TracklyColors.Mist),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Send, if (canSend) TracklyColors.PaperHi else TracklyColors.Cobalt, Modifier.size(19.dp), stroke = 1.8f)
            }
        }
    }
}

/**
 * « Hors ligne » : le téléphone n'a plus Internet ; rien ne peut partir, rien n'est perdu.
 * [onManualEntry] : geste « Saisir à la main » (plan B), sous le texte à grande police.
 */
@Composable
private fun OfflineBanner(onManualEntry: (() -> Unit)?) {
    val shape = RoundedCornerShape(10.dp)
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val frame = Modifier
        .fillMaxWidth()
        .testTag(ChatTags.OFFLINE)
        .clip(shape)
        .background(TracklyColors.Mist)
        .drawWithContent {
            drawContent()
            drawRect(TracklyColors.Cobalt, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
        }
        .padding(start = 14.dp, end = 4.dp)
    val text: @Composable (Modifier) -> Unit = { modifier ->
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = TracklyType.NoticeTitle.fontWeight)) { append("Hors ligne") }
                append(" · rien ne peut partir pour l’instant. Ce que tu écris reste ici.")
            },
            style = TracklyType.Notice.copy(color = TracklyColors.Ink),
            modifier = modifier.padding(top = 9.dp, bottom = 9.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val action: @Composable () -> Unit = {
        if (onManualEntry != null) {
            BasicText(
                "Saisir à la main",
                style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt),
                maxLines = 1,
                modifier = Modifier
                    .testTag(ChatTags.MANUAL)
                    .pressable(onManualEntry, pressedScale = 0.94f)
                    .semantics { role = Role.Button }
                    .heightIn(min = TracklySpacing.TouchTarget)
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
    }
    if (stacked) {
        Column(frame) {
            text(Modifier.fillMaxWidth())
            action()
        }
    } else {
        Row(frame, verticalAlignment = Alignment.CenterVertically) {
            text(Modifier.weight(1f))
            action()
        }
    }
}

/** Vignettes des photos d'un message envoyé : en mémoire seulement, jamais enregistrées. */
@Composable
private fun SentThumbnails(thumbnails: List<Bitmap>) {
    val images = remember(thumbnails) { thumbnails.map { it.asImageBitmap() } }
    Row(
        Modifier
            .testTag(ChatTags.PHOTO_THUMBS)
            .semantics { contentDescription = if (images.size == 1) "Photo envoyée" else "${images.size} photos envoyées" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (image in images.take(4)) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Rappelle l'enregistrement visé par « Corriger » : la phrase qui suit porte sur lui. */
@Composable
private fun CorrectionChip(label: String, onCancel: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ChatTags.CORRECTING)
            .clip(shape)
            .background(TracklyColors.Mist)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "Je corrige : $label",
            style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        BasicText(
            "Retirer",
            style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt),
            modifier = Modifier
                .testTag(ChatTags.CORRECTING_CANCEL)
                .pressable(onCancel, pressedScale = 0.96f)
                .semantics { contentDescription = "Ne plus corriger cet enregistrement" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
