package app.trackly.assistant.tools

import app.trackly.assistant.cards.RecordCard
import app.trackly.assistant.cards.RecordKind
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.CorrectConsumption
import app.trackly.core.model.Confidence
import app.trackly.core.model.EntryId
import app.trackly.core.model.FoodPatch
import app.trackly.core.model.Nutrition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Corrige une consommation enregistrée par une nouvelle version complète (rien n'est perdu,
 * l'heure réelle est conservée). Action du moteur : [CorrectConsumption].
 *
 * Deux façons de changer l'aliment : `changes` ajuste le même aliment et **conserve** ce qu'il ne
 * touche pas (hypothèses, source, produit lié…) ; `food` le remplace par une autre description
 * libre. Pour une consommation liée à un produit, une nouvelle quantité dans `changes` fait
 * recalculer les valeurs par le moteur depuis la fiche. Passer à un autre produit connu
 * (« non, c'est l'autre barre ») : [SwitchProductTool].
 */
object CorrectConsumptionTool : AssistantTool {
    override val name = "correct_consumption"

    override val description = """
        Corrige une consommation déjà enregistrée quand l'utilisateur signale une erreur ou précise (« non, c'est l'autre barre », « c'était 200 g », « c'était au dîner », « c'était hier »).
        Identifie la consommation par son entry_id (journée du contexte, correction demandée par l'utilisateur, cartes précédentes, ou get_day pour un autre jour) ; n'invente jamais un entry_id.
        Champs null = inchangés. Pour le MÊME aliment (quantité, nom, hypothèses), utilise `changes` : tout ce que tu ne changes pas est conservé. Si la quantité change, donne dans `changes.nutrition` les valeurs complètes recalculées pour la nouvelle quantité, SAUF pour une consommation liée à un produit (`produit` dans le contexte) : donne seulement quantité et unité, l'app recalcule les valeurs depuis la fiche.
        Pour un AUTRE produit connu (« non, c'est l'autre barre »), utilise switch_product, pas cet outil. Pour un autre aliment sans fiche, utilise `food` (description complète de remplacement). Ne donne jamais `changes` et `food` ensemble.
        Un changement de moment ou de jour seul : `moment` et/ou `day`, rien d'autre.
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "entry_id" to Schema.string("Identifiant de la consommation à corriger."),
        "changes" to Schema.obj(
            "label" to Schema.string("Nouveau nom, ou null.", nullable = true),
            "quantity" to Schema.number("Nouvelle quantité mangée, ou null.", nullable = true, minimum = 0.0),
            "unit" to Schema.string("Unité de `quantity` (g, ml, pièce…), ou null pour garder celle de l'aliment.", nullable = true),
            "nutrition" to Schema.obj(
                "kcal" to Schema.number("Kilocalories pour la nouvelle quantité.", minimum = 0.0),
                "protein_g" to Schema.number("Protéines en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
                "carbs_g" to Schema.number("Glucides en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
                "fat_g" to Schema.number("Lipides en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
                "fiber_g" to Schema.number("Fibres en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
                description = "Valeurs COMPLÈTES recalculées (remplacent les anciennes), ou null si l'aliment ne change pas de valeurs. null pour une consommation liée à un produit : l'app les calcule.",
                nullable = true,
            ),
            "assumptions" to Schema.array(
                Schema.string("Une hypothèse d'estimation, courte."),
                "Nouvelle liste COMPLÈTE d'hypothèses (liste vide : aucune), ou null pour garder les actuelles.",
                nullable = true,
            ),
            "confidence" to Schema.string("Nouvelle confiance, ou null.", nullable = true, enum = listOf("high", "medium", "low")),
            description = "Ajustement du même aliment ; ce qui est null est conservé. null si on remplace l'aliment ou si on ne change que le moment ou le jour.",
            nullable = true,
        ),
        "food" to foodSchema("Description complète d'un AUTRE aliment sans fiche produit qui remplace l'ancien, ou null (autre produit connu : switch_product).", nullable = true),
        "moment" to Schema.string("Nouveau moment de la journée, ou null.", nullable = true, enum = MOMENT_CODES),
        "day" to Schema.date("Nouvelle date de rattachement AAAA-MM-JJ exprimée par l'utilisateur, ou null. Jamais dans le futur."),
    )

    @Serializable
    internal data class NutritionArgs(
        val kcal: Double,
        @SerialName("protein_g") val proteinG: Double? = null,
        @SerialName("carbs_g") val carbsG: Double? = null,
        @SerialName("fat_g") val fatG: Double? = null,
        @SerialName("fiber_g") val fiberG: Double? = null,
    )

    @Serializable
    internal data class ChangesArgs(
        val label: String? = null,
        val quantity: Double? = null,
        val unit: String? = null,
        val nutrition: NutritionArgs? = null,
        val assumptions: List<String>? = null,
        val confidence: String? = null,
    ) {
        fun toPatch() = FoodPatch(
            label = label?.trim()?.takeIf { it.isNotEmpty() },
            quantity = quantity,
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            nutrition = nutrition?.let { Nutrition(it.kcal, it.proteinG, it.carbsG, it.fatG, it.fiberG) },
            assumptions = assumptions?.map { it.trim() }?.filter { it.isNotEmpty() },
            confidence = confidence?.let(Confidence::fromCode),
        )
    }

    @Serializable
    internal data class Args(
        @SerialName("entry_id") val entryId: String,
        val changes: ChangesArgs? = null,
        val food: FoodArgs? = null,
        val moment: String? = null,
        val day: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        if (args.changes != null && args.food != null) {
            return ToolResult.error("Donne `changes` (même aliment) ou `food` (autre aliment), pas les deux.")
        }
        val action = try {
            CorrectConsumption(
                entryId = EntryId(args.entryId),
                food = args.food?.toFood(),
                moment = args.moment?.let(::parseMoment),
                day = parseDay(args.day, context.latestDay()),
                patch = args.changes?.toPatch(),
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val card = RecordCard.from(result, RecordKind.Corrected)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "corrected")
                        put("action_id", result.id.value)
                        put("entry_id", args.entryId)
                        putJsonArray("entries") { for (item in card.items) add(entryFacts(item)) }
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
