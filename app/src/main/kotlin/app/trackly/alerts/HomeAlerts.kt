package app.trackly.alerts

import android.content.Context
import app.trackly.backup.BackupStatus
import app.trackly.review.AndroidReviewNotifier
import app.trackly.review.ReportInfo
import app.trackly.review.ReviewDue
import app.trackly.review.ReviewSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ce qu'une ligne d'alerte demande à l'utilisateur de regarder. */
enum class AlertKind { StaleBackup, NotificationsOff, ReviewDue }

/** Une ligne d'alerte discrète : une phrase, et ce qu'on peut faire ([actionLabel] `null` : rien). */
data class AlertLine(val kind: AlertKind, val text: String, val actionLabel: String? = null)

/**
 * Alertes à montrer sans bruit : sur l'écran du jour (une ligne discrète) et dans les Réglages. Cet état est seulement
 * exposé (`AppContainer.homeAlerts`) : l'écran du jour le branchera lui-même, il n'est pas touché ici.
 */
data class HomeAlerts(
    val backup: AlertLine? = null,
    val notifications: AlertLine? = null,
    /**
     * Le bilan est à faire (voir [ReviewDue]). Sur l'écran du jour, ce n'est plus une ligne mais la pastille du bouton
     * « Bilan » de la barre du bas ; absent des Réglages, qui ont leur section « Bilan du lundi ».
     */
    val review: AlertLine? = null,
    /** Nous sommes lundi (heure locale) : le jour de l'invitation du bilan. */
    val isMonday: Boolean = false,
) {
    /**
     * Lignes de l'écran du jour, par priorité : la sauvegarde (risque de perte de données), puis les notifications (le lundi
     * seulement). L'écran en montre une seule. Le bilan à faire n'y est plus : il a sa pastille sur « Bilan » ([reviewDue]).
     */
    val forDayScreen: List<AlertLine> get() = listOfNotNull(backup, notifications.takeIf { isMonday })

    /** Pastille « à faire » du bouton « Bilan » de l'écran du jour. */
    val reviewDue: Boolean get() = review != null

    /** Lignes des Réglages : tout ce qui est à signaler. */
    val forSettings: List<AlertLine> get() = listOfNotNull(backup, notifications)

    companion object {
        /** Au-delà, des changements attendent trop longtemps d'être copiés sur Drive : on le dit. */
        val BACKUP_MAX_AGE: Duration = Duration.ofHours(36)

        fun of(
            backup: BackupStatus,
            review: ReviewSettingsState,
            notificationsAllowed: Boolean,
            now: ZonedDateTime,
            /** Bilans produits ; `null` : inconnus, aucune ligne de bilan. */
            reports: List<ReportInfo>? = null,
        ): HomeAlerts = HomeAlerts(
            backup = staleBackup(backup, now.toInstant()),
            notifications = notificationsOff(review, notificationsAllowed),
            review = reports?.let { reviewDue(review, now, it) },
            isMonday = now.dayOfWeek == DayOfWeek.MONDAY,
        )

        /**
         * Drive est connecté et des changements attendent depuis plus de 36 h d'être copiés (ou aucune copie n'a jamais
         * réussi alors que des essais ont échoué). Rien de changé depuis la dernière copie : rien à signaler, même si
         * elle est ancienne. Sans Drive connecté, il n'y a rien à surveiller : la connexion est proposée ailleurs.
         */
        fun staleBackup(status: BackupStatus, now: Instant): AlertLine? {
            if (!status.connected || !status.behindFor(now, BACKUP_MAX_AGE)) return null
            return AlertLine(
                AlertKind.StaleBackup,
                if (status.lastSuccessAt == null) {
                    "Aucune sauvegarde Drive n’a réussi."
                } else {
                    "Des changements attendent depuis plus de 36 heures d’être copiés sur Drive."
                },
                actionLabel = "Voir la sauvegarde",
            )
        }

        /** « Bilan de la semaine › Faire mon bilan » tant que le bilan est à faire, selon la règle de l'invitation. */
        fun reviewDue(review: ReviewSettingsState, now: ZonedDateTime, reports: List<ReportInfo>): AlertLine? =
            ReviewDue.period(now.toInstant(), now.zone, review, reports.latest())?.let {
                AlertLine(AlertKind.ReviewDue, "Bilan de la semaine", actionLabel = "Faire mon bilan")
            }

        private fun List<ReportInfo>.latest(): ReportInfo? = maxWithOrNull(compareBy<ReportInfo> { it.periodEnd }.thenBy { it.id })

        /** Les rappels du lundi sont voulus mais Android ne laisserait pas la notification s'afficher. */
        fun notificationsOff(review: ReviewSettingsState, allowed: Boolean): AlertLine? =
            if (review.enabled && !allowed) {
                AlertLine(AlertKind.NotificationsOff, "Active les notifications pour l’invitation du lundi.", actionLabel = "Autoriser")
            } else {
                null
            }
    }
}

/** Les notifications de Trackly sont-elles permises ? À relire au retour dans l'app (l'utilisateur a pu changer le réglage). */
interface NotificationsAllowed {
    val allowed: StateFlow<Boolean>

    fun refresh()

    /**
     * La fenêtre d'Android a déjà été montrée une fois : le système ne la remontre plus après un refus, il faut alors ouvrir
     * les réglages de notification de l'app.
     */
    var asked: Boolean
}

class AndroidNotificationsAllowed(context: Context) : NotificationsAllowed {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("trackly-notifications", Context.MODE_PRIVATE)
    private val _allowed = MutableStateFlow(AndroidReviewNotifier.canPost(appContext))
    override val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    override var asked: Boolean
        get() = prefs.getBoolean("demandees", false)
        set(value) {
            prefs.edit().putBoolean("demandees", value).apply()
        }

    override fun refresh() {
        _allowed.value = AndroidReviewNotifier.canPost(appContext)
    }
}

class FixedNotificationsAllowed(initial: Boolean, override var asked: Boolean = false) : NotificationsAllowed {
    private val _allowed = MutableStateFlow(initial)
    override val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    fun set(value: Boolean) {
        _allowed.value = value
    }

    override fun refresh() = Unit
}

/** Les alertes à jour, recalculées quand la sauvegarde, les rappels, la permission ou l'heure ([refresh]) changent. */
class HomeAlertsSource(
    backup: StateFlow<BackupStatus>,
    review: StateFlow<ReviewSettingsState>,
    private val notifications: NotificationsAllowed,
    private val clock: Clock,
    private val zone: ZoneId,
    scope: CoroutineScope,
    /** Rapports de bilan produits : la ligne « Bilan de la semaine » disparaît dès que l'un couvre la semaine écoulée. `null` : pas de ligne de bilan. */
    reports: Flow<List<ReportInfo>>? = null,
) {
    private val tick = MutableStateFlow(0)
    private val reportList: Flow<List<ReportInfo>?> = reports ?: flowOf(null)

    val alerts: StateFlow<HomeAlerts> = combine(backup, review, notifications.allowed, tick, reportList) { b, r, allowed, _, done ->
        HomeAlerts.of(b, r, allowed, ZonedDateTime.ofInstant(clock.instant(), zone), done)
    }.stateIn(scope, SharingStarted.Eagerly, HomeAlerts())

    /** À appeler au retour dans l'app : relit la permission et l'heure. */
    fun refresh() {
        notifications.refresh()
        tick.value++
    }
}

/** Réglages de notification de Trackly dans Android (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`, documenté). */
object NotificationSettingsIntent {
    fun of(context: Context): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
}
