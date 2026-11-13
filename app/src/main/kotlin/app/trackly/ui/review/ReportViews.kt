package app.trackly.ui.review

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.trackly.review.ReportFormat
import app.trackly.review.ReportInfo
import app.trackly.review.StoredReport
import app.trackly.review.page.ReportContent
import app.trackly.review.page.ReportPage
import app.trackly.core.time.TrackingDay
import app.trackly.ui.chat.PillButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

object ReportTags {
    const val LIST = "bilans-liste"
    const val ROW = "bilans-ligne"
    const val EMPTY = "bilans-vide"
    const val START = "bilans-faire"
    const val VIEWER = "bilan-rapport"
    const val MISSING = "bilan-rapport-introuvable"
}

@Composable
internal fun ScreenHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 14.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TracklySpacing.BackSize).pressable(onBack, pressedScale = 0.86f).semantics { contentDescription = "Retour" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp)) }
        Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(title, style = TracklyType.ChatTitle.copy(color = TracklyColors.Ink), modifier = Modifier.semantics { heading() })
            if (subtitle != null) BasicText(subtitle, style = TracklyType.ChatDate.copy(color = TracklyColors.Ink2))
        }
    }
}

/** Liste des bilans passés, le plus récent d'abord ; un toucher ouvre le rapport. */
@Composable
fun ReportsScreen(
    reports: List<ReportInfo>,
    onOpen: (Long) -> Unit,
    onStartReview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(ReportTags.LIST)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Mes bilans", null, onBack)
            if (reports.isEmpty()) {
                Column(Modifier.padding(horizontal = TracklySpacing.Gutter, vertical = 24.dp).testTag(ReportTags.EMPTY)) {
                    BasicText("Aucun bilan pour l’instant.", style = TracklyType.Hint.copy(color = TracklyColors.Ink))
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.size(36.dp, 1.dp).background(TracklyColors.Cobalt))
                    Spacer(Modifier.height(14.dp))
                    BasicText(
                        "Le premier arrive après ta conversation du lundi. Tu peux aussi la commencer quand tu veux.",
                        style = TracklyType.HintBody.copy(color = TracklyColors.Ink2),
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.testTag(ReportTags.START)) { PillButton("Faire mon bilan maintenant", primary = true, onClick = onStartReview) }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = TracklySpacing.Gutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(reports, key = { it.id }) { report -> ReportRow(report, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(report: ReportInfo, onOpen: (Long) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val week = ReportFormat.weekLabel(report.period)
    val produced = report.generatedAt.atZone(TrackingDay.ZONE).toLocalDate()
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ReportTags.ROW)
            .pressable({ onOpen(report.id) }, pressedScale = 0.98f)
            .semantics { role = Role.Button; contentDescription = "Bilan du $week, produit le ${ReportFormat.dayMonthYear(produced)}" }
            .clip(shape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText("Du $week", style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
            BasicText("Produit le ${ReportFormat.dayMonthYear(produced)}", style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
        }
        GlyphIcon(Glyph.ChevronRight, TracklyColors.Ink3, Modifier.size(17.dp))
    }
}

/** Réglages du rapport affiché : aucun JavaScript, aucun accès au réseau, aucune navigation. */
@SuppressLint("SetJavaScriptEnabled")
fun configureReportWebView(webView: WebView) {
    with(webView.settings) {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        domStorageEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
    webView.webViewClient = object : WebViewClient() {
        // Aucun lien n'est suivi, aucune ressource n'est chargée : le rapport est autonome.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.url.scheme == "about" || request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)
    }
    webView.setBackgroundColor(TracklyColors.Paper.toArgb())
}

/** Le rapport HTML du bilan dans un WebView sans JavaScript ni réseau. */
@Composable
fun ReportWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize().testTag(ReportTags.VIEWER),
        factory = { context ->
            WebView(context).also { view ->
                configureReportWebView(view)
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

/**
 * Un bilan ouvert. Un bilan produit depuis la 1.3 est une page ([ReportPage], dessinée ici) ; un ancien bilan est un HTML,
 * qui garde son rendu dans [viewer] (WebView par défaut ; remplaçable dans les tests d'écran).
 */
@Composable
fun ReportScreen(
    report: StoredReport?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAsk: (() -> Unit)? = null,
    viewer: @Composable (String) -> Unit = { ReportWebView(it) },
    /** Boutons des conseils, lus pour la page décodée ; `null` : sans bouton. */
    advice: (@Composable (app.trackly.review.page.ReportPage) -> AdviceUi?)? = null,
    /** Bouton Annuler du poids de référence de la page ; `null` par défaut (aperçus, tests sans moteur). */
    reference: @Composable (ReportPage) -> ReferenceUi? = { null },
) {
    val page = remember(report) { report?.let { ReportContent.decode(it.html) } }
    if (report != null && page != null) {
        ReportPageScreen(page, onBack = onBack, onAsk = onAsk, modifier = modifier, advice = advice?.invoke(page), reference = reference(page))
        return
    }
    Box(modifier.fillMaxSize().background(TracklyColors.Paper)) {
        Column(Modifier.fillMaxSize()) {
            val week = report?.let { ReportFormat.weekLabel(it.info.period) }
            ScreenHeader("Bilan", week, onBack)
            Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                if (report == null) {
                    BasicText(
                        "Ce bilan est introuvable.",
                        style = TracklyType.HintBody.copy(color = TracklyColors.Ink),
                        modifier = Modifier.padding(TracklySpacing.Gutter).testTag(ReportTags.MISSING),
                    )
                } else if (ReportContent.isPage(report.html)) {
                    // Une page enregistrée par une version plus récente, ou abîmée : jamais affichée comme du HTML.
                    BasicText(
                        "Ce bilan n’a pas pu être lu : il vient peut-être d’une version plus récente de Trackly. Mets l’application à jour, puis rouvre-le.",
                        style = TracklyType.HintBody.copy(color = TracklyColors.Ink),
                        modifier = Modifier.padding(TracklySpacing.Gutter).testTag(ReportTags.MISSING),
                    )
                } else {
                    viewer(report.html)
                }
            }
        }
    }
}
