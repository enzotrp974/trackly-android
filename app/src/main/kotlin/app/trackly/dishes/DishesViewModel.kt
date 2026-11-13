package app.trackly.dishes

import app.trackly.core.suggestion.MomentHabit
import app.trackly.core.suggestion.FoodMatcher
import app.trackly.core.suggestion.FoodHabits
import app.trackly.core.action.ForgetFact
import app.trackly.core.action.NewProduct
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddProductShortcut
import app.trackly.core.action.DeleteDish
import app.trackly.core.action.MakeProductRecurring
import app.trackly.core.action.RemoveProductShortcut
import app.trackly.core.action.RenameProduct
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.SetDefaultVariant
import app.trackly.core.action.SetDishPaused
import app.trackly.core.action.Undo
import app.trackly.core.action.UpdateDish
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.CatalogSearch
import app.trackly.core.catalog.Dish
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.ActionId
import app.trackly.core.model.Preference
import app.trackly.core.model.Product
import app.trackly.core.model.ProductKind
import app.trackly.core.importing.ImportHistory
import java.time.Duration
import java.time.LocalDate
import app.trackly.core.model.Provision
import app.trackly.core.model.TextMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Message après une modification ou une annulation. [undoActionId] : l'action qu'on peut encore annuler. */
data class DishesNotice(val text: String, val error: Boolean = false, val undoActionId: Long? = null)

/** Ce que l'écran « Mes aliments » affiche. */
data class DishesUiState(
    val dishes: List<Dish> = emptyList(),
    val provisions: List<Provision> = emptyList(),
    /** Produits connus utilisables comme ingrédient (valeurs au gramme). */
    val products: List<Product> = emptyList(),
    val draft: DishDraft? = null,
    /** Ce qu'il faut corriger dans le formulaire. */
    val problem: String? = null,
    val notice: DishesNotice? = null,
    /** Recherche de produit ouverte dans le formulaire. */
    val productQuery: String? = null,
    /** Toutes les fiches de produits courantes (Mes produits : récurrents et ponctuels récents). */
    val catalog: List<Product> = emptyList(),
    /** Utilisation récente par plat et par produit : ordre de la liste. */
    val usage: Map<String, Usage> = emptyMap(),
    /** Préférences actives (lecture seule, « Ce que Trackly sait »). */
    val preferences: List<Preference> = emptyList(),
    /** Produits récurrents mémorisés depuis moins de [RecurringBook.FRESH_DAYS] jours (hors import). */
    val fresh: Set<String> = emptySet(),
    /** Tout le stock : ce qui est là, et ce qui ne l'est pas en ce moment. */
    val allProvisions: List<Provision> = emptyList(),
    /** Habitudes par moment de la journée, calculées sur les repas. */
    val habits: List<MomentHabit> = emptyList(),
    /** Le jour du journal, pour dire depuis quand un aliment n'a pas été mangé. */
    val today: LocalDate = LocalDate.now(),
)

/** Messages de pause et de réactivation, au niveau où elles s'appliquent. */
object PauseMessages {
    fun of(dish: Dish, recipeId: String, paused: Boolean): String {
        val whole = recipeId == dish.base.recipeId
        return when {
            whole && paused -> dish.name + " en pause" + if (dish.hasVariants) " (toutes les variantes)" else ""
            whole -> dish.name + " réactivé" + if (dish.hasVariants) " (toutes les variantes)" else ""
            else -> {
                val variant = dish.find(recipeId)?.variantName ?: "variante"
                "Variante « $variant » de ${dish.name} " + if (paused) "en pause" else "réactivée"
            }
        }
    }
}

/**
 * « Mes plats » et provisions. Toute modification passe par les actions du moteur
 * ([app.trackly.core.action.CreateDish], [SetDefaultVariant], [SetDishPaused], [UpdateProvisions]…),
 * les mêmes que l'assistant : un plat changé dans le chat apparaît ici aussitôt, et l'inverse.
 */
class DishesViewModel(private val engine: TracklyEngine) : ViewModel() {
    private val draft = MutableStateFlow<DishDraft?>(null)
    private val problem = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<DishesNotice?>(null)
    private val productQuery = MutableStateFlow<String?>(null)
    private val products = MutableStateFlow<List<Product>>(emptyList())
    private val catalog = MutableStateFlow<List<Product>>(emptyList())
    private val known = MutableStateFlow(Known())

    /** Ce qui se relit à chaque action : utilisation récente et préférences. */
    private data class Known(
        val usage: Map<String, Usage> = emptyMap(),
        val preferences: List<Preference> = emptyList(),
        val fresh: Set<String> = emptySet(),
        val allProvisions: List<Provision> = emptyList(),
        val habits: List<MomentHabit> = emptyList(),
        val today: LocalDate = LocalDate.now(),
    )

    val state: StateFlow<DishesUiState> = combine(
        engine.observeDishBook(), products, draft, problem, notice,
    ) { book, products, draft, problem, notice ->
        DishesUiState(book.dishes, book.provisions, products, draft, problem, notice)
    }.combine(productQuery) { s, q -> s.copy(productQuery = q) }
        .combine(catalog) { s, c -> s.copy(catalog = c) }
        .combine(known) { s, k -> s.copy(usage = k.usage, preferences = k.preferences, fresh = k.fresh, allProvisions = k.allProvisions, habits = k.habits, today = k.today) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DishesUiState())

    init {
        viewModelScope.launch {
            engine.revision.collect {
                val all = engine.allProducts()
                catalog.value = all
                products.value = CatalogSearch.visibleProducts(all).filter { DishDraft.fromProduct(it) != null }
                val today = engine.today()
                val eaten = engine.daySummaries(today.minusDays(RecurringBook.USAGE_DAYS - 1), today).flatMap { it.consumptions }
                val dishes = engine.dishBook().dishes
                val matcher = FoodMatcher(all, dishes, RecurringBook.replacements(all), ProductsBook::title)
                known.value = Known(
                    RecurringBook.usage(dishes, eaten, all),
                    engine.activePreferences(),
                    fresh(all),
                    engine.allProvisions(),
                    FoodHabits.of(eaten, matcher),
                    today,
                )
            }
        }
    }

    /** Récurrents mémorisés récemment dans l'app : un import d'historique ne compte pas comme « retiens-le ». */
    private suspend fun fresh(all: List<Product>): Set<String> {
        val since = engine.now().minus(Duration.ofDays(RecurringBook.FRESH_DAYS))
        return all.filter { it.isActive && it.kind == ProductKind.Recurring && it.recordedAt >= since }
            .filter { p -> p.actionId?.let { engine.action(it)?.type } != ImportHistory.TYPE }
            .map { it.productId }.toSet()
    }

    // ───── Formulaire ─────

    fun startNew() = open(DishDraft.newDish())

    fun startVariant(dish: Dish) = open(DishDraft.newVariant(dish))

    fun startEdit(dish: Dish, recipeId: String) {
        val recipe = dish.find(recipeId) ?: return
        open(DishDraft.edit(dish, recipe))
    }

    private fun open(value: DishDraft) {
        notice.value = null
        problem.value = null
        productQuery.value = null
        draft.value = value
    }

    fun change(value: DishDraft) {
        draft.value = value
        problem.value = null
    }

    fun close() {
        draft.value = null
        problem.value = null
        productQuery.value = null
    }

    fun searchProducts(query: String?) {
        productQuery.value = query
    }

    /** Produits dont le nom contient la recherche (sans casse ni accents). */
    fun matching(query: String): List<Product> {
        val plain = TextMatch.normalize(query)
        return products.value.filter { plain.isEmpty() || TextMatch.normalize(it.name).contains(plain) || it.aliases.any { a -> TextMatch.normalize(a).contains(plain) } }.take(6)
    }

    fun addProduct(product: Product) {
        val current = draft.value ?: return
        DishDraft.fromProduct(product)?.let { change(current.withNewIngredient(it)) }
        productQuery.value = null
    }

    fun save() {
        val current = draft.value ?: return
        when (val read = current.read()) {
            is DishDraftResult.Invalid -> problem.value = read.message
            is DishDraftResult.Ready -> viewModelScope.launch {
                when (val outcome = engine.execute(read.action)) {
                    is ActionOutcome.Applied -> {
                        close()
                        notice.value = DishesNotice(outcome.result.title, undoActionId = outcome.result.id.value)
                    }
                    is ActionOutcome.Rejected -> problem.value = outcome.rejection.message
                }
            }
        }
    }

    // ───── Actions directes ─────

    fun setDefault(dish: Dish, variantId: String?) = run(SetDefaultVariant(dish.base.recipeId, variantId))

    /**
     * Pause ou réactivation d'un plat entier (sa version de base) ou d'une seule variante. Le message dit ce qui s'est
     * vraiment passé : « Bowl thon en pause (toutes les variantes) », jamais le nom d'une seule variante pour tout le plat.
     */
    fun setPaused(recipeId: String, paused: Boolean) {
        val dish = state.value.dishes.firstOrNull { it.find(recipeId) != null }
        val message = dish?.let { PauseMessages.of(it, recipeId, paused) }
        run(SetDishPaused(recipeId, paused), message = message, keepUndo = true)
    }

    fun delete(recipeId: String) = run(DeleteDish(recipeId))

    /** Nomme ou renomme une version du plat (« sauce B »). [done] reçoit vrai si l'action est passée. */
    fun renameVersion(recipeId: String, name: String, done: (Boolean) -> Unit = {}) = run(UpdateDish(recipeId, variantName = name), onResult = done)

    // ───── Mes produits : mêmes actions que l'assistant ─────

    /** Renomme un produit (l'ancien nom reste un raccourci). [done] reçoit vrai si l'action est passée. */
    fun renameProduct(productId: String, name: String, done: (Boolean) -> Unit = {}) = run(RenameProduct(productId, name), onResult = done)

    fun addShortcut(productId: String, shortcut: String, done: (Boolean) -> Unit = {}) = run(AddProductShortcut(productId, shortcut), onResult = done)

    fun removeShortcut(productId: String, shortcut: String, done: (Boolean) -> Unit = {}) = run(RemoveProductShortcut(productId, shortcut), onResult = done)

    /** « Rendre récurrent » : le nom vient toujours de l'utilisateur. */
    fun makeRecurring(productId: String, name: String, done: (Boolean) -> Unit = {}) = run(MakeProductRecurring(productId, name), onResult = done)

    fun addProvision(name: String) {
        if (name.trim().length > NewProduct.MAX_NAME) {
            notice.value = DishesNotice("Nom trop long : garde l’essentiel.", error = true)
            return
        }
        run(UpdateProvisions(have = listOf(ProvisionInput(name))))
    }

    fun removeProvision(name: String) = run(UpdateProvisions(out = listOf(name)))

    fun forget(memoryId: String) = run(ForgetFact(memoryId), message = "Oublié.", keepUndo = true)

    fun undo(actionId: Long) = run(Undo(ActionId(actionId)), message = "Changement annulé.")

    fun dismissNotice() {
        notice.value = null
    }

    private fun run(action: Action, message: String? = null, keepUndo: Boolean = false, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            notice.value = when (val outcome = engine.execute(action)) {
                is ActionOutcome.Applied -> DishesNotice(message ?: outcome.result.title, undoActionId = outcome.result.id.value.takeIf { message == null || keepUndo })
                is ActionOutcome.Rejected -> DishesNotice(outcome.rejection.message, error = true)
            }
            onResult(notice.value?.error == false)
        }
    }

    companion object {
        fun factory(engine: TracklyEngine): ViewModelProvider.Factory = viewModelFactory {
            initializer { DishesViewModel(engine) }
        }
    }
}
