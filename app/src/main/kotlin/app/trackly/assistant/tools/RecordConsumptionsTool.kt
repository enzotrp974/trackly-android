package app.trackly.assistant.tools

import app.trackly.assistant.cards.NoteOutcome
import app.trackly.assistant.cards.ProposalNoting
import app.trackly.assistant.cards.ProductDoubt
import app.trackly.assistant.cards.ProductRef
import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.assistant.cards.RecurrenceOffer
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.DeclareConsumptions
import app.trackly.core.action.DeclaredItem
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.ProductUse
import app.trackly.core.action.memorizedProducts
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.RecipeNutrition
import app.trackly.core.catalog.RecurrenceProposals
import app.trackly.core.model.Quantity
import app.trackly.core.model.Units
import app.trackly.core.time.TrackingDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Enregistre aussitôt, sans confirmation, les consommations d'une déclaration.
 * Action du moteur : [DeclareConsumptions] (une seule annulation pour toute la déclaration).
 *
 * Un aliment est soit un produit (connu par son `product_id`, ou à mémoriser avec `new_product`) :
 * les kcal et macros sont alors **calculés par l'app** ; soit une estimation libre (`food`).
 */
object RecordConsumptionsTool : AssistantTool {
    override val name = "record_consumptions"

    override val description = """
        Enregistre immédiatement ce que l'utilisateur DÉCLARE avoir mangé ou bu (« j'ai pris », « j'ai mangé », « ajoute à mon petit-déjeuner », photo d'un produit mangé).
        N'appelle JAMAIS cet outil pour une question (« combien si je mange… »), une intention (« je vais manger… »), une hypothèse ou une suggestion.
        Un appel par déclaration, avec tous les aliments décrits sauf ceux que l'utilisateur exclut. Les totaux du jour sont recalculés par l'app et renvoyés dans le résultat.
        Pour chaque aliment, remplis UN SEUL de ces champs : `product` (produit connu ou à mémoriser : l'app calcule les valeurs), `dish` (plat récurrent de la liste : l'app calcule les valeurs depuis ses ingrédients) ou `food` (estimation libre, sans fiche).
    """.trimIndent()

    private val productSchema = Schema.obj(
        "product_id" to Schema.string("Identifiant d'un produit connu (liste du contexte ou search_products), ou null.", nullable = true),
        "new_product" to newProductSchema(
            "Fiche d'un produit absent de la mémoire (emballé, de marque, identifiable) : il est mémorisé comme produit ponctuel. null si product_id est donné.",
            nullable = true,
        ),
        "quantity" to Schema.number("Quantité mangée, dans l'unité `unit`.", minimum = 0.0),
        "unit" to Schema.string("Unité de la quantité : une unité de la fiche (g, ml, pièce) ou « portion » (nombre de portions)."),
        "assumptions" to Schema.array(
            Schema.string("Une hypothèse d'estimation, courte."),
            "Hypothèses utiles à afficher ; liste vide si rien à signaler.",
        ),
        "alternatives" to Schema.array(
            Schema.string("Identifiant d'un autre produit possible."),
            "En cas de DOUTE entre plusieurs produits : les product_id des autres choix (le meilleur est dans product_id). Liste vide s'il n'y a pas de doute.",
        ),
        description = "Produit consommé.",
        nullable = true,
    )

    private val dishSchema = Schema.obj(
        "recipe_id" to Schema.string("Identifiant du plat (dish_id de la liste du contexte), ou d'une de ses variantes (recipe_id d'une version)."),
        "named_version" to Schema.boolean(
            "true SEULEMENT si l'utilisateur désigne une version précise du plat (« la salade sauce B », « celle avec la sauce A ») ; " +
                "false s'il dit seulement le nom du plat (« salade ») : l'app prend alors la variante par défaut.",
        ),
        "quantity" to Schema.number("Nombre de portions (1 = le plat entier, 0,5 = la moitié) ou grammes de plat selon `unit`. null : 1 portion.", nullable = true, minimum = 0.0),
        "unit" to Schema.string("« portion » ou « g ». null : portion.", nullable = true),
        "assumptions" to Schema.array(Schema.string("Une hypothèse utile, courte."), "Hypothèses à afficher ; liste vide sinon."),
        description = "Plat récurrent mangé. Ne donne NI kcal NI macros : l'app les calcule.",
        nullable = true,
    )

    override val parameters: JsonObject = Schema.obj(
        "items" to Schema.array(
            Schema.obj(
                "product" to productSchema,
                "dish" to dishSchema,
                "food" to foodSchema("Estimation libre d'un aliment sans fiche produit (plat maison, aliment générique). null si `product` est rempli.", nullable = true),
                "recipe_id" to Schema.string("Identifiant d'un plat récurrent dont les ingrédients ne sont pas pesés (search_products), avec `food` ; sinon null. Un plat calculable se déclare par `dish`.", nullable = true),
                "moment" to Schema.string(
                    "Moment de la journée : celui que l'utilisateur précise, sinon déduit de l'heure et de ce qui est déjà enregistré.",
                    enum = MOMENT_CODES,
                ),
                "day" to Schema.date(
                    "Date de rattachement AAAA-MM-JJ UNIQUEMENT si l'utilisateur exprime un jour (« pour hier », « samedi »). Sinon null : l'app applique la règle des 5 h.",
                ),
            ),
            "Consommations de la déclaration, une par aliment.",
            minItems = 1,
        ),
        "question" to Schema.string(
            "Question utile à poser APRÈS avoir enregistré la meilleure estimation (ex. poids cru ou cuit) ; null s'il n'y en a pas. Pas pour un doute entre produits : utilise `alternatives`.",
            nullable = true,
        ),
        "proposal_id" to Schema.string(
            "Identifiant de la carte de proposition (« prop-… ») que l'utilisateur dit avoir mangée, telle quelle ; null sinon.",
            nullable = true,
        ),
    )

    @Serializable
    internal data class ProductItem(
        @SerialName("product_id") val productId: String? = null,
        @SerialName("new_product") val newProduct: NewProductArgs? = null,
        val quantity: Double,
        val unit: String,
        val assumptions: List<String> = emptyList(),
        val alternatives: List<String> = emptyList(),
    )

    @Serializable
    internal data class DishItem(
        @SerialName("recipe_id") val recipeId: String,
        @SerialName("named_version") val namedVersion: Boolean = false,
        val quantity: Double? = null,
        val unit: String? = null,
        val assumptions: List<String> = emptyList(),
    )

    @Serializable
    internal data class Item(
        val product: ProductItem? = null,
        val dish: DishItem? = null,
        val food: FoodArgs? = null,
        @SerialName("recipe_id") val recipeId: String? = null,
        val moment: String,
        val day: String? = null,
    )

    @Serializable
    internal data class Args(
        val items: List<Item>,
        @SerialName("question") val question: String? = null,
        /** Carte de proposition notée par cette déclaration, ou `null`. */
        @SerialName("proposal_id") val proposalId: String? = null,
    )

    /**
     * « Noter » d'une carte de proposition : l'outil n'écrit rien lui-même. La déclaration du modèle ne
     * sert qu'à lire ses estimations ; l'app vérifie qu'elle reprend la carte, puis écrit le repas et sa carte
     * d'enregistrement ensemble ([ProposalNoting]). Refusée, rien n'est noté et le modèle ne réessaie pas.
     */
    private suspend fun noteProposal(args: Args, noting: ProposalNoting): ToolResult {
        val outcome = try {
            val offered = args.items.map { item ->
                when {
                    item.product != null -> {
                        val ref = item.product.productId ?: item.product.newProduct?.name.orEmpty()
                        ProposalNoting.Offered(item.product.productId, ref, null)
                    }
                    item.dish != null -> ProposalNoting.Offered(item.dish.recipeId, item.dish.recipeId, null)
                    item.food != null -> ProposalNoting.Offered(null, item.food.label, item.food.toFood())
                    else -> throw IllegalArgumentException("aliment vide")
                }
            }
            noting.take(offered, args.proposalId?.takeIf { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            noting.refuse(e.message ?: "déclaration illisible")
        }
        return ToolResult(
            output = buildJsonObject {
                when (outcome) {
                    is NoteOutcome.Noted -> {
                        put("status", "recorded")
                        put("recorded_by", "app")
                        put("next_step", "Noté par l'app avec les fiches de la carte ; la carte d'enregistrement est affichée. Réponds en une phrase courte, sans répéter les valeurs.")
                    }
                    NoteOutcome.AlreadyNoted -> {
                        put("status", "already_recorded")
                        put("next_step", "Cette carte était déjà notée : rien de plus. Dis-le en une phrase courte.")
                    }
                    else -> {
                        put("status", "not_recorded")
                        put("reason", reasonOf(outcome))
                        put("next_step", "Rien n'a été noté. N'appelle plus record_consumptions pour cette carte : dis-le en une phrase courte.")
                    }
                }
            },
            applied = (outcome as? NoteOutcome.Noted)?.result,
        )
    }

    private fun reasonOf(outcome: NoteOutcome): String = when (outcome) {
        is NoteOutcome.Mismatch -> "la déclaration ne correspond pas à la carte : ${outcome.reason}"
        NoteOutcome.Changed -> "une fiche de la carte a changé : la carte montre maintenant sa nouvelle version"
        is NoteOutcome.Refused -> outcome.message
        NoteOutcome.Failed -> "écriture impossible sur le téléphone"
        else -> "estimation manquante"
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        context.noting?.let { return noteProposal(args, it) }
        val latest = context.latestDay()
        val recipes = if (args.items.any { it.recipeId != null }) context.engine.allRecipes().filter { it.isActive } else emptyList()
        val recipeIds = recipes.map { it.recipeId }.toSet()
        val items = try {
            args.items.mapIndexed { i, item ->
                // Heure du message, décalée d'1 ms par aliment : le journal garde l'ordre de la déclaration.
                val consumedAt = context.messageAt.plusMillis(i.toLong())
                // Sans date exprimée, règle des 5 h appliquée à l'heure du message (même jour pour tous).
                val day = parseDay(item.day, latest) ?: TrackingDay.of(context.messageAt)
                val moment = parseMoment(item.moment)
                require(listOfNotNull(item.product, item.dish, item.food).size == 1) {
                    "Aliment ${i + 1} : remplis UN SEUL champ parmi `product`, `dish` et `food`."
                }
                item.recipeId?.let { require(it in recipeIds) { "Plat inconnu ou supprimé : $it" } }
                item.recipeId?.let { id ->
                    // Un plat calculable ne s'enregistre jamais avec des valeurs du modèle.
                    val recipe = recipes.firstOrNull { it.recipeId == id }
                    require(item.food == null || recipe == null || RecipeNutrition.total(recipe) == null) {
                        "Aliment ${i + 1} : « ${recipe?.let(Dishes::label)} » a des ingrédients pesés. Déclare-le avec `dish` : l'app calcule les valeurs."
                    }
                }
                if (item.dish != null) {
                    DeclaredItem.OfRecipe(
                        recipeId = item.dish.recipeId,
                        amount = Quantity(item.dish.quantity ?: 1.0, item.dish.unit?.trim()?.takeIf { it.isNotEmpty() } ?: Units.PORTION),
                        moment = moment,
                        assumptions = item.dish.assumptions.map { it.trim() }.filter { it.isNotEmpty() },
                        consumedAt = consumedAt,
                        explicitDay = day,
                        useDefault = !item.dish.namedVersion,
                    )
                } else if (item.product != null) {
                    require((item.product.productId == null) != (item.product.newProduct == null)) {
                        "Aliment ${i + 1} : `product_id` OU `new_product`, pas les deux ni aucun."
                    }
                    DeclaredItem.OfProduct(
                        use = ProductUse(
                            productId = item.product.productId,
                            newProduct = item.product.newProduct?.toNewProduct(),
                            amount = Quantity(item.product.quantity, item.product.unit.trim()),
                            assumptions = item.product.assumptions.map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                        moment = moment,
                        consumedAt = consumedAt,
                        explicitDay = day,
                        recipeId = item.recipeId,
                    )
                } else {
                    DeclaredItem.Free(
                        NewConsumption(
                            food = item.food!!.toFood().copy(recipeId = item.recipeId),
                            moment = moment,
                            consumedAt = consumedAt,
                            explicitDay = day,
                        ),
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(DeclareConsumptions(items, context.origin))) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val memorized = result.memorizedProducts.map { ProductRef(it.productId, it.name) }
                val proposals = RecurrenceProposals.after(context.engine, result)
                val changes = result.consumptionChanges
                // Doutes : le choix enregistré (de la consommation) et les autres choix valides.
                val doubts = buildList {
                    args.items.forEachIndexed { i, item ->
                        val others = item.product?.alternatives.orEmpty()
                        if (others.isEmpty()) return@forEachIndexed
                        val after = changes.getOrNull(i)?.after ?: return@forEachIndexed
                        val chosenId = after.food.productId ?: return@forEachIndexed
                        val refs = others.distinct().filter { it != chosenId }.mapNotNull { id ->
                            context.engine.product(id)?.takeIf { it.isActive }?.let { ProductRef(it.productId, it.name) }
                        }
                        if (refs.isNotEmpty()) add(ProductDoubt(after.entryId.value, ProductRef(chosenId, after.food.label), refs))
                    }
                }
                val card = RecordCard.from(result, RecordKind.Added, args.question).copy(
                    memorized = memorized,
                    recurrence = proposals.map { RecurrenceOffer(it.productId, it.productName, it.previousUses) },
                    doubts = doubts,
                    proposalId = args.proposalId?.trim()?.takeIf { it.startsWith("prop-") },
                )
                ToolResult(
                    output = buildJsonObject {
                        put("status", "recorded")
                        put("action_id", result.id.value)
                        putJsonArray("entries") {
                            for (item in card.items) addJsonObject {
                                put("entry_id", item.entryId)
                                put("label", item.label)
                                put("day", item.day)
                                put("moment", item.moment)
                                put("kcal", item.kcal)
                                put("product_id", item.productId)
                                if (item.recipeId != null) {
                                    put("recipe_id", item.recipeId)
                                    put("assumptions", item.assumptions.joinToString("; "))
                                }
                            }
                        }
                        if (memorized.isNotEmpty()) putJsonArray("memorized_products") {
                            for (m in memorized) addJsonObject {
                                put("product_id", m.productId)
                                put("name", m.name)
                                put("kind", "ponctuel")
                            }
                        }
                        if (proposals.isNotEmpty()) putJsonArray("recurrence_proposals") {
                            for (p in proposals) addJsonObject {
                                put("product_id", p.productId)
                                put("name", p.productName)
                                put("times_eaten_before", p.previousUses)
                                put(
                                    "next_step",
                                    "Propose à l'utilisateur d'en faire un produit récurrent et de choisir son nom. N'appelle update_product (make_recurring) qu'après sa réponse.",
                                )
                            }
                        }
                        if (doubts.isNotEmpty()) putJsonArray("doubts") {
                            for (d in doubts) addJsonObject {
                                put("entry_id", d.entryId)
                                put("recorded_as", d.chosen.name)
                                put("other_choices", d.others.joinToString(", ") { "${it.name} (${it.productId})" })
                                put("next_step", "Demande « Est-ce celui-ci ? ». Si l'utilisateur dit que c'est l'autre : switch_product.")
                            }
                        }
                        putJsonArray("days") { for (day in result.days) add(dayFacts(context.engine, day)) }
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }
}
