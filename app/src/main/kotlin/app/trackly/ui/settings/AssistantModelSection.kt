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
import app.trackly.assistant.model.AssistantModelSettings
import app.trackly.assistant.openai.AssistantModel
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import java.math.BigDecimal

/** Étiquettes de test de la section « Modèle de l'assistant ». */
object AssistantModelTags {
    const val SECTION = "reglages-modele"
    fun option(model: AssistantModel) = "reglages-modele-${model.code}"
}

/** Section « Modèle de l'assistant » des Réglages, branchée sur le réglage enregistré. */
@Composable
fun AssistantModelSettingsRoute(settings: AssistantModelSettings) {
    val model by settings.model.collectAsStateWithLifecycle()
    AssistantModelSettingsSection(model, settings::setModel)
}

/**
 * Choix du modèle : GPT-6.1 Sol (par défaut) ou GPT-6.1 Terra, plus rapide et moins cher.
 * Vaut pour le chat et pour le bilan du lundi, dès le message suivant.
 */
@Composable
fun AssistantModelSettingsSection(model: AssistantModel, onSelect: (AssistantModel) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(AssistantModelTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        BasicText("MODÈLE DE L’ASSISTANT", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        ModelOption(
            AssistantModel.Sol,
            selected = model == AssistantModel.Sol,
            body = "Le plus précis.",
            onSelect = onSelect,
        )
        Spacer(Modifier.height(8.dp))
        ModelOption(
            AssistantModel.Terra,
            selected = model == AssistantModel.Terra,
            body = "Plus rapide, environ deux fois moins cher.",
            onSelect = onSelect,
        )
    }
}

private val OptionShape = RoundedCornerShape(12.dp)

@Composable
private fun ModelOption(model: AssistantModel, selected: Boolean, body: String, onSelect: (AssistantModel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(AssistantModelTags.option(model))
            .pressable({ onSelect(model) }, pressedScale = 0.985f)
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
            BasicText(model.label + if (model == AssistantModel.Default) " (par défaut)" else "", style = TracklyType.NoticeTitle.copy(color = TracklyColors.Ink))
            Spacer(Modifier.height(3.dp))
            BasicText(body, style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink2))
            Spacer(Modifier.height(4.dp))
            BasicText(priceLine(model), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
        }
    }
}

/** « 2 $ en entrée, 10 $ en sortie, par million de jetons », d'après la table de prix unique. */
private fun priceLine(model: AssistantModel): String {
    val price = model.price ?: return "Tarif inconnu"
    return "${dollars(price.input)} en entrée, ${dollars(price.output)} en sortie, par million de jetons"
}

private fun dollars(micros: Long): String =
    BigDecimal(micros).movePointLeft(6).stripTrailingZeros().toPlainString().replace('.', ',') + " $"
