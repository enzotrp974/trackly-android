package app.trackly.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.call.CallEcho
import app.trackly.call.CallMetrics
import app.trackly.call.CallMetricsStore
import app.trackly.call.CallReflection
import app.trackly.call.CallSettings
import app.trackly.call.LastCallTranscript
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Étiquettes de test de Réglages › Appel. */
object CallSettingsTags {
    const val SECTION = "reglages-appel"
    fun reflection(value: CallReflection) = "reglages-appel-reflexion-${value.code}"
    fun echo(value: CallEcho) = "reglages-appel-echo-${value.code}"
    const val TRANSCRIPT = "reglages-appel-transcription"
    const val COPY_TRANSCRIPT = "reglages-appel-copier"
    const val METRICS = "reglages-appel-mesures"
    const val COPY_METRICS = "reglages-appel-copier-mesures"
}

/** Ce que la section lit du téléphone : transcription du dernier appel et mesures (fichiers privés). */
class CallRecordsView(val transcript: String?, val metrics: List<CallMetrics>)

/** Réglages › Appel, branché sur les réglages et les fichiers du dernier appel. */
@Composable
fun CallSettingsRoute(settings: CallSettings, transcript: LastCallTranscript, metrics: CallMetricsStore) {
    val reflection by settings.reflection.collectAsStateWithLifecycle()
    val echo by settings.echo.collectAsStateWithLifecycle()
    val records by produceState(CallRecordsView(null, emptyList()), transcript, metrics) {
        value = withContext(Dispatchers.IO) { CallRecordsView(transcript.read(), metrics.all()) }
    }
    val context = LocalContext.current
    CallSettingsSection(
        reflection = reflection,
        echo = echo,
        records = records,
        onReflection = settings::setReflection,
        onEcho = settings::setEcho,
        onCopy = { label, text, sensitive -> copy(context, label, text, sensitive) },
    )
}

/**
 * Réflexion pendant l'appel (Rapide, Moyenne par défaut, Élevée : distincte du réglage du chat), annulation d'écho
 * (WebRTC par défaut, ou Téléphone), transcription du dernier appel avec « Copier », et mesures des derniers appels.
 */
@Composable
fun CallSettingsSection(
    reflection: CallReflection,
    echo: CallEcho,
    records: CallRecordsView,
    onReflection: (CallReflection) -> Unit,
    onEcho: (CallEcho) -> Unit,
    onCopy: (label: String, text: String, sensitive: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(CallSettingsTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        Kicker("RÉFLEXION PENDANT L’APPEL")
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Quand tu as fini de décrire un repas, Trackly réfléchit en arrière-plan ; la voix continue pendant ce temps. Le chat garde son propre réglage.",
            style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2),
        )
        Spacer(Modifier.height(10.dp))
        CallReflection.entries.forEachIndexed { i, value ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Choice(
                title = value.label + if (value == CallReflection.Default) " (par défaut)" else "",
                body = value.body,
                selected = value == reflection,
                tag = CallSettingsTags.reflection(value),
                onClick = { onReflection(value) },
            )
        }

        Spacer(Modifier.height(26.dp))
        Kicker("ANNULATION D’ÉCHO")
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Si Trackly s’entend ou se coupe sur haut-parleur, essaie l’autre réglage. Il vaut dès le prochain appel.",
            style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2),
        )
        Spacer(Modifier.height(10.dp))
        CallEcho.entries.forEachIndexed { i, value ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Choice(
                title = value.label + if (value == CallEcho.Default) " (par défaut)" else "",
                body = value.body,
                selected = value == echo,
                tag = CallSettingsTags.echo(value),
                onClick = { onEcho(value) },
            )
        }

        Spacer(Modifier.height(26.dp))
        Kicker("DERNIER APPEL")
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (records.transcript != null) {
                "La transcription du dernier appel reste sur ce téléphone. Elle est remplacée au prochain appel et n’est jamais sauvegardée sur Drive."
            } else {
                "Aucune transcription : la transcription du dernier appel s’affichera ici, sur ce téléphone seulement."
            },
            style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2),
            modifier = Modifier.testTag(CallSettingsTags.TRANSCRIPT),
        )
        records.transcript?.let { text ->
            Spacer(Modifier.height(10.dp))
            ActionButton("Copier la transcription", CallSettingsTags.COPY_TRANSCRIPT) { onCopy("Dernier appel Trackly", text, true) }
        }

        Spacer(Modifier.height(26.dp))
        Kicker("MESURES DES APPELS")
        Spacer(Modifier.height(6.dp))
        BasicText(
            metricsLine(records.metrics),
            style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2),
            modifier = Modifier.testTag(CallSettingsTags.METRICS),
        )
        if (records.metrics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ActionButton("Copier les mesures", CallSettingsTags.COPY_METRICS) { onCopy("Mesures des appels", CallMetricsStore.text(records.metrics), false) }
        }
    }
}

/** « Dernier appel : je t'écoute en 0,8 s (préparé). Réflexions : 2,4 s en médiane. » */
internal fun metricsLine(all: List<CallMetrics>): String {
    val last = all.lastOrNull() ?: return "Aucun appel mesuré. Chaque lancement et chaque réflexion seront chronométrés ici, sans aucun texte d’appel."
    val listen = last.listenMs?.let { "« je t’écoute » en ${seconds(it)}" } ?: "session non démarrée"
    val prep = if (last.prepared) "préparé avant le toucher" else "sans préparation"
    val reflections = all.flatMap { it.reflections }.filter { it.ok }.map { it.ms }.sorted()
    val median = reflections.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    return "Dernier appel : $listen, $prep." + (median?.let { " Réflexions : ${seconds(it)} en médiane sur ${reflections.size}." } ?: "")
}

private fun seconds(ms: Long): String = "%.1f s".format(java.util.Locale.FRENCH, ms / 1000.0)

@Composable
private fun Kicker(text: String) {
    BasicText(text, style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
}

private val OptionShape = RoundedCornerShape(12.dp)

@Composable
private fun Choice(title: String, body: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.985f)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            }
            .clip(OptionShape)
            .background(if (selected) TracklyColors.Mist else TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, if (selected) TracklyColors.Cobalt else TracklyColors.Line2, OptionShape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .border(TracklySpacing.Hairline, if (selected) TracklyColors.Cobalt else TracklyColors.Line2, CircleShape)
                .background(if (selected) TracklyColors.Cobalt else TracklyColors.PaperHi),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.4f)
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
            Spacer(Modifier.height(3.dp))
            BasicText(body, style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2))
        }
    }
}

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun ActionButton(label: String, tag: String, onClick: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Box(
        Modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .testTag(tag)
            .pressable({
                onClick()
                done = true
            }, pressedScale = 0.97f)
            .clip(ButtonShape)
            .border(TracklySpacing.Hairline, TracklyColors.Cobalt, ButtonShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(if (done) "Copié" else label, style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt))
    }
}

/** Copie seulement sur le geste de l'utilisateur ; la transcription est marquée sensible (pas d'aperçu du presse-papiers). */
private fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) }
}
