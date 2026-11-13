package app.trackly.storage.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * Écoute de toute écriture dans la base de Trackly, quel que soit le chemin (action du moteur, message du chat, coût,
 * bilan, reprise d'historique). La sauvegarde automatique s'en sert pour savoir qu'il y a quelque chose à copier.
 */
object DatabaseChanges {
    /**
     * Toutes les tables de Trackly. Un test vérifie qu'une base neuve n'en a pas d'autre : une table ajoutée par une
     * version future doit être ajoutée ici, sinon ses changements ne déclencheraient pas de copie.
     */
    val TABLES: List<String> = listOf(
        "consumption_versions", "goal_versions", "actions", "action_effects", "chat_messages",
        "daily_estimate_versions", "day_tracking_versions", "product_versions", "recipe_versions",
        "preference_versions", "imported_sources", "usage_events", "provision_versions",
        "week_info_versions", "review_messages", "weekly_reports", "weight_goal_versions",
    )

    /** Entre deux signaux, au moins ce délai : une rafale d'écritures (réponse en cours d'écriture) n'en donne qu'un. */
    const val MIN_GAP_MS = 2_000L

    /**
     * Appelle [onChange] après chaque écriture, au plus une fois par [MIN_GAP_MS] : les écritures d'une rafale sont
     * regroupées, aucune n'est perdue (le dernier signal arrive toujours). Suspend tant que la portée vit.
     */
    suspend fun watch(database: TracklyDatabase, onChange: () -> Unit) {
        try {
            database.invalidationTracker.createFlow(*TABLES.toTypedArray(), emitInitialState = false)
                .conflate()
                .collect {
                    onChange()
                    delay(MIN_GAP_MS)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Jamais un plantage de l'app pour l'écoute (et rien dans les journaux) : le filet de nuit compare la base
            // à la dernière copie et rattrape ce qui n'a pas été signalé.
        }
    }
}
