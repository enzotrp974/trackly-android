package app.trackly.day

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager

/**
 * Durée d'affichage d'un élément à toucher qui disparaît seul (bandeau « Annuler »), allongée si la personne a
 * réglé « Délai avant disparition » (Accessibilité d'Android). Injectée : les tests n'attendent pas Android.
 */
fun interface RecommendedTimeout {
    /** Durée à utiliser pour un élément qui s'affiche normalement [defaultMillis] millisecondes. */
    fun forControls(defaultMillis: Long): Long

    companion object {
        /** Aucun réglage d'accessibilité connu : la durée par défaut. */
        val Default = RecommendedTimeout { it }

        /**
         * Réglage d'Android (API 29 et plus, `getRecommendedTimeoutMillis` avec `FLAG_CONTENT_CONTROLS`) ; en
         * dessous, ou sans gestionnaire d'accessibilité, la durée par défaut.
         */
        fun of(context: Context): RecommendedTimeout {
            val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)
            return AndroidRecommendedTimeout(
                sdk = Build.VERSION.SDK_INT,
                recommended = manager?.let { m -> { original, flags -> recommended(m, original, flags) } },
            )
        }

        @Suppress("NewApi") // appelé seulement à partir de l'API 29 (voir AndroidRecommendedTimeout)
        private fun recommended(manager: AccessibilityManager, original: Int, flags: Int): Int =
            manager.getRecommendedTimeoutMillis(original, flags)
    }
}

/** [recommended] : le gestionnaire d'accessibilité (durée d'origine, drapeaux) → durée recommandée, ou `null`. */
class AndroidRecommendedTimeout(
    private val sdk: Int,
    private val recommended: ((original: Int, flags: Int) -> Int)?,
) : RecommendedTimeout {
    override fun forControls(defaultMillis: Long): Long {
        val ask = recommended
        if (sdk < Build.VERSION_CODES.Q || ask == null || defaultMillis > Int.MAX_VALUE) return defaultMillis
        val millis = runCatching { ask(defaultMillis.toInt(), AccessibilityManager.FLAG_CONTENT_CONTROLS) }.getOrNull()
        return millis?.takeIf { it > 0 }?.toLong() ?: defaultMillis
    }
}
