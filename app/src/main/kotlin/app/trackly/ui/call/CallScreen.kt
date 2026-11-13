package app.trackly.ui.call

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.graphicsLayer
import app.trackly.ui.theme.TracklyDurations
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trackly.call.AudioRoute
import app.trackly.call.CallPhase
import app.trackly.call.CallUi
import app.trackly.call.live.AudioLevels
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Étiquettes de test de l'écran d'appel. */
object CallTags {
    const val SCREEN = "appel"
    const val STATUS = "appel-etat"
    const val TIMER = "appel-duree"
    const val WAVES = "appel-vagues"
    const val MUTE = "appel-muet"
    const val SPEAKER = "appel-haut-parleur"
    const val HANG_UP = "appel-raccrocher"
    const val CLOSE = "appel-fermer"
}

/**
 * Écran plein de l'appel, dans le style de l'app : fond porcelaine, encre et cobalt. En haut la durée et une
 * phrase d'état (« Connexion… », « Je t'écoute », « Je réfléchis… »), au centre les vagues qui suivent la voix, en bas
 * trois boutons : muet (micro), haut-parleur (actif par défaut) et raccrocher. Aucun réglage d'écho ici : il est dans
 * Réglages › Appel. L'appel fini, un message et « Fermer ».
 */
@Composable
fun CallScreen(
    ui: CallUi,
    levels: State<AudioLevels>,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(
        modifier
            .fillMaxSize()
            .testTag(CallTags.SCREEN),
    ) {
        PorcelainBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = TracklySpacing.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            BasicText(
                "APPEL AVEC TRACKLY",
                style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(14.dp))
            val live = ui.phase == CallPhase.Live || ui.phase == CallPhase.Ending
            // Un appel refusé ou qui n'a pas démarré n'a pas de durée : la place reste, vide, pour que rien ne saute.
            val shown = live || ui.phase == CallPhase.Connecting || ui.phase == CallPhase.Idle || ui.elapsedMs > 0
            BasicText(
                if (shown) clock(if (live || ui.phase == CallPhase.Ended) ui.elapsedMs else 0L) else " ",
                style = TracklyType.CallTimer.copy(color = if (live) TracklyColors.Ink else TracklyColors.Ink3),
                modifier = Modifier.testTag(CallTags.TIMER).then(
                    if (shown) Modifier.semantics { contentDescription = "Durée ${spokenClock(ui.elapsedMs)}" } else Modifier.clearAndSetSemantics {},
                ),
            )
            Spacer(Modifier.height(8.dp))
            val status = statusText(ui)
            BasicText(
                status,
                style = TracklyType.CallStatus.copy(color = if (ui.phase == CallPhase.Ended) TracklyColors.Ink else TracklyColors.Ink2, textAlign = TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CallTags.STATUS)
                    // Annoncée hors conversation seulement (connexion, fin) : pendant l'appel, TalkBack parlerait par-dessus la voix.
                    .semantics { if (ui.phase != CallPhase.Live) liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.weight(1f))
            Waves(
                levels = levels,
                active = ui.phase == CallPhase.Live,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .testTag(CallTags.WAVES)
                    .clearAndSetSemantics {},
            )
            Spacer(Modifier.weight(1f))
            if (ui.phase == CallPhase.Ended) {
                CloseButton(onClose)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    RoundButton(
                        glyph = if (ui.micMuted) Glyph.MicOff else Glyph.Mic,
                        label = "Muet",
                        spoken = "Couper le micro",
                        state = if (ui.micMuted) "activé" else "désactivé",
                        on = ui.micMuted,
                        tag = CallTags.MUTE,
                        onClick = onMute,
                    )
                    RoundButton(
                        glyph = Glyph.Speaker,
                        label = "Haut-parleur",
                        spoken = "Haut-parleur",
                        state = if (ui.route == AudioRoute.Speaker) "activé" else "désactivé, ${ui.route.label.lowercase()}",
                        on = ui.route == AudioRoute.Speaker,
                        tag = CallTags.SPEAKER,
                        onClick = onSpeaker,
                    )
                    HangUpButton(onHangUp)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Phrase d'état, sous la durée. */
internal fun statusText(ui: CallUi): String = when (ui.phase) {
    CallPhase.Idle, CallPhase.Connecting -> "Connexion…"
    CallPhase.Live -> when {
        ui.micMuted -> "Micro coupé"
        ui.thinking -> "Je réfléchis…"
        else -> "Je t’écoute"
    }
    CallPhase.Ending -> "Je raccroche…"
    CallPhase.Ended -> ui.status ?: "Appel terminé"
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun spokenClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return if (m > 0) "$m min $r s" else "$r s"
}

private val CloseShape = RoundedCornerShape(14.dp)

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(
        Modifier
            .widthIn(min = 180.dp)
            .heightIn(min = TracklySpacing.TouchTarget)
            .testTag(CallTags.CLOSE)
            .pressable(onClose, pressedScale = 0.96f)
            .clip(CloseShape)
            .background(TracklyColors.Cobalt)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Fermer", style = TracklyType.CardButton.copy(color = TracklyColors.PaperHi))
    }
}

/** Muet ou haut-parleur : rond de 64 dp, plein cobalt quand il est actif, libellé dessous. */
@Composable
private fun RoundButton(glyph: Glyph, label: String, spoken: String, state: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, LocalMotion.current.tween(TracklyDurations.PRESS), label = "pression")
    Column(
        Modifier
            .widthIn(min = 88.dp)
            .testTag(tag)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Interrupteur pour TalkBack : « Couper le micro, activé, interrupteur ».
            .toggleable(value = on, interactionSource = source, indication = null, role = Role.Switch, onValueChange = { onClick() })
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                stateDescription = state
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(CALL_BUTTON)
                .clip(CircleShape)
                .background(if (on) TracklyColors.Cobalt else TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, if (on) TracklyColors.Cobalt else TracklyColors.Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, if (on) TracklyColors.PaperHi else TracklyColors.Ink, Modifier.size(26.dp), stroke = 1.6f)
        }
        Spacer(Modifier.height(8.dp))
        BasicText(label, style = TracklyType.CallButtonLabel.copy(color = TracklyColors.Ink2), modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun HangUpButton(onClick: () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 88.dp)
            .testTag(CallTags.HANG_UP)
            .pressable(onClick, pressedScale = 0.92f)
            .semantics(mergeDescendants = true) { contentDescription = "Raccrocher" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(CALL_BUTTON)
                .softShadow(CircleShape, radius = 20.dp, spread = (-8).dp, y = 8.dp, alpha = 0.35f)
                .clip(CircleShape)
                .background(TracklyColors.Bad),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.HangUp, TracklyColors.PaperHi, Modifier.size(28.dp), stroke = 1.7f)
        }
        Spacer(Modifier.height(8.dp))
        BasicText("Raccrocher", style = TracklyType.CallButtonLabel.copy(color = TracklyColors.Ink2), modifier = Modifier.clearAndSetSemantics {})
    }
}

private val CALL_BUTTON = 64.dp

/** Niveau efficace du son vers une amplitude visible : presque rien au silence, vif quand on parle. */
internal fun visibleLevel(rms: Float): Float = ((rms - 0.006f) / 0.15f).coerceIn(0f, 1f).pow(0.6f)

/**
 * Vagues qui suivent la voix : encre quand l'utilisateur parle, cobalt quand Trackly parle, une ligne presque immobile au
 * silence. Avec les animations réduites, les barres suivent encore le niveau (c'est une information), sans
 * ondulation ni lissage.
 */
@Composable
private fun Waves(levels: State<AudioLevels>, active: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalMotion.current.reduced
    var phase by remember { mutableFloatStateOf(0f) }
    var mic by remember { mutableFloatStateOf(0f) }
    var ai by remember { mutableFloatStateOf(0f) }
    // Animations réduites : aucune boucle d'images ; le dessin relit le niveau à chaque changement.
    LaunchedEffect(active, reduced) {
        mic = 0f
        ai = 0f
        if (!active || reduced) return@LaunchedEffect
        while (true) {
            withFrameNanos { t ->
                val l = levels.value
                phase = (t / 1_000_000_000.0).toFloat()
                mic += (visibleLevel(l.mic) - mic) * 0.3f
                ai += (visibleLevel(l.ai) - ai) * 0.3f
            }
        }
    }
    Canvas(modifier) {
        val direct = if (reduced && active) levels.value else null
        val m = direct?.let { visibleLevel(it.mic) } ?: mic
        val a = direct?.let { visibleLevel(it.ai) } ?: ai
        val bars = 29
        val gap = size.width / bars
        val barW = gap * 0.42f
        val maxH = size.height * 0.92f
        val amp = maxOf(m, a)
        val color: Color = lerp(TracklyColors.Ink2, TracklyColors.Cobalt, if (m + a > 0.001f) a / (m + a) else 1f)
        for (i in 0 until bars) {
            val x = gap * (i + 0.5f)
            val centered = (i - bars / 2f) / (bars / 2f)
            val envelope = exp(-2.2f * centered * centered)
            val wobble = if (reduced) 1f else 0.55f + 0.45f * sin(phase * 7f + i * 0.8f) * sin(phase * 3.1f + i * 0.33f)
            val h = (4.dp.toPx() + amp * envelope * wobble * maxH).coerceAtMost(maxH)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * envelope),
                topLeft = Offset(x - barW / 2, (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}
