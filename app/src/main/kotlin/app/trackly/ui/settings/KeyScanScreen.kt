package app.trackly.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.keyscan.KeyScanState
import app.trackly.ui.chat.PillButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Étiquettes de test de l'écran de scan. */
object KeyScanTags {
    const val SCREEN = "scan-cle"
    const val VIEWFINDER = "scan-cle-viseur"
    const val STATUS = "scan-cle-etat"
    const val ALLOW = "scan-cle-autoriser"
    const val SETTINGS = "scan-cle-parametres"
    const val RETRY = "scan-cle-reessayer"
    const val LATER = "scan-cle-plus-tard"
    const val DONE = "scan-cle-terminer"
}

/** Où en est l'autorisation de l'appareil photo. */
enum class CameraAccess {
    /** Pas encore demandée : on explique d'abord pourquoi, puis le système pose sa question. */
    Unasked,

    /** Refusée une fois : le système acceptera de reposer la question. */
    Denied,

    /** Refusée pour de bon : seuls les paramètres de l'app permettent de revenir dessus. */
    Blocked,

    Granted,
}

private val ViewfinderShape = RoundedCornerShape(22.dp)

/**
 * Réglages › Clé OpenAI › Scanner un QR code. Écran sans logique : [viewfinder] reçoit l'image de
 * l'appareil photo (CameraX en vrai, un aplat dans les captures).
 */
@Composable
fun KeyScanScreen(
    access: CameraAccess,
    scan: KeyScanState,
    onAllow: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewfinder: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize().testTag(KeyScanTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(TracklySpacing.BackSize)
                        .pressable(onClose, pressedScale = 0.86f)
                        .semantics { contentDescription = "Fermer le scan" },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp))
                }
                BasicText(
                    "Scanner un QR code",
                    style = TracklyType.SettingsTitle.copy(color = TracklyColors.Ink),
                    modifier = Modifier.padding(start = 6.dp).semantics { heading() },
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = TracklySpacing.Gutter, vertical = 12.dp),
            ) {
                when (access) {
                    CameraAccess.Granted -> Scanning(scan, onRetry, onClose, viewfinder)
                    CameraAccess.Unasked -> Message(
                        title = "L’appareil photo sert à lire le QR code",
                        body = "Trackly lit le QR code que ton ordinateur affiche, directement sur ce téléphone. " +
                            "Aucune image n’est enregistrée ni envoyée : seule la clé lue est gardée, chiffrée.",
                    ) {
                        TaggedPill("Autoriser l’appareil photo", true, KeyScanTags.ALLOW, onAllow)
                        TaggedPill("Plus tard", false, KeyScanTags.LATER, onClose)
                    }
                    CameraAccess.Denied -> Message(
                        title = "Sans l’appareil photo, pas de scan",
                        body = "Tu peux autoriser l’appareil photo, ou coller ta clé à la main : les deux marchent pareil.",
                    ) {
                        TaggedPill("Réessayer", true, KeyScanTags.RETRY, onAllow)
                        TaggedPill("Coller la clé", false, KeyScanTags.LATER, onClose)
                    }
                    CameraAccess.Blocked -> Message(
                        title = "L’appareil photo est bloqué",
                        body = "Android ne demande plus l’autorisation. Ouvre les paramètres de Trackly, touche « Autorisations », " +
                            "puis « Appareil photo » et « Autoriser seulement si l’appli est en cours d’utilisation ». Sinon, colle ta clé.",
                    ) {
                        TaggedPill("Ouvrir les paramètres", true, KeyScanTags.SETTINGS, onOpenAppSettings)
                        TaggedPill("Coller la clé", false, KeyScanTags.LATER, onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaggedPill(text: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.testTag(tag)) { PillButton(text, primary, onClick) }
}

@Composable
private fun Message(title: String, body: String, buttons: @Composable () -> Unit) {
    BasicText(title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    BasicText(body, style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
}

/** Viseur carré et consignes, pendant la lecture. */
@Composable
private fun Scanning(scan: KeyScanState, onRetry: () -> Unit, onClose: () -> Unit, viewfinder: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(280.dp)
                .testTag(KeyScanTags.VIEWFINDER)
                .semantics { contentDescription = "Image de l’appareil photo, pour viser le QR code" }
                .clip(ViewfinderShape)
                .background(TracklyColors.Ink),
        ) {
            if (scan !is KeyScanState.Saved) viewfinder()
            Corners(if (scan is KeyScanState.Saved) TracklyColors.PaperHi else Color.White.copy(alpha = 0.92f))
            if (scan is KeyScanState.Saved) {
                Box(Modifier.fillMaxSize().background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(64.dp), stroke = 2f)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val (title, detail) = when (scan) {
        KeyScanState.Scanning -> "Vise le QR code de ton ordinateur." to "Il se lit tout seul, sans bouton, à environ 30 cm."
        is KeyScanState.Rejected -> scan.message to null
        KeyScanState.Saving -> "Clé lue, enregistrement…" to null
        KeyScanState.Saved -> "Clé enregistrée." to "Tu peux effacer le QR code du Terminal."
        is KeyScanState.Failed -> scan.message to null
    }
    Column(Modifier.fillMaxWidth().testTag(KeyScanTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite }) {
        BasicText(title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            BasicText(detail, style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2))
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (scan) {
            KeyScanState.Saved -> TaggedPill("Terminer", true, KeyScanTags.DONE, onClose)
            is KeyScanState.Failed -> {
                TaggedPill("Réessayer", true, KeyScanTags.RETRY, onRetry)
                TaggedPill("Coller la clé", false, KeyScanTags.LATER, onClose)
            }
            KeyScanState.Saving -> Unit
            else -> TaggedPill("Coller la clé", false, KeyScanTags.LATER, onClose)
        }
    }
}

/** Quatre coins, comme un viseur. */
@Composable
private fun BoxScope.Corners(color: Color) {
    Canvas(Modifier.matchParentSize()) {
        val inset = 22.dp.toPx()
        val arm = 34.dp.toPx()
        val width = 4.dp.toPx()
        val (l, t, r, b) = listOf(inset, inset, size.width - inset, size.height - inset)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), width, StrokeCap.Round)
        line(l, t, l + arm, t); line(l, t, l, t + arm)
        line(r, t, r - arm, t); line(r, t, r, t + arm)
        line(l, b, l + arm, b); line(l, b, l, b - arm)
        line(r, b, r - arm, b); line(r, b, r, b - arm)
    }
}
