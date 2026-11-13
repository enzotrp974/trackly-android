package app.trackly.ui.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.action.DeleteConsumption
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.action.Undo
import app.trackly.core.action.UndoLast
import app.trackly.core.model.EntryId
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Origin
import app.trackly.core.store.ActionRecord
import app.trackly.day.DayLabels
import app.trackly.day.DayView
import app.trackly.day.Meal
import app.trackly.ui.components.ArrowButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/** Ce dont le panneau a besoin : historique récent, dernier résultat, et l'exécution d'actions. */
class DevPanelModel(
    val recent: List<ActionRecord>,
    val outcome: ActionOutcome?,
    /** Moment proposé par défaut (heure de l'horloge du moteur). */
    val suggestedMoment: Moment,
    val perform: (Action) -> Unit,
)

/** Étiquettes de test des champs du panneau. */
object DevTags {
    const val PANEL = "dev-panneau"
    const val LABEL = "dev-aliment"
    const val KCAL = "dev-kcal"
    const val PROTEIN = "dev-proteines"
    const val CARBS = "dev-glucides"
    const val FAT = "dev-lipides"
    const val GOAL_KCAL = "dev-objectif-kcal"
    const val GOAL_PROTEIN = "dev-objectif-proteines"
    const val GOAL_CARBS = "dev-objectif-glucides"
    const val GOAL_FAT = "dev-objectif-lipides"
}

/**
 * Saisie sans l'assistant : ajouter, corriger, supprimer, annuler une consommation et fixer un objectif.
 * Tout passe par les actions du moteur, exactement
 * comme l'assistant. Ouverte par « Saisie manuelle » en bas de la liste des repas (ou l'appui long sur « Trackly »).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevPanel(day: DayView, model: DevPanelModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember(day.date) { mutableStateOf<Meal?>(null) }
    var label by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var moment by remember { mutableStateOf(model.suggestedMoment) }
    // Rattachement explicite au jour affiché (« pour hier ») ; sinon règle des 5 h.
    var forShownDay by remember(day.date) { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    var goalKcal by remember { mutableStateOf(day.goals?.kcal?.toString().orEmpty()) }
    var goalProtein by remember { mutableStateOf(day.goals?.protein?.toString().orEmpty()) }
    var goalCarbs by remember { mutableStateOf(day.goals?.carbs?.toString().orEmpty()) }
    var goalFat by remember { mutableStateOf(day.goals?.fat?.toString().orEmpty()) }

    fun clearForm() {
        editing = null
        label = ""
        kcal = ""
        protein = ""
        carbs = ""
        fat = ""
        localError = null
    }

    /** Construit l'action, ou affiche l'erreur de saisie sans rien envoyer au moteur. */
    fun submit(build: () -> Action) {
        val action = try {
            build()
        } catch (e: IllegalArgumentException) {
            localError = e.message ?: "Saisie invalide"
            return
        }
        localError = null
        model.perform(action)
    }

    fun food() = Food(
        label = label.trim(),
        nutrition = Nutrition(
            kcal = parseAmount(kcal) ?: throw IllegalArgumentException("Indique les kcal"),
            proteinG = parseAmount(protein),
            carbsG = parseAmount(carbs),
            fatG = parseAmount(fat),
        ),
    )

    Box(
        modifier
            .fillMaxSize()
            .background(TracklyColors.PaperHi)
            .testTag(DevTags.PANEL)
            // Le panneau capte les touchers : rien ne traverse vers le jour.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TracklySpacing.Gutter, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowButton(Glyph.ChevronLeft, "Fermer la saisie manuelle", enabled = true, onClick = onClose)
                Spacer(Modifier.width(12.dp))
                BasicText(
                    "Saisie sans assistant",
                    style = TracklyType.Brand.copy(color = TracklyColors.Ink),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                "Sans l’assistant. Jour affiché : ${DayLabels.inSentence(day.date)}.",
                style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
            )
            Feedback(localError, model.outcome)

            // ───── Ajouter ou corriger ─────
            Section(if (editing == null) "Ajouter un aliment" else "Corriger « ${editing?.description} »")
            Field("Aliment", label, { label = it }, DevTags.LABEL, KeyboardType.Text, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Kcal", kcal, { kcal = it }, DevTags.KCAL, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Prot. g", protein, { protein = it }, DevTags.PROTEIN, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Gluc. g", carbs, { carbs = it }, DevTags.CARBS, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Lip. g", fat, { fat = it }, DevTags.FAT, KeyboardType.Decimal, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Moment.entries) Chip(m.label, selected = moment == m, onClick = { moment = m })
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Date automatique", selected = !forShownDay, onClick = { forShownDay = false })
                Chip("Pour le jour affiché", selected = forShownDay, onClick = { forShownDay = true })
            }
            Spacer(Modifier.height(12.dp))
            val current = editing
            if (current == null) {
                DevButton("Ajouter", primary = true) {
                    submit {
                        AddConsumptions(
                            NewConsumption(food(), moment, explicitDay = if (forShownDay) day.date else null),
                            origin = Origin.DevPanel,
                        )
                    }
                    if (localError == null) clearForm()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("Enregistrer la correction", primary = true) {
                        submit {
                            CorrectConsumption(
                                EntryId(current.entryId),
                                food = food(),
                                moment = moment,
                                day = if (forShownDay) day.date else null,
                            )
                        }
                        if (localError == null) clearForm()
                    }
                    DevButton("Abandonner") { clearForm() }
                }
            }

            // ───── Consommations du jour affiché ─────
            Section("Déjà noté le ${DayLabels.inSentence(day.date)}")
            if (day.meals.isEmpty()) {
                BasicText("Rien pour l’instant", style = TracklyType.MealEmpty.copy(color = TracklyColors.Ink3))
            }
            day.meals.forEachIndexed { i, meal ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 14.dp)) {
                        BasicText(meal.description, style = TracklyType.MealDetail.copy(color = TracklyColors.Ink))
                        BasicText(
                            "${meal.moment.label} · ${meal.kcal.roundToInt()} kcal",
                            style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink3),
                        )
                    }
                    TextAction("Corriger", "Corriger ${meal.description}") {
                        editing = meal
                        label = meal.description
                        kcal = formatAmount(meal.kcal)
                        protein = meal.protein?.let(::formatAmount).orEmpty()
                        carbs = meal.carbs?.let(::formatAmount).orEmpty()
                        fat = meal.fat?.let(::formatAmount).orEmpty()
                        moment = meal.moment
                        forShownDay = false
                        localError = null
                    }
                    Spacer(Modifier.width(14.dp))
                    TextAction("Supprimer", "Supprimer ${meal.description}") {
                        submit { DeleteConsumption(EntryId(meal.entryId)) }
                    }
                }
            }

            // ───── Annuler ─────
            Section("Dernières actions")
            DevButton("Annuler la dernière action") { submit { UndoLast } }
            Spacer(Modifier.height(8.dp))
            for (record in model.recent) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        record.title,
                        style = TracklyType.GaugeCaption.copy(color = if (record.undoneBy == null) TracklyColors.Ink2 else TracklyColors.Ink3),
                        modifier = Modifier.weight(1f),
                    )
                    if (record.undoneBy == null) {
                        // Une « Annulation : … » se défait en rétablissant ce qu'elle avait annulé.
                        val restore = record.undoes != null
                        TextAction(if (restore) "Rétablir" else "Annuler", "${if (restore) "Rétablir" else "Annuler"} : ${record.title}") { submit { Undo(record.id) } }
                    } else {
                        BasicText("annulée", style = TracklyType.GaugeCaption.copy(color = TracklyColors.Ink3))
                    }
                }
            }

            // ───── Objectif ─────
            Section("Objectif dès le ${DayLabels.inSentence(day.date)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Kcal", goalKcal, { goalKcal = it }, DevTags.GOAL_KCAL, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Prot. g", goalProtein, { goalProtein = it }, DevTags.GOAL_PROTEIN, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Gluc. g", goalCarbs, { goalCarbs = it }, DevTags.GOAL_CARBS, KeyboardType.Decimal, Modifier.weight(1f))
                Field("Lip. g", goalFat, { goalFat = it }, DevTags.GOAL_FAT, KeyboardType.Decimal, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            DevButton("Fixer l’objectif", primary = true) {
                submit {
                    SetGoal(
                        GoalTargets(
                            kcal = parseAmount(goalKcal) ?: throw IllegalArgumentException("Indique l’objectif en kcal"),
                            proteinG = parseAmount(goalProtein),
                            carbsG = parseAmount(goalCarbs),
                            fatG = parseAmount(goalFat),
                        ),
                        from = day.date,
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** « 12,5 » ou « 12.5 » → 12.5 ; vide → `null` (valeur inconnue). */
internal fun parseAmount(text: String): Double? {
    val trimmed = text.trim().replace(',', '.')
    if (trimmed.isEmpty()) return null
    return trimmed.toDoubleOrNull() ?: throw IllegalArgumentException("Nombre invalide : $text")
}

private fun formatAmount(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString().replace('.', ',')

@Composable
private fun Feedback(localError: String?, outcome: ActionOutcome?) {
    val text = when {
        localError != null -> "Saisie refusée : $localError"
        outcome is ActionOutcome.Applied -> "Fait — ${outcome.result.title}"
        outcome is ActionOutcome.Rejected -> "Refusé — ${outcome.rejection.message}"
        else -> return
    }
    Spacer(Modifier.height(10.dp))
    BasicText(
        text,
        style = TracklyType.MealDetail.copy(color = if (outcome is ActionOutcome.Applied && localError == null) TracklyColors.Cobalt else TracklyColors.Ink),
        modifier = Modifier.semantics { contentDescription = text },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(26.dp))
    BasicText(
        title.uppercase(),
        style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt),
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(10.dp))
}

private val FieldShape = RoundedCornerShape(10.dp)

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        BasicText(label.uppercase(), style = TracklyType.MacroLabel.copy(color = TracklyColors.Ink2))
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
            cursorBrush = SolidColor(TracklyColors.Cobalt),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { contentDescription = label }
                .clip(FieldShape)
                .background(TracklyColors.Paper)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, FieldShape)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

private val ChipShape = RoundedCornerShape(999.dp)

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = if (selected) TracklyColors.PaperHi else TracklyColors.Ink),
        modifier = Modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics { this.selected = selected }
            .clip(ChipShape)
            .background(if (selected) TracklyColors.Cobalt else TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, if (selected) TracklyColors.Cobalt else TracklyColors.Line2, ChipShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun DevButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.MealTitle.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .pressable(onClick, pressedScale = 0.97f)
            .clip(ButtonShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.Mist)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun TextAction(text: String, description: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .pressable(onClick, pressedScale = 0.94f)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}
