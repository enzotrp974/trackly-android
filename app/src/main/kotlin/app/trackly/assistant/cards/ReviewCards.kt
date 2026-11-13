package app.trackly.assistant.cards

import app.trackly.core.review.WeekInfo
import app.trackly.core.review.WeekRange
import app.trackly.review.ReportFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class CardWeighIn(val day: String, val kg: Double)

@Serializable
data class CardSteps(val day: String, val steps: Int)

@Serializable
data class CardSessions(val type: String, val count: Int)

/**
 * Carte du bilan : ce qui est noté pour la période après une déclaration (pesées, pas, séances),
 * avec « Annuler ». Construite par le code depuis l'état enregistré, jamais depuis le texte du modèle.
 */
@Serializable
@SerialName("week_info")
data class WeekInfoCard(
    override val actionId: Long,
    val weekStart: String,
    val weighIns: List<CardWeighIn> = emptyList(),
    val steps: List<CardSteps> = emptyList(),
    val stepsAverage: Int? = null,
    /** `null` : séances non fournies. */
    val sessions: List<CardSessions>? = null,
) : ChatCard {
    override fun summaryForModel(): String {
        val weigh = if (weighIns.isEmpty()) "aucune pesée" else "pesées " + weighIns.joinToString(", ") { "${it.day} ${ReportFormat.one(it.kg)} kg" }
        val pas = when {
            steps.isNotEmpty() -> "pas " + steps.joinToString(", ") { "${it.day} ${it.steps}" }
            stepsAverage != null -> "pas : moyenne $stepsAverage"
            else -> "pas non fournis"
        }
        val seances = sessions?.let { s -> if (s.isEmpty()) "aucune séance" else "séances " + s.joinToString(", ") { "${it.count} ${it.type}" } } ?: "séances non fournies"
        return "[Informations de la période commencée le $weekStart enregistrées par l'action $actionId : $weigh ; $pas ; $seances]"
    }

    companion object {
        fun from(actionId: Long, info: WeekInfo) = WeekInfoCard(
            actionId = actionId,
            weekStart = info.weekStart.toString(),
            weighIns = info.weighIns.map { CardWeighIn(it.day.toString(), it.kg) },
            steps = info.steps.map { CardSteps(it.day.toString(), it.steps) },
            stepsAverage = info.stepsAverage,
            sessions = info.sessions?.map { CardSessions(it.type, it.count) },
        )
    }
}

/** « Ton bilan est prêt » : le rapport est enregistré, la carte l'ouvre. Pas une action du moteur (aucune donnée de suivi n'est modifiée). */
@Serializable
@SerialName("report_ready")
data class ReportReadyCard(
    val reportId: Long,
    val weekStart: String,
    override val actionId: Long = NO_ACTION,
    /** Dernier jour de la période du bilan ; `null` pour une carte d'une version plus ancienne (une semaine du lundi au dimanche). */
    val periodEnd: String? = null,
    /** La notification « Ton bilan est prêt » est vraiment partie ; `null` pour une carte d'une version plus ancienne (inconnu). */
    val notified: Boolean? = null,
) : ChatCard {
    val week: LocalDate get() = LocalDate.parse(weekStart)

    val period: WeekRange get() = periodEnd?.let { WeekRange(week, LocalDate.parse(it)) } ?: WeekRange(week)

    override fun summaryForModel() =
        "[Bilan du $weekStart au ${period.end} produit (rapport $reportId)" + (if (notified == true) " ; l'utilisateur a été notifié]" else "]")

    /** Ligne sous la période : la notification n'est mentionnée que si elle est vraiment partie. */
    val savedText: String
        get() = "Enregistré sur ton téléphone." + if (notified == true) " Une notification t’a aussi été envoyée." else ""

    companion object {
        /** Aucune action du moteur derrière cette carte. */
        const val NO_ACTION = -1L
    }
}

/**
 * Carte du bilan : objectif de poids changé (avant, après), avec « Annuler ». Construite par le code depuis le
 * résultat de l'action [app.trackly.core.goal.SetWeightGoal], jamais depuis le texte du modèle.
 */
@Serializable
@SerialName("weight_goal")
data class WeightGoalCard(
    override val actionId: Long,
    val beforeKg: Double,
    /** `AAAA-MM-JJ`. */
    val beforeDate: String,
    val afterKg: Double,
    val afterDate: String,
) : ChatCard {
    override fun summaryForModel() =
        "[Objectif de poids changé par l'action $actionId : ${ReportFormat.one(beforeKg)} kg au $beforeDate → ${ReportFormat.one(afterKg)} kg au $afterDate]"

    companion object {
        fun from(actionId: Long, before: app.trackly.core.goal.WeightTarget, after: app.trackly.core.goal.WeightTarget) = WeightGoalCard(
            actionId = actionId,
            beforeKg = before.kg,
            beforeDate = before.date.toString(),
            afterKg = after.kg,
            afterDate = after.date.toString(),
        )
    }
}

/**
 * Objectif temporaire proposé dans le chat du bilan : la même carte que sur la page du bilan, chiffrée par l'application
 * ([advice], `AdviceText`), **sans rien appliquer** : « Appliquer » est le geste de l'utilisateur, l'état se lit en direct dans
 * les objectifs (par le premier jour). Aucune action derrière la carte elle-même ([actionId] vaut 0).
 *
 * Une version plus ancienne de l'app ne connaît pas ce type : elle affiche le message sans ses cartes, sans planter.
 */
@Serializable
@SerialName("temporary_goal")
data class TemporaryGoalCard(
    val advice: app.trackly.review.page.Advice,
    override val actionId: Long = 0L,
) : ChatCard {
    override fun summaryForModel() =
        "[Carte d'objectif temporaire proposée, PAS appliquée par toi : ${advice.big} kcal, ${advice.rows.joinToString(" ; ")}. " +
            "Seul le bouton de l'utilisateur l'applique ; ne dis jamais qu'elle est appliquée.]"
}
