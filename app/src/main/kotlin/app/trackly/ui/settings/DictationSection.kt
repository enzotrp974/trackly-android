package app.trackly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.dictation.DictationMode
import app.trackly.dictation.DictationSettings
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import app.trackly.usage.PriceTable
import java.math.BigDecimal

/** Étiquettes de test de la section Dictée. */
object DictationTags {
    const val SECTION = "reglages-dictee"
    fun option(mode: DictationMode) = "reglages-dictee-${mode.code}"
}

/** Section « Dictée » des Réglages, branchée sur le réglage enregistré. */
@Composable
fun DictationSettingsRoute(settings: DictationSettings) {
    val mode by settings.mode.collectAsStateWithLifecycle()
    DictationSettingsSection(mode, settings::setMode)
}

/** Choix du mode de dictée : en direct (par défaut) ou après l'arrêt (moins cher). */
@Composable
fun DictationSettingsSection(mode: DictationMode, onSelect: (DictationMode) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(DictationTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        BasicText("DICTÉE", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        ModeOption(
            DictationMode.Live,
            selected = mode == DictationMode.Live,
            title = "En direct",
            body = "Le texte s’écrit pendant que tu parles.",
            onSelect = onSelect,
        )
        Spacer(Modifier.height(8.dp))
        ModeOption(
            DictationMode.AfterStop,
            selected = mode == DictationMode.AfterStop,
            title = "Après l’arrêt",
            body = "Le texte arrive quand tu arrêtes.",
            onSelect = onSelect,
        )
    }
}

private val OptionShape = RoundedCornerShape(12.dp)

@Composable
private fun ModeOption(mode: DictationMode, selected: Boolean, title: String, body: String, onSelect: (DictationMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(DictationTags.option(mode))
            .pressable({ onSelect(mode) }, pressedScale = 0.985f)
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
            Spacer(Modifier.height(4.dp))
            BasicText(priceLine(mode), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
        }
    }
}

/** « Environ 1,7 centime la minute », d'après la table de prix unique. */
private fun priceLine(mode: DictationMode): String {
    val micros = PriceTable.transcriptionRate(mode.model) ?: return "Tarif inconnu"
    val cents = BigDecimal(micros).movePointLeft(4).stripTrailingZeros().toPlainString().replace('.', ',')
    return "Environ $cents centime${if (micros > 10_000) "s" else ""} la minute"
}
