package app.trackly.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trackly.day.DayLabels
import app.trackly.day.DayView
import app.trackly.ui.components.ArrowButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import java.time.LocalDate
import java.time.YearMonth

/** Étiquettes de test du calendrier. */
object CalendarTags {
    const val PANEL = "calendrier"
}

private val CardShape = RoundedCornerShape(TracklySpacing.SheetRadius)
private val WEEKDAYS = listOf("L", "M", "M", "J", "V", "S", "D")

/** Durée d'ouverture du calendrier (même ressort que les entrées du prototype). */
private const val CALENDAR_IN = 420

/**
 * Calendrier pour aller plus loin que le glissement : un mois à la fois, les jours suivis
 * marqués d'un point, le jour affiché plein cobalt, aujourd'hui cerclé. Seuls les jours de
 * l'historique (du premier jour enregistré à aujourd'hui) sont accessibles.
 */
@Composable
fun DayCalendar(
    days: List<DayView>,
    selected: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lien discret vers la reprise de l'historique (null = masqué). */
    onImportHistory: (() -> Unit)? = null,
) {
    val motion = LocalMotion.current
    val first = days.first().date
    val last = days.last().date
    val withData = remember(days) { days.filter { it.hasData }.mapTo(HashSet()) { it.date } }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val shown = YearMonth.parse(month)
    val p = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, motion.tween(CALENDAR_IN, easing = TracklyEasing.Spring)) }
    BackHandler(onBack = onClose)

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
            .background(TracklyColors.Ink.copy(alpha = 0.16f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "Fermer le calendrier" },
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = 48.dp, start = TracklySpacing.DockGutter, end = TracklySpacing.DockGutter)
                .fillMaxWidth()
                .graphicsLayer {
                    val v = p.value
                    translationY = (1f - v) * -10.dp.toPx()
                    val s = 0.97f + 0.03f * v
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .softShadow(CardShape, radius = 36.dp, spread = (-18).dp, y = 18.dp, alpha = 0.3f)
                .clip(CardShape)
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line, CardShape)
                // Toucher la carte ne la ferme pas.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .testTag(CalendarTags.PANEL)
                .semantics { paneTitle = "Calendrier" }
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    DayLabels.month(shown),
                    style = TracklyType.MealTitle.copy(color = TracklyColors.Ink),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                ArrowButton(Glyph.ChevronLeft, "Mois précédent", shown > YearMonth.from(first)) { month = shown.minusMonths(1).toString() }
                Spacer(Modifier.size(8.dp))
                ArrowButton(Glyph.ChevronRight, "Mois suivant", shown < YearMonth.from(last)) { month = shown.plusMonths(1).toString() }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                for (initial in WEEKDAYS) {
                    BasicText(
                        initial,
                        style = TracklyType.MacroLabel.copy(color = TracklyColors.Ink3, textAlign = TextAlign.Center),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            MonthGrid(shown, selected, today, first, last, withData, onPick)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    "Aujourd’hui",
                    style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
                    modifier = Modifier
                        .heightIn(min = TracklySpacing.TouchTarget)
                        .pressable({ onPick(today) }, pressedScale = 0.95f)
                        .padding(horizontal = 4.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                if (onImportHistory != null) {
                    BasicText(
                        "Reprendre un historique",
                        style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink3),
                        modifier = Modifier
                            .heightIn(min = TracklySpacing.TouchTarget)
                            .pressable(onImportHistory, pressedScale = 0.95f)
                            .padding(horizontal = 4.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    first: LocalDate,
    last: LocalDate,
    withData: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
) {
    val start = month.atDay(1)
    val offset = start.dayOfWeek.value - 1
    val cells = offset + month.lengthOfMonth()
    val weeks = (cells + 6) / 7
    Column {
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayOfMonth = week * 7 + column - offset + 1
                    Box(Modifier.weight(1f).height(TracklySpacing.TouchTarget), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayOfMonth)
                            DayCell(
                                date = date,
                                selected = date == selected,
                                today = date == today,
                                enabled = !date.isBefore(first) && !date.isAfter(last),
                                hasData = date in withData,
                                onPick = onPick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, enabled: Boolean, hasData: Boolean, onPick: (LocalDate) -> Unit) {
    val color = when {
        selected -> TracklyColors.PaperHi
        enabled -> TracklyColors.Ink
        else -> TracklyColors.Ink3
    }
    // Zone tactile de 48 dp ; le disque dessiné reste de 38 dp.
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { onPick(date) }
            .semantics {
                contentDescription = DayLabels.format(date) + if (hasData) ", suivi" else ""
                this.selected = selected
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(TracklyColors.Cobalt) else Modifier)
            .then(if (today && !selected) Modifier.border(1.dp, TracklyColors.Cobalt, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(date.dayOfMonth.toString(), style = TracklyType.DayLabel.copy(color = color, textAlign = TextAlign.Center))
        if (hasData) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(if (selected) TracklyColors.PaperHi else TracklyColors.Cobalt),
            )
        }
    }
    }
}
