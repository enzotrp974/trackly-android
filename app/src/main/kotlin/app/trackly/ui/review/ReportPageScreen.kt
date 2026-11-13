package app.trackly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.core.review.WeekRange
import app.trackly.review.ReportFormat
import app.trackly.review.page.Advice
import app.trackly.review.page.Headline
import app.trackly.review.page.Point
import app.trackly.review.page.PointStatus
import app.trackly.review.page.ReportPage
import app.trackly.review.page.Tone
import app.trackly.review.page.WeightSection
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import java.time.LocalDate

object ReportPageTags {
    const val SCREEN = "bilan-page"
    const val VERDICT = "bilan-verdict"
    const val ARRIVAL = "bilan-arrivee"
    const val MARGIN = "bilan-marge"
    const val NOTE = "bilan-mot"
    const val POINT = "bilan-point"
    const val ASK = "bilan-poser-question"
    const val ADVICE = "bilan-conseil"
    const val REFERENCE = "bilan-poids-reference"
    const val HOW = "bilan-comment-calcule"
}

/**
 * La page du bilan : verdict et date en haut, courbe du poids, mot de Trackly, points de la
 * période (une ligne quand ça va, un détail avec graphique quand ça coince), puis les conseils et le poids de référence
 * quand ils existent, et « Comment la date est calculée ». « Poser une question » reste collé en bas et ouvre le chat du bilan.
 */
@Composable
fun ReportPageScreen(
    page: ReportPage,
    onBack: () -> Unit,
    /** Ouvre le chat du bilan ; `null` : pas de bouton (un bilan d'une autre période que celle en cours : le chat n'est pas le sien). */
    onAsk: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Bouton Annuler du poids de référence ; `null` : bloc sans bouton. */
    reference: ReferenceUi? = null,
    /** Boutons des conseils ; `null` : conseils montrés sans bouton. */
    advice: AdviceUi? = null,
) {
    // Le bouton collé en bas masque le pied de page : le défilement réserve sa hauteur réelle (barre de navigation et grande police comprises).
    var dockHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val days = (LocalDate.parse(page.periodEnd).toEpochDay() - LocalDate.parse(page.periodStart).toEpochDay() + 1).toInt()
    Box(modifier.fillMaxSize().background(TracklyColors.Paper).testTag(ReportPageTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize()) {
            val start = LocalDate.parse(page.periodStart)
            val period = if (days > 1) "Du ${ReportFormat.weekLabel(WeekRange(start, LocalDate.parse(page.periodEnd)))} · $days jours" else "Le ${ReportFormat.dayMonthYear(start)} · 1 jour"
            ScreenHeader("Ton bilan", period, onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = if (onAsk != null) dockHeight + 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Hero(page.headline)
                WeightBlock(page.weight)
                if (page.note.isNotBlank()) Note(page.note)
                if (page.points.isNotEmpty()) PointsBlock(page.points)
                if (page.advice.isNotEmpty()) AdviceBlock(page.advice, advice)
                Column(Modifier.padding(horizontal = TracklySpacing.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    page.referenceWeight?.let { ReferenceBlock(it, reference) }
                    if (page.howItWorks.isNotEmpty()) HowItWorks(page.howItWorks)
                }
            }
        }
        if (onAsk != null) AskDock(onAsk, onHeight = { dockHeight = with(density) { it.toDp() } })
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
}

@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = modifier.semantics { heading() })
}

// ───── Verdict et date ─────

@Composable
private fun Hero(h: Headline) {
    Column(Modifier.padding(horizontal = TracklySpacing.Gutter).padding(top = 4.dp)) {
        Kicker("VERDICT")
        Spacer(Modifier.height(8.dp))
        BasicText(h.verdict, style = TracklyType.ReportVerdict.copy(color = TracklyColors.Ink), modifier = Modifier.testTag(ReportPageTags.VERDICT))
        Spacer(Modifier.height(14.dp))
        BasicText(
            sentence(h),
            style = TracklyType.ReportSentence.copy(color = TracklyColors.Ink2),
        )
        Spacer(Modifier.height(18.dp))
        Hairline()
        if (LocalDensity.current.fontScale >= STACK_FROM_FONT_SCALE) {
            // Police très grande : trois colonnes ne tiennent plus, les chiffres s'empilent, un par ligne.
            StripRow(h.arrivalLabel, h.arrival, null, false, Modifier.testTag(ReportPageTags.ARRIVAL))
            Hairline()
            StripRow("OBJECTIF", h.target, null, false, Modifier)
            if (h.margin != null) {
                Hairline()
                StripRow("MARGE", h.margin, h.marginUnit, h.marginLate, Modifier.testTag(ReportPageTags.MARGIN))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StripCell(h.arrivalLabel, h.arrival, null, false, Modifier.weight(1f).testTag(ReportPageTags.ARRIVAL))
                Box(Modifier.fillMaxHeight().width(TracklySpacing.Hairline).padding(vertical = 14.dp).background(TracklyColors.Line))
                StripCell("OBJECTIF", h.target, null, false, Modifier.weight(1f).padding(start = 14.dp))
                if (h.margin != null) {
                    Box(Modifier.fillMaxHeight().width(TracklySpacing.Hairline).padding(vertical = 14.dp).background(TracklyColors.Line))
                    StripCell("MARGE", h.margin, h.marginUnit, h.marginLate, Modifier.weight(1f).padding(start = 14.dp).testTag(ReportPageTags.MARGIN))
                }
            }
        }
        Hairline()
        h.range?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TracklyType.ReportPointDesc.copy(color = TracklyColors.Ink2))
        }
    }
}

private fun sentence(h: Headline) = buildAnnotatedString {
    val bold = h.sentenceBold
    val at = bold?.let { h.sentence.indexOf(it) } ?: -1
    if (bold == null || at < 0) {
        append(h.sentence)
    } else {
        append(h.sentence.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TracklyColors.Ink)) { append(bold) }
        append(h.sentence.substring(at + bold.length))
    }
}

/** À partir de cette taille de police, le bandeau « arrivée, objectif, marge » passe de trois colonnes à trois lignes. */
private const val STACK_FROM_FONT_SCALE = 1.5f

@Composable
private fun StripRow(label: String, value: String, unit: String?, late: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TracklyType.Kicker.copy(color = TracklyColors.Ink2))
            if (late) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(TracklyColors.Warn))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(value, style = TracklyType.ReportStripValue.copy(color = TracklyColors.Ink))
            if (unit != null) BasicText(unit, style = TracklyType.ReportPointDesc.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(start = 3.dp, bottom = 1.dp))
        }
    }
}

@Composable
private fun StripCell(label: String, value: String, unit: String?, late: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TracklyType.Kicker.copy(color = TracklyColors.Ink2))
            // Une pastille jaune devant « MARGE » : un retard se voit sans colorer le chiffre.
            if (late) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(TracklyColors.Warn))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(value, style = TracklyType.ReportStripValue.copy(color = TracklyColors.Ink))
            if (unit != null) BasicText(unit, style = TracklyType.ReportPointDesc.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(start = 3.dp, bottom = 1.dp))
        }
    }
}

// ───── Courbe du poids ─────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeightBlock(w: WeightSection) {
    Column(Modifier.padding(horizontal = TracklySpacing.Gutter)) {
        Kicker("TON POIDS")
        Spacer(Modifier.height(10.dp))
        val chart = w.chart
        if (chart == null) {
            BasicText(w.emptyNote ?: "", style = TracklyType.ReportPointDesc.copy(color = TracklyColors.Ink2))
        } else {
            WeightChartView(chart)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Swatch("toi", TracklyColors.Cobalt, 2.dp, false)
                if (chart.planned.isNotEmpty()) Swatch("prévu", TracklyColors.Ink3, 1.5.dp, false)
                if (chart.projection.isNotEmpty()) Swatch("à ce rythme", if (chart.late) TracklyColors.Warn else TracklyColors.Cobalt, 1.5.dp, true)
                Swatch("objectif", TracklyColors.Ink, 1.5.dp, false, ring = true)
            }
        }
    }
}

@Composable
private fun Swatch(text: String, color: androidx.compose.ui.graphics.Color, thickness: androidx.compose.ui.unit.Dp, dashed: Boolean, ring: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (ring) {
            Box(Modifier.size(9.dp).border(1.6.dp, color, CircleShape))
        } else if (dashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(3) { Box(Modifier.size(width = 4.dp, height = thickness).background(color)) } }
        } else {
            Box(Modifier.size(width = 16.dp, height = thickness).background(color))
        }
        BasicText(text, style = chartLabelStyle().copy(color = TracklyColors.Ink2))
    }
}

// ───── Mot de Trackly ─────

@Composable
private fun Note(text: String) {
    Row(Modifier.padding(horizontal = TracklySpacing.Gutter).height(IntrinsicSize.Min).testTag(ReportPageTags.NOTE)) {
        Box(Modifier.width(4.dp).fillMaxSize().background(TracklyColors.Cobalt))
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (paragraph in text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
                BasicText(paragraph, style = TracklyType.ReportNote.copy(color = TracklyColors.Ink))
            }
        }
    }
}

// ───── Points de la période ─────

@Composable
private fun PointsBlock(points: List<Point>) {
    Column(Modifier.padding(horizontal = TracklySpacing.Gutter)) {
        Kicker("TA PÉRIODE")
        Spacer(Modifier.height(6.dp))
        Hairline()
        for (point in points) {
            PointRow(point)
            Hairline()
        }
    }
}

@Composable
private fun PointRow(p: Point) {
    val description = "${p.name}, ${p.desc}, ${p.value} ${p.unit}".trim() + when (p.status) {
        PointStatus.Warn -> ". À regarder."
        PointStatus.Info -> ". À savoir."
        else -> ""
    }
    Column(Modifier.fillMaxWidth().testTag(ReportPageTags.POINT)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusMark(p.status)
            Spacer(Modifier.width(12.dp))
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TracklyColors.Ink)) { append(p.name) }
                    append("  ")
                    withStyle(SpanStyle(color = TracklyColors.Ink2)) { append(p.desc) }
                },
                style = TracklyType.ReportPointDesc,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(p.value, style = TracklyType.ReportPointValue.copy(color = TracklyColors.Ink))
                if (p.unit.isNotEmpty()) BasicText(p.unit, style = TracklyType.ReportChartLabel.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(start = 3.dp, bottom = 1.dp))
            }
        }
        if (p.detail != null || p.chart != null) {
            Column(Modifier.padding(start = 34.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                p.detail?.let { BasicText(it, style = TracklyType.ReportSentence.copy(color = TracklyColors.Ink)) }
                p.chart?.let { PointChartView(it) }
            }
        }
    }
}

/** Pastille de statut : coche cerclée (ok), coche pleine (top), « ! » jaune (à regarder), « i » encre (à savoir), point (neutre). */
@Composable
private fun StatusMark(status: PointStatus) {
    val size = 22.dp
    // Le cercle est en dp : son glyphe aussi, pour qu'une grande police ne le fasse pas déborder.
    val glyph = with(LocalDensity.current) { TracklyType.CardStatus.copy(fontSize = 13.dp.toSp(), lineHeight = 13.dp.toSp(), fontWeight = FontWeight.ExtraBold) }
    when (status) {
        PointStatus.Ok -> Box(Modifier.requiredSize(size).border(1.4.dp, TracklyColors.Cobalt, CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Check, TracklyColors.Cobalt, Modifier.size(12.dp), stroke = 2f)
        }
        PointStatus.Top -> Box(Modifier.requiredSize(size).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.2f)
        }
        PointStatus.Warn -> Box(Modifier.requiredSize(size).clip(CircleShape).background(TracklyColors.Warn), contentAlignment = Alignment.Center) {
            BasicText("!", style = glyph.copy(color = TracklyColors.PaperHi))
        }
        PointStatus.Info -> Box(Modifier.requiredSize(size).border(1.4.dp, TracklyColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
            BasicText("i", style = glyph.copy(color = TracklyColors.Ink))
        }
        PointStatus.Neutral -> Box(Modifier.requiredSize(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(TracklyColors.Ink3))
        }
    }
}

// ───── Conseils : emplacement, sans bouton ; le poids de référence est dans ReferenceViews.kt ─────

@Composable
private fun AdviceBlock(advice: List<Advice>, ui: AdviceUi?) {
    Column(Modifier.padding(horizontal = TracklySpacing.Gutter).testTag(ReportPageTags.ADVICE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kicker("CE QUE JE TE PROPOSE")
        AdviceList(advice, ui)
    }
}

// ───── Comment la date est calculée ─────

@Composable
private fun HowItWorks(paragraphs: List<String>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(ReportPageTags.HOW)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TracklySpacing.TouchTarget)
                .pressable({ open = !open }, pressedScale = 0.99f, onClickLabel = if (open) "Replier" else "Déplier"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Comment la date est calculée", style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt), modifier = Modifier.weight(1f))
            GlyphIcon(Glyph.ChevronRight, TracklyColors.Cobalt, Modifier.size(16.dp).rotate(if (open) 90f else 0f), stroke = 1.6f)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                for (p in paragraphs) BasicText(p, style = TracklyType.ReportPointDesc.copy(color = TracklyColors.Ink2))
            }
        }
    }
}

// ───── « Poser une question », collé en bas ─────

@Composable
private fun BoxScope.AskDock(onAsk: () -> Unit, onHeight: (Int) -> Unit) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(Brush.verticalGradient(0f to TracklyColors.PaperHi.copy(alpha = 0f), 0.35f to TracklyColors.PaperHi.copy(alpha = 0.95f), 1f to TracklyColors.PaperHi.copy(alpha = 0.95f)))
            .navigationBarsPadding()
            .padding(start = TracklySpacing.DockGutter, end = TracklySpacing.DockGutter, top = 18.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TracklySpacing.AskHeight)
                .testTag(ReportPageTags.ASK)
                .pressable(onAsk, pressedScale = 0.98f, onClickLabel = "Ouvrir le chat du bilan")
                .clip(RoundedCornerShape(14.dp))
                .background(TracklyColors.Cobalt)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Chat, TracklyColors.PaperHi, Modifier.size(20.dp), stroke = 1.6f)
            Spacer(Modifier.width(10.dp))
            BasicText("Poser une question", style = TracklyType.CardButton.copy(color = TracklyColors.PaperHi))
        }
    }
}
