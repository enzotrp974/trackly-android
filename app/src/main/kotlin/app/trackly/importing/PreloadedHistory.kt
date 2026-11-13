package app.trackly.importing

import android.content.Context
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.Rejection
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.importing.ImportOutcome
import app.trackly.core.importing.KnowledgeSeedReader
import app.trackly.setup.SetupPrefs
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException

/** Applique les connaissances embarquées (rien à ajouter compte comme un succès). */
suspend fun applyKnowledgeSeed(engine: TracklyEngine, text: String?): Boolean {
    text ?: return true
    return when (val outcome = engine.execute(KnowledgeSeedReader.read(text))) {
        is ActionOutcome.Applied -> true
        is ActionOutcome.Rejected -> outcome.rejection == Rejection.NoChange
    }
}

/** Ce que le préchargement fait à ce lancement, décidé en un seul endroit : [PreloadedHistory.decide]. */
sealed interface PreloadDecision {
    /** Déjà fait sur cette installation. Une mise à jour par-dessus garde ce marqueur : elle tombe ici. */
    data object AlreadyDone : PreloadDecision

    /** APK sans historique embarqué : rien à faire, rien à marquer. */
    data object NothingEmbedded : PreloadDecision

    /**
     * La base contient déjà des données : saisies dans l'app, restaurées, ou remises par Android Auto Backup
     * (qui rend la base sans les préférences, donc sans le marqueur). Le préchargement ne s'y ajoute jamais
     * et il est marqué comme fait.
     */
    data object BaseInUse : PreloadDecision

    /** Base vide, mais la [PreloadGate] a dit « pas maintenant » : rien n'est écrit ni marqué, on redécide au lancement suivant. */
    data object Postponed : PreloadDecision

    /**
     * Base vide et feu vert, ou préchargement interrompu sur cette installation ([resume]) : précharger [text].
     */
    class Run(val text: String, val resume: Boolean = false) : PreloadDecision
}

/**
 * Dernier mot avant de précharger une base vide. Par défaut : toujours oui. Dans l'app, la restauration automatique y
 * fait attendre la réponse à la proposition de connexion Google, et répond non quand une copie Drive va être restaurée
 * ([app.trackly.backup.FreshInstall.awaitPreloadChoice]).
 */
fun interface PreloadGate {
    /** Vrai : précharger maintenant. Faux : pas à ce lancement. Peut suspendre aussi longtemps que nécessaire. */
    suspend fun allows(): Boolean
}

/**
 * Historique facultatif embarqué dans l'APK (variable TRACKLY_PRELOAD_DIR au build) : l'app démarre avec les
 * objectifs et les journées déjà là, sans fichier à choisir. Le fichier reste hors du dépôt : un APK
 * construit sans lui n'embarque aucune donnée et cette reprise ne fait rien.
 *
 * Il ne remplit qu'une base vide (voir [decide]). Il passe par la reprise normale : atomique, idempotente,
 * sans jamais réécrire ce qui a été saisi dans l'app. Une fois réussi, il n'est plus rejoué : ce que
 * l'utilisateur corrige ou supprime ensuite reste corrigé ou supprimé. Refusé, il sera retenté au prochain lancement.
 */
class PreloadedHistory(
    private val read: () -> String?,
    private val import: suspend (String) -> ImportOutcome,
    private val prefs: SetupPrefs,
    /** La base contient-elle déjà une ligne, quelle que soit la table ? Lue sur le disque, pas en cache. */
    private val baseHasData: suspend () -> Boolean,
    /** Connaissances de départ : mémoire, mots corrigés, stock. Faux : à retenter au prochain lancement. */
    private val seed: suspend () -> Boolean = { true },
    private val gate: PreloadGate = PreloadGate { true },
    /**
     * Reprise sur une base vide, la vacuité vérifiée dans la même transaction que l'écriture : `null` si la base
     * n'est plus vide (une saisie est arrivée après la dernière relecture). Absent : [import] sans cette vérification.
     */
    private val importOnEmptyBase: (suspend (String) -> ImportOutcome?)? = null,
) {
    /**
     * Seul point de décision du préchargement. N'écrit rien : [runOnce] applique la décision.
     * La base est relue après la [gate], car l'attente peut être longue et l'utilisateur a pu commencer à saisir.
     */
    suspend fun decide(): PreloadDecision {
        if (prefs.historyPreloaded) return PreloadDecision.AlreadyDone
        val text = read() ?: return PreloadDecision.NothingEmbedded
        // Interrompu (import refusé, connaissances passées, app tuée) : la base n'est plus vide de son fait, il reprend.
        if (prefs.historyPreloadStarted) return PreloadDecision.Run(text, resume = true)
        if (baseHasData()) return PreloadDecision.BaseInUse
        if (!gate.allows()) return PreloadDecision.Postponed
        if (baseHasData()) return PreloadDecision.BaseInUse
        return PreloadDecision.Run(text)
    }

    /** Vrai si l'historique embarqué vient d'être reconnu comme repris. */
    suspend fun runOnce(): Boolean {
        // Un fichier embarqué abîmé ou une base illisible ne doivent jamais empêcher l'app de s'ouvrir :
        // rien n'est marqué, la ligne « Historique » reste à faire.
        val done = try {
            when (val decision = decide()) {
                PreloadDecision.AlreadyDone, PreloadDecision.NothingEmbedded, PreloadDecision.Postponed -> return false
                PreloadDecision.BaseInUse -> {
                    prefs.historyPreloaded = true
                    return false
                }
                is PreloadDecision.Run -> {
                    // Posé avant la première écriture : un arrêt en cours de route reprend au lancement suivant.
                    prefs.historyPreloadStarted = true
                    val guarded = importOnEmptyBase
                    val outcome = if (decision.resume || guarded == null) import(decision.text) else guarded(decision.text)
                    if (outcome == null) {
                        // Une saisie est arrivée entre la relecture et l'écriture : la base est en usage, rien n'est ajouté.
                        prefs.historyPreloaded = true
                        return false
                    }
                    val imported = outcome !is ImportOutcome.Refused
                    // Les connaissances ne dépendent pas de l'historique : elles passent même s'il est refusé.
                    seed() && imported
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (done) prefs.historyPreloaded = true
        return done
    }

    /**
     * La base vient d'être remplacée par une restauration : le préchargement ne doit plus jamais s'y ajouter,
     * même s'il avait commencé sur la base d'avant. Faux si le marqueur n'a pas pu être écrit sur le disque.
     */
    fun onBaseReplaced(): Boolean = prefs.markHistoryPreloaded()

    companion object {
        const val ASSET = "preload/trackly-export.json"
        const val KNOWLEDGE_ASSET = "preload/connaissances.json"

        fun readAsset(context: Context, name: String = ASSET): String? = try {
            context.assets.open(name).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
    }
}
