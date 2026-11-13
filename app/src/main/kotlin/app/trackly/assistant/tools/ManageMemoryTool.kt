package app.trackly.assistant.tools

import app.trackly.assistant.cards.ProductCard
import app.trackly.assistant.cards.ProductOperation
import app.trackly.core.action.Action
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ForgetFact
import app.trackly.core.action.RememberFact
import app.trackly.core.action.preferenceChanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** La mémoire de l'agent : ce qui reste vrai sur l'utilisateur. Actions du moteur : [RememberFact], [ForgetFact]. */
object ManageMemoryTool : AssistantTool {
    override val name = "manage_memory"

    private const val ADD = "add"
    private const val UPDATE = "update"
    private const val REMOVE = "remove"

    override val description = """
        Écrit dans ta mémoire ce qui restera vrai sur l'utilisateur : goût, envie qui revient, aliment évité, allergie, routine, magasin, contrainte. Appelle-le sans demander quand il l'exprime.
        - add : nouvelle note, un sujet court (« Thon ») et une ou deux phrases sans pronom (« N'aime pas le thon en boîte. »).
        - update : remplace la note `memory_id` de la section « Mémoire » : préfère-le à une seconde note sur le même sujet.
        - remove : retire la note `memory_id` (« oublie ça »).
        Jamais pour une consommation, un fait d'un jour, une valeur de produit (update_product), un objectif (set_goal) ou la mise en forme des réponses (fixée par l'application).
    """.trimIndent()

    override val parameters: JsonObject = Schema.obj(
        "operation" to Schema.string("Opération à faire.", enum = listOf(ADD, UPDATE, REMOVE)),
        "memory_id" to Schema.string("Identifiant de la note, pour update et remove ; null pour add.", nullable = true),
        "topic" to Schema.string("Sujet court ; null pour remove.", nullable = true),
        "text" to Schema.string("La note ; null pour remove.", nullable = true),
    )

    @Serializable
    internal data class Args(
        val operation: String,
        @SerialName("memory_id") val memoryId: String? = null,
        val topic: String? = null,
        val text: String? = null,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val args = decodeArgs(Args.serializer(), arguments).getOrElse { return ToolResult.error(it.message ?: "Arguments invalides") }
        val id = args.memoryId?.takeIf { it.isNotBlank() }
        val action: Action = when (args.operation) {
            ADD, UPDATE -> {
                if (args.operation == UPDATE && id == null) return ToolResult.error("Il faut memory_id pour update.")
                val topic = args.topic?.takeIf { it.isNotBlank() } ?: return ToolResult.error("Il faut un sujet (`topic`).")
                val text = args.text?.takeIf { it.isNotBlank() } ?: return ToolResult.error("Il faut une note (`text`).")
                RememberFact(topic, text, id.takeIf { args.operation == UPDATE })
            }
            REMOVE -> ForgetFact(id ?: return ToolResult.error("Il faut memory_id pour remove."))
            else -> return ToolResult.error("Opération inconnue : ${args.operation}")
        }
        return when (val outcome = context.engine.execute(action)) {
            is ActionOutcome.Rejected -> outcome.toToolResult()
            is ActionOutcome.Applied -> {
                val result = outcome.result
                val forgotten = action is ForgetFact
                val note = result.preferenceChanges.lastOrNull()?.after
                ToolResult(
                    output = buildJsonObject {
                        put("status", if (forgotten) "forgotten" else "remembered")
                        put("action_id", result.id.value)
                        note?.let { put("memory_id", it.preferenceId) }
                        put("card_shown", true)
                    },
                    card = ProductCard(
                        result.id.value,
                        if (forgotten) ProductOperation.Forgotten else ProductOperation.Remembered,
                        productId = "",
                        name = note?.trigger.orEmpty(),
                        detail = note?.rule?.takeUnless { forgotten },
                    ),
                    applied = result,
                )
            }
        }
    }
}
