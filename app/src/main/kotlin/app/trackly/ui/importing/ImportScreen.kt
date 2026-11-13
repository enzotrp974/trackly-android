package app.trackly.ui.importing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.ImportReport
import app.trackly.importing.ImportScreenState
import app.trackly.importing.ImportSummary
import app.trackly.importing.SummaryRow
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test de l'écran de reprise. */
object ImportTags {
    const val SCREEN = "reprise"
}

/** Au-delà, les détails d'un refus sont résumés (« et 12 autres »). */
private const val MAX_DETAILS = 8

/**
 * Reprise de l'historique : invitation, vérification du fichier, puis résumé (journées,
 * produits, plats, objectif actuel, points à signaler) ou refus expliqué.
 */
@Composable
fun ImportScreen(
    state: ImportScreenState,
    onPickFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val p = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, motion.tween(TracklyDurations.HEAD_IN, easing = TracklyEasing.Soft)) }
    BackHandler(enabled = state != ImportScreenState.Working, onBack = onClose)

    Box(modifier.fillMaxSize().testTag(ImportTags.SCREEN).graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }) {
        PorcelainBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = TracklySpacing.Gutter, end = TracklySpacing.Gutter, top = 18.dp, bottom = 28.dp)
                .graphicsLayer { translationY = (1f - p.value) * 12.dp.toPx() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).border(1.5.dp, TracklyColors.Ink, CircleShape))
                Spacer(Modifier.width(8.dp))
                BasicText("Trackly", style = TracklyType.Brand.copy(color = TracklyColors.Ink))
            }
            Spacer(Modifier.height(44.dp))
            when (state) {
                ImportScreenState.Invite -> Invite(onPickFile, onClose)
                ImportScreenState.Working -> Working()
                is ImportScreenState.Result -> when (val outcome = state.outcome) {
                    is ImportOutcome.Imported -> Summary("Historique repris", "C’est fait.", outcome.report, onClose)
                    is ImportOutcome.NothingNew -> Summary(
                        kicker = "Déjà à jour",
                        title = "Rien de nouveau.",
                        report = outcome.report,
                        onClose = onClose,
                        note = "Tout ce fichier est déjà dans l’app : rien n’a été écrit.",
                    )
                    is ImportOutcome.Refused -> Refused(outcome, onPickFile, onClose)
                }
            }
        }
    }
}

@Composable
private fun Title(kicker: String, title: String) {
    BasicText(kicker.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
    Spacer(Modifier.height(10.dp))
    BasicText(
        title,
        style = TracklyType.Hint.copy(color = TracklyColors.Ink),
        modifier = Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = TracklyType.HintBody.copy(color = TracklyColors.Ink2), modifier = modifier)
}

@Composable
private fun Invite(onPickFile: () -> Unit, onClose: () -> Unit) {
    Title("Reprise", "Reprends ton historique")
    Body("Fichier trackly-export.json. Ce que tu as déjà saisi n’est pas remplacé.")
    Spacer(Modifier.height(30.dp))
    PrimaryButton("Choisir le fichier d’export", onPickFile)
    Spacer(Modifier.height(6.dp))
    TextButton("Plus tard", onClose)
}

@Composable
private fun Working() {
    Title("Reprise", "Vérification…")
    Body("Lecture du fichier et contrôle des totaux de chaque journée.")
    Spacer(Modifier.height(28.dp))
    ProgressLine()
}

/** Filet cobalt qui parcourt la ligne ; immobile si les mouvements sont réduits. */
@Composable
private fun ProgressLine() {
    val motion = LocalMotion.current
    val x = if (motion.reduced) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "progression")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
        value
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(TracklyColors.TrackEmpty)
            .drawBehind {
                val w = size.width * 0.3f
                drawRect(TracklyColors.Cobalt, topLeft = Offset((size.width + w) * x - w, 0f), size = Size(w, size.height))
            },
    )
}

@Composable
private fun Summary(kicker: String, title: String, report: ImportReport, onClose: () -> Unit, note: String? = null) {
    Title(kicker, title)
    if (note != null) {
        Body(note)
        Spacer(Modifier.height(14.dp))
    }
    val rows = ImportSummary.rows(report)
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(CardShape, radius = 30.dp, spread = (-20).dp, y = 14.dp, alpha = 0.22f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line, CardShape)
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Hairline()
            SummaryLine(row)
        }
    }
    if (report.anomalies.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        BasicText("À SIGNALER", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
        Spacer(Modifier.height(8.dp))
        Details(report.anomalies)
    }
    Spacer(Modifier.height(30.dp))
    PrimaryButton("Continuer", onClose)
}

private val CardShape = RoundedCornerShape(TracklySpacing.SheetRadius)

@Composable
private fun SummaryLine(row: SummaryRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(row.label, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink))
            row.detail?.let {
                Spacer(Modifier.height(3.dp))
                BasicText(it, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
            }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(row.value, style = TracklyType.MealKcal.copy(color = TracklyColors.Ink))
    }
}

@Composable
private fun Details(lines: List<String>) {
    val shown = lines.take(MAX_DETAILS)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in shown) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp).size(4.dp).clip(CircleShape).background(TracklyColors.Cobalt))
                Spacer(Modifier.width(10.dp))
                BasicText(line, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
            }
        }
        if (lines.size > shown.size) {
            BasicText("et ${lines.size - shown.size} autres", style = TracklyType.MealEmpty.copy(color = TracklyColors.Ink3))
        }
    }
}

@Composable
private fun Refused(outcome: ImportOutcome.Refused, onPickFile: () -> Unit, onClose: () -> Unit) {
    Title("Fichier refusé", "Rien n’a été repris.")
    Body(outcome.reason + ".")
    if (outcome.details.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Details(outcome.details)
    }
    Spacer(Modifier.height(30.dp))
    PrimaryButton("Choisir un autre fichier", onPickFile)
    Spacer(Modifier.height(6.dp))
    TextButton("Fermer", onClose)
}

private val ButtonShape = RoundedCornerShape(14.dp)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(TracklySpacing.AskHeight)
            .softShadow(ButtonShape, radius = 24.dp, spread = (-12).dp, y = 12.dp, alpha = 0.5f, cobalt = true)
            .pressable(onClick, pressedScale = 0.98f)
            .clip(ButtonShape)
            .background(TracklyColors.Cobalt),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TracklyType.AskPlaceholder.copy(color = TracklyColors.PaperHi, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressable(onClick, pressedScale = 0.97f),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TracklyType.AskPlaceholder.copy(color = TracklyColors.Ink2))
    }
}
