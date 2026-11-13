package app.trackly.call

import app.trackly.testing.ScriptedModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Réponses simulées de la réflexion pendant l'appel (aucun réseau). */
object CallReplies {
    /** Réponse finale structurée ([CallAnswer]). */
    fun answer(say: String? = null, questions: List<String> = emptyList(), recap: String = ""): JsonObject = ScriptedModel.text(
        buildJsonObject {
            put("say", say?.let(::JsonPrimitive) ?: JsonNull)
            put("questions", JsonArray(questions.map(::JsonPrimitive)))
            put("recap", recap)
        }.toString(),
    )

    /** Réponse finale structurée de la relecture après l'appel ([ReviewAnswer]). */
    fun review(questions: List<String> = emptyList(), recap: String = ""): JsonObject = ScriptedModel.text(
        buildJsonObject {
            put("questions", JsonArray(questions.map(::JsonPrimitive)))
            put("recap", recap)
        }.toString(),
    )

    /** `record_consumptions` d'un aliment libre. */
    fun recordFood(label: String, kcal: Int, moment: String = "midi", grams: Int? = null, id: String = "1"): JsonObject = ScriptedModel.toolCall(
        "record_consumptions",
        """{"items":[{"product":null,"dish":null,"food":{"label":"$label","quantity":${grams ?: "null"},"unit":${grams?.let { "\"g\"" } ?: "null"},"kcal":$kcal,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"$moment","day":null}],"question":null,"proposal_id":null}""",
        id,
    )

    /** `record_consumptions` de plusieurs aliments libres (même moment). */
    fun recordFoods(vararg foods: Pair<String, Int>, moment: String = "midi", id: String = "1"): JsonObject = ScriptedModel.toolCall(
        "record_consumptions",
        """{"items":[""" + foods.joinToString(",") { (label, kcal) ->
            """{"product":null,"dish":null,"food":{"label":"$label","quantity":null,"unit":null,"kcal":$kcal,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"assumptions":[],"confidence":"medium"},"recipe_id":null,"moment":"$moment","day":null}"""
        } + """],"question":null,"proposal_id":null}""",
        id,
    )

    /** `record_consumptions` d'un plat récurrent, variante par défaut. */
    fun recordDish(recipeId: String, moment: String = "midi", id: String = "1"): JsonObject = ScriptedModel.toolCall(
        "record_consumptions",
        """{"items":[{"product":null,"dish":{"recipe_id":"$recipeId","named_version":false,"quantity":null,"unit":null,"assumptions":[]},"food":null,"recipe_id":null,"moment":"$moment","day":null}],"question":null,"proposal_id":null}""",
        id,
    )

    /** `correct_consumption` : nouvelle quantité et nouvelles valeurs d'une entrée. */
    fun correctFood(entryId: String, grams: Int, kcal: Int, id: String = "1"): JsonObject = ScriptedModel.toolCall(
        "correct_consumption",
        """{"entry_id":"$entryId","changes":{"label":null,"quantity":$grams,"unit":"g","nutrition":{"kcal":$kcal,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null},"assumptions":null,"confidence":null},"food":null,"moment":null,"day":null}""",
        id,
    )
}
