package app.trackly.assistant.context

import app.trackly.assistant.cards.formatNumber
import app.trackly.core.goal.GoalTimeline
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.AgentMemory
import app.trackly.core.model.TermCorrections
import app.trackly.core.time.TrackingDay
import app.trackly.day.DayLabels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Objectif nutritionnel en vigueur pour le jour de suivi en cours, sa date d'effet et les changements déjà prévus. */
object GoalContext : ContextProvider {
    override val id = "goal"
    override val title = "Objectif en vigueur"

    override suspend fun render(env: ContextEnv): String {
        val today = TrackingDay.of(env.now)
        val goals = env.engine.goals()
        val inForce = GoalTimeline.inForce(today, goals) ?: return "Aucun objectif fixé."
        val upcoming = GoalTimeline.upcoming(today, goals)
        return buildString {
            append(describe(inForce.targets)).append(" (depuis le ${DayLabels.short(inForce.effectiveFrom)}).")
            for (next in upcoming) {
                append("\nPrévu dès ${DayLabels.inSentence(next.effectiveFrom)} (${next.effectiveFrom}) : ").append(describe(next.targets)).append('.')
            }
        }
    }

    /**
     * L'idéal de protéines est la cible ; le minimum de protéines n'est donné que comme plancher, pour que le
     * modèle ne le prenne jamais pour l'objectif.
     */
    internal fun describe(goal: GoalTargets): String = listOfNotNull(
        "${goal.kcal.roundToInt()} kcal",
        goal.proteinG?.let { ideal ->
            "protéines ${ideal.roundToInt()} g" +
                (goal.proteinMinG?.let { " (idéal visé ; minimum ${it.roundToInt()} g = plancher, jamais l'objectif)" } ?: "")
        },
        goal.carbsG?.let { "glucides ${it.roundToInt()} g" },
        goal.fatG?.let { "lipides ${it.roundToInt()} g" },
    ).joinToString(", ")
}

/**
 * Profil nutritionnel repris de l'ancien suivi (phase, règles de macros par kg) et poids de référence en vigueur :
 * de quoi ajuster les macros quand les calories changent. Indicatif ; l'objectif en vigueur fait foi.
 * Rien si aucune reprise n'a eu lieu.
 */
object NutritionProfileContext : ContextProvider {
    override val id = "nutrition-profile"
    override val title = "Profil nutritionnel (indicatif, pour ajuster les macros)"

    override suspend fun render(env: ContextEnv): String? {
        val text = env.engine.importedDocument("profile", "profile") ?: return null
        val profile = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val today = TrackingDay.of(env.now)
        // Règles de l'ancien suivi en vigueur à la date du jour (la dernière dont la date de départ n'est pas dépassée).
        val rules = (profile["goals"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { (it["effective_from"] as? JsonPrimitive)?.content?.let { d -> d <= today.toString() } ?: false }
            ?.maxByOrNull { (it["effective_from"] as JsonPrimitive).content }
        // Poids de référence en vigueur : celui mis à jour après le dernier bilan, sinon celui du profil.
        val weight = env.engine.referenceWeight(today)
        val phase = (profile["context"] as? JsonObject)?.get("phase")?.jsonPrimitive?.content
        val lines = listOfNotNull(
            weight?.let { "Poids de référence : ${formatNumber(it)} kg ${ReferenceWeightContext.RULE}" },
            phase?.let { "Phase : $it." },
            rules?.let(::rulesLine),
        )
        if (lines.isEmpty()) return null
        return lines.joinToString("\n") + "\nLes kcal des macros : 4 par g de protéines, 4 par g de glucides, 9 par g de lipides."
    }

    private fun rulesLine(rules: JsonObject): String? {
        val ideal = rules.number("protein_ideal_g_per_kg")
        val min = rules.number("protein_min_g_per_kg")
        val fat = rules.number("fat_g_per_kg")
        val parts = listOfNotNull(
            ideal?.let { "protéines ${formatNumber(it)} g/kg" + (min?.let { m -> " (minimum ${formatNumber(m)})" } ?: "") },
            fat?.let { "lipides ${formatNumber(it)} g/kg" },
            if (rules["carbs_rule"]?.jsonPrimitive?.content == "remaining_calories") "glucides = le reste des calories" else null,
        )
        return if (parts.isEmpty()) null else "Règles habituelles : ${parts.joinToString(", ")}."
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * Poids de référence en vigueur, pour le chat du bilan : il change à la production du rapport, par l'application.
 * Rien sans poids de référence connu.
 */
object ReferenceWeightContext : ContextProvider {
    override val id = "reference-weight"
    override val title = "Poids de référence"

    /** La règle, dite pareil dans le chat du jour et dans le bilan. */
    const val RULE = "(mis à jour par l'application à chaque bilan produit : moyenne des pesées des 7 derniers jours, sinon dernière pesée ; " +
        "idéal et minimum de protéines et lipides suivent, calories inchangées, Annuler sur la page du bilan ; tu ne le changes jamais toi-même)."

    override suspend fun render(env: ContextEnv): String? =
        env.engine.referenceWeight(TrackingDay.of(env.now))?.let { "Poids de référence : ${formatNumber(it)} kg $RULE" }
}

/**
 * Préférences durables de l'utilisateur (reprises à l'import ou notées depuis) : règles explicites,
 * seulement les actives. Liste **stable** (triée par type, déclencheur, identifiant) : elle ne change que si
 * une préférence change, donc elle reste dans la partie en cache du contexte. Rien si aucune règle active.
 */
object PreferencesContext : ContextProvider {
    override val id = "preferences"
    override val title = "Préférences de l'utilisateur"

    private const val MAX_PREFERENCES = 40

    override suspend fun render(env: ContextEnv): String? {
        val (corrections, others) = env.engine.activePreferences().filter { it.kind != AgentMemory.KIND }.partition { it.kind == TermCorrections.KIND }
        if (corrections.isEmpty() && others.isEmpty()) return null
        return buildString {
            if (corrections.isNotEmpty()) {
                append("Mots corrigés : quand tu lis le mot de gauche (mot entier) dans un message, c'est le mot de droite. Écris toujours le mot de droite dans tes réponses, tes enregistrements, tes fiches mémorisées et tes recherches sur Internet.")
                for (p in corrections.take(MAX_PREFERENCES)) append("\n- ").append(p.trigger).append(" → ").append(p.rule.trim())
                if (corrections.size > MAX_PREFERENCES) append("\n- … et ${corrections.size - MAX_PREFERENCES} autres mots.")
            }
            if (others.isNotEmpty()) {
                if (corrections.isNotEmpty()) append("\n\n")
                append("Règles durables formulées par l'utilisateur : applique-les quand le déclencheur apparaît, sauf si son message dit le contraire.")
                for (p in others.take(MAX_PREFERENCES)) {
                    append("\n- ").append(p.trigger).append(" (").append(kindLabel(p.kind)).append(") : ").append(p.rule.trim())
                }
                if (others.size > MAX_PREFERENCES) append("\n- … et ${others.size - MAX_PREFERENCES} autres règles.")
            }
        }
    }

    internal fun kindLabel(kind: String): String = when (kind) {
        "cooking_default" -> "cuisson"
        "portion_default" -> "portion"
        "product_default" -> "produit"
        "recipe_alias" -> "raccourci de plat"
        "interaction_preference" -> "façon de répondre"
        "display_preference" -> "affichage"
        TermCorrections.KIND -> "mot corrigé"
        else -> kind
    }
}
