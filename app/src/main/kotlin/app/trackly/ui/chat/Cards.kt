package app.trackly.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.CardMacro
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.DishCard
import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProposalCard
import app.trackly.assistant.cards.ProvisionsCard
import app.trackly.assistant.cards.GoalCard
import app.trackly.assistant.cards.RecordAlerts
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.SourcesCard
import app.trackly.assistant.cards.UndoCard
import app.trackly.assistant.cards.formatNumber
import app.trackly.day.QuantityText
import app.trackly.day.formatKcal
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Odometer
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.MotionPolicy
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/** Étiquettes de test de la carte d'enregistrement. */
object CardTags {
    const val RECORD = "carte-enregistrement"
    const val UNDONE = "carte-annulee"
    const val UNDO = "carte-annuler"
    const val CORRECT = "carte-corriger"
    const val PRODUCTS = "carte-produits"
    const val PRODUCT = "carte-produit"
    const val GOAL = "carte-objectif"
    const val QUESTION = "carte-question"
    const val SOURCES = "carte-sources"
    const val DISH = "carte-plat"
    const val PROVISIONS = "carte-provisions"

    /** Bloc des hypothèses d'estimation, et sa ligne qui le déroule. */
    const val ASSUMPTIONS = "carte-hypotheses"
    const val ASSUMPTIONS_TOGGLE = "carte-hypotheses-bascule"

    /** « Rétablir » sur une carte annulée. */
    const val RESTORE = "carte-retablir"

    /** Carte périmée : une action plus récente a modifié ses données. */
    const val SUPERSEDED = "carte-perimee"

    /** Message propre à une carte (refus d'annuler…). */
    const val CARD_NOTICE = "carte-message"

    /** Carte de proposition, son bouton « Noter », sa ligne « Noté » et ses valeurs inconnues. */
    const val PROPOSAL = "carte-proposition"
    const val PROPOSAL_NOTE = "carte-proposition-noter"
    const val PROPOSAL_NOTED = "carte-proposition-notee"
    const val PROPOSAL_UNKNOWN = "carte-proposition-inconnu"
}

/** Durées du déroulé de la carte (unspool 1 s, cascade de 120 ms). */
private object CardMotion {
    const val UNSPOOL = 1000
    const val PART = 700
    const val PART_DELAY = 250
    const val STAGGER = 120
}

/**
 * Carte d'une action de l'assistant. [animate] : la carte vient d'arriver et se déroule ;
 * une carte relue depuis l'historique s'affiche directement.
 */
@Composable
fun CardView(
    card: ChatCard,
    undone: Boolean,
    animate: Boolean,
    onUndo: (ChatCard) -> Unit,
    onCorrect: (ChatCard) -> Unit,
    /** La question de la carte n'a pas encore de réponse ; sinon elle disparaît de la carte. */
    questionPending: Boolean = true,
    /**
     * Carte périmée : une action plus récente a modifié ses données ; le texte dit quoi (« Corrigé depuis »).
     * Ses boutons sont désactivés : annuler serait refusé.
     */
    superseded: String? = null,
    /** Message propre à la carte (refus d'annuler…), affiché sur la carte elle-même. */
    notice: String? = null,
    /** « Rétablir » une carte annulée, sans appel à l'assistant. */
    onRestore: (ChatCard) -> Unit = {},
    /** Carte de proposition déjà notée : « Noté » remplace le bouton. */
    noted: Boolean = false,
    /** « Noter » une carte de proposition. */
    onNote: (ChatCard) -> Unit = {},
) {
    when (card) {
        is RecordCard -> if (undone) {
            UndoneRow(card, superseded, notice, onRestore)
        } else {
            RecordCardView(card, animate, onUndo, onCorrect, questionPending, superseded, notice)
        }
        is SourcesCard -> SourcesView(card)
        is UndoCard -> UndoNotice(card, undone, superseded, notice, onUndo, onRestore)
        is ProductCard -> if (undone) UndoneProductRow(card) else ProductCardView(card, onUndo)
        is GoalCard -> GoalCardView(card, undone, animate, onUndo)
        is DishCard -> DishCardView(card, undone, onUndo)
        is ProvisionsCard -> ProvisionsCardView(card, undone, onUndo)
        is ProposalCard -> ProposalCardView(card, noted, animate, onNote)
        // Cartes du bilan du lundi : rendues par l'écran de bilan (ui/review), qui a l'ouverture du rapport.
        is app.trackly.assistant.cards.WeekInfoCard, is app.trackly.assistant.cards.ReportReadyCard,
        is app.trackly.assistant.cards.WeightGoalCard -> Unit
        is app.trackly.assistant.cards.TemporaryGoalCard -> Unit
    }
}

internal val CardShape = RoundedCornerShape(12.dp)

@Composable
private fun RecordCardView(
    card: RecordCard,
    animate: Boolean,
    onUndo: (ChatCard) -> Unit,
    onCorrect: (ChatCard) -> Unit,
    questionPending: Boolean,
    superseded: String?,
    notice: String?,
) {
    val motion = LocalMotion.current
    val play = animate && !motion.reduced
    // Déroulé : la carte se révèle de haut en bas, puis chaque partie monte en fondu.
    val unspool = remember(card.actionId) { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(card.actionId) {
        if (play) unspool.animateTo(1f, motion.tween(CardMotion.UNSPOOL, easing = TracklyEasing.Soft))
    }
    val status = when (card.kind) {
        RecordKind.Added -> "Ajouté"
        RecordKind.Corrected -> "Corrigé"
        RecordKind.Deleted -> "Supprimé"
        RecordKind.Restored -> "Rétabli"
    }
    val total = card.totalKcal.roundToInt()
    val before = card.beforeTotalKcal?.roundToInt()?.takeIf { it != total }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(CardTags.RECORD)
            .semantics {
                contentDescription = "$status, ${card.momentsLabel}, " +
                    (if (before != null) "$before puis " else "") + "$total kilocalories" +
                    (if (superseded != null) ", $superseded" else "")
            }
            .graphicsLayer {
                translationY = (1f - unspool.value) * -10.dp.toPx()
            }
            .drawWithContent {
                // Révélation de haut en bas (clip), ombre comprise sous la carte.
                val p = unspool.value
                if (p >= 1f) {
                    drawContent()
                } else {
                    clipRect(left = -60.dp.toPx(), top = 0f, right = size.width + 60.dp.toPx(), bottom = (size.height + 90.dp.toPx()) * p) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Part(0, play) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f).padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
                    }
                    Spacer(Modifier.width(7.dp))
                    BasicText(
                        buildAnnotatedString {
                            append("$status · ")
                            withStyle(SpanStyle(color = TracklyColors.Ink)) { append(card.momentsLabel) }
                        },
                        style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt),
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    // Correction : le total d'avant, puis le nouveau.
                    if (before != null) {
                        BasicText(
                            "${formatKcal(before)} →",
                            style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink3),
                            modifier = Modifier.padding(bottom = 3.dp, end = 6.dp),
                        )
                    }
                    Odometer(total, TracklyType.CardTotal, delayMs = if (play) 500 else 0)
                    Spacer(Modifier.width(5.dp))
                    BasicText("kcal", style = TracklyType.CardTotalUnit.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        Rule(5.dp, play, delayMs = 350)
        card.items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(TracklySpacing.Hairline).background(TracklyColors.Line))
            Part(2 + i, play) {
                Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        buildAnnotatedString {
                            append(item.label)
                            // Les cartes enregistrées avant l'accord de l'unité (« 2 pièce ») sont remises d'accord à l'affichage.
                            val quantity = item.quantity?.let(QuantityText::agree)?.let { q ->
                                item.beforeQuantity?.let { "${QuantityText.agree(it)} → $q" } ?: q
                            }
                            quantity?.let { q ->
                                withStyle(SpanStyle(color = TracklyColors.Ink3, fontWeight = TracklyType.CardItemDetail.fontWeight)) { append("  $q") }
                            }
                        },
                        style = TracklyType.CardItem.copy(
                            color = TracklyColors.Ink,
                            textDecoration = if (card.kind == RecordKind.Deleted) TextDecoration.LineThrough else null,
                        ),
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                    )
                    if (item.kcalChanged) {
                        BasicText(
                            "${formatKcal(item.beforeKcal!!.roundToInt())} →",
                            style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3),
                            modifier = Modifier.padding(end = 5.dp, bottom = 1.dp),
                        )
                    }
                    Odometer(item.kcal.roundToInt(), TracklyType.CardItemKcal, delayMs = if (play) 950 + i * 150 else 0)
                }
            }
        }
        Rule(2.dp, play, delayMs = 900)
        Part(2 + card.items.size, play) { MacroRow(card) }
        val assumptions = card.assumptionLines
        if (assumptions.isNotEmpty()) {
            Part(3 + card.items.size, play) { Assumptions(card.actionId, assumptions) }
        }
        card.question?.takeIf { questionPending }?.let { question ->
            Part(4 + card.items.size, play) {
                BasicText(
                    "Question en attente : $question",
                    style = TracklyType.Notice.copy(color = TracklyColors.Cobalt),
                    modifier = Modifier.padding(top = 9.dp).testTag(CardTags.QUESTION),
                )
            }
        }
        if (card.memorized.isNotEmpty() || card.doubts.isNotEmpty() || card.recurrence.isNotEmpty()) {
            Part(5 + card.items.size, play) { ProductNotes(card) }
        }
        if (superseded != null) {
            Part(6 + card.items.size, play) { SupersededNote(superseded) }
        }
        notice?.let { CardNotice(it) }
        Part(6 + card.items.size, play) {
            val enabled = superseded == null
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO, enabled = enabled) { onUndo(card) }
                if (card.kind != RecordKind.Deleted) CardButton("Corriger", Glyph.Pen, ghost = false, tag = CardTags.CORRECT, enabled = enabled) { onCorrect(card) }
            }
        }
    }
}

/**
 * Hypothèses d'estimation : repliées par défaut, une ligne qui se déroule d'un toucher. Le bloc replié
 * garde le filet et l'icône d'avant ; TalkBack lit « 2 hypothèses d'estimation, Repliées », propose d'afficher
 * (ou masquer) et expose les actions déplier / replier ; l'état suit la carte au défilement et à la rotation.
 */
@Composable
private fun Assumptions(actionId: Long, lines: List<String>) {
    var expanded by rememberSaveable(actionId) { mutableStateOf(false) }
    val motion = LocalMotion.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, motion.tween(200), label = "hypotheses")
    val spoken = if (lines.size == 1) "Hypothèse d’estimation" else "${lines.size} hypothèses d’estimation"
    Column(Modifier.fillMaxWidth().drawTopLine().testTag(CardTags.ASSUMPTIONS)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(CardTags.ASSUMPTIONS_TOGGLE)
                .pressable(
                    onClick = { expanded = !expanded },
                    pressedScale = 0.99f,
                    onClickLabel = if (expanded) "Masquer les hypothèses" else "Afficher les hypothèses",
                )
                .semantics {
                    contentDescription = spoken
                    stateDescription = if (expanded) "Dépliées" else "Repliées"
                    if (expanded) collapse { expanded = false; true } else expand { expanded = true; true }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Contenu visuel masqué à TalkBack : la description ci-dessus le remplace.
            Row(Modifier.weight(1f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Info, TracklyColors.Ink3, Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                BasicText(
                    if (lines.size == 1) "Hypothèse" else "Hypothèses (${lines.size})",
                    style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2),
                    modifier = Modifier.weight(1f),
                )
                GlyphIcon(Glyph.ChevronRight, TracklyColors.Ink3, Modifier.size(14.dp).graphicsLayer { rotationZ = turn }, stroke = 1.8f)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(finite(motion, 220)) + fadeIn(finite(motion, 220)),
            exit = shrinkVertically(finite(motion, 180)) + fadeOut(finite(motion, 120)),
        ) {
            // Une hypothèse par ligne, alignée sous le libellé.
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (line in lines) {
                    BasicText(
                        if (lines.size == 1) line else "– $line",
                        style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2),
                    )
                }
            }
        }
    }
}

/** Animation d'une durée donnée, instantanée quand le système réduit les mouvements. */
private fun <T> finite(motion: MotionPolicy, durationMs: Int): FiniteAnimationSpec<T> =
    if (motion.reduced) snap() else tween(durationMs, easing = TracklyEasing.Soft)

/** Carte périmée : ce qui s'est passé depuis, en clair, au lieu d'un bouton qui échouerait. */
@Composable
private fun SupersededNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag(CardTags.SUPERSEDED)
            .clip(RoundedCornerShape(8.dp))
            .background(TracklyColors.Mist)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Pen, TracklyColors.Cobalt, Modifier.size(13.dp), stroke = 1.8f)
        Spacer(Modifier.width(7.dp))
        BasicText(text, style = TracklyType.Notice.copy(color = TracklyColors.Ink))
    }
}

/** Message propre à une carte (refus d'annuler…), dit sur la carte même. */
@Composable
private fun CardNotice(text: String) {
    BasicText(
        text,
        style = TracklyType.Notice.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .padding(top = 9.dp)
            .testTag(CardTags.CARD_NOTICE)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}


/**
 * Produits de l'enregistrement : fiche mémorisée, « est-ce celui-ci ? », proposition de nommer
 * un produit remangé. L'utilisateur répond par une phrase dans le chat.
 */
@Composable
private fun ProductNotes(card: RecordCard) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(CardTags.PRODUCTS),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (product in card.memorized) {
            BasicText("Produit mémorisé : ${product.name}", style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2))
        }
        for (doubt in card.doubts) {
            BasicText(
                "Est-ce celui-ci ? ${doubt.chosen.name}. Sinon : ${doubt.others.joinToString(" ou ") { it.name }}.",
                style = TracklyType.Notice.copy(color = TracklyColors.Cobalt),
            )
        }
        for (offer in card.recurrence) {
            BasicText(
                "Déjà pris : ${offer.productName}. Tu peux en faire un produit récurrent : dis-moi le nom à lui donner.",
                style = TracklyType.Notice.copy(color = TracklyColors.Cobalt),
            )
        }
    }
}

/** Carte d'une opération sur un produit (mémorisé, nommé, renommé, raccourci). */
@Composable
private fun ProductCardView(card: ProductCard, onUndo: (ChatCard) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .testTag(CardTags.PRODUCT)
            .semantics { contentDescription = "${card.status}, ${card.name}" }
            .softShadow(CardShape, radius = 34.dp, spread = (-18).dp, y = 22.dp, alpha = 0.35f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line2, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(TracklyColors.Cobalt), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, TracklyColors.PaperHi, Modifier.size(12.dp), stroke = 2.6f)
            }
            Spacer(Modifier.width(7.dp))
            BasicText(card.status, style = TracklyType.CardStatus.copy(color = TracklyColors.Cobalt))
        }
        BasicText(card.name, style = TracklyType.CardItem.copy(color = TracklyColors.Ink), modifier = Modifier.padding(top = 9.dp))
        card.detail?.let { BasicText(it, style = TracklyType.CardAssumption.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(top = 3.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Annuler", Glyph.Undo, ghost = true, tag = CardTags.UNDO) { onUndo(card) }
        }
    }
}

@Composable
private fun UndoneProductRow(card: ProductCard) {
    DashedRow(tag = CardTags.UNDONE, description = "Annulé, ${card.status}, ${card.name}") {
        BasicText(
            buildAnnotatedString {
                append("Annulé · ")
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append("${card.status} : ${card.name}") }
            },
            style = TracklyType.Undone.copy(color = TracklyColors.Ink3),
        )
    }
}

/** Partie de la carte qui monte en fondu, à son tour dans la cascade. */
@Composable
internal fun Part(index: Int, play: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (play) progress.animateTo(1f, motion.tween(CardMotion.PART, delayMs = CardMotion.PART_DELAY + index * CardMotion.STAGGER))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * 10.dp.toPx()
        },
    ) { content() }
}

/** Filet épais de l'étiquette nutritionnelle, qui se trace de gauche à droite. */
@Composable
internal fun Rule(thickness: androidx.compose.ui.unit.Dp, play: Boolean, delayMs: Int) {
    val motion = LocalMotion.current
    val draw = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (play) draw.animateTo(1f, motion.tween(CardMotion.PART, delayMs = delayMs, easing = TracklyEasing.Soft))
    }
    Box(
        Modifier
            .padding(top = if (thickness > 2.dp) 10.dp else 0.dp)
            .fillMaxWidth()
            .height(thickness)
            .graphicsLayer {
                scaleX = draw.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .background(TracklyColors.Ink, RoundedCornerShape(1.dp)),
    )
}

/**
 * Macros affichées en rouge sur la carte, pour les tests uniquement (comme l'état d'une barre de l'accueil) : la couleur ne
 * se lit pas dans l'arbre sémantique, TalkBack, lui, entend la phrase de la description.
 */
internal val OverLimitKey = androidx.compose.ui.semantics.SemanticsPropertyKey<List<String>>("OverLimit")
internal var androidx.compose.ui.semantics.SemanticsPropertyReceiver.overLimit: List<String> by OverLimitKey

@Composable
private fun MacroRow(card: RecordCard) = MacroRow(card.protein, card.carbs, card.fat, overLimit = card.overLimit)

/**
 * Protéines, glucides et lipides d'une carte ; une macro incomplète est une borne basse (« ≥ »).
 * [overLimit] : les macros hors objectif au moment de l'enregistrement, dont le texte passe en rouge. La pastille, elle,
 * garde sa texture. Les glucides n'ont jamais de rouge. Une carte sans cette information (ancienne) s'affiche comme avant.
 */
@Composable
internal fun MacroRow(p: CardMacro, c: CardMacro, f: CardMacro, overLimit: List<String> = emptyList()) {
    val pBad = RecordAlerts.PROTEIN in overLimit
    val fBad = RecordAlerts.FAT in overLimit
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
            .clearAndSetSemantics {
                // Un fait, sans jugement. Le rouge parle de la journée, le chiffre de la carte est celui du repas : la phrase le précise.
                val protein = if (pBad) "${spoken(p)}, minimum du jour non atteint" else spoken(p)
                val fat = if (fBad) "${spoken(f)}, limite du jour dépassée" else spoken(f)
                contentDescription = "Protéines $protein, glucides ${spoken(c)}, lipides $fat"
                if (overLimit.isNotEmpty()) this.overLimit = overLimit
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Macro("P", p, TracklyColors.Protein, hatched = false, bad = pBad)
        Macro("G", c, TracklyColors.Carbs, hatched = false, bad = false)
        Macro("L", f, TracklyColors.Fat, hatched = true, bad = fBad)
    }
}

/** Macro : pastille distinguée par la valeur et la texture (encre, cobalt clair, cobalt hachuré). [bad] : texte en rouge. */
@Composable
private fun RowScope.Macro(letter: String, macro: CardMacro, color: Color, hatched: Boolean, bad: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (hatched) TracklyColors.FatHatchGround else color)
                .then(if (hatched) Modifier.drawWithContent { drawHatch(color) } else Modifier),
        )
        Spacer(Modifier.width(5.dp))
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight(700))) { append(letter) }
                withStyle(SpanStyle(color = if (bad) TracklyColors.Bad else TracklyColors.Ink2)) { append(" ${amount(macro)}") }
            },
            style = TracklyType.CardMacros.copy(color = if (bad) TracklyColors.Bad else TracklyColors.Ink),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHatch(color: Color) {
    val step = 2.5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
        x += step
    }
}

private fun amount(macro: CardMacro): String = (if (macro.complete) "" else "≥ ") + "${formatNumber((macro.grams * 10).roundToInt() / 10.0)} g"

private fun spoken(macro: CardMacro): String =
    (if (macro.complete) "" else "au moins ") + "${formatNumber((macro.grams * 10).roundToInt() / 10.0)} grammes"

private val ButtonShape = RoundedCornerShape(9.dp)

@Composable
internal fun RowScope.CardButton(
    text: String,
    glyph: Glyph,
    ghost: Boolean,
    tag: String,
    enabled: Boolean = true,
    /** Ce que TalkBack annonce (par défaut, le texte du bouton). */
    description: String = text,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .weight(1f)
            .height(40.dp)
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.96f, enabled = enabled)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.4f)
            .clip(ButtonShape)
            .background(if (ghost) TracklyColors.PaperHi else TracklyColors.Mist)
            .border(if (ghost) 1.5.dp else TracklySpacing.Hairline, if (ghost) TracklyColors.Ink else TracklyColors.Line2, ButtonShape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (ghost) TracklyColors.Ink else TracklyColors.Cobalt, Modifier.size(15.dp), stroke = 2f)
        Spacer(Modifier.width(7.dp))
        BasicText(text, style = TracklyType.CardButton.copy(color = if (ghost) TracklyColors.Ink else TracklyColors.Cobalt))
    }
}

/**
 * Carte annulée : une ligne en pointillés, le contenu barré, et « Rétablir » :
 * l'annulation est défaite sur le téléphone, sans appel à l'assistant.
 */
@Composable
private fun UndoneRow(card: RecordCard, superseded: String?, notice: String?, onRestore: (ChatCard) -> Unit) {
    val label = "${card.momentsLabel} · ${card.totalKcal.roundToInt()} kcal"
    val verb = when (card.kind) {
        RecordKind.Added, RecordKind.Restored -> "retiré du journal"
        else -> "annulé"
    }
    Column(Modifier.fillMaxWidth()) {
        DashedRow(
            tag = CardTags.UNDONE,
            description = "Annulé, $label $verb",
            trailing = if (superseded == null) ({ RestoreButton { onRestore(card) } }) else null,
        ) {
            BasicText(
                buildAnnotatedString {
                    append("Annulé · ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(label) }
                    append(" $verb")
                },
                style = TracklyType.Undone.copy(color = TracklyColors.Ink3),
            )
        }
        superseded?.let { SupersededNote(it) }
        notice?.let { CardNotice(it) }
    }
}

/**
 * Annulation (ou rétablissement) demandée par une phrase (« annule ça »). Elle se défait d'un toucher :
 * « Rétablir » après une annulation, « Annuler » après un rétablissement.
 */
@Composable
private fun UndoNotice(card: UndoCard, undone: Boolean, superseded: String?, notice: String?, onUndo: (ChatCard) -> Unit, onRestore: (ChatCard) -> Unit) {
    // L'action de la carte, si elle a elle-même été défaite, montre l'effet inverse.
    val showsRestored = card.restores != undone
    val text = (if (showsRestored) "Rétabli : " else "Annulé : ") + card.subject
    Column(Modifier.fillMaxWidth()) {
        DashedRow(
            tag = CardTags.UNDONE,
            description = text,
            trailing = if (superseded == null) {
                {
                    val act = { if (undone) onRestore(card) else onUndo(card) }
                    if (showsRestored) RestoreButton(label = "Annuler", tag = CardTags.UNDO, onClick = act) else RestoreButton(onClick = act)
                }
            } else {
                null
            },
        ) {
            BasicText(text, style = TracklyType.Undone.copy(color = TracklyColors.Ink3))
        }
        superseded?.let { SupersededNote(it) }
        notice?.let { CardNotice(it) }
    }
}

/** Petit bouton texte d'une ligne en pointillés (« Rétablir »), cible de 48 dp de haut. */
@Composable
private fun RestoreButton(label: String = "Rétablir", tag: String = CardTags.RESTORE, onClick: () -> Unit) {
    BasicText(
        label,
        style = TracklyType.CardButton.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 40.dp)
            .pressable(onClick, pressedScale = 0.96f)
            .semantics { contentDescription = label }
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 10.dp),
    )
}

@Composable
internal fun DashedRow(
    tag: String,
    description: String,
    /** Bouton à droite de la ligne (« Rétablir »), hors du texte lu d'un bloc. */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    TracklyColors.Line2,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp, top = if (trailing != null) 0.dp else 10.dp, bottom = if (trailing != null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .testTag(tag)
                .clearAndSetSemantics { contentDescription = description }
                .padding(vertical = if (trailing != null) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Undo, TracklyColors.Ink3, Modifier.size(14.dp), stroke = 1.8f)
            Spacer(Modifier.width(8.dp))
            content()
        }
        trailing?.invoke()
    }
}

internal fun Modifier.drawTopLine(): Modifier = drawWithContent {
    drawContent()
    drawRect(TracklyColors.Line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}
