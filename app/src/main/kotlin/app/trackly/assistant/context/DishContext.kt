package app.trackly.assistant.context

import app.trackly.assistant.cards.formatNumber
import app.trackly.core.catalog.Dish
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import app.trackly.core.catalog.RecipeServing
import kotlin.math.roundToInt

/**
 * Plats récurrents avec leurs versions, leur variante par défaut et leur état (actif ou en
 * pause). Liste **stable** : triée par nom, elle ne change que si les plats changent, donc elle ne
 * casse pas le cache d'entrée d'un message à l'autre. Les valeurs par portion sont calculées par l'app.
 */
object DishListContext : ContextProvider {
    override val id = "dishes"
    override val title = "Plats récurrents"

    private const val MAX_DISHES = 40

    override suspend fun render(env: ContextEnv): String? {
        val dishes = Dishes.group(env.engine.allRecipes())
        if (dishes.isEmpty()) return null
        return buildString {
            append("Déclare un plat par `dish` (recipe_id d'une version) : l'app calcule ses valeurs depuis ses ingrédients. Un plat en pause n'est jamais proposé en suggestion, mais se déclare s'il est mangé.")
            for (dish in dishes.take(MAX_DISHES)) append("\n- ").append(line(dish))
            if (dishes.size > MAX_DISHES) append("\n- … et ${dishes.size - MAX_DISHES} autres : utilise search_products.")
        }
    }

    internal fun line(dish: Dish): String = buildString {
        append(dish.base.recipeId).append(" · ").append(dish.name)
        if (dish.base.aliases.isNotEmpty()) append(" · raccourcis : ").append(dish.base.aliases.joinToString(", "))
        if (dish.isPaused) append(" · EN PAUSE")
        if (!dish.hasVariants) {
            append(" · ").append(portion(dish.base))
            dish.base.variantName?.let { append(" · version : ").append(it) }
        } else {
            append(" · versions (celle par défaut quand l'utilisateur dit seulement le nom) :")
            for (v in dish.versions) {
                append("\n    - ").append(v.recipeId).append(" · ").append(v.variantName ?: "version de base")
                if (v.recipeId == dish.defaultVersion.recipeId) append(" · PAR DÉFAUT")
                if (!v.active) append(" · en pause")
                append(" · ").append(portion(v))
            }
        }
    }

    private fun portion(recipe: app.trackly.core.model.Recipe): String =
        RecipeServing.perPortion(recipe)?.let { "${it.kcal.roundToInt()} kcal / portion" } ?: "ingrédients non pesés (déclare-le avec food + recipe_id)"
}

/**
 * Provisions « j'ai », triées par nom : stable tant que la liste ne change pas. Une provision
 * n'est jamais une consommation ; elle sert aux suggestions et à la reconnaissance.
 */
object ProvisionsContext : ContextProvider {
    override val id = "provisions"
    override val title = "Provisions de l'utilisateur"

    override suspend fun render(env: ContextEnv): String? {
        val held = Provisions.held(env.engine.allProvisions())
        if (held.isEmpty()) return null
        return "Ce que l'utilisateur dit avoir (jamais une consommation) : " + held.joinToString(", ") { it.name } + "."
    }
}
