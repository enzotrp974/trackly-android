package app.trackly.core.model

import java.time.LocalTime

/**
 * Moment de la journée : repère chronologique qui ordonne l'affichage.
 * L'ordre des valeurs est l'ordre d'affichage. Le `code` est stocké : ne jamais le renommer.
 */
enum class Moment(val code: String, val label: String) {
    Matin("matin", "Matin"),
    Midi("midi", "Midi"),
    Snack("snack", "Snack"),
    Soir("soir", "Soir"),
    ;

    companion object {
        fun fromCode(code: String): Moment =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Moment inconnu : $code")

        /** Catégories du système historique (`meal-entry.category`), pour la reprise. */
        fun fromLegacyCategory(category: String): Moment = when (category) {
            "breakfast" -> Matin
            "lunch" -> Midi
            "snack" -> Snack
            "dinner" -> Soir
            else -> throw IllegalArgumentException("Catégorie inconnue : $category")
        }

        /**
         * Proposition par défaut selon l'heure locale, quand personne ne précise le moment
         * (panneau de développement). L'assistant, lui, juge selon le contexte.
         */
        fun guessFor(time: LocalTime): Moment = when (time.hour) {
            in 5..10 -> Matin
            in 11..13 -> Midi
            in 14..17 -> Snack
            else -> Soir
        }
    }
}
