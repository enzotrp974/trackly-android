package app.trackly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackly.usage.Money
import app.trackly.usage.MonthCost
import app.trackly.usage.PriceTable
import app.trackly.usage.RoomUsageLedger
import app.trackly.usage.UsageBudget
import app.trackly.ui.theme.TracklyColors
import app.trackly.ui.theme.TracklyType
import java.time.Clock
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Étiquettes de test du coût du mois. */
object UsageTags {
    const val SECTION = "reglages-cout"
    const val TOTAL = "reglages-cout-total"
    const val TEXT = "reglages-cout-texte"
    const val SEARCH = "reglages-cout-recherche"
    const val TRANSCRIPTION = "reglages-cout-transcription"
    const val LIVE = "reglages-cout-appels"
    const val ACTIVITY = "reglages-cout-echanges"
    const val BUDGET = "reglages-cout-budget"
    const val ESTIMATE = "reglages-cout-estimation"
}

/** Section « Coût du mois » des Réglages, branchée sur le compteur local : se met à jour en direct. */
@Composable
fun UsageSettingsRoute(ledger: RoomUsageLedger, clock: Clock) {
    // Le mois est fixé à l'ouverture de l'écran (heure locale) ; il change à la prochaine ouverture.
    val month = remember(ledger) { ledger.monthOf(clock.instant()) }
    val flow = remember(ledger, month) { ledger.observeMonth(month) }
    val cost by flow.collectAsStateWithLifecycle(initialValue = MonthCost(month))
    UsageSettingsSection(cost)
}

/**
 * Coût estimé du mois civil en cours : total, répartition texte / recherche web / transcription,
 * nombre d'échanges, et rappel du budget. Calculé sur le téléphone : la facture d'OpenAI fait foi.
 */
@Composable
fun UsageSettingsSection(cost: MonthCost) {
    Column(Modifier.fillMaxWidth().testTag(UsageTags.SECTION)) {
        Spacer(Modifier.height(26.dp))
        BasicText("COÛT DU MOIS", style = TracklyType.Kicker.copy(color = TracklyColors.Cobalt), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        BasicText(monthLabel(cost.month), style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2))
        Spacer(Modifier.height(4.dp))
        // Total = somme des lignes affichées (arrondies chacune au centime), pour que tout s'additionne à l'écran.
        val shownTotal = Money.formatSum(listOf(cost.text, cost.search, cost.transcription, cost.live))
        BasicText(
            shownTotal,
            style = TracklyType.CardTotal.copy(color = TracklyColors.Ink),
            modifier = Modifier.testTag(UsageTags.TOTAL).semantics { contentDescription = "Coût estimé du mois : $shownTotal" },
        )
        Spacer(Modifier.height(10.dp))
        BudgetBar(cost.total)
        Spacer(Modifier.height(6.dp))
        BasicText(
            budgetLine(cost.total),
            style = TracklyType.MealDetail.copy(color = TracklyColors.Ink2),
            modifier = Modifier.testTag(UsageTags.BUDGET),
        )
        Spacer(Modifier.height(16.dp))
        CostRow("Texte (assistant)", cost.text, UsageTags.TEXT)
        CostRow("Recherche web", cost.search, UsageTags.SEARCH)
        CostRow("Transcription (dictée)", cost.transcription, UsageTags.TRANSCRIPTION)
        CostRow("Appels (voix)", cost.live, UsageTags.LIVE)
        Spacer(Modifier.height(10.dp))
        BasicText(
            activityLine(cost),
            style = TracklyType.MealDetail.copy(color = TracklyColors.Ink),
            modifier = Modifier.testTag(UsageTags.ACTIVITY),
        )
        if (cost.unpriced > 0) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                "${cost.unpriced} requête${if (cost.unpriced > 1) "s" else ""} avec un modèle sans tarif connu : son coût n’est pas compté.",
                style = TracklyType.MealDetail.copy(color = TracklyColors.Ink),
            )
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Estimation, tarifs du ${dateLabel(PriceTable.AS_OF)}.",
            style = TracklyType.Notice.copy(color = TracklyColors.Ink3),
            modifier = Modifier.testTag(UsageTags.ESTIMATE),
        )
    }
}

@Composable
private fun CostRow(label: String, micros: Long, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink))
        BasicText(Money.format(micros), style = TracklyType.SettingsBody.copy(color = TracklyColors.Ink), modifier = Modifier.testTag(tag))
    }
}

/** Part du budget mensuel déjà estimée, en trait fin (au-delà de 100 %, le trait est plein). */
@Composable
private fun BudgetBar(total: Long) {
    val share = (total.toDouble() / UsageBudget.MONTHLY).coerceIn(0.0, 1.0).toFloat()
    val percent = (total * 100 / UsageBudget.MONTHLY).toInt()
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TracklyColors.TrackEmpty)
            .semantics { contentDescription = "$percent % du budget mensuel utilisé" },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(TracklyColors.Cobalt))
    }
}

private fun budgetLine(total: Long): String {
    val budget = Money.formatWhole(UsageBudget.MONTHLY)
    val target = "${(UsageBudget.TARGET_LOW / 1_000_000)} à ${Money.formatWhole(UsageBudget.TARGET_HIGH)}"
    val percent = total * 100 / UsageBudget.MONTHLY
    val state = if (total > UsageBudget.MONTHLY) "Budget dépassé" else "$percent % du budget"
    return "$state · budget de $budget par mois · cible d’usage : $target"
}

private fun activityLine(cost: MonthCost): String {
    val exchanges = "${cost.exchanges} échange${if (cost.exchanges > 1) "s" else ""} avec l’assistant"
    val dictations = if (cost.dictations > 0) " · ${cost.dictations} dictée${if (cost.dictations > 1) "s" else ""}" else ""
    val calls = if (cost.calls > 0) " · ${cost.calls} appel${if (cost.calls > 1) "s" else ""}" else ""
    return exchanges + dictations + calls
}

private fun monthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)} ${month.year}"

private fun dateLabel(iso: String): String {
    val date = java.time.LocalDate.parse(iso)
    return "${date.dayOfMonth}${if (date.dayOfMonth == 1) "er" else ""} ${date.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)} ${date.year}"
}
