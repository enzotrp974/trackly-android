package app.trackly.assistant.turn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * `prompt_cache_key` de la Responses API : oriente les requêtes d'un même préfixe vers les mêmes machines, pour que le cache
 * d'entrée soit relu. Tirée du préfixe lui-même (consignes et définitions d'outils) : le chat et la réflexion de l'appel
 * partagent la clé (le cache seulement si le reste du préfixe, format de réponse et modèle compris, est identique : non
 * mesuré) ; un préfixe modifié (nouvelle version des consignes) change de clé, ce
 * qui ne coûte rien de plus puisque son cache est neuf de toute façon. Aucune donnée de l'utilisateur n'y entre.
 */
object PromptCacheKey {
    fun of(instructions: String, tools: List<JsonObject>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(instructions.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(JsonArray(tools).toString().toByteArray(Charsets.UTF_8))
        return "trackly-" + digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
