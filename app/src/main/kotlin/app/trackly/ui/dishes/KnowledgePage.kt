package app.trackly.ui.dishes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.core.model.Product
import app.trackly.dishes.DishesUiState
import app.trackly.dishes.ProductsBook
import app.trackly.core.model.AgentMemory
import app.trackly.dishes.RecurringBook
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType

/** Sections de « Ce que l'assistant sait » (clés des étiquettes de test). */
object KnowledgeSections {
    const val MEMORY = "memoire"
    const val HABITS = "habitudes"
    const val PREFERENCES = "preferences"
    const val TERMS = "mots"
}

/**
 * « Ce que l'assistant sait » : sa mémoire (qu'on peut lui faire oublier), les habitudes qu'il voit dans les repas,
 * les mots corrigés et les règles reprises de l'ancien suivi.
 */
@Composable
internal fun KnowledgePage(state: DishesUiState, actions: DishesActions) {
    val notes = AgentMemory.notes(state.preferences)
    val groups = RecurringBook.preferenceGroups(state.preferences)
    val terms = RecurringBook.termCorrections(state.preferences)
    Column(Modifier.fillMaxWidth().testTag(DishesTags.KNOWLEDGE_PAGE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Mémoire", notes.size, KnowledgeSections.MEMORY, startOpen = true) {
            if (notes.isEmpty()) Empty()
            notes.forEachIndexed { i, note ->
                if (i > 0) Hairline()
                Column(Modifier.fillMaxWidth().testTag(DishesTags.preference(note.preferenceId)).padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    BasicText(note.trigger, style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
                    BasicText(note.rule.trim(), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
                    TextAction("Oublier", DishesTags.memoryForget(note.preferenceId)) { actions.forgetMemory(note.preferenceId) }
                }
            }
        }
        Section("Habitudes", state.habits.size, KnowledgeSections.HABITS, startOpen = true) {
            if (state.habits.isEmpty()) Empty()
            state.habits.forEachIndexed { i, habit ->
                if (i > 0) Hairline()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BasicText(habit.moment.label, style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
                    BasicText(
                        habit.usual.take(5).joinToString(" · ") { it.name }.ifEmpty { "Rien de régulier" },
                        style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
                    )
                    if (habit.rare.isNotEmpty()) {
                        BasicText("Presque jamais : " + habit.rare.joinToString(" · "), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink3))
                    }
                }
            }
        }
        Section("Mots corrigés", terms.size, KnowledgeSections.TERMS) {
            if (terms.isEmpty()) Empty()
            terms.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                BasicText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TracklyColors.Ink3)) { append(p.trigger) }
                        withStyle(SpanStyle(color = TracklyColors.Ink3)) { append("  →  ") }
                        append(p.rule.trim())
                    },
                    style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
                    modifier = Modifier.fillMaxWidth().testTag(DishesTags.preference(p.preferenceId)).padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        Section("Règles", groups.sumOf { it.second.size }, KnowledgeSections.PREFERENCES) {
            if (groups.isEmpty()) Empty()
            groups.forEachIndexed { i, (label, rules) ->
                if (i > 0) Hairline()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BasicText(label.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Ink3))
                    rules.forEach { p ->
                        Column(Modifier.fillMaxWidth().testTag(DishesTags.preference(p.preferenceId)).padding(top = 8.dp)) {
                            BasicText(p.trigger, style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
                            BasicText(p.rule.trim(), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
                        }
                    }
                }
            }
        }
    }
}

/** Une section repliée : son nom et son nombre ; un toucher l'ouvre. */
@Composable
private fun Section(title: String, count: Int, key: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(DishesTags.section(key))
                .pressable({ open = !open }, pressedScale = 0.985f)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    heading()
                    stateDescription = if (open) "Ouvert" else "Fermé"
                }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.weight(1f))
            BasicText(count.toString(), style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3))
            Spacer(Modifier.width(10.dp))
            GlyphIcon(Glyph.ChevronRight, TracklyColors.Ink3, Modifier.size(16.dp).rotate(if (open) 90f else 0f))
        }
        if (open) {
            Hairline()
            content()
        }
    }
}

@Composable
private fun Empty(tag: String? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Body("Rien pour l’instant.", tag = tag) }
}

/** Un récurrent peu mangé : toucher ouvre sa fiche. */
