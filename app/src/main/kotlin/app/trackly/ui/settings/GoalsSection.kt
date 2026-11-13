package app.trackly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.trackly.core.action.SetGoal
import app.trackly.core.model.GoalTargets
import app.trackly.day.DayLabels
import app.trackly.day.formatKcal
import app.trackly.goals.DraftResult
import app.trackly.goals.GoalDraft
import app.trackly.goals.GoalPeriod
import app.trackly.goals.GoalStart
import app.trackly.goals.GoalStatus
import app.trackly.goals.GoalsNotice
import app.trackly.goals.GoalsUiState
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/** Étiquettes de test de la section Objectifs. */
object GoalsTags {
    const val SECTION = "reglages-objectifs"
    const val CURRENT = "objectif-en-vigueur"
    const val CURRENT_KCAL = "objectif-kcal-en-vigueur"
    const val UPCOMING = "objectif-a-venir"
    const val EDIT = "objectif-modifier"
    const val FIELD_KCAL = "objectif-saisie-kcal"
    const val FIELD_PROTEIN = "objectif-saisie-proteines"
    const val FIELD_CARBS = "objectif-saisie-glucides"
    const val FIELD_FAT = "objectif-saisie-lipides"
    const val START_TODAY = "objectif-depart-aujourdhui"
    const val START_TOMORROW = "objectif-depart-demain"
    const val START_OTHER = "objectif-depart-autre"
    const val OTHER_DATE = "objectif-depart-date"
    const val HINT = "objectif-rappel"
    const val ADJUST = "objectif-ajuster"
    const val ERROR = "objectif-erreur"
    const val SAVE = "objectif-enregistrer"
    const val CANCEL = "objectif-fermer"
    const val NOTICE = "objectif-message"
    const val UNDO = "objectif-annuler"
    const val HISTORY_TOGGLE = "objectif-historique"
    const val HISTORY_ROW = "objectif-historique-ligne"
}

/** Ce que la section Objectifs peut demander. */
class GoalsActions(
    val save: (SetGoal) -> Unit = {},
    val undo: (actionId: Long) -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

private val DraftSaver = listSaver<GoalDraft, String>(
    save = { listOf(it.kcal, it.protein, it.carbs, it.fat, it.start.name, it.otherDate, it.proteinMin?.toString().orEmpty()) },
    restore = { GoalDraft(it[0], it[1], it[2], it[3], GoalStart.valueOf(it[4]), it[5], it.getOrNull(6)?.toDoubleOrNull()) },
)

/**
 * Section « Objectifs » des Réglages : objectif en vigueur et sa date d'effet, modification avec
 * une date de départ, historique. Stateless côté données ; seul le formulaire garde son état.
 * L'enregistrement est l'action [SetGoal], la même que pour l'assistant.
 */
@Composable
fun GoalsSettingsSection(state: GoalsUiState, actions: GoalsActions) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(GoalDraft()) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val current = state.inForce

    Column(Modifier.fillMaxWidth().testTag(GoalsTags.SECTION)) {
        SectionTitle("Objectifs")
        state.notice?.let { notice ->
            NoticeLine(notice, actions)
            Spacer(Modifier.height(10.dp))
        }
        if (current == null) {
            Body("Aucun objectif.")
        } else {
            CurrentGoal(current)
            state.upcoming.forEach { next -> UpcomingLine(next) }
        }
        Spacer(Modifier.height(14.dp))

        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton("Modifier l’objectif", primary = true, tag = GoalsTags.EDIT) {
                    draft = GoalDraft.from(current?.targets)
                    problem = null
                    actions.dismissNotice()
                    editing = true
                }
            }
        } else {
            Form(
                draft = draft,
                today = state.today,
                problem = problem,
                onChange = {
                    draft = it
                    problem = null
                },
                onSave = {
                    when (val result = draft.read(state.today)) {
                        is DraftResult.Invalid -> problem = result.message
                        is DraftResult.Ready -> {
                            problem = null
                            editing = false
                            actions.save(result.action)
                        }
                    }
                },
                onClose = {
                    editing = false
                    problem = null
                },
            )
        }

        if (state.periods.size > 1 || (state.periods.isNotEmpty() && current == null)) {
            Spacer(Modifier.height(6.dp))
            TextAction(
                if (showHistory) "Masquer l’historique" else "Voir l’historique des objectifs",
                tag = GoalsTags.HISTORY_TOGGLE,
            ) { showHistory = !showHistory }
            if (showHistory) History(state)
        }
    }
}

@Composable
private fun CurrentGoal(period: GoalPeriod) {
    Column(Modifier.fillMaxWidth().testTag(GoalsTags.CURRENT)) {
        Line("Objectif en vigueur", strong = true)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            BasicText(
                formatKcal(period.targets.kcal.roundToInt()),
                style = TracklyType.CardTotal.copy(color = TracklyColors.Ink),
                modifier = Modifier.testTag(GoalsTags.CURRENT_KCAL),
            )
            Spacer(Modifier.width(5.dp))
            BasicText("kcal", style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 3.dp))
        }
        BasicText(macrosText(period.targets), style = TracklyType.CardMacros.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 2.dp))
        Body("En vigueur depuis le ${DayLabels.short(period.from)}.")
    }
}

@Composable
private fun UpcomingLine(period: GoalPeriod) {
    Body(
        "Prévu dès ${DayLabels.inSentence(period.from)} : ${formatKcal(period.targets.kcal.roundToInt())} kcal, ${macrosText(period.targets)}.",
        tag = GoalsTags.UPCOMING,
    )
}

@Composable
private fun Form(
    draft: GoalDraft,
    today: java.time.LocalDate,
    problem: String?,
    onChange: (GoalDraft) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Line("Nouvel objectif", strong = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Calories (kcal)", draft.kcal, GoalsTags.FIELD_KCAL) { onChange(draft.copy(kcal = it)) }
            Field("Protéines (g)", draft.protein, GoalsTags.FIELD_PROTEIN) { onChange(draft.copy(protein = it)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Glucides (g)", draft.carbs, GoalsTags.FIELD_CARBS) { onChange(draft.copy(carbs = it)) }
            // Dernier champ : la touche ✓ du clavier enregistre.
            Field("Lipides (g)", draft.fat, GoalsTags.FIELD_FAT, ime = ImeAction.Done, onImeDone = onSave) { onChange(draft.copy(fat = it)) }
        }
        draft.coherenceHint()?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(
                it,
                style = TracklyType.Notice.copy(color = TracklyColors.Ink2),
                modifier = Modifier.testTag(GoalsTags.HINT).semantics { liveRegion = LiveRegionMode.Polite },
            )
            draft.adjustedInProportion()?.let { adjusted ->
                TextAction("Ajuster en proportion", tag = GoalsTags.ADJUST) { onChange(adjusted) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Line("À partir de", strong = true)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Aujourd’hui", draft.start == GoalStart.Today, GoalsTags.START_TODAY) { onChange(draft.copy(start = GoalStart.Today)) }
            Choice("Demain", draft.start == GoalStart.Tomorrow, GoalsTags.START_TOMORROW) { onChange(draft.copy(start = GoalStart.Tomorrow)) }
            Choice("Autre date", draft.start == GoalStart.Other, GoalsTags.START_OTHER) { onChange(draft.copy(start = GoalStart.Other)) }
        }
        if (draft.start == GoalStart.Other) {
            Spacer(Modifier.height(10.dp))
            Row { Field("Date (JJ/MM/AAAA)", draft.otherDate, GoalsTags.OTHER_DATE, KeyboardType.Text) { onChange(draft.copy(otherDate = it)) } }
        }
        val date = when (draft.start) {
            GoalStart.Today -> today
            GoalStart.Tomorrow -> today.plusDays(1)
            GoalStart.Other -> GoalDraft.parseDate(draft.otherDate)
        }
        Body(
            when {
                date == null -> "Les jours passés gardent leur objectif."
                draft.start == GoalStart.Today -> "S’applique à toute la journée en cours (${DayLabels.inSentence(date)}). Les jours passés gardent leur objectif."
                else -> "S’applique dès ${DayLabels.inSentence(date)}. Les jours d’avant gardent leur objectif."
            },
        )
        problem?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(
                "À corriger : $it",
                style = TracklyType.Notice.copy(color = TracklyColors.Ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(TracklyColors.Paper)
                    .border(TracklySpacing.Hairline, TracklyColors.Line2, PanelShape)
                    .padding(12.dp)
                    .testTag(GoalsTags.ERROR)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton("Enregistrer l’objectif", primary = true, tag = GoalsTags.SAVE, onClick = onSave)
            TextAction("Fermer", tag = GoalsTags.CANCEL, onClick = onClose)
        }
    }
}

@Composable
private fun History(state: GoalsUiState) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        state.periods.forEach { period ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(PanelShape)
                    .background(TracklyColors.Paper)
                    .border(TracklySpacing.Hairline, TracklyColors.Line, PanelShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag(GoalsTags.HISTORY_ROW),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(formatKcal(period.targets.kcal.roundToInt()), style = TracklyType.CardItemKcal.copy(color = TracklyColors.Ink))
                    Spacer(Modifier.width(4.dp))
                    BasicText("kcal", style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 2.dp).weight(1f))
                    BasicText(
                        when (period.status) {
                            GoalStatus.InForce -> "En vigueur"
                            GoalStatus.Upcoming -> "À venir"
                            GoalStatus.Past -> ""
                        },
                        style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt),
                    )
                }
                BasicText(macrosText(period.targets), style = TracklyType.CardMacros.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 2.dp))
                BasicText(
                    periodText(period) + if (period.version > 1) " · modifié (version ${period.version})" else "",
                    style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun periodText(period: GoalPeriod): String =
    if (period.until == null) "Depuis le ${DayLabels.short(period.from)}"
    else if (period.until == period.from) "Le ${DayLabels.short(period.from)}"
    else "Du ${DayLabels.short(period.from)} au ${DayLabels.short(period.until)}"

private fun macrosText(targets: GoalTargets): String {
    val parts = listOfNotNull(
        targets.proteinG?.let { "P ${it.roundToInt()} g" },
        targets.carbsG?.let { "G ${it.roundToInt()} g" },
        targets.fatG?.let { "L ${it.roundToInt()} g" },
    )
    return if (parts.isEmpty()) "pas d’objectif de macros" else parts.joinToString("  ·  ")
}

// ───── Éléments communs ─────

private val PanelShape = RoundedCornerShape(12.dp)
private val FieldShape = RoundedCornerShape(10.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(26.dp))
    BasicText(title.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Body(text: String, tag: String? = null) {
    BasicText(
        text,
        style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
        modifier = Modifier.padding(vertical = 3.dp).let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun Line(text: String, strong: Boolean = false) {
    BasicText(
        text,
        style = (if (strong) TracklyType.MealTitle else TracklyType.MealDetail).copy(color = TracklyColors.Ink),
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun NoticeLine(notice: GoalsNotice, actions: GoalsActions) {
    val label = if (notice.error) "À noter : ${notice.text}" else notice.text
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(if (notice.error) TracklyColors.Paper else TracklyColors.Mist)
            .border(TracklySpacing.Hairline, if (notice.error) TracklyColors.Line2 else TracklyColors.Mist, PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(GoalsTags.NOTICE),
    ) {
        BasicText(
            label,
            style = TracklyType.MealDetail.copy(color = if (notice.error) TracklyColors.Ink else TracklyColors.Cobalt),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            notice.undoActionId?.let { id -> TextAction("Annuler ce changement", tag = GoalsTags.UNDO) { actions.undo(id) } }
            TextAction("Fermer", tag = null, onClick = actions.dismissNotice)
        }
    }
}

@Composable
private fun RowScope.Field(
    label: String,
    value: String,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Decimal,
    ime: ImeAction = ImeAction.Next,
    onImeDone: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    Column(Modifier.weight(1f)) {
        BasicText(label, style = TracklyType.DayLabel.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(FieldShape)
                .background(TracklyColors.Paper)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, FieldShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TracklyType.CardItemKcal.copy(color = TracklyColors.Ink),
                cursorBrush = SolidColor(TracklyColors.Cobalt),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ime),
                keyboardActions = KeyboardActions(onDone = { onImeDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.CardButton.copy(color = if (selected) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clip(ButtonShape)
            .background(if (selected) TracklyColors.Cobalt else TracklyColors.Mist)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsButton(text: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.MealTitle.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.97f)
            .clip(ButtonShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.Mist)
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun TextAction(text: String, tag: String?, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .let { if (tag != null) it.testTag(tag) else it }
            .pressable(onClick, pressedScale = 0.94f)
            .heightIn(min = 44.dp)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}
