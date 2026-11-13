package app.trackly.importing

import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.ForgetFormattingNotes
import app.trackly.core.engine.TracklyEngine
import kotlinx.coroutines.CancellationException

/**
 * Au lancement : retire de la mémoire les notes de mise en forme, une seule fois par base, en une action annulable
 * ([ForgetFormattingNotes]). La trace de ce passage est dans le journal des actions, donc dans la base et dans ses
 * copies : rien n'est gardé dans les préférences. Les lancements suivants sont refusés aussitôt (`NoChange`).
 *
 * Une base vide n'est pas regardée : le préchargement ou une restauration peuvent encore y apporter de vieilles notes.
 */
class FormatNotesCleanup(
    private val engine: TracklyEngine,
    /** La base contient-elle déjà une ligne ? Lue sur le disque. */
    private val baseHasData: suspend () -> Boolean,
) {
    /** Résultat de l'action, ou `null` si rien n'a été tenté (base vide, erreur). */
    suspend fun runOnce(): ActionOutcome? = try {
        if (baseHasData()) engine.execute(ForgetFormattingNotes) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Une base illisible ne doit jamais empêcher l'app de s'ouvrir.
        null
    }
}
