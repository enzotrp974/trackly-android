package app.trackly.assistant.tools

import app.trackly.review.ReviewNotifier
import app.trackly.review.ReviewPeriods

/**
 * Outils de la conversation de bilan (distincte du chat de saisie : aucun outil de
 * consommation ici, rien n'est compté pendant le bilan). L'objectif de poids s'y change ([SetWeightGoalTool]) ; un objectif temporaire s'y propose, sans s'appliquer ([ProposeTemporaryGoalTool]).
 */
fun ToolRegistry.Companion.review(periods: ReviewPeriods, notifier: ReviewNotifier) =
    ToolRegistry(tools = listOf(RecordWeekInfoTool(periods), SetWeightGoalTool(periods), ProposeTemporaryGoalTool(periods), ProduceReportTool(periods, notifier)))
