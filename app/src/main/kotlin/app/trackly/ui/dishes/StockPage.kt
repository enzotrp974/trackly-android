package app.trackly.ui.dishes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.trackly.core.model.Provision
import app.trackly.core.model.TextMatch
import app.trackly.dishes.DishesUiState
import app.trackly.ui.components.Hairline
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/**
 * « Mon stock » : la base qu'on a d'habitude chez soi. Un aliment fini n'est jamais effacé : il passe dans « Pas en ce
 * moment » et revient d'un toucher, sans rien réécrire. Les quantités n'existent pas : l'assistant s'en passe.
 */
@Composable
internal fun StockPage(state: DishesUiState, actions: DishesActions) {
    val (held, out) = state.allProvisions.sortedBy { TextMatch.normalize(it.name) }.partition { it.isActive }
    StockField(actions)
    Spacer(Modifier.height(16.dp))
    Group("EN STOCK", held, "Plus là", actions.removeProvision)
    if (out.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        Group("PAS EN CE MOMENT", out, "Remettre", actions.addProvision)
    }
}

@Composable
private fun Group(title: String, items: List<Provision>, action: String, onAction: (String) -> Unit) {
    Kicker(title)
    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Body("Rien pour l’instant.")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape),
    ) {
        items.forEachIndexed { i, p ->
            if (i > 0) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(DishesTags.provision(p.name))
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(p.name, style = TracklyType.CardItem.copy(color = TracklyColors.Ink), modifier = Modifier.weight(1f))
                TextAction(action, DishesTags.provisionOut(p.name)) { onAction(p.name) }
            }
        }
    }
}

@Composable
private fun StockField(actions: DishesActions) {
    var text by rememberSaveable { mutableStateOf("") }
    // La touche « Ajouter » du clavier fait comme le bouton : on enchaîne sans quitter le clavier.
    val add = {
        if (text.isNotBlank()) {
            actions.addProvision(text.trim())
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field("Ajouter à ma base", text, DishesTags.PROVISION_FIELD, KeyboardType.Text, ime = ImeAction.Done, onImeDone = add) { text = it }
        ActionButton("Ajouter", primary = false, tag = DishesTags.PROVISION_ADD, onClick = add)
    }
}
