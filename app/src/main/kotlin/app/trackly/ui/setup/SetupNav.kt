package app.trackly.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Où en est l'écran « Mise en route » : ouvert ou non, et demandes faites à l'écran des Réglages (amener la clé à l'écran).
 * Partagé entre l'écran, les Réglages (qui l'ouvrent) et la racine (qui l'ouvre après la reprise).
 */
class SetupNav(
    /** Faut-il ouvrir la liste d'elle-même ? (une seule fois, tant qu'elle n'est pas finie) */
    private val autoOpen: () -> Boolean = { false },
    /** L'utilisateur quitte la liste (« Plus tard », retour, clé) : sur une app neuve, c'est continuer sans Google. */
    private val onLeave: () -> Unit = {},
) {
    var open by mutableStateOf(false)
        private set

    /** Augmente quand la ligne « Clé OpenAI » demande les Réglages, défilés jusqu'à la section Clé. */
    var keyRequest by mutableIntStateOf(0)
        private set

    fun show() {
        open = true
    }

    fun hide() {
        open = false
    }

    /** Fermeture voulue par l'utilisateur. */
    fun leave() {
        onLeave()
        hide()
    }

    /**
     * La liste se ferme pour la clé : c'est aussi la quitter. Sur une app neuve, la proposition de connexion Google ne
     * reste pas en suspens pendant qu'on saisit (le préchargement passe, la base n'attend pas indéfiniment).
     */
    fun requestKey() {
        onLeave()
        keyRequest++
        open = false
    }

    /** Ouvre la liste si elle doit s'ouvrir d'elle-même. */
    fun openIfDue() {
        if (!open && autoOpen()) open = true
    }
}

/** L'écran de mise en route et son état de navigation, ajoutés à la racine par l'activité. */
class SetupSlot(val nav: SetupNav, val screen: @Composable (nav: SetupNav, onPickHistory: (() -> Unit)?) -> Unit)
