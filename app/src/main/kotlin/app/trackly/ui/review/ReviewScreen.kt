package app.trackly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ReportReadyCard
import app.trackly.assistant.cards.WeekInfoCard
import app.trackly.assistant.cards.WeightGoalCard
import app.trackly.assistant.cards.TemporaryGoalCard
import app.trackly.review.page.Advice
import app.trackly.review.page.AdviceState
import app.trackly.assistant.chat.ChatMessage
import app.trackly.assistant.chat.ChatRole
import app.trackly.assistant.key.ApiKeyStatus
import app.trackly.core.review.WeekInfo
import app.trackly.photos.PhotoLimits
import app.trackly.photos.PreparedPhoto
import app.trackly.review.ReportFormat
import app.trackly.review.ReviewUiState
import app.trackly.ui.chat.AssistantMark
import app.trackly.ui.chat.AssistantReply
import app.trackly.ui.chat.CardButton
import app.trackly.ui.chat.CardShape
import app.trackly.ui.chat.CardTags
import app.trackly.ui.chat.ChatActions
import app.trackly.ui.chat.DashedRow
import app.trackly.ui.chat.DictationBinding
import app.trackly.ui.chat.DictationLivePanel
import app.trackly.ui.chat.DictationStrip
import app.trackly.ui.chat.MicButton
import app.trackly.ui.chat.showsStrip
import app.trackly.ui.chat.ErrorView
import app.trackly.ui.chat.NoticeLine
import app.trackly.ui.chat.PillButton
import app.trackly.ui.chat.Thinking
import app.trackly.ui.chat.UserBubble
import app.trackly.ui.components.Glyph
import app.trackly.ui.photos.PhotoAttachButton
import app.trackly.ui.photos.PhotoOptions
import app.trackly.ui.photos.PhotoPickerState
import app.trackly.ui.photos.PhotoStrip
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test de la conversation de bilan. */
object ReviewTags {
    const val SCREEN = "bilan-ecran"
    const val HEADER = "bilan-entete"
    const val THREAD = "bilan-fil"
    const val HINT = "bilan-invitation"
    const val INPUT = "bilan-saisie"
    const val SEND = "bilan-envoyer"
    const val PRODUCE = "bilan-produire"
    const val REPORTS = "bilan-liste"
    const val INFO_CARD = "bilan-carte-informations"
    const val READY_CARD = "bilan-carte-pret"
    const val WEIGHT_GOAL_CARD = "bilan-carte-objectif-poids"
    const val OPEN_REPORT = "bilan-ouvrir"
}

/** Ce que la conversation de bilan peut demander (branché sur `ReviewViewModel`). */
class ReviewActions(
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onProduce: () -> Unit = {},
    val onUndo: (ChatCard) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRemovePhoto: (PreparedPhoto) -> Unit = {},
    val onOpenReport: (Long) -> Unit = {},
    val onOpenReports: () -> Unit = {},
    /** Boutons de la carte d'objectif temporaire. */
    val onApplyAdvice: (Advice) -> Unit = {},
    val onToggleAdvice: (AdviceState) -> Unit = {},
)

/**
 * Conversation de bilan du lundi, distincte du chat de saisie : on y donne pesées, pas et séances
 * (texte ou captures de la montre) ; l'assistant note seulement ce qui est donné, puis produit le rapport.
 *
 * [photoPicker] est le sélecteur de photos (`rememberPhotoPicker`) : bouton à gauche de la saisie,
 * choix « Appareil photo / Galerie » et vignettes au-dessus ; les captures partent avec le message suivant
 * (`ReviewViewModel.attach`). `null` : pas de bouton.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    draft: String = "",
    keyStatus: ApiKeyStatus = ApiKeyStatus.Present(""),
    actions: ReviewActions = ReviewActions(),
    onOpenSettings: () -> Unit = {},
    photoPicker: PhotoPickerState? = null,
    /** Micro, comme dans le chat de saisie ; `null` : pas de bouton. */
    dictation: DictationBinding? = null,
) {
    val list = rememberLazyListState()
    val motion = LocalMotion.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val showHint = state.messages.isEmpty() && !state.thinking
    val itemCount = (if (showHint) 1 else 0) + state.messages.size + listOf(state.thinking, state.error != null, state.notice != null).count { it }
    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            if (motion.reduced) list.scrollToItem(itemCount - 1) else list.animateScrollToItem(itemCount - 1)
        }
    }
    val chatActions = ChatActions(onRetry = actions.onRetry, onDismissError = actions.onDismissError)

    Box(modifier.fillMaxSize().testTag(ReviewTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.24f, dotsAlpha = 0.8f)
        Column(Modifier.fillMaxSize()) {
            Header(state, onBack, actions.onOpenReports)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(ReviewTags.THREAD),
                contentPadding = PaddingValues(
                    start = TracklySpacing.ChatGutter,
                    end = TracklySpacing.ChatGutter,
                    top = 14.dp,
                    bottom = 196.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (showHint) item(key = "hint") { Hint(state, keyStatus, onOpenSettings) }
                for (message in state.messages) {
                    item(key = "m${message.id}") { MessageView(message, state, actions) }
                }
                if (state.thinking) item(key = "thinking") { Thinking() }
                state.error?.let { error -> item(key = "error") { ErrorView(error, chatActions, onOpenSettings) } }
                state.notice?.let { text -> item(key = "notice") { NoticeLine(text) } }
            }
        }
        Composer(draft, state, actions, photoPicker, dictation, onOpenSettings)
        if (dictation != null) DictationLivePanel(dictation.state, dictation.actions, onOpenSettings)
    }
}

@Composable
private fun Header(state: ReviewUiState, onBack: () -> Unit, onOpenReports: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(ReviewTags.HEADER).statusBarsPadding().padding(top = 8.dp, start = 14.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TracklySpacing.BackSize).pressable(onBack, pressedScale = 0.86f).semantics { contentDescription = "Fermer le bilan" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp)) }
        Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText("Bilan du lundi", style = TracklyType.ChatTitle.copy(color = TracklyColors.Ink), modifier = Modifier.semantics { heading() })
            BasicText("Du ${ReportFormat.weekLabel(state.period)}", style = TracklyType.ChatDate.copy(color = TracklyColors.Ink2))
        }
        Box(
            Modifier.size(TracklySpacing.BackSize).testTag(ReviewTags.REPORTS).pressable(onOpenReports, pressedScale = 0.86f).semantics { contentDescription = "Bilans passés" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Calendar, TracklyColors.Ink, Modifier.size(19.dp)) }
    }
}

@Composable
private fun Hint(state: ReviewUiState, keyStatus: ApiKeyStatus, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(top = 40.dp, start = 8.dp, end = 8.dp).testTag(ReviewTags.HINT)) {
        BasicText("Ton bilan de la semaine.", style = TracklyType.Hint.copy(color = TracklyColors.Ink))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(36.dp, 1.dp).background(TracklyColors.Cobalt))
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Raconte librement ta semaine : tes pesées, tes pas, tes séances, ce que tu as mangé ou dépensé. Écris, dicte ou joins des captures de ta montre. Je note seulement ce que tu me donnes.",
            style = TracklyType.HintBody.copy(color = TracklyColors.Ink2),
            modifier = Modifier.widthIn(max = 290.dp),
        )
        Spacer(Modifier.height(14.dp))
        Declared(state.declared)
        if (keyStatus is ApiKeyStatus.Missing || keyStatus is ApiKeyStatus.Unreadable) {
            Spacer(Modifier.height(18.dp))
            BasicText("Pour commencer, ajoute ta clé OpenAI.", style = TracklyType.HintBody.copy(color = TracklyColors.Ink), modifier = Modifier.widthIn(max = 260.dp))
            Spacer(Modifier.height(10.dp))
            PillButton("Ajouter ma clé OpenAI", primary = true, onClick = onOpenSettings)
        }
    }
}

/** Ce qui est déjà noté pour la semaine, ou ce qui reste à donner. */
@Composable
private fun Declared(info: WeekInfo?) {
    val lines = InfoLines.of(info)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Déjà noté", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
        for ((label, value, known) in lines) {
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TracklyColors.Ink)) { append("$label : ") }
                    withStyle(SpanStyle(color = if (known) TracklyColors.Ink else TracklyColors.Ink3)) { append(value) }
                },
                style = TracklyType.HintBody,
            )
        }
    }
}

@Composable
private fun MessageView(message: ChatMessage, state: ReviewUiState, actions: ReviewActions) {
    Column(Modifier.fillMaxWidth()) {
        when (message.role) {
            ChatRole.User -> UserBubble(message.shownText, message.photoCount)
            ChatRole.Assistant -> {
                Box(Modifier.padding(top = 9.dp)) { AssistantMark() }
                for (card in message.cards) {
                    when (card) {
                        is WeekInfoCard -> WeekInfoCardView(card, undone = card.actionId in state.undone, onUndo = actions.onUndo)
                        is WeightGoalCard -> WeightGoalCardView(card, undone = card.actionId in state.undone, onUndo = actions.onUndo)
                        is ReportReadyCard -> ReportReadyCardView(card, actions.onOpenReport)
                        is TemporaryGoalCard -> Box(Modifier.padding(top = 9.dp)) {
                            AdviceList(listOf(card.advice), AdviceUi(state.advice, onApply = actions.onApplyAdvice, onToggle = actions.onToggleAdvice))
                        }
                        else -> Unit
                    }
                }
                if (message.text.isNotBlank()) AssistantReply(message.text)
            }
        }
    }
}

/** Ce que l'utilisateur a donné, ligne par ligne : pesées, pas, séances. */
internal data class InfoLine(val label: String, val value: String, val known: Boolean)

internal object InfoLines {
    fun of(info: WeekInfo?): List<InfoLine> {
        val weigh = info?.weighIns.orEmpty()
        val steps = info?.steps.orEmpty()
        val sessions = info?.sessions
        return listOf(
            if (weigh.isEmpty()) InfoLine("Pesées", "pas encore", false)
            else InfoLine("Pesées", weigh.joinToString(" · ") { "${ReportFormat.weekdayName(it.day).take(3)}. ${it.day.dayOfMonth} : ${ReportFormat.one(it.kg)} kg" }, true),
            when {
                steps.isNotEmpty() -> InfoLine("Pas", steps.joinToString(" · ") { "${ReportFormat.weekdayName(it.day).take(3)}. ${it.day.dayOfMonth} : ${ReportFormat.int(it.steps.toDouble())}" }, true)
                info?.stepsAverage != null -> InfoLine("Pas", "moyenne ${ReportFormat.int(info.stepsAverage!!.toDouble())} par jour", true)
                else -> InfoLine("Pas", "pas encore", false)
            },
            when {
                sessions == null -> InfoLine("Séances", "pas encore", false)
                sessions.isEmpty() -> InfoLine("Séances", "aucune cette semaine", true)
                else -> InfoLine("Séances", sessions.joinToString(" · ") { "${it.count} ${it.type}" }, true)
            },
        )
    }
}

@Composable
internal fun WeekInfoCardView(card: WeekInfoCard, undone: Boolean, onUndo: (ChatCard) -> Unit) =
    NotedCardView(card, WeekInfoLines.of(card), "Noté pour la semaine", "informations de la semaine", ReviewTags.INFO_CARD, undone, onUndo)

/** Objectif de poids changé : avant, maintenant, et « Annuler ». */
@Composable
internal fun WeightGoalCardView(card: WeightGoalCard, undone: Boolean, onUndo: (ChatCard) -> Unit) {
    fun label(kg: Double, iso: String) = "${ReportFormat.one(kg)} kg le ${ReportFormat.dayMonthYear(java.time.LocalDate.parse(iso))}"
    val lines = listOf(
        InfoLine("Avant", label(card.beforeKg, card.beforeDate), false),
        InfoLine("Maintenant", label(card.afterKg, card.afterDate), true),
    )
    NotedCardView(card, lines, "Objectif de poids changé", "objectif de poids", ReviewTags.WEIGHT_GOAL_CARD, undone, onUndo)
}

@Composable
private fun NotedCardView(card: ChatCard, info: List<InfoLine>, title: String, undoneLabel: String, tag: String, undone: Boolean, onUndo: (ChatCard) -> Unit) {
    if (undone) {
        DashedRow(tag = CardTags.UNDONE, description = "Annulé, $undoneLabel") {
            BasicText(
                buildAnnotatedString {
                    append("Annulé · ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(undoneLabel) }
                },
                style = TracklyType.Undone.copy(color = TracklyColors.Ink3),
            )
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(tag)
            .semantics { contentDescription = "$title : " + info.joinToString(". ") { "${it.label} ${it.value}" } }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
            }
            Spacer(Modifier.width(7.dp))
            BasicText(title, style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(TracklyColors.Ink, RoundedCornerShape(1.dp)))
        for (line in info) {
            Column(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 2.dp)) {
                BasicText(line.label, style = TracklyType.Kicker.copy(color = TracklyColors.Ink2))
                Spacer(Modifier.height(2.dp))
                BasicText(line.value, style = TracklyType.CardItemDetail.copy(color = if (line.known) TracklyColors.Ink else TracklyColors.Ink3))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO) { onUndo(card) }
        }
    }
}

internal object WeekInfoLines {
    fun of(card: WeekInfoCard): List<InfoLine> = listOf(
        if (card.weighIns.isEmpty()) InfoLine("Pesées", "aucune", false)
        else InfoLine("Pesées", card.weighIns.joinToString("\n") { "${dayLabel(it.day)} : ${ReportFormat.one(it.kg)} kg" }, true),
        when {
            card.steps.isNotEmpty() -> InfoLine("Pas", card.steps.joinToString("\n") { "${dayLabel(it.day)} : ${ReportFormat.int(it.steps.toDouble())}" }, true)
            card.stepsAverage != null -> InfoLine("Pas", "Moyenne de ${ReportFormat.int(card.stepsAverage.toDouble())} par jour", true)
            else -> InfoLine("Pas", "non fournis", false)
        },
        when {
            card.sessions == null -> InfoLine("Séances", "non fournies", false)
            card.sessions.isEmpty() -> InfoLine("Séances", "aucune cette semaine", true)
            else -> InfoLine("Séances", card.sessions.joinToString(" · ") { "${it.count} ${it.type}" }, true)
        },
    )

    private fun dayLabel(iso: String): String {
        val day = java.time.LocalDate.parse(iso)
        return "${ReportFormat.weekdayName(day).replaceFirstChar { it.uppercase() }} ${ReportFormat.dayMonth(day)}"
    }
}

@Composable
internal fun ReportReadyCardView(card: ReportReadyCard, onOpen: (Long) -> Unit) {
    val label = "Du ${ReportFormat.weekLabel(card.period)}"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(ReviewTags.READY_CARD)
            .semantics { contentDescription = "Ton bilan est prêt. $label" }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f, cobalt = true)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
            }
            Spacer(Modifier.width(7.dp))
            BasicText("Ton bilan est prêt", style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt))
        }
        BasicText(label, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
        Spacer(Modifier.height(4.dp))
        BasicText(card.savedText, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Box(Modifier.testTag(ReviewTags.OPEN_REPORT)) { PillButton("Ouvrir le bilan", primary = true) { onOpen(card.reportId) } }
        }
    }
}

private val ComposerShape = RoundedCornerShape(14.dp)

@Composable
private fun BoxScope.Composer(
    draft: String,
    state: ReviewUiState,
    actions: ReviewActions,
    picker: PhotoPickerState?,
    dictation: DictationBinding?,
    onOpenSettings: () -> Unit,
) {
    val enabled = !state.thinking
    val canSend = enabled && (draft.isNotBlank() || state.photos.isNotEmpty())
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
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
        // Dictée : permission du micro, ou enregistrement du mode « après l'arrêt ».
        if (dictation != null && dictation.state.showsStrip()) {
            DictationStrip(dictation.state, dictation.actions, onOpenSettings)
            Spacer(Modifier.height(6.dp))
        }
        // Captures jointes (vignettes) et choix de la source, comme dans le chat.
        PhotoStrip(state.photos, actions.onRemovePhoto, Modifier.padding(start = 6.dp, bottom = 4.dp))
        if (picker != null) PhotoOptions(picker, Modifier.padding(start = 6.dp, bottom = 4.dp))
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.testTag(ReviewTags.PRODUCE).alpha(if (enabled) 1f else 0.5f)) {
                PillButton(if (state.latestReport == null) "Produire mon bilan" else "Produire un nouveau bilan", primary = true) { if (enabled) actions.onProduce() }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TracklySpacing.AskHeight)
                .softShadow(ComposerShape, radius = 22.dp, spread = (-16).dp, y = 10.dp, alpha = 0.35f)
                .clip(ComposerShape)
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, ComposerShape)
                .padding(start = if (picker != null) 7.dp else 18.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picker != null) {
                PhotoAttachButton(picker, enabled = enabled && state.photos.size < PhotoLimits.MAX_PHOTOS)
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    BasicText("Raconte ta semaine…", style = TracklyType.AskPlaceholder.copy(color = TracklyColors.Ink3), maxLines = 1)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = actions.onDraftChange,
                    textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
                    cursorBrush = SolidColor(TracklyColors.Cobalt),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.INPUT).semantics { contentDescription = "Message pour le bilan" },
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
                    .testTag(ReviewTags.SEND)
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
