package app.trackly.assistant.tools

import app.trackly.core.review.WeekRange
import app.trackly.assistant.cards.ChatCard
import app.trackly.assistant.cards.ProposalNoting
import app.trackly.assistant.openai.HostedTool
import app.trackly.core.action.ActionResult
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Origin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Outil exposé au modèle et exécuté **localement** par l'app. Un outil = un fichier :
 * nom, description, schéma JSON strict, et une exécution qui appelle une action du moteur
 * (`:core`) puis renvoie un résultat structuré pour le modèle et, éventuellement, une carte.
 *
 * Ajouter un outil : écrire un `object` qui implémente [AssistantTool] dans ce dossier,
 * puis l'ajouter à [ToolRegistry.default]. `ToolSchemaTest` vérifie son schéma.
 */
interface AssistantTool {
    /** Nom vu par le modèle : `^[a-z0-9_]{1,64}$`, stable. */
    val name: String

    /** Quand l'utiliser et quand ne pas l'utiliser, pour le modèle (français). */
    val description: String

    /** Schéma strict des arguments (voir [Schema]). */
    val parameters: JsonObject

    /**
     * Exécute l'appel. Les arguments ont déjà été décodés en objet JSON. Une erreur métier
     * ou d'argument se renvoie dans [ToolResult.output] (le modèle peut se corriger),
     * jamais par une exception.
     */
    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult

    /** Déclaration envoyée dans `tools` de la requête. */
    fun definition(): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("parameters", parameters)
        put("strict", true)
    }
}

/** Ce dont un outil dispose pendant un tour de conversation. */
class ToolContext(
    val engine: TracklyEngine,
    /** Instant du message de l'utilisateur : heure réelle des consommations déclarées. */
    val messageAt: Instant,
    val origin: Origin = Origin.Assistant,
    /** Période du bilan de cet envoi ([app.trackly.assistant.turn.TurnInput.period]), ou `null` : celle de [messageAt]. */
    val period: WeekRange? = null,
    /** « Noter » d'une carte de proposition en cours : `record_consumptions` passe par elle, sans écrire seul. */
    val noting: ProposalNoting? = null,
)

/**
 * Résultat d'un appel d'outil : [output] part au modèle (`function_call_output`) ;
 * [card] s'affiche dans le chat ; [applied] signale une modification enregistrée.
 */
data class ToolResult(
    val output: JsonObject,
    val card: ChatCard? = null,
    val applied: ActionResult? = null,
) {
    companion object {
        fun error(message: String) = ToolResult(buildJsonObject {
            put("status", "error")
            put("error", message)
        })
    }
}

/**
 * Registre des outils d'un assistant, dans un ordre **stable** (le cache d'entrée d'OpenAI
 * porte sur le préfixe exact de la requête, définitions d'outils comprises).
 */
class ToolRegistry(
    val tools: List<AssistantTool>,
    /** Outils intégrés d'OpenAI (recherche web). */
    val hosted: List<HostedTool> = emptyList(),
) {
    private val byName = tools.associateBy { it.name }.also {
        require(it.size == tools.size) { "Deux outils portent le même nom" }
    }

    fun find(name: String): AssistantTool? = byName[name]

    fun definitions(): List<JsonObject> = tools.map { it.definition() } + hosted.map { it.toJson() }

    companion object {
        /**
         * Outils de l'assistant. Une ligne par outil ; la recherche web est dans `hosted`.
         */
        fun default() = ToolRegistry(
            tools = listOf(
                RecordConsumptionsTool,
                CorrectConsumptionTool,
                DeleteConsumptionTool,
                UndoActionTool,
                ReadDayTool,
                SearchProductsTool,
                SwitchProductTool,
                UpdateProductTool,
                MemorizeProductTool,
                SetGoalTool,
                ManageDishTool,
                SwitchDishTool,
                UpdateProvisionsTool,
                MealIdeasTool,
                ManageTermCorrectionTool,
                ManageMemoryTool,
                // Après les outils existants : le préfixe de la requête (cache d'OpenAI) reste le même.
                ProposeMealTool,
            ),
            // Recherche web, localisée dans le pays du téléphone (`SearchLocation`).
            hosted = listOf(HostedTool.WebSearch()),
        )
    }
}
