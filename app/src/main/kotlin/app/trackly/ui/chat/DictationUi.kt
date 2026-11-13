package app.trackly.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackly.dictation.DictationFailure
import app.trackly.dictation.DictationMode
import app.trackly.dictation.DictationPhase
import app.trackly.dictation.DictationUiState
import app.trackly.dictation.PermissionPrompt
import app.trackly.dictation.notice
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.sin

/** Étiquettes de test de la dictée dans le chat. */
object MicTags {
    const val MIC = "dictee-micro"
    const val PANEL = "dictee-panneau"
    const val TEXT = "dictee-texte"
    const val SCROLL = "dictee-defilement"
    const val STATUS = "dictee-etat"
    const val TIMER = "dictee-duree"
    const val SEND = "dictee-envoyer"
    const val EDIT = "dictee-relire"
    const val CANCEL = "dictee-annuler"
    const val STRIP = "dictee-bandeau"
    const val STOP = "dictee-arreter"
    const val CARD = "dictee-carte"
    const val ALLOW = "dictee-autoriser"
    const val APP_SETTINGS = "dictee-parametres"
    const val RETRY = "dictee-reessayer"
    const val KEEP_TEXT = "dictee-garder-texte"
    const val CLOSE = "dictee-fermer"
    const val KEY = "dictee-cle"
    const val ERROR = "dictee-erreur"

    /** « Saisir à la main » : seulement quand la dictée échoue faute de réseau. */
    const val MANUAL = "dictee-saisie-manuelle"
}

/** Ce que la dictée peut demander (branché sur `DictationController` et sur le système). */
class DictationActions(
    val onMic: () -> Unit = {},
    /** « Envoyer » : arrête, attend le texte final, l'envoie. */
    val onSend: () -> Unit = {},
    /** « Relire d'abord » / « Arrêter » : arrête, place le texte dans la zone de saisie. */
    val onStopEdit: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /** Ouvre la fenêtre d'autorisation du système. */
    val onAllowMic: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onKeepText: () -> Unit = {},
    /** Le chat se ferme (ou l'app passe en arrière-plan) pendant une dictée : on la termine, le texte va dans la zone de saisie. */
    val onLeave: () -> Unit = {},
)

/** État et actions de la dictée tels que le chat les affiche. */
class DictationBinding(
    val state: DictationUiState = DictationUiState(),
    val actions: DictationActions = DictationActions(),
)

/** Le panneau plein écran sert au direct ; le reste (après l'arrêt, permission) tient dans un bandeau au-dessus de la saisie. */
internal fun DictationUiState.showsPanel(): Boolean =
    mode == DictationMode.Live && (phase == DictationPhase.Listening || phase == DictationPhase.Finishing || phase == DictationPhase.Failed)

internal fun DictationUiState.showsStrip(): Boolean = when (phase) {
    DictationPhase.Idle -> false
    DictationPhase.Permission -> true
    else -> !showsPanel()
}

private val MicShape = RoundedCornerShape(10.dp)

/** Bouton micro de la ligne de saisie. */
@Composable
internal fun MicButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TracklySpacing.MicSize)
            .testTag(MicTags.MIC)
            .pressable(onClick, pressedScale = 0.9f, enabled = !busy)
            .semantics { contentDescription = "Dicter un message" }
            .alpha(if (busy) 0.55f else 1f)
            .clip(MicShape)
            .background(TracklyColors.Mist),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Mic, TracklyColors.Cobalt, Modifier.size(19.dp))
    }
}

private fun <T> FiniteAnimationSpec<T>.orSnap(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else this

private val DictatedShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)

/**
 * Dictée en direct, en plein chat : le texte défile pendant la parole, dans la bulle qu'il deviendra.
 * À l'arrêt, la bulle descend vers le fil et le panneau se fond (instantané si les mouvements sont réduits).
 */
@Composable
internal fun DictationLivePanel(
    state: DictationUiState,
    actions: DictationActions,
    onOpenKeySettings: () -> Unit,
    onManualEntry: (() -> Unit)? = null,
) {
    val motion = LocalMotion.current
    val present = state.showsPanel()
    // Le panneau reste affiché le temps de son animation de sortie, avec son dernier état.
    val last = remember { LastState(state) }
    if (present) last.value = state
    var visible by remember { mutableStateOf(present) }
    LaunchedEffect(present) { if (present) visible = true }
    val progress by animateFloatAsState(
        if (present) 1f else 0f,
        tween<Float>(if (present) 220 else 300).orSnap(motion.reduced),
        label = "panneau-dictee",
        finishedListener = { if (it == 0f) visible = false },
    )
    if (!visible && !present) return
    val shown = last.value
    val delivered = !present && shown.phase == DictationPhase.Finishing
    BackHandler(enabled = present) { actions.onCancel() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(MicTags.PANEL)
            .graphicsLayer { alpha = progress }
            .background(TracklyColors.PaperHi),
    ) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.3f, dotsAlpha = 0.7f)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // Clavier ouvert : tout le panneau (texte, animation, boutons) se pose juste au-dessus de lui.
                // Le clavier recouvre déjà la barre de navigation : la ligne suivante n'ajoute rien dans ce cas.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = TracklySpacing.ChatGutter, vertical = 16.dp),
        ) {
            PanelStatus(shown)
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                // La fin du texte reste en vue aussi quand la place change (clavier qui s'ouvre ou se ferme en cours de dictée).
                LaunchedEffect(shown.text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .testTag(MicTags.SCROLL)
                        .heightIn(max = maxHeight)
                        .verticalScroll(scroll)
                        // La bulle descend vers le fil quand le texte est livré.
                        .graphicsLayer { translationY = if (delivered) (1f - progress) * 96.dp.toPx() else 0f },
                    horizontalAlignment = Alignment.End,
                ) {
                    DictatedBubble(shown)
                }
            }
            Spacer(Modifier.height(12.dp))
            when (shown.phase) {
                DictationPhase.Failed -> PanelFailure(shown, actions, onOpenKeySettings, onManualEntry)
                DictationPhase.Finishing -> {
                    Spacer(Modifier.height(4.dp))
                    BasicText("Je termine le texte…", style = TracklyType.Thinking.copy(color = TracklyColors.Ink2))
                    Spacer(Modifier.height(44.dp))
                }
                else -> {
                    LevelBars(shown.level, Modifier.fillMaxWidth().height(36.dp))
                    Spacer(Modifier.height(18.dp))
                    PanelControls(actions)
                }
            }
        }
    }
}

private class LastState(var value: DictationUiState)

@Composable
private fun PanelStatus(state: DictationUiState) {
    val label = when (state.phase) {
        DictationPhase.Listening -> if (state.connecting && state.text.isEmpty()) "Connexion…" else "Je t’écoute"
        DictationPhase.Finishing -> "Un instant"
        DictationPhase.Failed -> "Dictée interrompue"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecordingDot(active = state.phase == DictationPhase.Listening)
        Spacer(Modifier.width(8.dp))
        BasicText(
            label.uppercase(),
            style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt),
            modifier = Modifier.testTag(MicTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.weight(1f))
        // Après une dictée interrompue, la durée n'a plus de sens (« 0:00 », A23) : elle n'est montrée qu'en cours.
        if (state.phase == DictationPhase.Listening || state.phase == DictationPhase.Finishing) {
            BasicText(
                formatElapsed(state.elapsedMs),
                style = TracklyType.DayLabel.copy(color = TracklyColors.Ink2),
                modifier = Modifier.testTag(MicTags.TIMER),
            )
        }
    }
}

/** Texte dicté, dans la bulle qu'il deviendra dans le fil (encre sur fond porcelaine, comme un message envoyé). */
@Composable
private fun DictatedBubble(state: DictationUiState) {
    val empty = state.text.isEmpty()
    if (empty) {
        BasicText(
            if (state.phase == DictationPhase.Listening) {
                if (state.connecting) "Parle déjà : je garde tout, rien n’est perdu." else "Parle, le texte s’écrit ici."
            } else {
                "Aucun texte reçu."
            },
            style = TracklyType.Hint.copy(color = TracklyColors.Ink3),
            modifier = Modifier.testTag(MicTags.TEXT).padding(end = 40.dp),
        )
        return
    }
    BasicText(
        state.text,
        style = TracklyType.Bubble.copy(color = TracklyColors.PaperHi, fontSize = 21.sp, lineHeight = 29.sp),
        modifier = Modifier
            .testTag(MicTags.TEXT)
            .widthIn(max = 320.dp)
            .background(TracklyColors.Ink, DictatedShape)
            .padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 16.dp),
    )
}

@Composable
private fun PanelControls(actions: DictationActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextAction("Annuler", MicTags.CANCEL, actions.onCancel, "Annuler la dictée")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(68.dp)
                    .testTag(MicTags.SEND)
                    .pressable(actions.onSend, pressedScale = 0.92f)
                    .semantics { contentDescription = "Terminer et envoyer" }
                    .clip(CircleShape)
                    .background(TracklyColors.Cobalt),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Send, TracklyColors.PaperHi, Modifier.size(26.dp), stroke = 1.9f)
            }
            Spacer(Modifier.height(6.dp))
            BasicText("Envoyer", style = TracklyType.CardButton.copy(color = TracklyColors.Ink))
        }
        TextAction("Relire d’abord", MicTags.EDIT, actions.onStopEdit, "Terminer et relire avant d’envoyer")
    }
}

@Composable
private fun TextAction(label: String, tag: String, onClick: () -> Unit, description: String) {
    BasicText(
        label,
        style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 14.dp),
    )
}

@Composable
private fun PanelFailure(state: DictationUiState, actions: DictationActions, onOpenKeySettings: () -> Unit, onManualEntry: (() -> Unit)?) {
    FailureCard(state, actions, onOpenKeySettings, onManualEntry, Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

/** Erreur de dictée : les mêmes messages que le chat, et les gestes utiles (garder le texte, réessayer, fermer). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureCard(
    state: DictationUiState,
    actions: DictationActions,
    onOpenKeySettings: () -> Unit,
    onManualEntry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val failure = state.failure ?: return
    val notice = failure.notice()
    CardFrame(modifier.testTag(MicTags.ERROR)) {
        BasicText(notice.title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(4.dp))
        BasicText(notice.body, style = TracklyType.Notice.copy(color = TracklyColors.Ink2))
        if (state.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            BasicText("Le texte déjà dicté est gardé.", style = TracklyType.Notice.copy(color = TracklyColors.Ink))
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notice.opensKeySettings) Pill("Saisir ma clé", MicTags.KEY, primary = state.text.isBlank() && !state.canRetry, onClick = onOpenKeySettings)
            if (state.canRetry) Pill("Réessayer", MicTags.RETRY, primary = true, onClick = actions.onRetry)
            if (state.text.isNotBlank()) Pill("Garder le texte", MicTags.KEEP_TEXT, primary = !state.canRetry && !notice.opensKeySettings, onClick = actions.onKeepText)
            if (notice.network && onManualEntry != null) Pill("Saisir à la main", MicTags.MANUAL, primary = false, onClick = onManualEntry)
            Pill("Fermer", MicTags.CLOSE, primary = false, onClick = actions.onDismiss)
        }
    }
}

/** Carte à filet cobalt, comme les erreurs du chat. */
@Composable
private fun CardFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(12.dp))
            .background(TracklyColors.Mist)
            .drawWithContent {
                drawContent()
                drawRect(TracklyColors.Cobalt, size = Size(2.dp.toPx(), size.height))
            }
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) { content() }
}

@Composable
private fun Pill(text: String, tag: String, primary: Boolean, onClick: () -> Unit) {
    Box(Modifier.testTag(tag)) { PillButton(text, primary, onClick) }
}

/**
 * Bandeau au-dessus de la saisie : permission du micro (explication, refus, blocage), et mode
 * « après l'arrêt » (enregistrement, transcription, erreur). Le chat reste tel quel autour.
 */
@Composable
internal fun DictationStrip(
    state: DictationUiState,
    actions: DictationActions,
    onOpenKeySettings: () -> Unit,
    onManualEntry: (() -> Unit)? = null,
) {
    when (state.phase) {
        DictationPhase.Permission -> PermissionCard(state.permission ?: PermissionPrompt.Explain, actions)
        DictationPhase.Listening -> RecordingStrip(state, actions)
        DictationPhase.Finishing -> StripFrame {
            BasicText(
                "Transcription…",
                style = TracklyType.Thinking.copy(color = TracklyColors.Ink),
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        DictationPhase.Failed -> FailureCard(state, actions, onOpenKeySettings, onManualEntry, Modifier.fillMaxWidth().testTag(MicTags.STRIP))
        DictationPhase.Idle -> Unit
    }
}

@Composable
private fun StripFrame(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(MicTags.STRIP)
            .clip(RoundedCornerShape(10.dp))
            .background(TracklyColors.Mist)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RecordingStrip(state: DictationUiState, actions: DictationActions) {
    StripFrame {
        RecordingDot(active = true)
        Spacer(Modifier.width(10.dp))
        BasicText(
            formatElapsed(state.elapsedMs),
            style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
            modifier = Modifier
                .testTag(MicTags.TIMER)
                .semantics { contentDescription = "Enregistrement en cours, ${formatElapsed(state.elapsedMs)}" },
        )
        Spacer(Modifier.width(14.dp))
        LevelBars(state.level, Modifier.width(56.dp).height(22.dp), bars = 10, gapDp = 2f)
        Spacer(Modifier.weight(1f))
        TextAction("Annuler", MicTags.CANCEL, actions.onCancel, "Annuler l’enregistrement")
        Box(Modifier.testTag(MicTags.STOP).semantics { contentDescription = "Arrêter et transcrire" }) {
            PillButton("Arrêter", primary = true, onClick = actions.onStopEdit)
        }
        Spacer(Modifier.width(6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(prompt: PermissionPrompt, actions: DictationActions) {
    val (title, body) = when (prompt) {
        PermissionPrompt.Explain -> "Autoriser le micro ?" to
            "Trackly écoute ta voix pour écrire ce que tu dis. Le son part chez OpenAI le temps de la transcription, puis il est jeté : il n’est jamais conservé."
        PermissionPrompt.Denied -> "Micro refusé" to
            "Sans le micro, la dictée ne marche pas. Tu peux toujours écrire ton message, ou autoriser le micro quand tu veux."
        PermissionPrompt.Blocked -> "Le micro est bloqué" to
            "Android ne propose plus la fenêtre d’autorisation. Ouvre les paramètres de Trackly, touche Autorisations puis Micro, et choisis Autoriser."
    }
    CardFrame(Modifier.fillMaxWidth().testTag(MicTags.CARD)) {
        BasicText(title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(4.dp))
        BasicText(body, style = TracklyType.Notice.copy(color = TracklyColors.Ink2))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (prompt) {
                PermissionPrompt.Explain -> {
                    Pill("Autoriser le micro", MicTags.ALLOW, primary = true, onClick = actions.onAllowMic)
                    Pill("Plus tard", MicTags.CLOSE, primary = false, onClick = actions.onDismiss)
                }
                PermissionPrompt.Denied -> {
                    Pill("Réessayer", MicTags.RETRY, primary = true, onClick = actions.onMic)
                    Pill("Fermer", MicTags.CLOSE, primary = false, onClick = actions.onDismiss)
                }
                PermissionPrompt.Blocked -> {
                    Pill("Ouvrir les paramètres", MicTags.APP_SETTINGS, primary = true, onClick = actions.onOpenAppSettings)
                    Pill("C’est fait", MicTags.RETRY, primary = false, onClick = actions.onMic)
                    Pill("Fermer", MicTags.CLOSE, primary = false, onClick = actions.onDismiss)
                }
            }
        }
    }
}

/** Point cobalt qui pulse pendant l'écoute (fixe si les mouvements sont réduits). */
@Composable
private fun RecordingDot(active: Boolean) {
    val motion = LocalMotion.current
    val pulse = if (active && !motion.reduced) {
        rememberInfiniteTransition(label = "enregistrement").animateFloat(
            0.35f,
            1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulsation",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(9.dp)
            .alpha(if (active) pulse else 0.4f)
            .clip(CircleShape)
            .background(TracklyColors.Cobalt),
    )
}

/**
 * Niveau sonore en barres fines. Mouvements normaux : l'historique des derniers niveaux défile.
 * Mouvements réduits : rien ne défile, seule la hauteur d'un motif fixe suit le niveau actuel.
 */
@Composable
internal fun LevelBars(level: Float, modifier: Modifier = Modifier, bars: Int = 28, gapDp: Float = 3f) {
    val motion = LocalMotion.current
    val history = remember(bars) { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    LaunchedEffect(level) {
        if (!motion.reduced) {
            history.removeAt(0)
            history.add(level)
        }
    }
    val values = if (motion.reduced) List(bars) { i -> level * (0.45f + 0.55f * kotlin.math.abs(sin(i * 0.9f))) } else history.toList()
    Canvas(modifier.semantics { contentDescription = "Niveau sonore" }) {
        val gap = gapDp.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        val minimum = 3.dp.toPx()
        for (i in 0 until bars) {
            val amplitude = (values[i] * 5f).coerceIn(0f, 1f)
            val h = minimum + (size.height - minimum) * amplitude
            drawRoundRect(
                color = TracklyColors.Cobalt,
                topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 0.35f + 0.65f * amplitude,
            )
        }
    }
}

internal fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
