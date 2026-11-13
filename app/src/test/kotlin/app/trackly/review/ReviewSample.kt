package app.trackly.review

import app.trackly.assistant.openai.ImageInput
import app.trackly.core.action.ActionOutcome
import app.trackly.core.action.AddConsumptions
import app.trackly.core.action.NewConsumption
import app.trackly.core.action.SetGoal
import app.trackly.core.engine.TracklyEngine
import app.trackly.core.model.Food
import app.trackly.core.model.GoalTargets
import app.trackly.core.model.Moment
import app.trackly.core.model.Nutrition
import app.trackly.core.review.WeekRange
import app.trackly.testing.SampleData
import java.time.Clock
import java.time.LocalDate

/** Notifications du bilan enregistrées au lieu d'être envoyées. */
class RecordingReviewNotifier : ReviewNotifier {
    val invitations = mutableListOf<WeekRange>()
    val reminders = mutableListOf<WeekRange>()
    val ready = mutableListOf<ReportInfo>()

    override fun invite(week: WeekRange) {
        invitations += week
    }

    override fun remind(week: WeekRange) {
        reminders += week
    }

    override fun reportReady(report: ReportInfo): Boolean {
        ready += report
        return true
    }
}

/**
 * Jeu d'essai fictif du bilan : période du lundi 26 octobre au dimanche 1er novembre 2026 (sans bilan précédent,
 * le lundi 26 octobre est le début), objectif 2 600 kcal. Six jours complets, le samedi partiel. Le bilan se fait le dimanche
 * 1er novembre au soir.
 */
object ReviewSample {
    val week = WeekRange(LocalDate.parse("2026-10-26"))

    fun clock(local: String = "2026-11-01T21:30"): Clock = SampleData.clock(local)

    /** Moteur avec la semaine saisie ; aucune pesée ni information déclarée. */
    suspend fun engine(clock: Clock = clock()): TracklyEngine {
        val engine = SampleData.emptyEngine(clock)
        check(engine.execute(SetGoal(GoalTargets(2600.0, 140.0, 352.0, 70.0), from = LocalDate.parse("2026-10-01"))) is ActionOutcome.Applied)
        val days = listOf(
            "2026-10-26" to Triple(2550.0, 150.0, 300.0),
            "2026-10-27" to Triple(2700.0, 145.0, 340.0),
            "2026-10-28" to Triple(2450.0, 138.0, 290.0),
            "2026-10-29" to Triple(2600.0, 142.0, 330.0),
            "2026-10-30" to Triple(2300.0, 130.0, 270.0),
            "2026-11-01" to Triple(2900.0, 160.0, 360.0),
        )
        for ((iso, v) in days) add(engine, iso, Moment.Soir, "Repas du jour", v.first, v.second, v.third, 75.0)
        add(engine, "2026-10-31", Moment.Matin, "Petit-déjeuner seul", 600.0, 30.0, 70.0, 20.0)
        return engine
    }

    private suspend fun add(engine: TracklyEngine, iso: String, moment: Moment, label: String, kcal: Double, p: Double, c: Double, f: Double) {
        val food = Food(label, Nutrition(kcal, p, c, f))
        check(engine.execute(AddConsumptions(NewConsumption(food, moment, explicitDay = LocalDate.parse(iso)))) is ActionOutcome.Applied)
    }

    /** Fausse capture d'écran : une image minuscule, jamais lue. */
    val screenshot = ImageInput("data:image/png;base64,iVBORw0KGgo=")
}
