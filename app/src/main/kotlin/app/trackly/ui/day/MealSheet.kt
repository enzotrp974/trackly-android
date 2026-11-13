package app.trackly.ui.day

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trackly.day.DayView
import app.trackly.day.Meal
import app.trackly.day.MomentSlot
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.components.trackCenter
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/** Étiquettes de test de la feuille d'un repas et du bandeau « Annuler ». */
object MealTags {
    const val SHEET = "repas-feuille"
    const val DELETE = "repas-supprimer"
    const val CORRECT = "repas-corriger"
    const val ADD = "repas-ajouter"
    const val UNDO_BANNER = "repas-annuler-bandeau"
    const val UNDO = "repas-annuler"
}

private val PanelShape = RoundedCornerShape(topStart = TracklySpacing.SheetRadius, topEnd = TracklySpacing.SheetRadius)

/**
 * Feuille d'un repas : chaque aliment (quantité, kcal, macros, hypothèses une par ligne, produit lié),
 * puis « Corriger » (chat) et « Supprimer » (action du moteur, annulable depuis le bandeau).
 * Le « + » de l'en-tête ajoute à ce moment (chat prérempli) ; `null` : pas de « + ».
 * Le fond ne reçoit plus de touchers ; « Retour » et le toucher hors de la feuille la ferment.
 */
@Composable
internal fun BoxScope.MealSheet(
    day: DayView,
    slot: MomentSlot,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onCorrect: (origin: Offset) -> Unit,
    onAdd: ((origin: Offset) -> Unit)? = null,
) {
    val motion = LocalMotion.current
    val progress = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, motion.tween(450, easing = TracklyEasing.Spring)) }
    BackHandler(onBack = onClose)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    var correctCenter by remember { mutableStateOf(Offset.Zero) }
    var addCenter by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(TracklyColors.Ink.copy(alpha = 0.16f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "Fermer le détail du repas" },
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .graphicsLayer { translationY = (1f - progress.value) * 60.dp.toPx(); alpha = progress.value.coerceIn(0f, 1f) }
            .softShadow(PanelShape, radius = 36.dp, spread = (-18).dp, y = (-16).dp, alpha = 0.3f)
            .clip(PanelShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line, PanelShape)
            // Toucher la feuille ne la ferme pas, et rien ne traverse vers le jour.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag(MealTags.SHEET)
            .semantics { paneTitle = "Détail du ${slot.moment.label.lowercase()}" }
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 9.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp, 2.dp).background(TracklyColors.Line2))
        }
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = TracklySpacing.Gutter, end = TracklySpacing.Gutter, top = 12.dp, bottom = 20.dp),
        ) {
            val total = slot.kcal ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        (day.relative ?: day.shortLabel).uppercase(),
                        style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        slot.moment.label,
                        style = TracklyType.MealTitle.copy(color = TracklyColors.Ink),
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    BasicText(
                        "${app.trackly.day.formatKcal(total)} kcal",
                        style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
                        maxLines = 1,
                    )
                }
                if (onAdd != null) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(TracklySpacing.TouchTarget)
                            .testTag(MealTags.ADD)
                            .trackCenter { addCenter = it }
                            .pressable({ onAdd(addCenter) }, pressedScale = 0.88f)
                            .semantics { contentDescription = "Ajouter au ${slot.moment.label.lowercase()}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(TracklySpacing.RowAddSize)
                                .border(TracklySpacing.Hairline, TracklyColors.Cobalt.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlyphIcon(Glyph.Plus, TracklyColors.Cobalt, Modifier.size(13.dp), stroke = 1.6f)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            slot.meals.forEachIndexed { i, meal ->
                Hairline()
                MealDetail(meal)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SheetButton(
                    "Corriger",
                    "Corriger le ${slot.moment.label.lowercase()}",
                    primary = true,
                    tag = MealTags.CORRECT,
                    modifier = Modifier.weight(1f).trackCenter { correctCenter = it },
                ) { onCorrect(correctCenter) }
                SheetButton(
                    "Supprimer",
                    "Supprimer le ${slot.moment.label.lowercase()}",
                    primary = false,
                    tag = MealTags.DELETE,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete,
                )
            }
        }
    }
}

/** Un aliment : nom et quantité, kcal, macros, produit lié, hypothèses (une par ligne). */
@Composable
private fun MealDetail(meal: Meal) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(meal.description, style = TracklyType.CardItem.copy(color = TracklyColors.Ink))
                meal.quantity?.let {
                    BasicText(it, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
                }
            }
            Spacer(Modifier.width(12.dp))
            BasicText(
                "${app.trackly.day.formatKcal(meal.kcal.roundToInt())} kcal",
                style = TracklyType.CardItem.copy(color = TracklyColors.Ink),
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            macroLine(meal),
            style = TracklyType.CardMacros.copy(color = TracklyColors.Ink2),
            modifier = Modifier.semantics { contentDescription = macroSpoken(meal) },
        )
        if (meal.productLinked) {
            Spacer(Modifier.height(4.dp))
            BasicText("Produit mémorisé", style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2))
        }
        if (meal.assumptions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                if (meal.assumptions.size == 1) "HYPOTHÈSE" else "HYPOTHÈSES",
                style = TracklyType.MacroLabel.copy(color = TracklyColors.Ink3),
            )
            for (assumption in meal.assumptions) {
                Spacer(Modifier.height(2.dp))
                BasicText(assumption, style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2))
            }
        }
    }
}

/** « P 44 g · G 89 g · L 20 g » ; une macro inconnue reste « inconnu », jamais 0. */
internal fun macroLine(meal: Meal): String =
    "P ${grams(meal.protein)} · G ${grams(meal.carbs)} · L ${grams(meal.fat)}"

private fun macroSpoken(meal: Meal): String =
    "Protéines ${grams(meal.protein)}, glucides ${grams(meal.carbs)}, lipides ${grams(meal.fat)}"

private fun grams(value: Double?): String = when {
    value == null -> "inconnu"
    value == value.roundToInt().toDouble() -> "${value.roundToInt()} g"
    else -> String.format(java.util.Locale.FRENCH, "%.1f g", value)
}

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun SheetButton(
    text: String,
    description: String,
    primary: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.97f)
            .clip(ButtonShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, if (primary) TracklyColors.Cobalt else TracklyColors.Line2, ButtonShape)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TracklyType.CardButton.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Ink),
            maxLines = 1,
        )
    }
}

/**
 * Bandeau après une suppression : « Midi supprimé » et « Annuler » (action locale, sans réseau).
 * Annoncé par TalkBack ; le modèle de l'écran le retire seul après quelques secondes.
 */
@Composable
internal fun BoxScope.UndoBanner(label: String, onUndo: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = TracklySpacing.DockGutter, end = TracklySpacing.DockGutter, bottom = 100.dp)
            .fillMaxWidth()
            .softShadow(shape, radius = 22.dp, spread = (-12).dp, y = 10.dp, alpha = 0.4f)
            .clip(shape)
            .background(TracklyColors.Ink)
            .testTag(MealTags.UNDO_BANNER)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            style = TracklyType.CardItem.copy(color = TracklyColors.PaperHi),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        Row(
            Modifier
                .heightIn(min = TracklySpacing.TouchTarget)
                .testTag(MealTags.UNDO)
                .pressable(onUndo, pressedScale = 0.95f)
                .semantics { contentDescription = "Annuler : $label" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Undo, TracklyColors.PaperHi, Modifier.size(16.dp), stroke = 1.5f)
            Spacer(Modifier.width(8.dp))
            BasicText("Annuler", style = TracklyType.CardButton.copy(color = TracklyColors.PaperHi))
        }
    }
}
