package app.trackly.core.goal

import app.trackly.core.importing.pythonRound2
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/**
 * Minimum de protéines tiré du profil repris de l'ancien suivi (`profile.json`) : `protein_min_g_per_kg` de la règle
 * en vigueur, multiplié par son poids de référence (`reference_weight_kg` de la règle, sinon
 * `current_reference_weight_kg` du profil). Même calcul que `compute_targets` du moteur Python, arrondi compris.
 *
 * Sert à la reprise (objectifs du profil) et à la migration 8 → 9 (objectifs déjà en base). Jamais de valeur inventée :
 * `null` dès qu'une donnée manque.
 */
object ProteinMinimum {
    /** Minimum pour un objectif qui démarre le [day] : règle du profil dont la date de départ est la plus récente sans dépasser ce jour. */
    fun fromProfile(profile: JsonObject, day: LocalDate): Double? {
        val rules = (profile["goals"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val rule = rules
            .mapNotNull { rule -> rule.date("effective_from")?.let { it to rule } }
            .filter { (from, _) -> !from.isAfter(day) }
            .maxByOrNull { (from, _) -> from }
            ?.second ?: return null
        return ofRule(rule, profile.number("current_reference_weight_kg"))
    }

    /** Minimum d'une règle du profil, avec le poids de référence du profil en secours. */
    fun ofRule(rule: JsonObject, currentWeightKg: Double?): Double? {
        val perKg = rule.number("protein_min_g_per_kg") ?: return null
        val weight = rule.number("reference_weight_kg") ?: currentWeightKg ?: return null
        if (!perKg.isFinite() || !weight.isFinite() || perKg < 0 || weight <= 0) return null
        return pythonRound2(weight * perKg)
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.date(key: String): LocalDate? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
