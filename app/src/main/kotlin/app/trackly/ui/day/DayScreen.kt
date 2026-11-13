package app.trackly.ui.day

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.trackly.core.model.Moment
import app.trackly.core.overview.Bar
import app.trackly.core.overview.BarState
import app.trackly.day.DayView
import app.trackly.day.MacroAmount
import app.trackly.day.MomentSlot
import app.trackly.ui.components.ArrowButton
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.Odometer
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.components.softShadow
import app.trackly.ui.components.trackCenter
import app.trackly.ui.theme.LocalMotion
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyDurations
import app.trackly.ui.theme.TracklyEasing
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Délai avant que les totaux ne commencent à tourner au lancement (120 + 250 ms). */
private const val BOOT_TOTALS_DELAY = 370

/**
 * Écran du jour (macros en barres longues) : calories restantes en odomètre, jauge, macros en lignes,
 * carte des repas, puis la barre du bas « Écrire à Trackly… », « Aliments », micro et « Bilan ».
 * Glisser horizontalement change de jour.
 */
@Composable
fun DayScreen(
    days: List<DayView>,
    index: Int,
    todayIndex: Int,
    onIndexChange: (Int) -> Unit,
    onOpenChat: (origin: Offset) -> Unit,
    modifier: Modifier = Modifier,
    /** Appui long sur « Trackly » : panneau de développement (null = désactivé). */
    onOpenDevPanel: (() -> Unit)? = null,
    /** Toucher la date : calendrier pour aller plus loin (null = désactivé). */
    onOpenCalendar: (() -> Unit)? = null,
    /** Toucher « Trackly » : réglages (null = désactivé). */
    onOpenSettings: (() -> Unit)? = null,
    /** « Aliments » de la barre du bas : ouvre « Mes aliments » (null = place vide). */
    onOpenDishes: (() -> Unit)? = null,
    /** Feuille d'un repas : supprimer, corriger, annuler (null = les lignes de repas ne s'ouvrent pas). */
    meals: MealActions? = null,
    /** Une ligne d'alerte discrète sous l'en-tête (sauvegarde ancienne, notifications bloquées) ; `null` : rien. */
    alert: DayAlert? = null,
    /**
     * Ajouter **sur le jour affiché** : champ de la barre du bas ([Moment] `null`) ou « + » de la feuille d'un repas.
     * Le chat s'ouvre prérempli pour ce jour et ce moment (« Pour dimanche 1er novembre : » sur un jour passé).
     * `null` : retour à aujourd'hui, puis [onOpenChat].
     */
    onAdd: ((origin: Offset, moment: Moment?) -> Unit)? = null,
    /**
     * Gros micro de la barre du bas : même ouverture que le champ (jour affiché), sans clavier, et la dictée démarre.
     * `null` (pas de dictée) : le micro ouvre le chat comme le champ.
     */
    onDictate: ((origin: Offset) -> Unit)? = null,
    /** « Bilan » de la barre du bas : ouvre la conversation de bilan (null = place vide). */
    onOpenReview: (() -> Unit)? = null,
    /** Le bilan est à faire : pastille sur « Bilan » (elle remplace l'ancienne ligne « Bilan de la semaine »). */
    reviewDue: Boolean = false,
    /** Bouton d'appel au bout du champ de la barre du bas : un toucher lance l'appel (null = pas de bouton). */
    onCall: (() -> Unit)? = null,
    /** Questions laissées dans le chat après un appel et pas encore vues : pastille sur le champ (0 : aucune). */
    chatQuestions: Int = 0,
) {
    val motion = LocalMotion.current
    val day = days[index]
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Entrée orchestrée au lancement (mouvements réduits : tout est déjà en place).
    var entered by remember { mutableStateOf(motion.reduced) }
    var booted by remember { mutableStateOf(motion.reduced) }
    LaunchedEffect(Unit) {
        entered = true
        delay(motion.delayMs(1500))
        booted = true
    }
    val totalsDelay = if (booted) 0 else BOOT_TOTALS_DELAY

    // Changement de jour : le corps sort d'un côté et rentre de l'autre.
    val slide = remember { Animatable(0f) }
    val bodyAlpha = remember { Animatable(1f) }
    var busy by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(0) }
    val travel = with(density) { 44.dp.toPx() }
    fun settle() {
        scope.launch { slide.animateTo(0f, motion.tween(500)) }
    }

    /** Change de jour ; faux si le jour n'existe pas ou si une transition est en cours. */
    fun go(dir: Int): Boolean {
        val next = index + dir
        if (next !in days.indices || busy) return false
        busy = true
        direction = dir
        scope.launch {
            try {
                if (!motion.reduced) {
                    launch { bodyAlpha.animateTo(0f, tween(TracklyDurations.DAY_OUT, easing = TracklyEasing.DayOut)) }
                    slide.animateTo(-dir * travel, tween(TracklyDurations.DAY_OUT, easing = TracklyEasing.DayOut))
                }
                onIndexChange(next)
                if (!motion.reduced) {
                    slide.snapTo(dir * travel)
                    launch { bodyAlpha.animateTo(1f, tween(TracklyDurations.DAY_IN, easing = TracklyEasing.Soft)) }
                    slide.animateTo(0f, tween(TracklyDurations.DAY_IN, easing = TracklyEasing.DayIn))
                }
            } finally {
                // Même interrompue, la transition rend la main avec un corps visible et en place.
                busy = false
                scope.launch {
                    slide.snapTo(0f)
                    bodyAlpha.snapTo(1f)
                }
            }
        }
        return true
    }

    // Saut de date (calendrier, retour à aujourd'hui) : le corps entre du côté de la nouvelle date.
    var shownIndex by remember { mutableIntStateOf(index) }
    LaunchedEffect(index) {
        val dir = (index - shownIndex).coerceIn(-1, 1)
        shownIndex = index
        if (dir == 0 || busy) return@LaunchedEffect
        direction = dir
        if (!motion.reduced) {
            slide.snapTo(dir * travel)
            bodyAlpha.snapTo(0f)
            launch { bodyAlpha.animateTo(1f, tween(TracklyDurations.DAY_IN, easing = TracklyEasing.Soft)) }
            slide.animateTo(0f, tween(TracklyDurations.DAY_IN, easing = TracklyEasing.DayIn))
        }
    }

    // Feuille d'un repas ouverte (par son moment) ; fermée si le jour change ou si le repas disparaît.
    var openMoment by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(day.date) { openMoment = null }
    val sheetSlot = openMoment?.let { name -> day.slots.firstOrNull { it.moment.name == name && it.meals.isNotEmpty() } }
    val sheetOpen = meals != null && sheetSlot != null
    // Ajouter à un moment précis (feuille d'un repas, action TalkBack d'une ligne) : chat prérempli pour ce moment.
    val canAdd = day.isToday || onAdd != null
    val addTo: (Offset, Moment) -> Unit = { origin, moment -> onAdd?.invoke(origin, moment) ?: onOpenChat(origin) }
    // Champ de la barre du bas : ajoute sur le jour affiché, comme l'ancien « + » du bas.
    val write: (Offset) -> Unit = { origin ->
        // Une transition de jour en cours écraserait le jour visé.
        if (!busy) {
            if (onAdd != null) {
                onAdd(origin, null)
            } else {
                if (index != todayIndex) onIndexChange(todayIndex)
                onOpenChat(origin)
            }
        }
    }
    var dockHeight by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxSize()) {
        // Feuille ouverte : le jour, caché derrière, sort de l'arbre d'accessibilité (TalkBack).
        Box(Modifier.fillMaxSize().then(if (sheetOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
        PorcelainBackdrop(nudge = day.date.toEpochDay().toInt())
        Column(Modifier.fillMaxSize()) {
            DayHead(
                day = day,
                direction = direction,
                canGoBack = index > 0,
                canGoForward = index < days.lastIndex,
                onBack = { go(-1) },
                onForward = { go(1) },
                entered = entered,
                onOpenDevPanel = onOpenDevPanel,
                onOpenCalendar = onOpenCalendar,
                onOpenSettings = onOpenSettings,
            )
            alert?.let { AlertStrip(it) }
            Column(
                Modifier
                    .weight(1f)
                    .testTag(DayTags.BODY)
                    .graphicsLayer {
                        translationX = slide.value
                        alpha = bodyAlpha.value.coerceIn(0f, 1f)
                    }
                    .pointerInput(index, days.size, busy) {
                        val threshold = 56.dp.toPx()
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                // Pendant une transition, on ne touche pas à `slide` : elle se termine seule.
                                if (!busy && !(abs(dx) > threshold && go(if (dx < 0) 1 else -1))) settle()
                            },
                            onDragCancel = { if (!busy) settle() },
                        ) { change, amount ->
                            if (busy) return@detectHorizontalDragGestures
                            change.consume()
                            dx += amount
                            val atLimit = (dx < 0 && index == days.lastIndex) || (dx > 0 && index == 0)
                            scope.launch { slide.snapTo(dx * if (atLimit) 0.12f else 0.38f) }
                        }
                    },
            ) {
                DayBody(
                    day,
                    totalsDelay,
                    entered,
                    canAdd = canAdd,
                    onAddTo = addTo,
                    onOpenMeal = if (meals != null) ({ slot -> openMoment = slot.moment.name }) else null,
                    // La carte des repas finit où commence la barre du bas (le haut de son dégradé est transparent) ;
                    // au-delà, le défilement passe derrière le dégradé.
                    bottomReserve = with(density) { dockHeight.toDp() },
                )
            }
        }
        Dock(
            entered = entered,
            onWrite = write,
            onDictate = { origin -> if (onDictate == null) write(origin) else if (!busy) onDictate(origin) },
            onOpenDishes = onOpenDishes,
            onOpenReview = onOpenReview,
            reviewDue = reviewDue,
            onCall = onCall,
            chatQuestions = chatQuestions,
            onSize = { dockHeight = it },
        )
        }
        if (meals != null) {
            if (sheetSlot != null) {
                MealSheet(
                    day = day,
                    slot = sheetSlot,
                    onClose = { openMoment = null },
                    onDelete = {
                        openMoment = null
                        meals.onDelete(sheetSlot.consumptionIds, "${sheetSlot.moment.label} supprimé")
                    },
                    onCorrect = { origin ->
                        openMoment = null
                        meals.onCorrect(sheetSlot.consumptionIds, correctionDraft(day, sheetSlot))
                        onOpenChat(origin)
                    },
                    onAdd = if (canAdd) {
                        { origin ->
                            openMoment = null
                            addTo(origin, sheetSlot.moment)
                        }
                    } else {
                        null
                    },
                )
            }
            meals.undoLabel?.let { UndoBanner(it, meals.onUndo) }
        }
    }
}

/**
 * Alerte d'accueil : une phrase et, si on peut y répondre, un geste ([actionLabel]).
 * [opensSettings] : le geste ouvre les Réglages (la racine branche l'ouverture) ; sinon [onAction] fait le travail.
 */
class DayAlert(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val opensSettings: Boolean = false,
)

/**
 * Ligne discrète sous l'en-tête : texte secondaire, un seul geste en cobalt. Pas de cadre, pas de couleur d'alerte.
 * Avec une grande police, le geste passe sous le texte (sinon la phrase se casse en quatre lignes étroites).
 */
@Composable
private fun AlertStrip(alert: DayAlert) {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val text: @Composable (Modifier) -> Unit = { modifier ->
        BasicText(
            alert.text,
            style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
            modifier = modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val action: @Composable () -> Unit = {
        val onAction = alert.onAction
        if (alert.actionLabel != null && onAction != null) {
            BasicText(
                alert.actionLabel,
                style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
                modifier = Modifier
                    .testTag(DayTags.ALERT_ACTION)
                    .pressable(onAction, pressedScale = 0.94f)
                    .semantics { role = Role.Button }
                    .heightIn(min = TracklySpacing.TouchTarget)
                    .padding(horizontal = 2.dp, vertical = 14.dp),
            )
        }
    }
    val frame = Modifier.fillMaxWidth().padding(horizontal = TracklySpacing.Gutter).testTag(DayTags.ALERT)
    if (stacked) {
        Column(frame) {
            text(Modifier.fillMaxWidth())
            action()
        }
    } else {
        Row(frame, verticalAlignment = Alignment.CenterVertically) {
            text(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

/**
 * Ce que l'écran du jour demande pour la feuille d'un repas. Supprimer passe par une action du moteur,
 * annulable tant que [undoLabel] est affiché (bandeau « Annuler »).
 */
class MealActions(
    val onDelete: (consumptionIds: List<String>, label: String) -> Unit,
    /**
     * « Corriger » : point d'accroche du chat. L'écran appelle ceci, puis ouvre le chat.
     * [consumptionIds] : les entrées du repas (pour cibler la correction sans deviner) ;
     * [draft] : le brouillon prêt à l'emploi, « Corrige le midi (Riz, poulet) : » (voir [correctionDraft]).
     * Comportement actuel (TracklyRoot) : le brouillon est mis dans la zone de saisie du chat, que l'utilisateur complète.
     * Le chat pourrait, à la place, lancer une vraie correction ciblée sur [consumptionIds] avec la carte « avant → après ».
     */
    val onCorrect: (consumptionIds: List<String>, draft: String) -> Unit,
    /** Libellé du bandeau « Annuler » (« Midi supprimé ») ; `null` : pas de bandeau. */
    val undoLabel: String? = null,
    val onUndo: () -> Unit = {},
)

/**
 * Brouillon de « Corriger » : « Corrige le midi (Riz basmati, Poulet rôti) : ».
 * Jour passé : « … le midi d’hier (…) : » ou « … le midi du vendredi 4 septembre (…) : », pour que la correction vise le bon jour.
 */
internal fun correctionDraft(day: DayView, slot: MomentSlot): String {
    val moment = slot.moment.label.lowercase()
    val quand = when {
        day.isToday -> ""
        day.relative != null -> " d’${day.relative.lowercase()}"
        else -> " du ${app.trackly.day.DayLabels.inSentence(day.date)}"
    }
    val foods = slot.description?.let { " ($it)" }.orEmpty()
    return "Corrige le $moment$quand$foods : "
}

// ───── En-tête ─────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DayHead(
    day: DayView,
    direction: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    entered: Boolean,
    onOpenDevPanel: (() -> Unit)?,
    onOpenCalendar: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val p = rememberEntrance(entered, TracklyDurations.HEAD_IN, easingSoft = true)
    // Les deux blocs passent à la ligne quand la place manque (grande taille de police) au lieu de se chevaucher.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 4.dp, start = TracklySpacing.Gutter, end = TracklySpacing.Gutter)
            .graphicsLayer {
                alpha = p.value.coerceIn(0f, 1f)
                translationY = (1f - p.value) * -6.dp.toPx()
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        BrandButton(onOpenSettings, onOpenDevPanel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArrowButton(Glyph.ChevronLeft, "Jour précédent", canGoBack, onBack)
            DayLabel(day, direction, onOpenCalendar)
            ArrowButton(Glyph.ChevronRight, "Jour suivant", canGoForward, onForward)
        }
    }
}

/**
 * « Trackly » et l'icône Réglages : toucher ouvre les réglages. L'appui long ouvre la saisie manuelle :
 * raccourci caché, sans libellé ; l'entrée visible n'existe que dans le chat, faute de réseau.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrandButton(onOpenSettings: (() -> Unit)?, onOpenDevPanel: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val actionable = onOpenSettings != null || onOpenDevPanel != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = TracklySpacing.TouchTarget)
            .then(
                if (actionable) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = if (onOpenSettings != null) "Ouvrir les réglages" else null,
                        onClick = { onOpenSettings?.invoke() },
                        onLongClick = onOpenDevPanel,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size(9.dp).border(1.5.dp, TracklyColors.Ink, CircleShape))
        Spacer(Modifier.width(8.dp))
        BasicText("Trackly", style = TracklyType.Brand.copy(color = TracklyColors.Ink), maxLines = 1)
        if (onOpenSettings != null) {
            Spacer(Modifier.width(8.dp))
            // Icône visible : sans elle, rien n'indique que « Trackly » ouvre les réglages.
            GlyphIcon(Glyph.Settings, TracklyColors.Ink2, Modifier.size(18.dp), stroke = 1.4f)
        }
    }
}

@Composable
private fun DayLabel(day: DayView, direction: Int, onOpenCalendar: (() -> Unit)?) {
    val motion = LocalMotion.current
    val shift = with(LocalDensity.current) { 26.dp.roundToPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = (
            if (onOpenCalendar == null) {
                Modifier
            } else {
                Modifier
                    .pressable(onOpenCalendar, pressedScale = 0.96f, onClickLabel = "Ouvrir le calendrier")
            }
            )
            .heightIn(min = TracklySpacing.TouchTarget)
            // Toujours un cran de plus que la date : « Aujourd’hui, lundi 2 novembre ».
            .semantics(mergeDescendants = true) { contentDescription = day.spokenLabel },
    ) {
        AnimatedDayLabel(day, direction, motion.reduced, shift)
        if (onOpenCalendar != null) {
            GlyphIcon(Glyph.Calendar, TracklyColors.Ink3, Modifier.padding(end = 4.dp).size(12.dp), stroke = 1.4f)
        }
    }
}

@Composable
private fun AnimatedDayLabel(day: DayView, direction: Int, reduced: Boolean, shift: Int) {
    AnimatedContent(
        targetState = day.relative to day.shortLabel,
        modifier = Modifier
            .widthIn(min = 84.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
        transitionSpec = {
            if (reduced || direction == 0) {
                ContentTransform(EnterTransition.None, ExitTransition.None)
            } else {
                val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(TracklyDurations.DAY_LABEL, easing = TracklyEasing.Spring)
                (slideInHorizontally(slideSpec) { direction * shift } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(slideSpec) { -direction * shift } + fadeOut(tween(300)))
            }
        },
        label = "jour",
    ) { (relative, date) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (relative != null) {
                BasicText(
                    relative.uppercase(),
                    style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt, textAlign = TextAlign.Center),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            BasicText(
                date,
                style = TracklyType.DayLabel.copy(color = TracklyColors.Ink, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

// ───── Mise en page ─────

/** Durée du changement de hauteur de la carte des repas (un repas arrive ou part). */
private const val LAYOUT_MS = 620

/** Espace entre les blocs du corps (bilan, macros, repas). */
private val BlockGap = 18.dp

/** Moments affichés : seulement ceux où quelque chose est noté. */
internal fun DayView.visibleSlots(): List<MomentSlot> = slots.filter { it.meals.isNotEmpty() }

/** Lignes de la carte des repas : l'estimation journalière, puis chaque moment noté. */
internal val DayView.rowCount: Int get() = visibleSlots().size + (if (estimate != null) 1 else 0)

/**
 * Corps du jour (macros en barres longues) : le bilan reste à la même place quel que soit le nombre de repas,
 * sans centrage à vide ni chiffre qui rétrécit. Seule la carte des repas grandit ; l'espace sous elle varie.
 * Tout défile d'un bloc derrière la barre du bas seulement si ça ne tient pas (grandes polices, journée chargée).
 */
@Composable
private fun DayBody(
    day: DayView,
    totalsDelay: Int,
    entered: Boolean,
    canAdd: Boolean,
    onAddTo: (Offset, Moment) -> Unit,
    onOpenMeal: ((MomentSlot) -> Unit)?,
    bottomReserve: Dp,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(day.date) { scroll.scrollTo(0) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = bottomReserve),
    ) {
        Hero(day, totalsDelay)
        // Jour passé sans rien de noté : ni macros à zéro ni carte vide, la phrase suffit.
        if (!day.untracked) {
            Spacer(Modifier.height(BlockGap + 6.dp))
            Macros(day, totalsDelay, entered)
            Spacer(Modifier.height(BlockGap))
            // Une carte par jour : changer de jour la pose d'emblée, seule l'arrivée ou le départ d'un repas l'anime.
            key(day.date) { MealsCard(day, canAdd, onAddTo, onOpenMeal) }
        }
    }
}

// ───── Calories restantes et jauge ─────

@Composable
private fun Hero(day: DayView, totalsDelay: Int) {
    val headline = day.headline
    val unit = TracklyType.HeroUnit.copy(color = TracklyColors.Ink2)
    // L'unité suit la taille de police ; au-delà de 130 %, elle passe sous le chiffre plutôt que de se couper.
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, start = TracklySpacing.Gutter, end = TracklySpacing.Gutter)) {
        // Espacement porté par la rangée : une étiquette renvoyée à la ligne n'emporte pas d'espace en tête.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(headline.kicker.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt))
            day.energyNote?.let { StatusTag(it.tag) }
            if (day.goals == null) StatusTag("SANS OBJECTIF")
        }
        val headlineKcal = headline.kcal
        // Le grand chiffre ne suit pas la taille de police (au-delà, « 2 410 » déborderait d'un écran de 360 dp avant Android 14).
        val density = LocalDensity.current
        val fixed = Density(density.density, fontScale = 1f)
        val number: @Composable (Modifier) -> Unit = { modifier ->
            CompositionLocalProvider(LocalDensity provides fixed) {
                Odometer(headlineKcal ?: 0, TracklyType.HeroNumber, modifier, delayMs = totalsDelay, groupGap = 0.22.em)
            }
        }
        if (headlineKcal != null && stacked) {
            number(Modifier)
            Spacer(Modifier.height(6.dp))
            BasicText(headline.unit, style = unit)
        } else if (headlineKcal != null) {
            Row {
                number(Modifier.alignByBaseline())
                BasicText(headline.unit, style = unit, modifier = Modifier.alignByBaseline().padding(start = 10.dp))
            }
        } else {
            // Jour passé sans rien de noté : pas de chiffre inventé, une phrase.
            BasicText(headline.unit, style = unit, modifier = Modifier.padding(top = 18.dp, bottom = 18.dp))
        }
        val goal = day.goals
        if (goal != null && headlineKcal != null) {
            // 42 dp : l'œil de la police des chiffres laisse déjà de l'air au-dessus du chiffre (environ 21 dp visibles au-dessus, 50 dessous).
            Spacer(Modifier.height(BlockGap + 24.dp))
            KcalGauge(day.consumedKcal, goal.kcal, delayMs = totalsDelay, atLeast = day.consumedAtLeast, averageMark = day.averageMark(Bar.Kcal), state = day.barState(Bar.Kcal))
        }
    }
}

/** Petite étiquette d'état (« SANS OBJECTIF »). */
@Composable
private fun StatusTag(text: String) {
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        style = TracklyType.SampleTag.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .border(TracklySpacing.Hairline, TracklyColors.Cobalt.copy(alpha = 0.45f), shape)
            .background(TracklyColors.Mist.copy(alpha = 0.6f), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

// ───── Macros ─────

private enum class MacroKind(val label: String, val color: Color, val hatched: Boolean, val bar: Bar) {
    Protein("Protéines", TracklyColors.Protein, false, Bar.Protein),
    Carbs("Glucides", TracklyColors.Carbs, false, Bar.Carbs),
    Fat("Lipides", TracklyColors.Fat, true, Bar.Fat),
}

/** Hauteur d'une barre de macro (barres longues). */
internal val MacroBarHeight = 8.dp

/** Les trois macros en lignes, chacune avec sa barre sur toute la largeur. */
@Composable
private fun Macros(day: DayView, totalsDelay: Int, entered: Boolean) {
    val values = listOf(
        Triple(MacroKind.Protein, day.protein, day.goals?.protein),
        Triple(MacroKind.Carbs, day.carbs, day.goals?.carbs),
        Triple(MacroKind.Fat, day.fat, day.goals?.fat),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = TracklySpacing.Gutter).testTag(DayTags.MACROS),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        values.forEachIndexed { i, (kind, value, goal) ->
            MacroRow(kind, value, goal, day.averageMark(kind.bar), day.barState(kind.bar), i, totalsDelay, entered)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroRow(
    kind: MacroKind,
    amount: MacroAmount,
    goal: Int?,
    /** Position du trait de moyenne (0 à 1), ou `null` : pas de trait. */
    mark: Float?,
    /** État de la barre : seul son remplissage change de couleur. Les glucides n'en ont jamais. */
    state: BarState,
    index: Int,
    totalsDelay: Int,
    entered: Boolean,
) {
    val motion = LocalMotion.current
    val shownState = if (kind == MacroKind.Carbs) BarState.Normal else state
    val valueStyle = TracklyType.MacroRowValue
    val p = rememberEntrance(entered, 700, delayMs = index * 80)
    val value = amount.grams
    val fraction = if (goal != null && goal > 0) (value.toFloat() / goal).coerceIn(0f, 1f) else 0f
    // Dépassement discret (« +18 g ») une fois la cible franchie.
    val over = if (goal != null && goal > 0 && value > goal) value - goal else 0
    val bar = remember { Animatable(if (motion.reduced) fraction else 0f) }
    LaunchedEffect(fraction) {
        bar.animateTo(fraction, motion.tween(TracklyDurations.MACRO_BAR, totalsDelay + 260 + index * 60))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(DayTags.macro(kind.name))
            .graphicsLayer {
                alpha = p.value.coerceIn(0f, 1f)
                translationY = (1f - p.value) * 8.dp.toPx()
            }
            .semantics(mergeDescendants = true) {
                // Le trait de moyenne n'entre pas dans la description : c'est un repère à regarder, jamais annoncé (voir AverageMarkKey).
                if (mark != null) averageMark = mark
                if (shownState != BarState.Normal) barState = shownState
                // Une macro inconnue dans un repas : le total connu est une borne basse.
                val known = if (amount.complete) "$value" else "au moins $value"
                val description = if (goal != null) "${kind.label} : $known sur $goal grammes" else "${kind.label} : $known grammes"
                val withOver = if (over > 0) "$description, $over de plus" else description
                // L'état est dit comme un fait, sans jugement (« attention », « limite dépassée », « minimum non atteint »).
                contentDescription = shownState.spoken(kind.bar)?.let { "$withOver, $it" } ?: withOver
            },
    ) {
        // Étiquette à gauche, valeur à droite ; la valeur passe sous l'étiquette si la place manque (très grande police).
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .border(1.dp, TracklyColors.CobaltLo, CircleShape)
                        .clip(CircleShape)
                        .drawBehind { macroFill(kind, size.width) },
                )
                Spacer(Modifier.width(9.dp))
                // Étiquette courte et redondante (la couleur et la valeur disent déjà quoi) : plafonnée à 130 % de police.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, 1.3f))) {
                    BasicText(kind.label.uppercase(), style = TracklyType.MacroLabel.copy(color = TracklyColors.Ink2), maxLines = 1, softWrap = false)
                }
            }
            Row {
                if (!amount.complete) {
                    // « ≥ » à la taille du chiffre : c'est une valeur basse, pas une note de bas de page.
                    BasicText("≥", style = valueStyle.copy(color = TracklyColors.Ink3), modifier = Modifier.alignByBaseline().padding(end = 3.dp))
                }
                Odometer(value, valueStyle, Modifier.alignByBaseline(), delayMs = totalsDelay + 200 + index * 60)
                BasicText(
                    if (goal != null) "/ $goal g" else "g",
                    style = TracklyType.MacroGoal.copy(color = TracklyColors.Ink3),
                    modifier = Modifier.alignByBaseline().padding(start = 4.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MacroBar(kind, fraction = { bar.value }, mark = mark, state = shownState)
        if (over > 0) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                "+$over g",
                style = TracklyType.MacroGoal.copy(color = TracklyColors.Ink2, textAlign = TextAlign.End),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
        }
    }
}

/**
 * Barre d'une macro :
 * - couleur d'alerte : seul le remplissage change (`Warn` / `Bad`, et pour les lipides les traits des hachures
 *   et leur fond `WarnHatchGround` / `BadHatchGround`) ; le point, l'étiquette, la valeur et « +N g » restent tels quels ;
 * - trait de moyenne : un trait de 1,5 dp dessiné ici ([drawAverageMark]), qui dépasse de 4 dp au-dessus et
 *   au-dessous de la barre (les 8 dp sous la valeur et les 4 dp avant « +N g » lui laissent la place).
 */
@Composable
private fun MacroBar(kind: MacroKind, fraction: () -> Float, mark: Float?, state: BarState) {
    val line by animatedBarColor(state.fill(kind.color))
    val ground by animatedBarColor(state.hatchGround())
    Box(
        Modifier
            .fillMaxWidth()
            .height(MacroBarHeight)
            .background(TracklyColors.TrackEmpty)
            .drawBehind {
                macroFill(kind, size.width * fraction().coerceAtLeast(0f), line, ground)
                if (mark != null) drawAverageMark(mark, fraction(), MacroMarkOvershoot)
            },
    )
}

/** Aplat (protéines, glucides) ou hachures à 135° (lipides) ; [color] et [ground] suivent l'état de la barre. */
private fun DrawScope.macroFill(kind: MacroKind, width: Float, color: Color = kind.color, ground: Color = TracklyColors.FatHatchGround) {
    if (width <= 0f) return
    val area = androidx.compose.ui.geometry.Size(width, size.height)
    if (!kind.hatched) {
        drawRect(color, size = area)
        return
    }
    drawRect(ground, size = area)
    clipRect(right = width) {
        val period = 3.dp.toPx() * 1.4142f
        val stroke = 1.dp.toPx()
        var x = -size.height
        while (x < width + size.height) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
            x += period
        }
    }
}

// ───── Repas ─────

private val CardShape = RoundedCornerShape(TracklySpacing.SheetRadius)

/**
 * Carte blanche des repas, aussi haute que nécessaire : l'estimation journalière, puis un moment par ligne, seulement
 * s'il a quelque chose de noté. Rien de noté aujourd'hui : « Pas encore de repas aujourd’hui ».
 * Ajouter passe par la barre du bas ; ajouter à un moment précis, par la feuille du repas (ou l'action TalkBack de la ligne).
 */
@Composable
private fun MealsCard(
    day: DayView,
    canAdd: Boolean,
    onAdd: (Offset, Moment) -> Unit,
    onOpenMeal: ((MomentSlot) -> Unit)?,
) {
    val motion = LocalMotion.current
    Column(
        Modifier
            .padding(horizontal = TracklySpacing.Gutter)
            .fillMaxWidth()
            .testTag(DayTags.MEALS)
            .softShadow(CardShape, radius = 30.dp, spread = (-24).dp, y = (-10).dp, alpha = 0.5f)
            .clip(CardShape)
            .background(TracklyColors.PaperHi)
            .border(TracklySpacing.Hairline, TracklyColors.Line, CardShape)
            .animateContentSize(if (motion.reduced) snap() else tween(LAYOUT_MS, easing = TracklyEasing.Soft))
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        key(day.label) {
            var i = 0
            day.estimate?.let {
                EstimateRow(it)
                i++
            }
            for (slot in day.visibleSlots()) {
                // Une clé par moment : une ligne qui arrive entre en douceur, les autres restent en place.
                key(slot.moment) {
                    if (i > 0) Hairline()
                    MealRow(
                        slot,
                        i,
                        onAdd = if (canAdd) ({ origin -> onAdd(origin, slot.moment) }) else null,
                        onOpen = onOpenMeal,
                    )
                }
                i++
            }
            if (i == 0) {
                BasicText(
                    if (day.isToday) "Pas encore de repas aujourd’hui" else "Aucun repas noté",
                    style = TracklyType.MealEmpty.copy(color = TracklyColors.Ink3),
                    modifier = Modifier.padding(vertical = 16.dp).testTag(DayTags.MEALS_EMPTY),
                )
            }
        }
    }
}

private val Moment.glyph: Glyph
    get() = when (this) {
        Moment.Matin -> Glyph.Cup
        Moment.Midi -> Glyph.Bowl
        Moment.Snack -> Glyph.Apple
        Moment.Soir -> Glyph.Moon
    }

/** Hauteur d'une ligne de repas (au moins : elle grandit avec la police). */
private val MealRowHeight = 50.dp

/**
 * Un moment noté, sur une ligne : pictogramme, moment, aliments coupés par « … », kcal. Toucher ouvre sa feuille.
 * Au-delà de 150 % de police, les aliments passent sous le moment (deux lignes au plus), faute de place à côté.
 */
@Composable
private fun MealRow(
    slot: MomentSlot,
    index: Int,
    onAdd: ((Offset) -> Unit)?,
    onOpen: ((MomentSlot) -> Unit)?,
) {
    val p = rememberEntrance(true, TracklyDurations.ROW_IN, delayMs = 350 + index * TracklyDurations.ROW_STAGGER)
    var center by remember { mutableStateOf(Offset.Zero) }
    val moment = slot.moment.label.lowercase()
    val opens = onOpen != null
    val stacked = LocalDensity.current.fontScale > 1.5f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MealRowHeight)
            .testTag(DayTags.row(slot.moment))
            .trackCenter { center = it }
            .graphicsLayer {
                alpha = p.value.coerceIn(0f, 1f)
                translationY = (1f - p.value) * 14.dp.toPx()
            }
            .then(if (opens) Modifier.pressable({ onOpen?.invoke(slot) }, pressedScale = 0.99f, onClickLabel = "Voir le détail du $moment") else Modifier)
            .then(
                if (onAdd != null) {
                    Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Ajouter au $moment") { onAdd(center); true }) }
                } else {
                    Modifier
                },
            )
            // Ligne qui ne s'ouvre pas : tout se fusionne quand même en un seul arrêt TalkBack.
            .then(if (opens) Modifier else Modifier.semantics(mergeDescendants = true) {})
            .padding(vertical = if (stacked) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(slot.moment.glyph, TracklyColors.Cobalt, Modifier.size(20.dp), stroke = 1.3f)
        Spacer(Modifier.width(12.dp))
        val name: @Composable (Modifier) -> Unit = { modifier ->
            BasicText(
                slot.moment.label,
                style = TracklyType.MealRowName.copy(color = TracklyColors.Ink),
                maxLines = 1,
                softWrap = false,
                modifier = modifier.semantics { heading() },
            )
        }
        // TalkBack lit le texte entier ; la feuille du repas garde le détail.
        val detail: @Composable (Modifier, Int) -> Unit = { modifier, lines ->
            slot.description?.let {
                BasicText(
                    it,
                    style = TracklyType.MealRowDetail.copy(color = TracklyColors.Ink2),
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = modifier.testTag(DayTags.rowDetail(slot.moment)),
                )
            }
        }
        if (stacked) {
            Column(Modifier.weight(1f)) {
                name(Modifier)
                Spacer(Modifier.height(2.dp))
                detail(Modifier, 2)
            }
        } else {
            name(Modifier)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { detail(Modifier, 1) }
        }
        slot.kcal?.let { kcal ->
            Spacer(Modifier.width(12.dp))
            KcalFigure(kcal, atLeast = false, delayMs = 300 + index * 90)
        }
    }
}

/** « 1 220 KCAL » au bout d'une ligne de repas. */
@Composable
private fun KcalFigure(kcal: Int, atLeast: Boolean, delayMs: Int) {
    Row {
        if (atLeast) {
            BasicText("≥", style = TracklyType.MealRowKcal.copy(color = TracklyColors.Ink3), modifier = Modifier.alignByBaseline().padding(end = 3.dp))
        }
        Odometer(kcal, TracklyType.MealRowKcal, Modifier.alignByBaseline(), delayMs = delayMs)
        BasicText(
            "KCAL",
            style = TracklyType.MealUnit.copy(color = TracklyColors.Ink3),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline().padding(start = 4.dp),
        )
    }
}

/** Estimation journalière validée (journée reprise de l'ancien suivi) : elle remplace le détail. */
@Composable
private fun EstimateRow(line: app.trackly.day.EstimateLine) {
    val p = rememberEntrance(true, TracklyDurations.ROW_IN, delayMs = 300)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MealRowHeight)
            .graphicsLayer {
                alpha = p.value.coerceIn(0f, 1f)
                translationY = (1f - p.value) * 14.dp.toPx()
            }
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Chart, TracklyColors.Cobalt, Modifier.size(20.dp), stroke = 1.3f)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                "Journée estimée",
                style = TracklyType.MealRowName.copy(color = TracklyColors.Ink),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            BasicText("${line.description}, ${line.range}", style = TracklyType.MealRowDetail.copy(color = TracklyColors.Ink2))
        }
        Spacer(Modifier.width(12.dp))
        KcalFigure(line.kcal, atLeast = line.atLeast, delayMs = 300)
    }
}

/** Étiquettes de test de l'écran du jour. */
object DayTags {
    /** Corps du jour : on y glisse pour changer de jour. */
    const val BODY = "jour-corps"

    /** Bloc des trois macros, et la ligne d'une macro (`Protein`, `Carbs`, `Fat`). */
    const val MACROS = "jour-macros"
    fun macro(kind: String) = "jour-macro-${kind.lowercase()}"

    /** Carte des repas, sa phrase quand rien n'est noté, et la ligne d'un moment. */
    const val MEALS = "jour-repas"
    const val MEALS_EMPTY = "jour-repas-vide"
    fun row(moment: Moment) = "jour-repas-${moment.name.lowercase()}"
    fun rowDetail(moment: Moment) = "jour-repas-detail-${moment.name.lowercase()}"

    /** Barre du bas : champ, micro, « Aliments », « Bilan » et sa pastille « à faire ». */
    const val DOCK = "jour-barre"
    const val FIELD = "jour-ecrire"
    const val CALL = "jour-appel"
    const val MIC = "jour-micro"
    const val DISHES = "jour-mes-plats"
    const val REVIEW = "jour-bilan"
    const val REVIEW_DUE = "jour-bilan-a-faire"
    const val CHAT_QUESTIONS = "jour-questions-appel"

    /** Ligne d'alerte sous l'en-tête, et son geste. */
    const val ALERT = "jour-alerte"
    const val ALERT_ACTION = "jour-alerte-action"
}

// ───── Barre du bas ─────

private val FieldShape = RoundedCornerShape(14.dp)

/**
 * Barre du bas : « Écrire à Trackly… » sur toute la largeur, puis « Aliments », le gros micro et « Bilan ».
 * Plus de « + » : le champ (clavier) et le micro (dictée) ajoutent sur le jour affiché. [onSize] : hauteur de la barre,
 * que le corps réserve sous la carte des repas.
 */
@Composable
private fun BoxScope.Dock(
    entered: Boolean,
    onWrite: (Offset) -> Unit,
    onDictate: (Offset) -> Unit,
    onOpenDishes: (() -> Unit)?,
    onOpenReview: (() -> Unit)?,
    reviewDue: Boolean,
    onCall: (() -> Unit)?,
    chatQuestions: Int,
    onSize: (Int) -> Unit,
) {
    val p = rememberEntrance(entered, TracklyDurations.DOCK_IN, delayMs = 500)
    var fieldCenter by remember { mutableStateOf(Offset.Zero) }
    var micCenter by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .testTag(DayTags.DOCK)
            .onSizeChanged { onSize(it.height) }
            .graphicsLayer {
                alpha = p.value.coerceIn(0f, 1f)
                translationY = (1f - p.value) * 40.dp.toPx()
            }
            .background(
                Brush.verticalGradient(
                    0f to TracklyColors.PaperHi.copy(alpha = 0f),
                    0.42f to TracklyColors.PaperHi.copy(alpha = 0.9f),
                    1f to TracklyColors.PaperHi.copy(alpha = 0.97f),
                ),
            )
            .navigationBarsPadding()
            .padding(start = TracklySpacing.DockGutter, end = TracklySpacing.DockGutter, top = 24.dp, bottom = 18.dp),
    ) {
        // Cible de 48 dp autour d'un champ dessiné à 44 dp : 2 dp de marge tactile en haut et en bas.
        // Le bouton d'appel est posé au bout du champ, avec sa propre cible : il ne déclenche jamais l'écriture.
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TracklySpacing.TouchTarget)
                    .testTag(DayTags.FIELD)
                    .trackCenter { fieldCenter = it }
                    .pressable({ onWrite(fieldCenter) }, pressedScale = 0.985f)
                    .semantics { contentDescription = fieldDescription(chatQuestions) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TracklySpacing.DockFieldHeight)
                        .clip(FieldShape)
                        .background(TracklyColors.PaperHi)
                        .border(TracklySpacing.Hairline, TracklyColors.Line2, FieldShape)
                        .padding(start = 16.dp, end = if (onCall != null) TracklySpacing.TouchTarget else 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Questions laissées après un appel : la pastille du « Bilan », devant le texte du champ,
                    // loin du bouton d'appel pour qu'on ne le touche pas en croyant ouvrir la question.
                    if (chatQuestions > 0) {
                        Badge(DayTags.CHAT_QUESTIONS, Modifier)
                        Spacer(Modifier.width(8.dp))
                    }
                    BasicText("Écrire à Trackly…", style = TracklyType.AskPlaceholder.copy(color = TracklyColors.Ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onCall != null) DockCall(onCall, Modifier.align(Alignment.CenterEnd))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            DockSide(Glyph.Bowl, "Aliments", "Mes aliments", DayTags.DISHES, onOpenDishes, stroke = 1.3f)
            DockMic(onClick = { onDictate(micCenter) }, modifier = Modifier.trackCenter { micCenter = it })
            DockSide(
                Glyph.Chart,
                "Bilan",
                if (reviewDue) "Bilan, à faire" else "Bilan",
                DayTags.REVIEW,
                onOpenReview,
                stroke = 1.4f,
                badge = reviewDue,
            )
        }
    }
}

/**
 * « Aliments » ou « Bilan » : pictogramme et libellé. Sans geste ([onClick] `null`, aperçus), la place reste vide
 * pour que le micro reste au centre. [badge] : petite pastille cobalt (le bilan est à faire).
 */
@Composable
private fun DockSide(glyph: Glyph, label: String, spoken: String, tag: String, onClick: (() -> Unit)?, stroke: Float, badge: Boolean = false) {
    // Largeur minimale : à grande police, le libellé élargit le bouton au lieu d'être coupé.
    val frame = Modifier.widthIn(min = TracklySpacing.DockSideWidth).heightIn(min = TracklySpacing.TouchTarget)
    if (onClick == null) {
        Spacer(frame)
        return
    }
    Column(
        frame
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.92f)
            // Un seul arrêt TalkBack : le bouton (rôle et geste viennent de `pressable`) et son nom.
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            GlyphIcon(glyph, TracklyColors.Cobalt, Modifier.size(24.dp), stroke = stroke)
            if (badge) Badge(DayTags.REVIEW_DUE, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(label, style = TracklyType.DockLabel.copy(color = TracklyColors.Ink2, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
    }
}

/** Petite pastille cobalt cerclée de blanc : le bilan est à faire, ou des questions attendent dans le chat. */
@Composable
private fun Badge(tag: String, modifier: Modifier) {
    Box(
        modifier
            .testTag(tag)
            .size(9.dp)
            .border(1.5.dp, TracklyColors.PaperHi, CircleShape)
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(TracklyColors.Cobalt),
    )
}

/** Ce que TalkBack lit sur le champ : la pastille des questions se dit avec lui. */
internal fun fieldDescription(questions: Int): String = when {
    questions <= 0 -> "Écrire à Trackly"
    questions == 1 -> "Écrire à Trackly, une question t’attend"
    else -> "Écrire à Trackly, $questions questions t’attendent"
}

/**
 * Bouton d'appel, au bout du champ : un rond cobalt clair avec des barres de son, distinct du gros micro (la
 * dictée). Un toucher lance l'appel, sans confirmation (un appel par erreur ne coûte presque
 * rien). Cible de 48 dp, rond dessiné de 34 dp.
 */
@Composable
private fun DockCall(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TracklySpacing.TouchTarget)
            .testTag(DayTags.CALL)
            .pressable(onClick, pressedScale = 0.9f)
            .clearAndSetSemantics { contentDescription = "Appeler Trackly" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TracklySpacing.DockCallSize)
                .clip(CircleShape)
                .background(TracklyColors.Mist),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Waves, TracklyColors.Cobalt, Modifier.size(20.dp), stroke = 1.8f)
        }
    }
}

/** Gros micro cobalt de la barre du bas : ouvre le chat et lance la dictée. */
@Composable
private fun DockMic(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TracklySpacing.DockMicSize)
            .testTag(DayTags.MIC)
            .softShadow(CircleShape, radius = 24.dp, spread = (-10).dp, y = 10.dp, alpha = 0.65f, cobalt = true)
            .pressable(onClick, pressedScale = 0.92f)
            .clip(CircleShape)
            .background(TracklyColors.Cobalt)
            .semantics { contentDescription = "Dicter à Trackly" },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Mic, TracklyColors.PaperHi, Modifier.size(28.dp), stroke = 1.6f)
    }
}

/** Progression 0 → 1 d'une entrée (opacité + glissement), avec le léger dépassement « spring ». */
@Composable
internal fun rememberEntrance(entered: Boolean, durationMs: Int, delayMs: Int = 0, easingSoft: Boolean = false): Animatable<Float, *> {
    val motion = LocalMotion.current
    val anim = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(entered) {
        if (entered) {
            anim.animateTo(1f, motion.tween(durationMs, delayMs, if (easingSoft) TracklyEasing.Soft else TracklyEasing.Spring))
        }
    }
    return anim
}
