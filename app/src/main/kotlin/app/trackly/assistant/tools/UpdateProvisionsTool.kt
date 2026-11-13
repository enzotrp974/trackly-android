package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProvisionsCard
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ProvisionInput
import app.trackly.core.action.ProvisionPlan
import app.trackly.core.action.UpdateProvisions
import app.trackly.core.catalog.Dishes
import app.trackly.core.catalog.Provisions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Retient les provisions : « retiens que j'ai des pommes, des bananes, une nouvelle whey » /
 * « je n'ai plus de thon ». Action du moteur : [UpdateProvisions]. Une provision n'est JAMAIS une
 * consommation : le journal n'est pas touché.
 */
object UpdateProvisionsTool : AssistantTool {
    override val name = "update_provisions"

    override val description = """
        Met à jour la liste des PROVISIONS (ce que l'utilisateur a chez lui) : `have` pour « retiens que j'ai… », « j'ai racheté… », `out` pour « je n'ai plus de… », « plus de thon ».
        Une provision n'est PAS une consommation : n'appelle jamais record_consumptions pour cela, et ne compte rien. Pas de quantités : juste le nom de l'aliment, au singulier ou au pluriel.
        Si un produit de la liste du contexte correspond, donne son `product_id` ; sinon null. Le résultat dit ce qui était déjà là et quels plats actifs utilisent un aliment retiré (propose alors, sans le faire, de les mettre en pause).
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "have" to Schema.array(
            Schema.obj(
                "name" to Schema.string("Nom de l'aliment (« pommes », « nouvelle whey »)."),
                "product_id" to Schema.string("Produit connu qui correspond, ou null.", nullable = true),
                description = "Un aliment que l'utilisateur a.",
            ),
            "Aliments que l'utilisateur dit avoir ; liste vide s'il n'y en a pas.",
        ),
        "out" to Schema.array(Schema.string("Nom d'un aliment qu'il n'a plus."), "Aliments qu'il n'a plus ; liste vide s'il n'y en a pas."),
    )

    @Serializable
    internal data class HaveArgs(val name: String, @SerialName("product_id") val productId: String? = null)

    @Serializable
    internal data class Args(val have: List<HaveArgs> = emptyList(), val out: List<String> = emptyList())

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val action = try {
            UpdateProvisions(args.have.map { ProvisionInput(it.name, it.productId?.takeIf(String::isNotBlank)) }, args.out.filter { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Valeur invalide")
        }
        val before = context.engine.allProvisions()
        val plan = ProvisionPlan.of(before, action.have, action.out)
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val dishes = Dishes.group(context.engine.allRecipes())
                val affected = plan.removed.flatMap { p -> Provisions.dishesUsing(p, dishes).map { it.name } }.distinct()
                val added = plan.created.map { it.name.trim() } + plan.reactivated.map { it.second.name.trim() }
                val card = ProvisionsCard(result.id.value, have = added, out = plan.removed.map { it.name }, affectedDishes = affected)
                ToolResult(
                    output = buildJsonObject {
                        put("status", "updated")
                        put("action_id", result.id.value)
                        putJsonArray("now_have") { added.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("already_had") { plan.already().forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("now_out") { plan.removed.forEach { add(JsonPrimitive(it.name)) } }
                        putJsonArray("not_in_list") { plan.notFound.forEach { add(JsonPrimitive(it)) } }
                        if (affected.isNotEmpty()) {
                            putJsonArray("active_dishes_using_removed") { affected.forEach { add(JsonPrimitive(it)) } }
                            put("next_step", "Propose en une phrase de mettre ces plats en pause ; ne le fais que si l'utilisateur est d'accord (manage_dish, pause).")
                        }
                        put("nothing_counted", true)
                        put("card_shown", true)
                    },
                    card = card,
                    applied = result,
                )
            }
        }
    }

    private fun ProvisionPlan.already() = alreadyHeld.map { it.name }
}
