package app.trackly.ui.day

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.alerts.AlertKind
import app.trackly.alerts.HomeAlertsSource
import app.trackly.alerts.NotificationsAllowed
import app.trackly.ui.settings.rememberNotificationRequester

/**
 * Alerte d'accueil branchée sur [HomeAlertsSource] : une seule ligne, la première de `forDayScreen`
 * (copie Drive ancienne ; notifications bloquées, le lundi seulement).
 *
 * Gestes les plus sûrs :
 * - notifications : la même demande que dans les Réglages (fenêtre d'Android, ou réglages de notification de l'app) ;
 * - le bilan à faire n'a plus de ligne : c'est la pastille du bouton « Bilan » ([rememberReviewDue]) ;
 * - sauvegarde : ouvre les Réglages, où « Sauvegarder maintenant » montre le résultat et les éventuelles autorisations
 *   Drive. Lancer la copie d'ici la ferait partir sans qu'on voie un refus ou une demande d'accord.
 */
@Composable
fun rememberDayAlert(source: HomeAlertsSource, notifications: NotificationsAllowed): DayAlert? {
    val alerts by source.alerts.collectAsStateWithLifecycle()
    val requester = rememberNotificationRequester(notifications)
    val line = alerts.forDayScreen.firstOrNull() ?: return null
    return when (line.kind) {
        AlertKind.StaleBackup -> DayAlert(line.text, "Réglages", opensSettings = true)
        AlertKind.NotificationsOff -> DayAlert(line.text, if (requester.firstTime) "Autoriser" else "Réglages", requester.request)
        // Absent de `forDayScreen` : le bilan à faire est la pastille du bouton « Bilan ».
        AlertKind.ReviewDue -> null
    }
}

/** Le bilan est à faire : pastille du bouton « Bilan » de la barre du bas (même règle que l'ancienne ligne, voir `ReviewDue`). */
@Composable
fun rememberReviewDue(source: HomeAlertsSource): State<Boolean> {
    val alerts = source.alerts.collectAsStateWithLifecycle()
    return remember(alerts) { derivedStateOf { alerts.value.reviewDue } }
}
