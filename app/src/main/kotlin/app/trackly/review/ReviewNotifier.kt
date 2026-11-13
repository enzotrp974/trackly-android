package app.trackly.review

import app.trackly.core.review.WeekRange

/**
 * Notifications du bilan du lundi (implémentation Android : [AndroidReviewNotifier]). Un toucher sur
 * l'invitation ou la relance ouvre la conversation de bilan ; sur « Ton bilan est prêt », le rapport.
 */
interface ReviewNotifier {
    /** Le lundi : « Fais ton bilan ». [week] : la période du bilan, depuis le dernier jusqu'à aujourd'hui. */
    fun invite(week: WeekRange)

    /** Le soir, seulement si le bilan n'est pas fait. */
    fun remind(week: WeekRange)

    /** « Ton bilan est prêt » : ouvre le rapport. Vrai si la notification a vraiment été postée (la carte le dit). */
    fun reportReady(report: ReportInfo): Boolean
}

/**
 * La conversation de bilan est-elle à l'écran ? Posé par l'écran du bilan (`ReviewHost`), lu par
 * [ScreenAwareReviewNotifier] : inutile de notifier « Ton bilan est prêt » à qui le regarde.
 */
class ReviewPresence {
    @Volatile
    var conversationShown: Boolean = false
}

/**
 * N'envoie pas « Ton bilan est prêt » quand l'utilisateur est devant la conversation de bilan ([shown]) :
 * la carte du rapport s'y affiche déjà. L'invitation et la relance passent toujours.
 */
class ScreenAwareReviewNotifier(private val inner: ReviewNotifier, private val shown: () -> Boolean) : ReviewNotifier by inner {
    override fun reportReady(report: ReportInfo): Boolean = !shown() && inner.reportReady(report)
}
