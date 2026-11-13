package app.trackly.assistant.openai

import app.trackly.core.time.TrackingDay
import java.util.Locale

/**
 * Où se trouve l'utilisateur, pour la recherche web. Sans cette indication, OpenAI
 * suppose les États-Unis : les enseignes et les produits trouvés seraient les mauvais.
 *
 * Pays et fuseau viennent du téléphone (langue/région et fuseau du système) ; aucune ville
 * n'est envoyée (`CITY` à `null` : pays seul). La requête les reprend telles quelles (`user_location`).
 */
object SearchLocation {
    /** Code pays ISO 3166-1 alpha-2 de la région du téléphone (« US » si elle est inconnue). */
    val COUNTRY: String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    /** Ville approximative, ou `null`. */
    val CITY: String? = null

    /** Fuseau IANA : celui du jour de suivi (fuseau du téléphone). */
    val TIMEZONE: String = TrackingDay.ZONE.id
}
