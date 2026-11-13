package app.trackly.backup

import android.content.SharedPreferences
import java.io.File
import java.time.Clock

/**
 * Au démarrage du processus, avant l'ouverture de la base et avant qu'un réglage ne soit lu : la restauration préparée
 * (Réglages, ou restauration automatique) est appliquée, ses réglages remis dans les préférences, et tout ce qui dépend
 * d'une base remplacée est prévenu. Une restauration écartée (base préparée abîmée, app utilisée entre-temps) est notée.
 *
 * Ce qui suit le remplacement (restauration notée, garde-fou levé, préchargement prévenu, app utilisée) se fait sous le
 * journal de la restauration ([PendingRestore.journalFile]) : un arrêt à ce moment le rejoue au lancement suivant.
 * Rien ici ne lève d'exception : l'app s'ouvre quoi qu'il arrive.
 */
object StartupRestore {
    fun apply(
        databaseFile: File,
        safetyDir: File,
        clock: Clock,
        prefsOf: (String) -> SharedPreferences,
        status: BackupStatusStore,
        fresh: FreshInstall,
        /** Le préchargement ne doit plus jamais s'ajouter à la base restaurée ; faux si ce n'est pas écrit sur le disque. */
        onBaseReplaced: () -> Boolean,
        /** Étapes franchies (tests : arrêt simulé du processus). */
        onStep: (RestoreStep) -> Unit = {},
    ): RestoreApplication? {
        val result = try {
            PendingRestore.applyPending(
                databaseFile, safetyDir, clock,
                beforeReplace = { settings -> settings?.let { PortableSettings.write(prefsOf, it) } },
                label = status.pendingRestoreLabel,
                onApplied = { applied ->
                    // Chaque écriture est vérifiée : le journal reste tant que l'une n'est pas sur le disque.
                    val recorded = status.recordRestore(clock.instant(), applied.label ?: status.pendingRestoreLabel ?: "Sauvegarde")
                    onStep(RestoreStep.RestoreRecorded)
                    val preloadKnows = onBaseReplaced()
                    onStep(RestoreStep.PreloadNotified)
                    val used = fresh.markUsed()
                    onStep(RestoreStep.MarkedUsed)
                    recorded && preloadKnows && used
                },
                onStep = onStep,
            )
        } catch (e: Exception) {
            // Filet : jamais d'arrêt au démarrage. Le journal, s'il existe, reprendra au lancement suivant.
            RestoreApplication.Interrupted("Restauration pas finie (${e.message}) : elle se finira au prochain lancement de l'app")
        }
        when (result) {
            null, is RestoreApplication.Applied -> Unit
            is RestoreApplication.Discarded -> runCatching { status.recordRestoreDiscarded(clock.instant(), result.reason) }
            is RestoreApplication.Interrupted -> {
                // La base est remplacée : le préchargement ne doit jamais s'y ajouter (au moins pour ce lancement). Le
                // journal finit la restauration au lancement suivant : rien à signaler comme « non faite ».
                runCatching { onBaseReplaced() }
            }
        }
        return result
    }
}
