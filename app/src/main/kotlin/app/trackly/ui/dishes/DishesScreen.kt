package app.trackly.ui.dishes

import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.TextMatch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trackly.assistant.cards.formatNumber
import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.RecipeServing
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.model.Recipe
import app.trackly.dishes.DishDraft
import app.trackly.dishes.DishesNotice
import app.trackly.dishes.DishesUiState
import app.trackly.dishes.DraftTarget
import app.trackly.dishes.FoodSort
import app.trackly.dishes.IngredientDraft
import app.trackly.dishes.ProductsBook
import app.trackly.dishes.RecurringBook
import app.trackly.dishes.RecurringItem
import app.trackly.ui.components.Glyph
import app.trackly.ui.components.GlyphIcon
import app.trackly.ui.components.Hairline
import app.trackly.ui.components.PorcelainBackdrop
import app.trackly.ui.components.pressable
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklySpacing
import app.trackly.ui.theme.TracklyType
import kotlin.math.roundToInt

/** Étiquettes de test de « Mes aliments ». */
object DishesTags {
    const val STOCK = "plats-stock"
    fun sort(key: String) = "plats-tri-$key"
    fun foodSection(key: String) = "plats-groupe-$key"
    fun memoryForget(id: String) = "sait-oublier-$id"

    const val SCREEN = "plats"
    const val NEW = "plats-nouveau"
    const val EMPTY = "plats-vide"
    const val LIST = "recurrents-liste"
    const val SEARCH = "recurrents-recherche"
    const val KNOWLEDGE = "recurrents-ce-que-trackly-sait"
    const val KNOWLEDGE_PAGE = "sait-page"
    const val DISH_PAGE = "plat-fiche"
    const val PRODUCT_PAGE = "produit-fiche"
    const val NAME = "plats-saisie-nom"
    const val VERSION = "plats-saisie-version"
    const val SHORTCUTS = "plats-saisie-raccourcis"
    const val ADD_INGREDIENT = "plats-ingredient-ajouter"
    const val PICK_PRODUCT = "plats-produit-choisir"
    const val PRODUCT_SEARCH = "plats-produit-recherche"
    const val PREVIEW = "plats-apercu"
    const val ERROR = "plats-erreur"
    const val SAVE = "plats-enregistrer"
    const val CLOSE_FORM = "plats-fermer-formulaire"
    const val DELETE = "plats-supprimer"
    const val NOTICE = "plats-message"
    const val UNDO = "plats-annuler"
    const val PROVISION_FIELD = "plats-provision-saisie"
    const val PROVISION_ADD = "plats-provision-ajouter"
    const val BACK = "plats-retour"
    const val RECENT_EMPTY = "produits-recents-vide"

    const val PRODUCT_VALUES = "produits-valeurs"
    fun productRow(id: String) = "produits-ligne-$id"
    fun productRename(id: String) = "produits-renommer-$id"
    fun productRawName(id: String) = "produits-vrai-nom-$id"
    fun productRenameField(id: String) = "produits-renommer-saisie-$id"
    fun productRenameSave(id: String) = "produits-renommer-ok-$id"
    fun productShortcut(id: String) = "produits-raccourci-ajouter-$id"
    fun productShortcutField(id: String) = "produits-raccourci-saisie-$id"
    fun productShortcutSave(id: String) = "produits-raccourci-ok-$id"
    fun productShortcutRemove(id: String, shortcut: String) = "produits-raccourci-retirer-$id-$shortcut"

    fun dish(id: String) = "plats-plat-$id"
    fun version(id: String) = "plats-version-$id"
    fun edit(id: String) = "plats-modifier-$id"
    fun rename(id: String) = "plats-renommer-$id"
    fun renameField(id: String) = "plats-renommer-saisie-$id"
    fun renameSave(id: String) = "plats-renommer-ok-$id"
    fun variant(dishId: String) = "plats-variante-$dishId"
    fun pause(id: String) = "plats-pause-$id"
    fun makeDefault(id: String) = "plats-defaut-$id"
    fun defaultTag(id: String) = "plats-est-defaut-$id"
    fun paused(id: String) = "plats-en-pause-$id"
    fun product(id: String) = "plats-produit-$id"
    fun provision(name: String) = "plats-provision-$name"
    fun provisionOut(name: String) = "plats-provision-plus-$name"
    fun section(key: String) = "sait-section-$key"
    fun preference(id: String) = "sait-preference-$id"
    fun ingName(key: Int) = "plats-ing-nom-$key"
    fun ingGrams(key: Int) = "plats-ing-g-$key"
    fun ingKcal(key: Int) = "plats-ing-kcal-$key"
    fun ingProtein(key: Int) = "plats-ing-p-$key"
    fun ingCarbs(key: Int) = "plats-ing-gl-$key"
    fun ingFat(key: Int) = "plats-ing-l-$key"
    fun ingRemove(key: Int) = "plats-ing-retirer-$key"
}

/** Ce que l'écran peut demander. Tout passe par les actions du moteur (voir `DishesViewModel`). */
class DishesActions(
    val startNew: () -> Unit = {},
    val startVariant: (Dish) -> Unit = {},
    val startEdit: (Dish, String) -> Unit = { _, _ -> },
    val change: (DishDraft) -> Unit = {},
    val save: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val searchProducts: (String?) -> Unit = {},
    val matching: (String) -> List<Product> = { emptyList() },
    val addProduct: (Product) -> Unit = {},
    val setDefault: (Dish, String?) -> Unit = { _, _ -> },
    val setPaused: (String, Boolean) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    /** Nommer ou renommer une version du plat (le dernier argument reçoit vrai si c'est passé). */
    val renameVersion: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val addProvision: (String) -> Unit = {},
    val removeProvision: (String) -> Unit = {},
    val forgetMemory: (String) -> Unit = {},
    /** Produits : renommer, ajouter ou retirer un raccourci, rendre récurrent (le dernier argument reçoit vrai si c'est passé). */
    val renameProduct: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val addShortcut: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val removeShortcut: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val makeRecurring: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val undo: (Long) -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

internal val PanelShape = RoundedCornerShape(12.dp)
internal val ListShape = RoundedCornerShape(TracklySpacing.SheetRadius)
private val FieldShape = RoundedCornerShape(10.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/** Page ouverte dans « Mes aliments » : la liste (`null`), la fiche d'un plat ou d'un produit, ou « Ce que Trackly sait ». */
private object Pages {
    const val KNOWLEDGE = "sait"
    const val STOCK = "stock"
    fun dish(id: String) = "plat:$id"
    fun product(id: String) = "produit:$id"
}

/**
 * « Mes aliments » : tout ce que l'assistant connaît, classé par fréquence, par dernier repas ou de A à Z ; toucher une
 * ligne ouvre sa fiche. En tête, « Mon stock » et « Ce que l'assistant sait ».
 * Plein écran, ouvert par le bol de la barre du bas. Le retour système remonte d'un niveau.
 */
@Composable
fun DishesScreen(state: DishesUiState, actions: DishesActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val dish = page?.removePrefix("plat:")?.takeIf { page!!.startsWith("plat:") }?.let { id -> state.dishes.firstOrNull { it.base.recipeId == id } }
    val product = page?.removePrefix("produit:")?.takeIf { page!!.startsWith("produit:") }?.let { id -> CatalogSearch.visibleProducts(state.catalog).firstOrNull { it.productId == id } }
    // Fiche supprimée (ou produit plus récurrent) : retour à la liste.
    val missing = (page?.startsWith("plat:") == true && dish == null) || (page?.startsWith("produit:") == true && product == null)
    LaunchedEffect(missing) { if (missing) page = null }
    // Recherche, tri et position survivent à l'aller-retour vers une fiche.
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FoodSort.Frequent) }
    val listScroll = rememberScrollState()
    val draft = state.draft
    val back = {
        when {
            draft != null -> actions.closeForm()
            page != null -> page = null
            else -> onClose()
        }
    }
    BackHandler(onBack = back)
    val title = when {
        dish != null -> dish.name
        product != null -> ProductsBook.title(product)
        page == Pages.KNOWLEDGE -> "Ce que l’assistant sait"
        page == Pages.STOCK -> "Mon stock"
        else -> "Mes aliments"
    }
    Box(modifier.fillMaxSize().testTag(DishesTags.SCREEN)) {
        PorcelainBackdrop(background = TracklyColors.PaperHi, glow = false, dotsFadeAt = 0.2f, dotsAlpha = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(TracklySpacing.BackSize)
                        .testTag(DishesTags.BACK)
                        .pressable(back, pressedScale = 0.86f)
                        .semantics { contentDescription = if (draft != null || page != null) "Retour" else "Fermer Mes aliments" },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.ChevronLeft, TracklyColors.Ink, Modifier.size(19.dp)) }
                BasicText(
                    title,
                    style = TracklyType.SettingsTitle.copy(color = TracklyColors.Ink),
                    modifier = Modifier.weight(1f).padding(start = 6.dp).semantics { heading() },
                )
            }
            key(page, draft == null) {
                val scroll = if (page == null && draft == null) listScroll else rememberScrollState()
                // Un message (pause, annulation, nom déjà pris…) s'affiche en haut : on y remonte pour qu'il se voie.
                // Pas sur la liste : au retour d'une fiche, le message d'une pause y reste et la position doit tenir.
                val onList = page == null && draft == null
                LaunchedEffect(state.notice) { if (state.notice != null && !onList) scroll.animateScrollTo(0) }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = TracklySpacing.Gutter, vertical = 8.dp),
                ) {
                    state.notice?.let {
                        Notice(it, actions)
                        Spacer(Modifier.height(14.dp))
                    }
                    when {
                        draft != null -> Form(draft, state, actions)
                        dish != null -> DishPage(dish, actions)
                        product != null -> ProductPage(product, actions)
                        page == Pages.KNOWLEDGE -> KnowledgePage(state, actions)
                        page == Pages.STOCK -> StockPage(state, actions)
                        else -> FoodList(
                            state,
                            query = query,
                            onQuery = { query = it },
                            sort = sort,
                            onSort = { sort = it },
                            onOpen = { item -> page = if (item is RecurringItem.OfDish) Pages.dish(item.id) else Pages.product(item.id) },
                            onStock = { page = Pages.STOCK },
                            onKnowledge = { page = Pages.KNOWLEDGE },
                            onNewDish = actions.startNew,
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

// ───── Liste ─────

@Composable
private fun FoodList(
    state: DishesUiState,
    query: String,
    onQuery: (String) -> Unit,
    sort: FoodSort,
    onSort: (FoodSort) -> Unit,
    onOpen: (RecurringItem) -> Unit,
    onStock: () -> Unit,
    onKnowledge: () -> Unit,
    onNewDish: () -> Unit,
) {
    val all = remember(state.dishes, state.catalog) { RecurringBook.all(state.dishes, state.catalog) }
    SearchField(query, onQuery)
    Spacer(Modifier.height(12.dp))
    val searching = TextMatch.tokens(query).isNotEmpty()
    if (!searching) {
        ShortcutRows(state, onStock, onKnowledge)
        Spacer(Modifier.height(16.dp))
    }
    if (all.isEmpty()) {
        Body("Rien d’enregistré pour l’instant. Dis-le à l’assistant, il s’en souviendra.", tag = DishesTags.EMPTY)
    } else {
        SortRow(sort, onSort)
        Spacer(Modifier.height(16.dp))
        if (searching) {
            val found = RecurringBook.filter(RecurringBook.sorted(all, state.usage, sort), query)
            if (found.isEmpty()) Body("Aucun résultat.") else FoodRows(found, state, onOpen, DishesTags.LIST)
        } else {
            val sections = remember(all, state.usage, state.fresh, sort, state.today) { RecurringBook.sections(all, state.usage, state.fresh, sort, state.today) }
            Column(Modifier.fillMaxWidth().testTag(DishesTags.LIST), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                sections.forEach { section ->
                    Column(Modifier.fillMaxWidth()) {
                        section.title?.let {
                            Kicker("$it · ${section.items.size}")
                            Spacer(Modifier.height(8.dp))
                        }
                        FoodRows(section.items, state, onOpen, DishesTags.foodSection(section.key))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    TextAction("Créer un plat à la main", DishesTags.NEW, onClick = onNewDish)
}

/** Le choix de l'ordre : toujours visible, un seul actif. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortRow(sort: FoodSort, onSort: (FoodSort) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FoodSort.entries.forEach { option ->
            val on = option == sort
            Box(
                Modifier
                    .testTag(DishesTags.sort(option.name))
                    .pressable({ onSort(option) }, pressedScale = 0.96f)
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        selected = on
                        contentDescription = "Trier : ${option.label}"
                    }
                    .clip(shape)
                    .background(if (on) TracklyColors.Cobalt else TracklyColors.Paper)
                    .border(TracklySpacing.Hairline, if (on) TracklyColors.Cobalt else TracklyColors.Line, shape)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(option.label, style = TracklyType.DayLabel.copy(color = if (on) TracklyColors.PaperHi else TracklyColors.Ink))
            }
        }
    }
}

@Composable
private fun FoodRows(items: List<RecurringItem>, state: DishesUiState, onOpen: (RecurringItem) -> Unit, tag: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape)
            .testTag(tag),
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) Hairline()
            RecurringRow(item, state) { onOpen(item) }
        }
    }
}

/** Les deux entrées vers le stock et la mémoire de l'assistant. */
@Composable
private fun ShortcutRows(state: DishesUiState, onStock: () -> Unit, onKnowledge: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape),
    ) {
        ShortcutRow("Mon stock", state.allProvisions.count { it.isActive }.toString(), DishesTags.STOCK, onStock)
        Hairline()
        ShortcutRow("Ce que l’assistant sait", AgentMemory.notes(state.preferences).size.toString(), DishesTags.KNOWLEDGE, onKnowledge)
    }
}

@Composable
private fun ShortcutRow(title: String, count: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.985f)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, style = TracklyType.MealTitle.copy(color = TracklyColors.Ink), modifier = Modifier.weight(1f))
        BasicText(count, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink3))
        Spacer(Modifier.width(10.dp))
        GlyphIcon(Glyph.ChevronRight, TracklyColors.Ink3, Modifier.size(16.dp))
    }
}

/** Une ligne : à gauche ce que c'est et ses valeurs, à droite combien de fois et quand il a été mangé. */
@Composable
private fun RecurringRow(item: RecurringItem, state: DishesUiState, onClick: () -> Unit) {
    val dish = item is RecurringItem.OfDish
    val eaten = state.usage[item.id]
    // Police agrandie : « 16 fois · il y a 1 sem. » passe sous les valeurs, pour laisser la largeur au nom.
    val stacked = LocalDensity.current.fontScale >= 1.3f
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(if (dish) DishesTags.dish(item.id) else DishesTags.productRow(item.id))
            .pressable(onClick, pressedScale = 0.985f)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (item.paused) TracklyColors.Line else TracklyColors.Mist),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (dish) Glyph.Bowl else Glyph.Apple, if (item.paused) TracklyColors.Ink3 else TracklyColors.Cobalt, Modifier.size(18.dp), stroke = 1.5f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                item.name,
                style = TracklyType.MealTitle.copy(color = if (item.paused) TracklyColors.Ink2 else TracklyColors.Ink),
                maxLines = if (stacked) 4 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(RecurringBook.rowDetail(item), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(top = 2.dp))
            if (stacked) {
                eaten?.let {
                    BasicText(
                        "${it.count} fois · ${RecurringBook.lastEaten(it.lastDay, state.today)}",
                        style = TracklyType.MealDetail.copy(color = TracklyColors.Ink),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (item.paused) Tag("EN PAUSE", DishesTags.paused(item.id), muted = true)
            }
        }
        if (!stacked && (item.paused || eaten != null)) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (item.paused) Tag("EN PAUSE", DishesTags.paused(item.id), muted = true)
                eaten?.let {
                    BasicText("${it.count}\u00A0fois", style = TracklyType.DayLabel.copy(color = TracklyColors.Ink))
                    BasicText(RecurringBook.lastEaten(it.lastDay, state.today), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink3), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) BasicText("Chercher", style = TracklyType.Bubble.copy(color = TracklyColors.Ink3))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
            cursorBrush = SolidColor(TracklyColors.Cobalt),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().testTag(DishesTags.SEARCH).semantics { contentDescription = "Chercher" },
        )
    }
}

// ───── Fiche d'un plat ─────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DishPage(dish: Dish, actions: DishesActions) {
    val id = dish.base.recipeId
    Column(Modifier.fillMaxWidth().testTag(DishesTags.DISH_PAGE)) {
        if (dish.isPaused || dish.base.aliases.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dish.isPaused) {
                    Tag("EN PAUSE", DishesTags.paused(id), muted = true)
                    Spacer(Modifier.width(10.dp))
                }
                if (dish.base.aliases.isNotEmpty()) {
                    BasicText("Raccourcis : " + dish.base.aliases.joinToString(", "), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Kicker(if (dish.hasVariants) "VARIANTES · UNE PORTION" else "UNE PORTION")
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ListShape)
                .background(TracklyColors.Paper)
                .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape),
        ) {
            dish.versions.forEachIndexed { i, version ->
                if (i > 0) Hairline()
                VersionBlock(dish, version, actions)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TextAction("Nouvelle variante", DishesTags.variant(id)) { actions.startVariant(dish) }
            TextAction(if (dish.isPaused) "Réactiver le plat" else "Mettre le plat en pause", DishesTags.pause(id)) { actions.setPaused(id, !dish.isPaused) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionBlock(dish: Dish, version: Recipe, actions: DishesActions) {
    val id = version.recipeId
    val isDefault = dish.hasVariants && id == dish.defaultVersion.recipeId
    val isBase = id == dish.base.recipeId
    var renaming by rememberSaveable(id) { mutableStateOf(false) }
    var text by rememberSaveable(id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag(DishesTags.version(id)).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        if (dish.hasVariants || version.variantName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    version.variantName ?: "Version de base",
                    style = TracklyType.MealTitle.copy(color = if (version.active) TracklyColors.Ink else TracklyColors.Ink3),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (isDefault) Tag("PAR DÉFAUT", DishesTags.defaultTag(id), muted = false)
                else if (!version.active) Tag("EN PAUSE", DishesTags.paused(id), muted = true)
            }
            Spacer(Modifier.height(2.dp))
        }
        PortionLine(version)
        if (version.ingredients.isNotEmpty()) {
            BasicText(
                version.ingredients.joinToString(" · ") { "${it.label}\u00A0${formatNumber(it.quantityG)}\u00A0g" },
                style = TracklyType.MealDetail.copy(color = TracklyColors.Ink3),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (renaming) {
            InlineEdit(
                label = "Nom de la variante",
                value = text,
                onChange = { text = it },
                fieldTag = DishesTags.renameField(id),
                saveTag = DishesTags.renameSave(id),
                onCancel = { renaming = false },
            ) { actions.renameVersion(id, text.trim()) { ok -> if (ok) renaming = false } }
            Spacer(Modifier.height(8.dp))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (dish.hasVariants && !isDefault && version.active) {
                    TextAction("Choisir par défaut", DishesTags.makeDefault(id)) { actions.setDefault(dish, id.takeIf { !isBase }) }
                }
                if (dish.hasVariants || version.variantName != null) {
                    TextAction(if (version.variantName == null) "Nommer" else "Renommer", DishesTags.rename(id)) {
                        text = version.variantName.orEmpty()
                        renaming = true
                    }
                }
                TextAction("Modifier", DishesTags.edit(id)) { actions.startEdit(dish, id) }
                // La pause de la version de base, c'est celle du plat entier : elle est sous la liste, pas ici.
                if (!isDefault && !isBase) {
                    TextAction(if (version.active) "Mettre en pause" else "Réactiver", DishesTags.pause(id)) { actions.setPaused(id, version.active) }
                }
            }
        }
    }
}

@Composable
private fun PortionLine(recipe: Recipe) {
    val n = RecipeServing.perPortion(recipe)
    BasicText(
        n?.let { "${it.kcal.roundToInt()}\u00A0kcal" + macros(it).let { m -> if (m.isEmpty()) "" else " · $m" } } ?: "Ingrédients non pesés : pas encore calculable.",
        style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2),
    )
}

private fun macros(n: Nutrition): String = listOfNotNull(
    n.proteinG?.let { "P\u00A0${formatNumber(it.roundToInt().toDouble())}\u00A0g" },
    n.carbsG?.let { "G\u00A0${formatNumber(it.roundToInt().toDouble())}\u00A0g" },
    n.fatG?.let { "L\u00A0${formatNumber(it.roundToInt().toDouble())}\u00A0g" },
).joinToString(" · ")

// ───── Fiche d'un produit ─────

private enum class ProductEdit { None, Rename, Shortcut }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductPage(product: Product, actions: DishesActions) {
    val id = product.productId
    var edit by rememberSaveable(id) { mutableStateOf(ProductEdit.None) }
    var text by rememberSaveable(id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, ListShape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(DishesTags.PRODUCT_PAGE),
    ) {
        BasicText(
            ProductsBook.summary(product) + ProductsBook.macros(product).let { if (it.isEmpty()) "" else " · $it" },
            style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink2),
            modifier = Modifier.testTag(DishesTags.PRODUCT_VALUES),
        )
        // Le nom affiché est nettoyé ; le vrai nom reste visible (et c'est lui qu'on renomme).
        if (ProductsBook.cleanName(product.name) != product.name.trim()) {
            BasicText(
                "Nom enregistré : ${product.name}",
                style = TracklyType.MealDetail.copy(color = TracklyColors.Ink3),
                modifier = Modifier.padding(top = 6.dp).testTag(DishesTags.productRawName(id)),
            )
        }
        if (product.aliases.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            BasicText("Raccourcis", style = TracklyType.DayLabel.copy(color = TracklyColors.Ink2))
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                product.aliases.forEach { alias ->
                    ShortcutChip(alias, DishesTags.productShortcutRemove(id, alias)) { actions.removeShortcut(id, alias) {} }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when (edit) {
            ProductEdit.None -> FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextAction("Renommer", DishesTags.productRename(id)) {
                    text = product.name
                    edit = ProductEdit.Rename
                }
                TextAction("Ajouter un raccourci", DishesTags.productShortcut(id)) {
                    text = ""
                    edit = ProductEdit.Shortcut
                }
            }
            ProductEdit.Rename -> InlineEdit(
                label = "Nouveau nom (l’ancien reste un raccourci)",
                value = text,
                onChange = { text = it },
                fieldTag = DishesTags.productRenameField(id),
                saveTag = DishesTags.productRenameSave(id),
                onCancel = { edit = ProductEdit.None },
            ) {
                // Un produit ponctuel n'avait pas de nom choisi : le nommer le rend récurrent.
                val save = if (product.kind == ProductKind.Occasional) actions.makeRecurring else actions.renameProduct
                save(id, text) { ok -> if (ok) edit = ProductEdit.None }
            }
            ProductEdit.Shortcut -> InlineEdit(
                label = "Raccourci (par exemple « ma barre »)",
                value = text,
                onChange = { text = it },
                fieldTag = DishesTags.productShortcutField(id),
                saveTag = DishesTags.productShortcutSave(id),
                onCancel = { edit = ProductEdit.None },
            ) { actions.addShortcut(id, text) { ok -> if (ok) edit = ProductEdit.None } }
        }
    }
}

@Composable
private fun ShortcutChip(alias: String, tag: String, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(TracklyColors.Mist)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(alias, style = TracklyType.CardItemDetail.copy(color = TracklyColors.Ink))
        Box(
            Modifier
                .testTag(tag)
                .pressable(onRemove, pressedScale = 0.9f)
                .semantics {
                    role = Role.Button
                    contentDescription = "Retirer le raccourci $alias"
                }
                .size(width = 40.dp, height = 36.dp),
            contentAlignment = Alignment.Center,
        ) { BasicText("×", style = TracklyType.MealTitle.copy(color = TracklyColors.Cobalt)) }
    }
}

/** Champ + « Enregistrer » + « Fermer », sous une ligne ; la touche du clavier valide aussi. */
@Composable
internal fun InlineEdit(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    fieldTag: String,
    saveTag: String,
    onCancel: () -> Unit,
    saveLabel: String = "Enregistrer",
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row { Field(label, value, fieldTag, KeyboardType.Text, ime = ImeAction.Done, onImeDone = { if (value.isNotBlank()) onSave() }, onChange = onChange) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(saveLabel, primary = true, tag = saveTag) { if (value.isNotBlank()) onSave() }
            TextAction("Fermer", null, onClick = onCancel)
        }
    }
}

@Composable
internal fun Kicker(text: String) {
    BasicText(text, style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
}

// ───── Formulaire ─────

@Composable
private fun Form(draft: DishDraft, state: DishesUiState, actions: DishesActions) {
    val target = draft.target
    val title = when {
        target is DraftTarget.NewDish -> "Nouveau plat"
        target is DraftTarget.NewVariant -> "Nouvelle variante de « ${draft.name} »"
        draft.isVariant -> "Modifier la variante « ${draft.versionName} » de « ${draft.name} »"
        else -> "Modifier « ${draft.name} »"
    }
    BasicText(title.uppercase(), style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
    if (!draft.isVariant) {
        Row { Field("Nom du plat", draft.name, DishesTags.NAME, KeyboardType.Text) { actions.change(draft.copy(name = it)) } }
        Spacer(Modifier.height(10.dp))
    }
    Row {
        Field(
            if (draft.isVariant) "Nom de la variante (par exemple sauce B)" else "Nom de cette version (facultatif, par exemple sauce A)",
            draft.versionName, DishesTags.VERSION, KeyboardType.Text,
        ) { actions.change(draft.copy(versionName = it)) }
    }
    if (!draft.isVariant) {
        Spacer(Modifier.height(10.dp))
        Row { Field("Raccourcis (séparés par des virgules)", draft.shortcuts, DishesTags.SHORTCUTS, KeyboardType.Text) { actions.change(draft.copy(shortcuts = it)) } }
    }

    Spacer(Modifier.height(20.dp))
    BasicText("Ingrédients pour une portion", style = TracklyType.MealTitle.copy(color = TracklyColors.Ink))
    Body("Les grammes, et les valeurs pour 100 g (étiquette). Les kcal et macros du plat sont calculées d’après ces lignes.")
    draft.ingredients.forEach { ingredient ->
        Spacer(Modifier.height(10.dp))
        IngredientEditor(ingredient, draft, actions)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextAction("Ajouter un ingrédient", DishesTags.ADD_INGREDIENT) { actions.change(draft.withNewIngredient()) }
        if (state.products.isNotEmpty()) {
            TextAction("Choisir un produit connu", DishesTags.PICK_PRODUCT) { actions.searchProducts(if (state.productQuery == null) "" else null) }
        }
    }
    state.productQuery?.let { query -> ProductPicker(query, actions) }

    Spacer(Modifier.height(14.dp))
    draft.preview()?.let {
        BasicText(
            "Une portion : ${it.kcal.roundToInt()} kcal" + macros(it).let { m -> if (m.isEmpty()) "" else " · $m" },
            style = TracklyType.MealTitle.copy(color = TracklyColors.Ink),
            modifier = Modifier.testTag(DishesTags.PREVIEW).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.problem?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(
            "À corriger : $it",
            style = TracklyType.Notice.copy(color = TracklyColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .background(TracklyColors.Paper)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, PanelShape)
                .padding(12.dp)
                .testTag(DishesTags.ERROR)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton("Enregistrer", primary = true, tag = DishesTags.SAVE, onClick = actions.save)
        TextAction("Fermer", DishesTags.CLOSE_FORM, onClick = actions.closeForm)
    }
    if (target is DraftTarget.Edit) {
        Spacer(Modifier.height(6.dp))
        TextAction(if (draft.isVariant) "Supprimer cette variante" else "Supprimer ce plat", DishesTags.DELETE) {
            actions.delete(target.recipeId)
            actions.closeForm()
        }
    }
}

@Composable
private fun IngredientEditor(i: IngredientDraft, draft: DishDraft, actions: DishesActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(TracklyColors.Paper)
            .border(TracklySpacing.Hairline, TracklyColors.Line, PanelShape)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Ingrédient", i.label, DishesTags.ingName(i.key), KeyboardType.Text, weight = 2f) { actions.change(draft.withIngredient(i.key) { c -> c.copy(label = it) }) }
            Field("Quantité (g)", i.grams, DishesTags.ingGrams(i.key)) { actions.change(draft.withIngredient(i.key) { c -> c.copy(grams = it) }) }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (i.productId != null) "Valeurs pour 100 g (reprises du produit connu)" else "Valeurs pour 100 g",
            style = TracklyType.DayLabel.copy(color = TracklyColors.Ink2),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("kcal", i.kcal, DishesTags.ingKcal(i.key)) { actions.change(draft.withIngredient(i.key) { c -> c.copy(kcal = it) }) }
            Field("Prot. (g)", i.protein, DishesTags.ingProtein(i.key)) { actions.change(draft.withIngredient(i.key) { c -> c.copy(protein = it) }) }
            Field("Gluc. (g)", i.carbs, DishesTags.ingCarbs(i.key)) { actions.change(draft.withIngredient(i.key) { c -> c.copy(carbs = it) }) }
            Field("Lip. (g)", i.fat, DishesTags.ingFat(i.key)) { actions.change(draft.withIngredient(i.key) { c -> c.copy(fat = it) }) }
        }
        if (draft.ingredients.size > 1) {
            TextAction("Retirer cet ingrédient", DishesTags.ingRemove(i.key)) { actions.change(draft.withoutIngredient(i.key)) }
        }
    }
}

@Composable
private fun ProductPicker(query: String, actions: DishesActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row { Field("Chercher un produit", query, DishesTags.PRODUCT_SEARCH, KeyboardType.Text) { actions.searchProducts(it) } }
        actions.matching(query).forEach { product ->
            Hairline()
            BasicText(
                product.name + (product.brand?.let { " ($it)" } ?: ""),
                style = TracklyType.CardItem.copy(color = TracklyColors.Cobalt),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DishesTags.product(product.productId))
                    .pressable({ actions.addProduct(product) }, pressedScale = 0.98f)
                    .heightIn(min = 44.dp)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

// ───── Éléments communs ─────

@Composable
private fun Notice(notice: DishesNotice, actions: DishesActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(if (notice.error) TracklyColors.Paper else TracklyColors.Mist)
            .border(TracklySpacing.Hairline, if (notice.error) TracklyColors.Line2 else TracklyColors.Mist, PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(DishesTags.NOTICE),
    ) {
        BasicText(
            if (notice.error) "À noter : ${notice.text}" else notice.text,
            style = TracklyType.MealDetail.copy(color = if (notice.error) TracklyColors.Ink else TracklyColors.Cobalt),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            notice.undoActionId?.let { id -> TextAction("Annuler ce changement", DishesTags.UNDO) { actions.undo(id) } }
            TextAction("Fermer", null, onClick = actions.dismissNotice)
        }
    }
}

@Composable
internal fun Body(text: String, tag: String? = null) {
    BasicText(
        text,
        style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
        modifier = Modifier.padding(vertical = 3.dp).let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
internal fun Tag(text: String, tag: String, muted: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        style = TracklyType.SampleTag.copy(color = if (muted) TracklyColors.Ink2 else TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .border(TracklySpacing.Hairline, if (muted) TracklyColors.Line2 else TracklyColors.Cobalt.copy(alpha = 0.45f), shape)
            .background(if (muted) TracklyColors.Paper else TracklyColors.Mist.copy(alpha = 0.6f), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
internal fun RowScope.Field(
    label: String,
    value: String,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Decimal,
    weight: Float = 1f,
    ime: ImeAction = ImeAction.Default,
    onImeDone: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    Column(Modifier.weight(weight)) {
        BasicText(label, style = TracklyType.DayLabel.copy(color = TracklyColors.Ink2), modifier = Modifier.padding(bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(FieldShape)
                .background(TracklyColors.PaperHi)
                .border(TracklySpacing.Hairline, TracklyColors.Line2, FieldShape)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TracklyType.Bubble.copy(color = TracklyColors.Ink),
                cursorBrush = SolidColor(TracklyColors.Cobalt),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ime),
                keyboardActions = KeyboardActions(onDone = { onImeDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

@Composable
internal fun ActionButton(text: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.MealTitle.copy(color = if (primary) TracklyColors.PaperHi else TracklyColors.Cobalt),
        modifier = Modifier
            .testTag(tag)
            .pressable(onClick, pressedScale = 0.97f)
            .clip(ButtonShape)
            .background(if (primary) TracklyColors.Cobalt else TracklyColors.Mist)
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
internal fun TextAction(text: String, tag: String?, onClick: () -> Unit) {
    BasicText(
        text,
        style = TracklyType.DayLabel.copy(color = TracklyColors.Cobalt),
        modifier = Modifier
            .let { if (tag != null) it.testTag(tag) else it }
            .pressable(onClick, pressedScale = 0.94f)
            .semantics { role = Role.Button }
            .heightIn(min = 44.dp)
            .padding(vertical = 12.dp, horizontal = 2.dp),
    )
}
