package app.trackly.assistant.tools

import app.trackly.assistant.cards.CardItem
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Rejection
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Confidence
import app.trackly.core.model.Food
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.model.Quantity
import app.trackly.core.summary.MacroTotal
import app.trackly.core.time.TrackingDay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Décodage tolérant des arguments d'outils (le schéma strict garantit déjà leur forme). */
internal val ToolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Décode [arguments] ou renvoie le message d'erreur à transmettre au modèle. */
internal fun <T> decodeArgs(serializer: KSerializer<T>, arguments: JsonObject): Result<T> = try {
    Result.success(ToolJson.decodeFromJsonElement(serializer, arguments))
} catch (e: SerializationException) {
    Result.failure(IllegalArgumentException("Arguments invalides : ${e.message}"))
} catch (e: IllegalArgumentException) {
    Result.failure(e)
}

internal val MOMENT_CODES = Moment.entries.map { it.code }

/** Schéma partagé d'un aliment (ajout et correction). */
internal fun foodSchema(description: String, nullable: Boolean = false) = Schema.obj(
    "label" to Schema.string("Nom court et précis de l'aliment ou du produit, en français (« Barre protéinée chocolat », « Pomme »)."),
    "quantity" to Schema.number("Quantité mangée, dans l'unité `unit`. null si inconnue.", nullable = true, minimum = 0.0),
    "unit" to Schema.string("Unité de `quantity` : g, ml, pièce, portion… null si `quantity` est null.", nullable = true),
    "kcal" to Schema.number("Kilocalories pour la quantité mangée (pas pour 100 g).", minimum = 0.0),
    "protein_g" to Schema.number("Protéines en grammes pour la quantité mangée ; null si inconnu.", nullable = true, minimum = 0.0),
    "carbs_g" to Schema.number("Glucides en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
    "fat_g" to Schema.number("Lipides en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
    "fiber_g" to Schema.number("Fibres en grammes ; null si inconnu.", nullable = true, minimum = 0.0),
    "assumptions" to Schema.array(
        Schema.string("Une hypothèse d'estimation, courte (« poulet supposé cuit, pesé cuit »)."),
        "Hypothèses d'estimation utiles à afficher ; liste vide si les valeurs sont certaines.",
    ),
    "confidence" to Schema.string("Confiance dans l'estimation.", nullable = true, enum = listOf("high", "medium", "low")),
    description = description,
    nullable = nullable,
)

@Serializable
internal data class FoodArgs(
    val label: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    val assumptions: List<String> = emptyList(),
    val confidence: String? = null,
) {
    /** Lève [IllegalArgumentException] (message en français) si une valeur est invalide. */
    fun toFood(): Food = Food(
        label = label.trim(),
        nutrition = Nutrition(kcal, proteinG, carbsG, fatG, fiberG),
        quantity = quantity?.let { q -> Quantity(q, unit?.trim()?.takeIf { it.isNotEmpty() } ?: "g") },
        assumptions = assumptions.map { it.trim() }.filter { it.isNotEmpty() },
        confidence = confidence?.let(Confidence::fromCode),
    )
}

/**
 * Date explicite de l'utilisateur (« pour hier ») ; refusée si elle est après [latest]
 * (la date civile du message). Le moteur refuse aussi toute date future : double garde-fou.
 */
internal fun parseDay(text: String?, latest: LocalDate): LocalDate? {
    if (text.isNullOrBlank()) return null
    val day = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Date invalide : $text (format AAAA-MM-JJ)")
    }
    require(!day.isAfter(latest)) { "Date future refusée : $day (nous sommes le $latest)" }
    return day
}

/** Dernier jour que l'utilisateur peut désigner depuis ce message : la date civile du message. */
internal fun ToolContext.latestDay(): LocalDate = TrackingDay.latestExplicitDay(messageAt)

internal fun parseMoment(code: String): Moment = Moment.fromCode(code)

/** Refus du moteur, renvoyé tel quel au modèle : rien n'a été écrit. */
internal fun rejected(rejection: Rejection) = ToolResult(buildJsonObject {
    put("status", "rejected")
    put("reason", rejection::class.simpleName ?: "Rejected")
    put("message", rejection.message)
    put("nothing_saved", true)
    if (rejection is Rejection.NotFound) {
        put("hint", "Cet entry_id n'est pas une consommation active. Retrouve le bon avec get_day (jour concerné), sans en inventer.")
    }
})

internal fun ActionOutcome.Rejected.toToolResult() = rejected(rejection)

/**
 * Bilan d'un jour calculé par le moteur, renvoyé au modèle après une modification :
 * le modèle cite ces chiffres et n'en calcule jamais.
 */
internal suspend fun dayFacts(engine: TracklyEngine, day: LocalDate): JsonObject {
    val summary = engine.daySummary(day)
    val energy = summary.energy
    // Les macros du détail ne valent que pour une journée détaillée (estimation, borne ou énergie inconnue : inconnues).
    fun macro(total: MacroTotal) = total.value?.takeIf { energy.macrosKnown }?.round1()?.let(::JsonPrimitive) ?: JsonPrimitive(null as Number?)
    return buildJsonObject {
        put("day", day.toString())
        // Énergie de la journée : détail, estimation validée, borne minimale ou inconnue (jamais la seule somme du détail).
        put("consumed_kcal", energy.shownKcal.round1())
        put("energy_status", energy.status.code)
        put("consumed_is_minimum", energy.isMinimum)
        put("kcal_low", energy.kcalLow.round1())
        put("kcal_high", energy.kcalHigh?.round1()?.let(::JsonPrimitive) ?: JsonPrimitive(null as Number?))
        put("detailed_kcal", summary.totals.kcal.round1())
        put("protein_g", macro(summary.totals.protein))
        put("carbs_g", macro(summary.totals.carbs))
        put("fat_g", macro(summary.totals.fat))
        put("goal_kcal", summary.goal?.kcal?.let(::JsonPrimitive) ?: JsonPrimitive(null as Number?))
        put("remaining_kcal", summary.remainingKcal?.round1()?.let(::JsonPrimitive) ?: JsonPrimitive(null as Number?))
    }
}

/** Une consommation telle que vue par le modèle après une modification. */
internal fun entryFacts(item: CardItem): JsonObject = buildJsonObject {
    put("entry_id", item.entryId)
    put("label", item.label)
    put("quantity", item.quantity)
    put("day", item.day)
    put("moment", item.moment)
    put("kcal", item.kcal)
    put("product_id", item.productId)
}

internal fun Double.round1(): Double = Math.round(this * 10) / 10.0
